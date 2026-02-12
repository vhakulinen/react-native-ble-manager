import Foundation
import CoreBluetooth

enum PeripheralError: Error, LocalizedError {
    case unexpectedEvent(event: String)
    case unexpectedError(message: String)
    case bleError(error: Error)
    case timeout
    case operationNotQueued
    case deferredAlreadyUsed
    case badConnectionState(state: CBPeripheralState)
    case closed
    case characteristicNotFound(characteristic: CBUUID, service: CBUUID)
    case badState(reason: String)

    var errorDescription: String? {
        switch self {
        case .unexpectedEvent(let event):
            "unexpected event: \(event)"
        case .unexpectedError(let message):
            "unexpected error: \(message)"
        case .bleError(let error):
            "core bluetooth error: \(error)"
        case .timeout:
            "operation timeout"
        case .operationNotQueued:
            "operation not queued (queue closed)"
        case .deferredAlreadyUsed:
            "deferred already used (internal bug)"
        case .badConnectionState(let state):
            "cannot do operation in state \(state.toString())"
        case .closed:
            "peripheral closed"
        case .characteristicNotFound(let characteristic, let service):
            "characteristic \(characteristic) not found on service \(service)"
        case .badState(let reason):
            "peripheral entered bad state: \(reason)"
        }
    }
}

extension CBPeripheralState {
    func toString() -> String {
        switch (self) {
        case .connected: return "connected"
        case .disconnected: return "disconnected"
        case .connecting: return "connecting"
        case .disconnecting: return "disconnecting"
        @unknown default: return "unknown"
        }
    }
}

extension CBUUID {
    var fullUUIDString: String {
        let s = self.uuidString.lowercased()
        switch s.count {
        case 4:  // 2-byte
            return "0000\(s)-0000-1000-8000-00805f9b34fb"
        case 8:  // 4-byte
            return "\(s)-0000-1000-8000-00805f9b34fb"
        default:
            return s
        }
    }
}
struct OperationBusClosedError: Error {}

class OperationBus {
    private var continuation: AsyncStream<PeripheralOperation>.Continuation?
    // TODO(ville): Can we use ! here?
    var stream: AsyncStream<PeripheralOperation>!

    init() {
        self.stream = AsyncStream(bufferingPolicy: .unbounded) {[weak self] cont in
            self?.continuation = cont
            cont.onTermination = {[weak self] _ in
                self?.continuation = nil
            }
        }
    }

    func send(op: PeripheralOperation) -> Result<Void, OperationBusClosedError> {
        guard let continuation = continuation else {
            return .failure(OperationBusClosedError())
        }

        switch continuation.yield(op) {
        case .enqueued:
            return .success(())
        default:
            return .failure(OperationBusClosedError())
        }
    }

    func close() {
        continuation?.finish()
        continuation = nil
    }
}

// Protocol for proxying central manager events to the peripheral.
protocol PeripheralCentralDelegate: AnyObject {
    func didUpdateState(state: CBManagerState)
    func peripheralDidConnect()
    func peripheralDidDisconnect(error: Error?)
    func peripheralDidFailToConnect(error: Error?)
}

struct AdvertisingData {
    let rssi: NSNumber?
    let data: [String: Any]
}

class Peripheral: NSObject {
    private var instance: CBPeripheral
    private var bus: OperationBus
    // TODO(ville): Can we use ! here?
    private var busTask: Task<Void, Never>!
    private var currentOperation = Mutex<PeripheralOperation?>(nil)
    private var advInfo: Mutex<AdvertisingData>
    private var closed = Mutex(false)

    private var cmConnect: () -> Void
    private var cmDisconnect: () -> Void
    private var sendEvent: (String, Any) -> Void

    init(peripheral: CBPeripheral,
         rssi: NSNumber?,
         advertisementData: [String: Any],
         connect: @escaping () -> Void,
         disconnect: @escaping () -> Void,
         sendEvent: @escaping (String, Any) -> Void
    ) {
        self.instance = peripheral
        self.cmConnect = connect
        self.cmDisconnect = disconnect
        self.sendEvent = sendEvent
        self.bus = OperationBus()
        self.advInfo = Mutex(AdvertisingData(rssi: rssi, data: advertisementData))

        super.init()
        self.instance.delegate = self

        self.busTask = Task { [weak self] in
            guard let self = self else {
                return
            }
            for await op in self.bus.stream {
                NSLog("start operation \(op.kind)")
                if self.closed.read() {
                    NSLog("operation loop close path!")
                    // We're closed, fail all remaining // operations.
                    op.complete(withError: .closed)
                    for await remaining in self.bus.stream {
                        remaining.complete(withError: .closed)
                    }

                    // The stream should be closed here already, but break
                    // out anyways.
                    break
                }

                // TODO(ville): Should probably keep the lock until the
                // operation is started
                self.currentOperation.withLock { currentOperation in
                    currentOperation = op
                }

                switch self.startOperation(op) {
                case .failure(let error):
                    op.complete(withError: error)
                case .await:
                    await withTaskGroup(of: Void.self) { group in
                        group.addTask { await op.wait() }
                        if op.timeoutMs != nil {
                            group.addTask {
                                try? await Task.sleep(nanoseconds: op.timeoutMs! * UInt64(1e6))
                                // If we were not cancelled, we should enter bad state.
                                if !Task.isCancelled {
                                    op.complete(withError: .timeout)
                                    // Eagerly set the current operatin to nil.
                                    self.currentOperation.withLock { currentOperation in
                                        currentOperation = nil
                                    }
                                    self.onBadState(op: nil, reason: "operation timeout")
                                }
                            }
                        }

                        await group.next()
                        group.cancelAll()
                    }
                case .noop:
                    break
                }

                NSLog("operation completed \(op.kind)")
                self.currentOperation.withLock { currentOperation in
                    currentOperation = nil
                }
            }
        }
    }

    private func onBadState(op: PeripheralOperation?, reason: String) {
        NSLog("Entering bad state: \(reason), forcing disconnect")
        // NOTE(ville): Don't try to take lock on currentOperation here since
        // we've probably already locked it while we're calling this method!
        // The op argument should be the current operation.
        op?.complete(withError: PeripheralError.badState(reason: reason))
        // Force disconnect.
        self.cmDisconnect()
    }

    private func checkBleError(_ error: Error?) {
        guard let error = error else { return }

        if let attError = error as? CBATTError {
            switch attError.code {
            case .insufficientEncryption, .insufficientAuthentication, .insufficientAuthorization:
                sendEvent("BleManagerAuthorizationError", [
                    "peripheral": self.instance.uuidAsString(),
                    "error": attError.localizedDescription,
                    "status": attError.code.rawValue,
                ])
            default:
                break
            }
        }

        // CBError.peerRemovedPairingInformation (code 14, iOS 16+)
        if let cbError = error as? CBError, cbError.code.rawValue == 14 {
            sendEvent("BleManagerAuthorizationError", [
                "peripheral": self.instance.uuidAsString(),
                "error": cbError.localizedDescription,
                "status": cbError.code.rawValue,
            ])
        }
    }

    private func sendStateEvent() {
        switch instance.state {
        case .connected:
            sendEvent("BleManagerConnectPeripheral", ["peripheral": self.instance.uuidAsString()])
        case .disconnected:
            sendEvent("BleManagerDisconnectPeripheral", ["peripheral": self.instance.uuidAsString()])
        default:
            break
        }
    }

    func state() -> CBPeripheralState {
        return self.instance.state
    }

    func close() {
        self.instance.delegate = nil
        // Close the operation bus.
        self.bus.close()
        // Signal the task to fail any pending operations. This will leave it
        // floating for a while but it will eventually finish. Ideally we would
        // cancel the task but that would leave the pending operations hanging
        // because we can't process them in a synchronous context.
        self.closed.withLock { v in v = true }
        // Send disconnect event. From JS point of view we're disconnected even
        // if the system is still connected to the peripheral.
        if self.instance.state == .connected {
            sendEvent("BleManagerDisconnectPeripheral", ["peripheral": self.instance.uuidAsString()])
        }
    }

    func setAdvertisingInfo(rssi: NSNumber?, data: Dictionary<String, Any>) {
        advInfo.withLock { adv in
            adv = AdvertisingData(rssi: rssi, data: data)
        }
    }

    func advertisingInfo() -> Dictionary<String, Any> {
        var peripheralInfo: [String: Any] = [:]

        peripheralInfo["name"] = instance.name
        peripheralInfo["id"] = instance.uuidAsString()
        advInfo.withLock{ adv in
            peripheralInfo["rssi"] = adv.rssi
            peripheralInfo["advertising"] = Helper.reformatAdvertisementData(adv.data)
        }

        return peripheralInfo
    }

    func servicesInfo() -> Dictionary<String, Any> {
        var servicesInfo: [String: Any] = advertisingInfo()

        var serviceList = [[String: Any]]()
        var characteristicList = [[String: Any]]()

        for service in instance.services ?? [] {
            var serviceDictionary = [String: Any]()
            serviceDictionary["uuid"] = service.uuid.fullUUIDString
            serviceList.append(serviceDictionary)

            for characteristic in service.characteristics ?? [] {
                var characteristicDictionary = [String: Any]()
                characteristicDictionary["service"] = service.uuid.fullUUIDString
                characteristicDictionary["characteristic"] = characteristic.uuid.fullUUIDString

                if let value = characteristic.value, value.count > 0 {
                    characteristicDictionary["value"] = Helper.dataToArrayBuffer(value)
                }

                characteristicDictionary["properties"] = Helper.decodeCharacteristicProperties(characteristic.properties)

                characteristicDictionary["isNotifying"] = characteristic.isNotifying

                var descriptorList = [[String: Any]]()
                for descriptor in characteristic.descriptors ?? [] {
                    var descriptorDictionary = [String: Any]()
                    descriptorDictionary["uuid"] = descriptor.uuid.fullUUIDString

                    if let value = descriptor.value {
                        descriptorDictionary["value"] = value
                    }

                    descriptorList.append(descriptorDictionary)
                }

                if descriptorList.count > 0 {
                    characteristicDictionary["descriptors"] = descriptorList
                }

                characteristicList.append(characteristicDictionary)
            }
        }

        servicesInfo["services"] = serviceList
        servicesInfo["characteristics"] = characteristicList

        return servicesInfo
    }

    // Result of startOperation.
    enum StartResult {
        // Operation should be waited for completion.
        case await
        // Operation should be completed with the following error.
        case failure(_ error: PeripheralError)
        // Operation was completed and is a no-op for the operations loop.
        case noop
    }

    /**
     * Start given operation. Returning result will be success if the operation was started and the result
     * should be awaited. On failure, operation was failed immediatelly and there is no need to await it.
     *
     * TODO(ville): Would be nice to transform the PeripheralOperation into a type that must be waited
     * which would then be returned in the success value.
     */
    private func startOperation(_ op: PeripheralOperation) -> StartResult {
        switch op.kind {
        case .connect(let deferred):
            switch self.instance.state {
            case .connected:
                deferred.complete(.success(()))
                return .noop
            case .disconnected:
                self.cmConnect()
                return .await
            case .disconnecting, .connecting:
                // NOTE(ville): The connection state event will eventually
                // trigger and complete this operation.
                return .await
            default:
                return .failure(.badConnectionState(state: self.instance.state))
            }
        case .disconnect(let deferred):
            switch self.instance.state {
            case .disconnected:
                deferred.complete(.success(()))
                return .noop
            case .connected:
                self.cmDisconnect()
                return .await
            case .disconnecting, .connecting:
                // NOTE(ville): The connection state event will eventually
                // trigger and complete this operation.
                return .await
            default:
                return .failure(.badConnectionState(state: self.instance.state))
            }
        case .read(_, let service, let characteristic):
            guard self.instance.state == .connected else {
                return .failure(.badConnectionState(state: self.instance.state))
            }

            guard let char = self.instance.findCharacteristic(characteristic, on: service) else {
                return .failure(.characteristicNotFound(
                    characteristic: characteristic,
                    service: service
                ))
            }

            self.instance.readValue(for: char)

            return .await
        case .write(_, let service, let characteristic, let value):
            guard self.instance.state == .connected else {
                return .failure(.badConnectionState(state: self.instance.state))
            }

            guard let char = self.instance.findCharacteristic(characteristic, on: service) else {
                return .failure(.characteristicNotFound(
                    characteristic: characteristic,
                    service: service
                ))
            }

            self.instance.writeValue(Data(value), for: char, type: .withResponse)

            return .await
        case .startNotifications(_, let service, let characteristic):
            guard self.instance.state == .connected else {
                return .failure(.badConnectionState(state: self.instance.state))
            }

            guard let char = self.instance.findCharacteristic(characteristic, on: service) else {
                return .failure(.characteristicNotFound(
                    characteristic: characteristic,
                    service: service
                ))
            }

            self.instance.setNotifyValue(true, for: char)

            return .await
        case .stopNotifications(_, let service, let characteristic):
            guard self.instance.state == .connected else {
                return .failure(.badConnectionState(state: self.instance.state))
            }

            guard let char = self.instance.findCharacteristic(characteristic, on: service) else {
                return .failure(.characteristicNotFound(
                    characteristic: characteristic,
                    service: service
                ))
            }

            self.instance.setNotifyValue(false, for: char)

            return .await
        case .discoverServices:
            guard self.instance.state == .connected else {
                return .failure(.badConnectionState(state: self.instance.state))
            }
            self.instance.discoverServices(nil)
            return .await
        }
    }

    private func queueOperation(_ op: PeripheralOperation) {
        if case .failure(_) = self.bus.send(op: op) {
            op.complete(withError: .operationNotQueued)
        }
    }

    public func connect(timeoutMs: NSNumber?,
                        callback: @escaping RCTResponseSenderBlock) {
        queueOperation(PeripheralOperation(connect: Void(),
                                           timeoutMs: timeoutMs,
                                           callback: callback))
    }

    public func disconnect(timeoutMs: NSNumber?,
                           callback: @escaping RCTResponseSenderBlock) {
        queueOperation(PeripheralOperation(disconnect: Void(),
                                           timeoutMs: timeoutMs,
                                           callback: callback))
    }

    public func read(service: CBUUID,
                     characteristic: CBUUID,
                     timeoutMs: NSNumber?,
                     callback: @escaping RCTResponseSenderBlock) {
        queueOperation(PeripheralOperation(read: service,
                                           characteristic: characteristic,
                                           timeoutMs: timeoutMs,
                                           callback: callback))
    }

    public func write(service: CBUUID,
                      characteristic: CBUUID,
                      value: [UInt8],
                      timeoutMs: NSNumber?,
                      callback: @escaping RCTResponseSenderBlock) {
        queueOperation(PeripheralOperation(write: service,
                                           characteristic: characteristic,
                                           value: value,
                                           timeoutMs: timeoutMs,
                                           callback: callback))
    }

    public func startNotifications(service: CBUUID,
                                   characteristic: CBUUID,
                                   timeoutMs: NSNumber?,
                                   callback: @escaping RCTResponseSenderBlock) {
        queueOperation(PeripheralOperation(startNotifications: service,
                                           characteristic: characteristic,
                                           timeoutMs: timeoutMs,
                                           callback: callback))
    }

    public func stopNotifications(service: CBUUID,
                                  characteristic: CBUUID,
                                  timeoutMs: NSNumber?,
                                  callback: @escaping RCTResponseSenderBlock) {
        queueOperation(PeripheralOperation(stopNotifications: service,
                                           characteristic: characteristic,
                                           timeoutMs: timeoutMs,
                                           callback: callback))
    }

    public func discoverServices(timeoutMs: NSNumber?,
                                 callback: @escaping RCTResponseSenderBlock) {
        queueOperation(PeripheralOperation(discoverServices: timeoutMs,
                                           callback: callback))
    }

}

extension CBPeripheral {
    func findCharacteristic(_ characteristic: CBUUID, on: CBUUID) -> CBCharacteristic? {
        return services?
            .first { $0.uuid == on }?
            .characteristics?
            .first { $0.uuid == characteristic }
    }
}

extension CBCharacteristic {
    func matchesOperation(_ characteristic: CBUUID, _ service: CBUUID) -> Bool {
        return self.uuid == characteristic && self.service?.uuid == service
    }
}

extension Peripheral: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverServices error: Error?) {
        NSLog("didDiscoverServices")
        checkBleError(error)
        currentOperation.withLock { currentOperation in
            guard case .discoverServices(let deferred, let state) = currentOperation?.kind else {
                onBadState(op: currentOperation, reason: "spurious service discovery")
                return
            }

            guard error == nil else {
                deferred.complete(.failure(.bleError(error: error!)))
                return
            }

            // Update the services list before queuing up the discovering
            // the characteristics.
            peripheral.services!.forEach {
                state.services.updateValue(Set(), forKey: $0.uuid)
            }
            // Discover the characteristics.
            peripheral.services!.forEach {
                peripheral.discoverCharacteristics(nil, for: $0)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        NSLog("didDiscoverCharacteristicsFor")
        checkBleError(error)
        currentOperation.withLock { currentOperation in
            guard case .discoverServices(let deferred, let state) = currentOperation?.kind else {
                onBadState(op: currentOperation, reason: "spurious characteristic discovery")
                return
            }

            guard error == nil else {
                deferred.complete(.failure(.bleError(error: error!)))
                return
            }

            state.services.updateValue(Set(
                service.characteristics?.map { chr in chr.uuid } ?? []
            ), forKey: service.uuid)

            for characteristic in (service.characteristics ?? []) {
                peripheral.discoverDescriptors(for: characteristic)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverDescriptorsFor characteristic: CBCharacteristic,
                    error: Error?) {
        NSLog("didDiscoverDescriptrosFor")
        checkBleError(error)
        currentOperation.withLock { currentOperation in
            guard case .discoverServices(let deferred, let state) = currentOperation?.kind else {
                onBadState(op: currentOperation, reason: "spurious descriptor discovery")
                return
            }

            guard error == nil else {
                deferred.complete(.failure(.bleError(error: error!)))
                return
            }

            guard let serviceUUID = characteristic.service?.uuid else {
                deferred.complete(.failure(.unexpectedError(
                    message: "characteristic \(characteristic.uuid.fullUUIDString) with no service")))
                return
            }

            state.services[serviceUUID]!.remove(characteristic.uuid)
            if state.services[serviceUUID]!.isEmpty {
                state.services.removeValue(forKey: serviceUUID)
            }

            if state.services.isEmpty {
                deferred.complete(.success(self.servicesInfo()))
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        checkBleError(error)
        currentOperation.withLock { currentOperation in
            if case .read(let deferred, let service, let char) = currentOperation?.kind {
                guard characteristic.matchesOperation(char, service) else {
                    // This is likely just a normal notification.
                    return
                }

                switch error {
                case .none: deferred.complete(.success(characteristic.value!.toArray()))
                case .some(let error): deferred.complete(.failure(.bleError(error: error)))
                }
            }
        }

        // NOTE(ville): We should only get errors when we actually tried to
        // read the characteristic (i.e. not from notifications). Don't emit
        // the update event on error since the value may be nil.
        if let value = characteristic.value, error == nil {
            sendEvent("BleManagerDidUpdateValueForCharacteristic", [
                "peripheral": peripheral.uuidAsString(),
                "characteristic": characteristic.uuid.fullUUIDString,
                "service": characteristic.service!.uuid.fullUUIDString,
                "value": value.toArray()
            ])
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        checkBleError(error)
        currentOperation.withLock { currentOperation in
            guard case .write(let deferred, let service, let char, _) = currentOperation?.kind,
                  characteristic.matchesOperation(char, service) else {
                onBadState(op: currentOperation, reason: "spurious write on \(characteristic.uuid.fullUUIDString)")
                return
            }

            switch error {
            case .none: deferred.complete(.success(()))
            case .some(let error): deferred.complete(.failure(.bleError(error: error)))
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        checkBleError(error)
        currentOperation.withLock { currentOperation in
            if characteristic.isNotifying {
                guard case .startNotifications(let deferred, let srv, let char) = currentOperation?.kind,
                      characteristic.matchesOperation(char, srv) else {
                    onBadState(op: currentOperation, reason: "spurious notification start on \(characteristic.uuid.fullUUIDString)")
                    return
                }

                switch error {
                case .none: deferred.complete(.success(()))
                case .some(let error): deferred.complete(.failure(.bleError(error: error)))
                }
            } else {
                guard case .stopNotifications(let deferred, let srv, let char) = currentOperation?.kind,
                      characteristic.matchesOperation(char, srv) else {
                    onBadState(op: currentOperation, reason: "spurious notification stop on \(characteristic.uuid.fullUUIDString)")
                    return
                }

                switch error {
                case .none: deferred.complete(.success(()))
                case .some(let error): deferred.complete(.failure(.bleError(error: error)))
                }
            }
        }
    }
}

extension Peripheral: PeripheralCentralDelegate {
    func didUpdateState(state: CBManagerState) {
        self.sendStateEvent()
    }

    func peripheralDidConnect() {
        currentOperation.withLock { currentOperation in
            if let currentOperation = currentOperation {
                switch currentOperation.kind {
                case .connect(let deferred):
                    deferred.complete(.success(Void()))
                default:
                    // TODO(ville): afaik we only get didConnect callbacks when
                    // we tried to connect. I.e. unexpected connect events
                    // shoudln't happen. Should we enter bad state?
                    currentOperation.complete(
                        withError: PeripheralError.unexpectedEvent(event: "didConnect"))
                    // TODO(ville): Depending how we handle restoration, we might do something
                    // differently here.
                }
            }
        }

        sendStateEvent()
    }

    func peripheralDidDisconnect(error: Error?) {
        checkBleError(error)
        currentOperation.withLock { currentOperation in
            if case let .disconnect(deferred)? = currentOperation?.kind {
                // TODO(ville): What to do with the error?
                deferred.complete(.success(Void()))
            } else {
                // If we get unexpected disconnect event, fail the current operation.
                // It would eventually fail with timeout if set, but in case not
                // fail it eagerly.
                switch error {
                case .some(let error):
                    currentOperation?.complete(withError: .bleError(error: error))
                case .none:
                    currentOperation?.complete(withError: .unexpectedEvent(event: "didDisconnect"))
                }
            }
        }

        sendStateEvent()
    }

    func peripheralDidFailToConnect(error: Error?) {
        checkBleError(error)
        currentOperation.withLock { currentOperation in
            if case let .connect(deferred)? = currentOperation?.kind {
                switch error {
                case .some(let error):
                    deferred.complete(.failure(.bleError(error: error)))
                case .none:
                    deferred.complete(.failure(.unexpectedEvent(event: "didFailToConnect")))
                }
            } else {
                // If we get unexpected connect failure, fail the current operation.
                switch error {
                case .some(let error):
                    currentOperation?.complete(withError: .bleError(error: error))
                case .none:
                    currentOperation?.complete(
                        withError: .unexpectedEvent(event: "didFailToConnect"))
                }
            }
        }

        sendStateEvent()
    }
}

/**
 * DiscoveryState wraps our services -> characteristic map into a referencable object. If the Dictionary was
 * a class object we could use it directly, but since its a struct its binding semanthics are copy, which doesn't
 * allow us mutating it unless we were to write it back to the operation each time. So, a wrapper class it is.
 */
class DiscoveryState {
    var services = Dictionary<CBUUID, Set<CBUUID>>()
}

class PeripheralOperation {

    enum Kind {
        case connect(deferred: DeferredValue<Void>)
        case disconnect(deferred: DeferredValue<Void>)
        case read(deferred: DeferredValue<[NSNumber]>, service: CBUUID, characteristic: CBUUID)
        case write(deferred: DeferredValue<Void>, service: CBUUID, characteristic: CBUUID, value: [UInt8])
        case startNotifications(deferred: DeferredValue<Void>, service: CBUUID, characteristic: CBUUID)
        case stopNotifications(deferred: DeferredValue<Void>, service: CBUUID, characteristic: CBUUID)
        case discoverServices(deferred: DeferredValue<Dictionary<String, Any>>,
                              services: DiscoveryState)
    }

    var timeoutMs: UInt64?
    var kind: Kind

    init(connect _: Void,
         timeoutMs: NSNumber?,
         callback: @escaping RCTResponseSenderBlock) {
        self.timeoutMs = timeoutMs?.uint64Value
        self.kind = .connect(deferred: DeferredValue(callback))
    }

    init(disconnect _: Void,
         timeoutMs: NSNumber?,
         callback: @escaping RCTResponseSenderBlock) {
        self.timeoutMs = timeoutMs?.uint64Value
        self.kind = .disconnect(deferred: DeferredValue(callback))
    }

    init(read service: CBUUID,
         characteristic: CBUUID,
         timeoutMs: NSNumber?,
         callback: @escaping RCTResponseSenderBlock) {
        self.timeoutMs = timeoutMs?.uint64Value
        self.kind = .read(deferred: DeferredValue(callback),
                          service: service,
                          characteristic: characteristic)
    }

    init(write service: CBUUID,
         characteristic: CBUUID,
         value: [UInt8],
         timeoutMs: NSNumber?,
         callback: @escaping RCTResponseSenderBlock) {
        self.timeoutMs = timeoutMs?.uint64Value
        self.kind = .write(deferred: DeferredValue(callback),
                           service: service,
                           characteristic: characteristic,
                           value: value)
    }

    init(startNotifications service: CBUUID,
         characteristic: CBUUID,
         timeoutMs: NSNumber?,
         callback: @escaping RCTResponseSenderBlock) {
        self.timeoutMs = timeoutMs?.uint64Value
        self.kind = .startNotifications(deferred: DeferredValue(callback),
                          service: service,
                          characteristic: characteristic)
    }

    init(stopNotifications service: CBUUID,
         characteristic: CBUUID,
         timeoutMs: NSNumber?,
         callback: @escaping RCTResponseSenderBlock) {
        self.timeoutMs = timeoutMs?.uint64Value
        self.kind = .stopNotifications(deferred: DeferredValue(callback),
                          service: service,
                          characteristic: characteristic)
    }

    init(discoverServices timeoutMs: NSNumber?,
         callback: @escaping RCTResponseSenderBlock) {
        self.timeoutMs = timeoutMs?.uint64Value
        self.kind = .discoverServices(deferred: DeferredValue(callback),
                                      services: DiscoveryState())
    }

    func complete(withError error: PeripheralError) {
        switch self.kind {
        case .connect(let deferred),
                .disconnect(let deferred):
            deferred.complete(.failure(error))
        case .read(let deferred, _, _):
            deferred.complete(.failure(error))
        case .write(let deferred, _, _, _),
                .startNotifications(let deferred, _, _),
                .stopNotifications(let deferred, _, _):
            deferred.complete(.failure(error))
        case .discoverServices(let deferred, _):
            deferred.complete(.failure(error))
        }
    }

    /**
     * Wait for the operation to complete.
     */
    func wait() async {
        switch self.kind {
        case .connect(let deferred),
                .disconnect(let deferred):
            await deferred.wait()
        case .read(let deferred, _, _):
            await deferred.wait()
        case .write(let deferred, _, _, _),
                .startNotifications(let deferred, _, _),
                .stopNotifications(let deferred, _, _):
            await deferred.wait()
        case .discoverServices(let deferred, _):
            await deferred.wait()
        }
    }
}

class Mutex<T> {
    private var value: T
    private let lock = NSLock()

    init(_ value: T) {
        self.value = value
    }

    func withLock<R>(_ body: (inout T) throws -> R) rethrows -> R {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }

    func read() -> T {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

class DeferredValue<T> {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Result<T, PeripheralError>, Never>?
    private var result: Result<T, PeripheralError>?
    private let callback: RCTResponseSenderBlock

    init(_ callback: @escaping RCTResponseSenderBlock) {
        self.callback = callback
    }

    /**
     * Complete the deferred value with given result. Completing already completed deferred value is no-op.
     */
    func complete(_ value: Result<T, PeripheralError>) {
        lock.withLock {
            // Already completed.
            guard result == nil else { return }

            self.result = value
            if let continuation = continuation {
                continuation.resume(returning: value)
            }

            switch value {
                case .success(let v): callback([NSNull(), v as Any])
                case .failure(let e): callback([e.localizedDescription])
            }
        }
    }

    /**
     * Wait for the result to become available.
     */
    @discardableResult
    func wait() async  -> Result<T, PeripheralError> {
        return await withCheckedContinuation { cont in
            lock.withLock {
                guard continuation == nil else {
                    cont.resume(returning: .failure(.deferredAlreadyUsed))
                    return
                }

                if let result {
                    // Short cicuit if we already have the result
                    cont.resume(returning: result)
                } else {
                    self.continuation = cont
                }
            }
        }
    }
}

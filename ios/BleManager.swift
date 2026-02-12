import Foundation
import CoreBluetooth

@objc(BleManager)
class BleManager: RCTEventEmitter, CBCentralManagerDelegate {
    
    static var shared:BleManager?
    static var sharedManager:CBCentralManager?
    
    private var hasListeners:Bool = false
    
    private var manager: CBCentralManager?
    private var scanTimer: Timer?
    
    private var peripherals: Mutex<Dictionary<String, Peripheral>> = Mutex([:])
    
    private let serialQueue = DispatchQueue(label: "BleManager.serialQueue")
    
    private var exactAdvertisingName: [String]
    
    static var verboseLogging = false
    
    private override init() {
        exactAdvertisingName = []
        
        super.init()
        
        NSLog("BleManager created");
        
        BleManager.shared = self
        
        
        NotificationCenter.default.addObserver(self, selector: #selector(bridgeReloading), name: NSNotification.Name(rawValue: "RCTBridgeWillReloadNotification"), object: nil)
    }
    
    @objc override static func requiresMainQueueSetup() -> Bool { return true }
    
    @objc override func supportedEvents() -> [String]! {
        return ["BleManagerDidUpdateValueForCharacteristic", "BleManagerStopScan", "BleManagerDiscoverPeripheral", "BleManagerConnectPeripheral", "BleManagerDisconnectPeripheral", "BleManagerDidUpdateState", "BleManagerDidUpdateNotificationStateFor", "BleManagerAuthorizationError"]
    }
    
    @objc override func startObserving() {
        hasListeners = true
    }
    
    @objc override func stopObserving() {
        hasListeners = false
    }
    
    @objc func bridgeReloading() {
        if let manager = manager {
            if let scanTimer = self.scanTimer {
                scanTimer.invalidate()
                self.scanTimer = nil
                manager.stopScan()
            }
            
            manager.delegate = nil
        }
        
        peripherals.withLock { peripherals in
            for p in peripherals.values {
                p.close()
            }
            
            peripherals.removeAll()
        }
    }
    
    @objc public func start(_ options: NSDictionary,
                            callback: RCTResponseSenderBlock) {
        if BleManager.verboseLogging {
            NSLog("BleManager initialized")
        }
        var initOptions = [String: Any]()
        
        if let showAlert = options["showAlert"] as? Bool {
            initOptions[CBCentralManagerOptionShowPowerAlertKey] = showAlert
        }
        
        if let verboseLogging = options["verboseLogging"] as? Bool {
            BleManager.verboseLogging = verboseLogging
        }
        
        var queue: DispatchQueue
        if let queueIdentifierKey = options["queueIdentifierKey"] as? String {
            queue = DispatchQueue(label: queueIdentifierKey, qos: DispatchQoS.background)
        } else {
            queue = DispatchQueue.main
        }
        
        if let restoreIdentifierKey = options["restoreIdentifierKey"] as? String {
            initOptions[CBCentralManagerOptionRestoreIdentifierKey] = restoreIdentifierKey
            
            if let sharedManager = BleManager.sharedManager {
                manager = sharedManager
                manager?.delegate = self
            } else {
                manager = CBCentralManager(delegate: self, queue: queue, options: initOptions)
                BleManager.sharedManager = manager
            }
        } else {
            manager = CBCentralManager(delegate: self, queue: queue, options: initOptions)
            BleManager.sharedManager = manager
        }
        
        callback([])
    }
    
    @objc public func scan(_ serviceUUIDStrings: [Any],
                           timeoutSeconds: NSNumber,
                           allowDuplicates: Bool,
                           scanningOptions: NSDictionary,
                           callback:RCTResponseSenderBlock) {
        if Int(truncating: timeoutSeconds) > 0 {
            NSLog("scan with timeout \(timeoutSeconds)")
        } else {
            NSLog("scan")
        }
        
        // Clear the peripherals before scanning again, otherwise cannot connect again after disconnection
        // Only clear peripherals that are not connected - otherwise connections fail silently (without any
        // onDisconnect* callback).
        // TODO(ville): Figure out what is correct here.
        peripherals.withLock { peripherals in
            let disconnectedPeripherals = peripherals.filter({
                $0.value.state() != .connected
                && $0.value.state() != .connecting })
            disconnectedPeripherals.forEach { (uuid, peripheral) in
                peripheral.close()
                peripherals.removeValue(forKey: uuid)
            }
        }
        
        var serviceUUIDs = [CBUUID]()
        if let serviceUUIDStrings = serviceUUIDStrings as? [String] {
            serviceUUIDs = serviceUUIDStrings.map { CBUUID(string: $0) }
        }
        
        var options: [String: Any]?
        if allowDuplicates {
            options = [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        }
        
        exactAdvertisingName.removeAll()
        if let names = scanningOptions["exactAdvertisingName"] as? [String] {
            exactAdvertisingName.append(contentsOf: names)
        }
        
        manager?.scanForPeripherals(withServices: serviceUUIDs, options: options)
        
        if timeoutSeconds.doubleValue > 0 {
            if let scanTimer = scanTimer {
                scanTimer.invalidate()
                self.scanTimer = nil
            }
            DispatchQueue.main.async {
                self.scanTimer = Timer.scheduledTimer(timeInterval: timeoutSeconds.doubleValue, target: self, selector: #selector(self.stopTimer), userInfo: nil, repeats: false)
            }
        }
        
        callback([])
    }
    
    @objc func stopTimer() {
        NSLog("Stop scan");
        scanTimer = nil;
        manager?.stopScan()
        if hasListeners {
            sendEvent(withName: "BleManagerStopScan", body: ["status": 10])
        }
    }
    
    
    @objc public func stopScan(_ callback: @escaping RCTResponseSenderBlock) {
        if let scanTimer = self.scanTimer {
            scanTimer.invalidate()
            self.scanTimer = nil
        }
        
        manager?.stopScan()
        
        if hasListeners {
            sendEvent(withName: "BleManagerStopScan", body: ["status": 0])
        }
        
        callback([])
    }
    
    
    @objc func connect(_ peripheralUUID: String,
                       options: NSDictionary,
                       callback: @escaping RCTResponseSenderBlock) {
        
        peripherals.withLock { peripherals in
            if peripherals[peripheralUUID] == nil {
                guard let uuid = UUID(uuidString: peripheralUUID) else {
                    let error = "Could not find peripheral \(peripheralUUID)."
                    NSLog(error)
                    callback([error, NSNull()])
                    return
                }
                
                guard let p = manager?.retrievePeripherals(withIdentifiers: [uuid]).first else {
                    let error = "Could not find peripheral \(peripheralUUID)."
                    NSLog(error)
                    callback([error, NSNull()])
                    return
                }
                
                let peripheral = createPeripheral(from: p)
                peripherals[p.uuidAsString()] = peripheral
                // NOTE(ville): connect call to already connected peripheral
                // wont cause us to emit the "peripheral connected" event. We'll
                // have to work around that. Do this by signaling the manager's
                // state to the peripheral if the peripheral is already connected.
                if p.state == .connected && manager != nil {
                    peripheral.didUpdateState(state: manager!.state)
                }
            }
            
            let p = peripherals[peripheralUUID]!
            let timeoutMs = options["timeoutMs"] as? NSNumber
            p.connect(timeoutMs: timeoutMs, callback: callback)
        }
    }
    
    private func createPeripheral(
        from peripheral: CBPeripheral,
        rssi: NSNumber? = nil,
        adverisementData: Dictionary<String, Any> = [:]
    ) -> Peripheral {
        return Peripheral(
            peripheral: peripheral,
            rssi: rssi,
            advertisementData: adverisementData,
            connect: { [weak self] in self?.manager?.connect(peripheral) },
            disconnect: { [weak self] in self?.manager?.cancelPeripheralConnection(peripheral) },
            sendEvent: { [weak self] (event, body) -> Void in
                if self?.hasListeners == true {
                    self?.sendEvent(withName: event, body: body)
                }
            }
        )
    }
    
    @objc func disconnect(_ peripheralUUID: String,
                          options: NSDictionary,
                          callback: @escaping RCTResponseSenderBlock) {
        let timeoutMs = options["timeoutMs"] as? NSNumber

        peripherals.withLock { peripherals in
            guard let peripheral = peripherals[peripheralUUID] else {
                let error = "Could not find peripheral \(peripheralUUID)."
                NSLog(error)
                callback([error])
                return
            }

            peripheral.disconnect(timeoutMs: timeoutMs, callback: callback)
        }
    }
    
    @objc func retrieveServices(_ peripheralUUID: String,
                                options: NSDictionary,
                                callback: @escaping RCTResponseSenderBlock) {
        let timeoutMs = options["timeoutMs"] as? NSNumber
        NSLog("retrieveServices")

        peripherals.withLock { peripherals in
            guard let peripheral = peripherals[peripheralUUID] else {
                callback(["Peripheral not found"])
                return
            }

            peripheral.discoverServices(timeoutMs: timeoutMs, callback: callback)
        }
    }
    
    @objc func readRSSI(_ peripheralUUID: String,
                        callback: @escaping RCTResponseSenderBlock) {
        NSLog("readRSSI")
        callback(["Not supported"])
    }
    
    @objc func readDescriptor(_ peripheralUUID: String,
                              serviceUUID: String,
                              characteristicUUID: String,
                              descriptorUUID: String,
                              callback: @escaping RCTResponseSenderBlock) {
        NSLog("readDescriptor")
        callback(["Not supported"])
    }
    
    @objc func writeDescriptor(_ peripheralUUID: String,
                              serviceUUID: String,
                              characteristicUUID: String,
                              descriptorUUID: String,
                              message: [UInt8],
                              callback: @escaping RCTResponseSenderBlock) {
        NSLog("writeDescriptor")
        callback(["Not supported"])
    }
    
    @objc func getDiscoveredPeripherals(_ callback: @escaping RCTResponseSenderBlock) {
        NSLog("Get discovered peripherals")
        peripherals.withLock { peripherals in
            var discoveredPeripherals: [[String: Any]] = []
            
            for (_, peripheral) in peripherals {
                discoveredPeripherals.append(peripheral.advertisingInfo())
            }
            
            callback([NSNull(), discoveredPeripherals])
        }
    }
    
    @objc func getConnectedPeripherals(_ serviceUUIDStrings: [String],
                                       callback: @escaping RCTResponseSenderBlock) {
        NSLog("Get connected peripherals")
        callback([NSNull(), []])
    }
    
    @objc func isPeripheralConnected(_ peripheralUUID: String,
                                     callback: @escaping RCTResponseSenderBlock) {
        peripherals.withLock { peripherals in
            if let peripheral = peripherals[peripheralUUID] {
                callback([NSNull(), peripheral.state() == .connected])
            } else {
                callback(["Peripheral not found"])
            }
        }
    }
    
    @objc func isScanning(_ callback: @escaping RCTResponseSenderBlock) {        
        if let manager = manager {
            callback([NSNull(), manager.isScanning])
        } else {
            callback(["CBCentralManager not found"])
        }
    }
    
    @objc func checkState(_ callback: @escaping RCTResponseSenderBlock) {
        if let manager = manager {
            centralManagerDidUpdateState(manager)
            
            let stateName = Helper.centralManagerStateToString(manager.state)
            callback([stateName])
        }
    }
    
    @objc func write(_ peripheralUUID: String,
                     options: NSDictionary,
                     callback: @escaping RCTResponseSenderBlock) {
        NSLog("write")

        guard let serviceUUID = options["service"] as? String,
              let characteristicUUID = options["characteristic"] as? String else {
            callback(["service and characteristic required."])
            return
        }
        guard let data = options["data"] as? [UInt8] else {
            callback(["data required."])
            return
        }
        let timeoutMs = options["timeoutMs"] as? NSNumber

        peripherals.withLock { peripherals in
            guard let peripheral = peripherals[peripheralUUID] else {
                callback(["Peripheral not found"])
                return
            }

            peripheral.write(service: CBUUID(string: serviceUUID),
                             characteristic: CBUUID(string: characteristicUUID),
                             value: data,
                             timeoutMs: timeoutMs,
                             callback: callback)
        }
    }

    @objc func writeWithoutResponse(_ peripheralUUID: String,
                                    options: NSDictionary,
                                    callback: @escaping RCTResponseSenderBlock) {
        NSLog("writeWithoutResponse")
        callback(["Not supported"])
    }
    
    @objc func read(_ peripheralUUID: String,
                    options: NSDictionary,
                    callback: @escaping RCTResponseSenderBlock) {
        NSLog("read")

        guard let serviceUUID = options["service"] as? String,
              let characteristicUUID = options["characteristic"] as? String else {
            callback(["service and characteristic required."])
            return
        }
        let timeoutMs = options["timeoutMs"] as? NSNumber

        peripherals.withLock { peripherals in
            guard let peripheral = peripherals[peripheralUUID] else {
                callback(["Peripheral not found"])
                return
            }

            peripheral.read(service: CBUUID(string: serviceUUID),
                            characteristic: CBUUID(string: characteristicUUID),
                            timeoutMs: timeoutMs,
                            callback: callback)
        }
    }
    
    @objc func startNotification(_ peripheralUUID: String,
                                 options: NSDictionary,
                                 callback: @escaping RCTResponseSenderBlock) {
        NSLog("startNotification")

        guard let serviceUUID = options["service"] as? String,
              let characteristicUUID = options["characteristic"] as? String else {
            callback(["service and characteristic required."])
            return
        }
        let timeoutMs = options["timeoutMs"] as? NSNumber

        peripherals.withLock { peripherals in
            guard let peripheral = peripherals[peripheralUUID] else {
                callback(["Peripheral not found"])
                return
            }

            peripheral.startNotifications(service: CBUUID(string: serviceUUID),
                                          characteristic: CBUUID(string: characteristicUUID),
                                          timeoutMs: timeoutMs,
                                          callback: callback)
        }
    }
    
    @objc func stopNotification(_ peripheralUUID: String,
                                options: NSDictionary,
                                callback: @escaping RCTResponseSenderBlock) {
        NSLog("stopNotification")

        guard let serviceUUID = options["service"] as? String,
              let characteristicUUID = options["characteristic"] as? String else {
            callback(["service and characteristic required."])
            return
        }
        let timeoutMs = options["timeoutMs"] as? NSNumber

        peripherals.withLock { peripherals in
            guard let peripheral = peripherals[peripheralUUID] else {
                callback(["Peripheral not found"])
                return
            }

            peripheral.stopNotifications(service: CBUUID(string: serviceUUID),
                                         characteristic: CBUUID(string: characteristicUUID),
                                         timeoutMs: timeoutMs,
                                         callback: callback)
        }
    }
    
    @objc func getMaximumWriteValueLengthForWithoutResponse(_ peripheralUUID: String,
                                                            callback: @escaping RCTResponseSenderBlock) {
        NSLog("getMaximumWriteValueLengthForWithoutResponse")
        callback(["Not supported"])
    }
    
    @objc func getMaximumWriteValueLengthForWithResponse(_ peripheralUUID: String,
                                                         callback: @escaping RCTResponseSenderBlock) {
        NSLog("getMaximumWriteValueLengthForWithResponse")
        callback(["Not supported"])
    }
    
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String : Any]) {
        NSLog("willRestoreState")
        if let restoredPeripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral], restoredPeripherals.count > 0 {
            peripherals.withLock { peripherals in
                var data = [[String: Any]]()
                for peripheral in restoredPeripherals {
                    let p = createPeripheral(from: peripheral)
                    peripherals[peripheral.uuidAsString()] = p
                    data.append(p.advertisingInfo())
                }
            }
        }
    }
    
    
    func centralManager(_ central: CBCentralManager,
                        didConnect peripheral: CBPeripheral) {
        NSLog("Peripheral Connected: \(peripheral.uuidAsString() )")
        peripherals.withLock { peripherals in
            guard let p = peripherals[peripheral.uuidAsString()] else {
                NSLog("Unknown peripheral connected!")
                return
            }
            
            p.peripheralDidConnect()
        }
    }
    
    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        let errorStr = "Peripheral connection failure: \(peripheral.uuidAsString() ) (\(error?.localizedDescription ?? "")"
        NSLog(errorStr)
        
        peripherals.withLock { peripherals in
            guard let p = peripherals[peripheral.uuidAsString()] else {
                NSLog("Unknown peripheral connect failure!")
                return
            }
            
            p.peripheralDidFailToConnect(error: error)
        }
    }
    
    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral:
                        CBPeripheral, error: Error?) {
        let peripheralUUIDString:String = peripheral.uuidAsString()
        NSLog("Peripheral Disconnected: \(peripheralUUIDString)")
        
        if let error = error {
            NSLog("Error: \(error)")
        }
        
        
        peripherals.withLock { peripherals in
            guard let p = peripherals[peripheral.uuidAsString()] else {
                NSLog("Unknown peripheral disconnect!")
                return
            }
            
            p.peripheralDidDisconnect(error: error)
        }
    }
    
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let stateName = Helper.centralManagerStateToString(central.state)
        if hasListeners {
            sendEvent(withName: "BleManagerDidUpdateState", body: ["state": stateName])
        }
        
        // Signal the peripherals for updated their state.
        peripherals.withLock { peripherals in
            peripherals.values.forEach { $0.didUpdateState(state: central.state) }
        }
    }
    
    func handleDiscoveredPeripheral(_ peripheral: CBPeripheral,
                                    advertisementData: [String : Any],
                                    rssi : NSNumber) {
        if BleManager.verboseLogging {
            NSLog("Discover peripheral: \(peripheral.name ?? "NO NAME")");
        }
        
        peripherals.withLock { peripherals in
            let uuid = peripheral.uuidAsString()
            if peripherals[uuid] == nil {
                peripherals[uuid] = createPeripheral(
                    from: peripheral,
                    rssi: rssi,
                    adverisementData: advertisementData)
            } else {
                peripherals[uuid]!.setAdvertisingInfo(rssi: rssi, data: advertisementData)
            }
            
            if (hasListeners) {
                sendEvent(withName: "BleManagerDiscoverPeripheral",
                          body: peripherals[uuid]!.advertisingInfo())
            }
        }
    }
    
    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String : Any],
                        rssi RSSI: NSNumber) {
        if exactAdvertisingName.count > 0 {
            if let peripheralName = peripheral.name {
                if exactAdvertisingName.contains(peripheralName) {
                    handleDiscoveredPeripheral(peripheral, advertisementData: advertisementData, rssi: RSSI)
                } else {
                    if let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String {
                        if exactAdvertisingName.contains(localName) {
                            handleDiscoveredPeripheral(peripheral, advertisementData: advertisementData, rssi: RSSI)
                        }
                    }
                }
            }
        } else {
            handleDiscoveredPeripheral(peripheral, advertisementData: advertisementData, rssi: RSSI)
        }
        
        
    }
    
    @objc static func getCentralManager() -> CBCentralManager? {
        return sharedManager
    }
    
    @objc static func getInstance() -> BleManager? {
        return shared
    }
    
    @objc func enableBluetooth(_ callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func getBondedPeripherals(_ callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func createBond(_ peripheralUUID: String,
                          devicePin: String,
                          callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func removeBond(_ peripheralUUID: String,
                          callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func removePeripheral(_ peripheralUUID: String,
                                callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func requestMTU(_ peripheralUUID: String,
                          options: NSDictionary,
                          callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func requestConnectionPriority(_ peripheralUUID: String,
                                         mtu: Int,
                                         callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func refreshCache(_ peripheralUUID: String,
                            callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func setName(_ name: String,
                       callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func getAssociatedPeripherals(_ callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func removeAssociatedPeripheral(_ peripheralUUID: String,
                                          callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc func supportsCompanion(_ callback: @escaping RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
    
    @objc public func companionScan(_ serviceUUIDs: [Any],
                                    callback:RCTResponseSenderBlock) {
        callback(["Not supported"])
    }
}

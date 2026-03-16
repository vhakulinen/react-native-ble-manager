package it.innove

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.RCTNativeAppEventEmitter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class GattBusyException() : Exception("Gatt is busy")
class OperationTimeoutException() : Exception("Operation timeout")
class OperationNotQueuedException() : Exception("Operation not queued")
class OperationFailedException(status: Int) : Exception("Operation failed. Status: $status")

class CharacteristicNotFoundException(service: UUID, characteristic: UUID) :
    Exception("Characteristic $characteristic not found in service $service")
class SpuriousGattCallback(message: String) : Exception(message)

private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
// Not exposed by BluetoothGatt but defined in the Bluetooth ATT/GATT spec.
private const val GATT_AUTH_FAIL = 0x89

@SuppressLint("MissingPermission")
class MyPeripheral (
    val reactContext: ReactContext,
    val device: BluetoothDevice,
) {
    private val TAG = "MyPeripheral"

    /**
     * Our current gatt instance. Replacing it will close the earlier gatt instance, if any.
     */
    @Volatile
    private var gatt: BluetoothGatt? = null
        set(value) {
            field?.close()
            field = value
        }
    // Our latest connection state.
    // TODO(ville): Would be ideal to combine this and our gatt instance, so that they can't be out
    // of sync.
    @Volatile
    private var latestState: Int = BluetoothProfile.STATE_DISCONNECTED;

    // Operations queue.
    private val operations = Channel<GattOperation<*>>(Channel.UNLIMITED)
    // Current ongoing operation. We're writing this in the operation loop and reading it from
    // the gatt callbacks, so volatile should be enough.
    @Volatile
    private var currentOperation: GattOperation<*>? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var advertisingData: ScanRecord? = null
    private var scanResult: ScanResult? = null
    private var advertisingDataBytes: ByteArray = byteArrayOf()
    private var advertisingRSSI = 0;

    constructor(device: BluetoothDevice, reactContext: ReactContext) : this(reactContext, device)

    constructor(reactContext: ReactContext, result: ScanResult) : this(reactContext, result.device) {
        this.scanResult = result
        this.advertisingRSSI = result.rssi
        this.advertisingDataBytes = result.scanRecord?.bytes ?: byteArrayOf()
        this.advertisingData = result.scanRecord
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            checkAuthorizationError(status)
            when (val op = currentOperation) {
                is GattOperation.Connect -> op.completeWith(status) { Unit }
                is GattOperation.Disconnect -> op.completeWith(status) { Unit }
                else -> {
                    // Noop. The connection state might change no matter what operation we're doing.
                }
            }

            // Connection state changed, emit it to js.
            if (latestState != newState) {
                latestState = newState
                if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    this@MyPeripheral.gatt = null
                    emitConnectionEvent("BleManagerDisconnectPeripheral")
                } else if (newState == BluetoothGatt.STATE_CONNECTED) {
                    emitConnectionEvent("BleManagerConnectPeripheral")
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onCharacteristicRead(gatt, characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            checkAuthorizationError(status)
            val op = currentOperation
            when {
                op is GattOperation.Read && op.match(characteristic)  -> op.completeWith(status) { value }
                else -> onBadState(SpuriousGattCallback(
                    "Spurious read on ${characteristic.uuid.toString()}"
                ))
            }

            // Match iOS functionality by also emitting the update event.
            onCharacteristicChanged(gatt, characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            checkAuthorizationError(status)
            val op = currentOperation
            when {
                op is GattOperation.Write && op.match(characteristic) -> op.completeWith(status) { Unit }
                else -> onBadState(SpuriousGattCallback(
                    "Spurious write on ${characteristic.uuid.toString()}"
                ))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            checkAuthorizationError(status)
            val op = currentOperation
            if (op is GattOperation.RequestMTU) {
                op.completeWith(status) { mtu }
            }

            // NOTE(ville): MTU can change without request, so we're not in bad state if we get
            // spurious mtu changed event.
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            checkAuthorizationError(status)
            val op = currentOperation
            when (op) {
                is GattOperation.DiscoverServices -> op.completeWith(status) {
                    val map = this@MyPeripheral.asWritableMap()
                    Helper.addServiceInfoToWritableMap(map, gatt.services)
                    map
                }
                else -> onBadState(SpuriousGattCallback(
                    "Spurious service discovered"
                ))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            checkAuthorizationError(status)
            val op = currentOperation
            when {
                op is GattOperation.RegisterNotify && op.match(descriptor) -> op.completeWith(status) { Unit }
                op is GattOperation.RemoveNotify && op.match(descriptor) -> op.completeWith(status) { Unit }
                else -> onBadState(SpuriousGattCallback(
                    "Spurious descriptor write on ${descriptor.characteristic.uuid.toString()}.${descriptor.uuid.toString()}"
                ))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            emitEvent("BleManagerDidUpdateValueForCharacteristic", Arguments.createMap().apply {
                putString("peripheral", device.address)
                putString("characteristic", characteristic.uuid.toString())
                putString("service", characteristic.service.uuid.toString())
                putArray("value", BleManager.bytesToWritableArray(value))
            })
        }
    }

    /**
     * Bad state refers to situation where we expect the state of BluetoothGatt to be invalid (e.g.
     * its stuck in "busy" or otherwise doesn't work as we expect).
     *
     * In practice, this either happens when we get BluetoothGattCallback calls in unexpected order,
     * our operation doesn't complete in time (although in reality it might complete but takes too
     * long; can't really tell), or we managed to mess up our operation queue and executed new
     * operation before the earlier one finished.
     *
     * If we end up here, we'll close the gatt and emit disconnected event to JS.
     *
     * Closing the gatt will fail any pending operations that try to access it until a new connect
     * operation will open a new gatt instance.
     *
     * Its important to know that going into bad state is different from closing the peripheral; on
     * close we also close our operations loop which blocks any further operations. Bad state still
     * allows us to reconnect using the same peripheral instance. If this is good or not is up for
     * a debate.
     */
    private fun onBadState(reason: Exception?) {
        Log.d(TAG, "Bad state!", reason);
        // Cancel the current operation so the loop continues.
        currentOperation?.deferred?.completeExceptionally(
            Exception("Operation cancelled due to bad state", reason)
        )
        gatt = null
        // Technically gatt.close doesn't disconnect the peripheral, but from JS point of view we
        // cannot operate on the gatt anymore before a new connect operation. So its reasonable
        // to emit disconnect event here.
        if (latestState == BluetoothGatt.STATE_CONNECTED) {
            emitConnectionEvent("BleManagerDisconnectPeripheral")
        }
        latestState = BluetoothGatt.STATE_DISCONNECTED
    }

    private fun emitEvent(event: String, args: WritableMap) {
        reactContext.runOnJSQueueThread {
            reactContext.getJSModule(RCTNativeAppEventEmitter::class.java).emit(event, args);
        }
    }

    private fun emitConnectionEvent(event: String) {
        emitEvent(event, Arguments.createMap().apply {
            putString("peripheral", device.address)
        })
    }

    /**
     * Checks if a GATT status code indicates an authorization/authentication error
     * (e.g. stale bond keys) and emits a BleManagerAuthorizationError event if so.
     */
    private fun checkAuthorizationError(status: Int) {
        if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
            || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
            || status == GATT_AUTH_FAIL
        ) {
            emitEvent("BleManagerAuthorizationError", Arguments.createMap().apply {
                putString("peripheral", device.address)
                putInt("status", status)
            })
        }
    }

    /**
     * Completely close the peripheral and release all resource.
     *
     * After peripheral is closed, all gatt actions (connect, read, write, etc.) will fail.
     */
    fun close() {
        val e = CancellationException("Peripheral closed")
        Log.w(TAG, "peripheral closed", e)
        // Close the operations loop.
        scope.cancel(e)
        // Close the operations channel.
        operations.close(e)
        // Fail current operation if any.
        currentOperation?.deferred?.completeExceptionally(e)
        // Drain queued operations.
        generateSequence { operations.tryReceive().getOrNull() }
            .forEach { it.deferred.completeExceptionally(e) }
        // Close the gatt instance.
        gatt = null
        // If we were connected, emit disconnect event.
        if (latestState == BluetoothGatt.STATE_CONNECTED) {
            emitConnectionEvent("BleManagerDisconnectPeripheral")
        }
        latestState = BluetoothGatt.STATE_DISCONNECTED
    }

    init {
        scope.launch {
            for (op in operations) {
                currentOperation = op
                Log.d(TAG, "process operation $op")

                when (val res = startOperation(op)) {
                    is GattOperationStart.Success -> {
                        // All good, continue normally.
                    }
                    // Nothing to do, continue to the next one.
                    is GattOperationStart.Noop<*> -> continue
                    is GattOperationStart.NotStarted ->  {
                        // Operation failed to start for normal reasons. Complete the deferred
                        // and continue to the next operation.
                        op.deferred.completeExceptionally(res.cause)
                        continue
                    }
                    is GattOperationStart.GattBusy -> {
                        // Gatt operation failed. If we end up here, the gatt object is in bad state
                        // and we should "start over" by closing the gatt object.
                        op.deferred.completeExceptionally(GattBusyException())
                        // onBadState completes currentOperation exceptionally. While this would be
                        // no-op here, set currentOperation to null here to avoid ambiguity.
                        currentOperation = null
                        onBadState(GattBusyException())
                        continue
                    }
                }

                try {
                    withTimeout(op.timeoutMs ?: Long.MAX_VALUE) { op.deferred.await() }
                } catch (e: TimeoutCancellationException) {
                    // Its possible that the deferred was completed before we got here, but
                    // complete it exceptionally regardless.
                    op.deferred.completeExceptionally(OperationTimeoutException())
                    currentOperation = null
                    onBadState(OperationTimeoutException())
                } catch (e: Exception) {
                    // Operation failed normally, e.g. something failed the deferred. Nothing to do.
                }

                // Reset currentOperation.
                currentOperation = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOperation(op: GattOperation<*>): GattOperationStart<*> {
        return when (op) {
            is GattOperation.Disconnect -> {
                // If we're currently disconnected, resolve the operation.
                // NOTE that our state management on this regard is split between the latestState
                // and our gatt instance.
                if (latestState == BluetoothGatt.STATE_DISCONNECTED && gatt == null) {
                    op.completeWith(BluetoothGatt.GATT_SUCCESS) { Unit }
                    return GattOperationStart.Noop(op, Unit)
                }

                if (gatt?.disconnect() != null) {
                    GattOperationStart.Success
                } else {
                    GattOperationStart.NotStarted(Exception("Not connected"))
                }
            }

            is GattOperation.Connect -> {
                // If we're currently connected, resolve the operation.
                // NOTE that our state management on this regard is split between the latestState
                // and our gatt instance.
                if (latestState == BluetoothGatt.STATE_CONNECTED && gatt != null) {
                    op.completeWith(BluetoothGatt.GATT_SUCCESS) { Unit }
                    return GattOperationStart.Noop(op, Unit)
                }

                gatt = device.connectGatt(reactContext, op.autoConnect, callback)

                // Based on the AOSP source code, connectGatt might return null. This happens when
                // BLE transport is not supported.
                if (gatt != null) {
                    GattOperationStart.Success
                } else {
                    GattOperationStart.NotStarted(Exception("Failed to create GATT connection"))
                }
            }

            is GattOperation.Read -> {
                val char = gatt?.findCharacteristic(op.service, op.characteristic)
                    ?: return GattOperationStart.NotStarted(CharacteristicNotFoundException(op.service, op.characteristic))

                GattOperationStart.fromGattOperation(gatt?.readCharacteristic(char) )
            }
            is GattOperation.Write -> {
                val char = gatt?.findCharacteristic(op.service, op.characteristic)
                    ?: return GattOperationStart.NotStarted(CharacteristicNotFoundException(op.service, op.characteristic))

                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                // TODO(ville): why is the original peripheral copy the value here?
                char.value = op.value

                GattOperationStart.fromGattOperation(gatt?.writeCharacteristic(char))
            }

            is GattOperation.RequestMTU -> GattOperationStart.fromGattOperation(gatt?.requestMtu(op.mtu))
            is GattOperation.DiscoverServices -> GattOperationStart.fromGattOperation(gatt?.discoverServices())
            is GattOperation.RegisterNotify -> {
                val char = gatt?.findCharacteristic(op.service, op.characteristic)
                    ?: return GattOperationStart.NotStarted(CharacteristicNotFoundException(op.service, op.characteristic))
                // Check if we have the CCCD descriptor before enabling notifications.
                val desc = char.getDescriptor(CCCD_UUID)
                    ?: return GattOperationStart.NotStarted(
                        Exception("CCCD not found on ${op.characteristic}"))

                // Enable notifications on local gatt client.
                // TODO(ville): To match iOS, we should enable indications if notifications aren't
                // supported by the characteristic. See CBPeripheral setNotifyValue docs.
                if (gatt?.setCharacteristicNotification(char, true) != true) {
                    return GattOperationStart.GattBusy
                }

                // Request the server to enable the notifications.
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                GattOperationStart.fromGattOperation(gatt?.writeDescriptor(desc))
            }
            is GattOperation.RemoveNotify -> {
                val char = gatt?.findCharacteristic(op.service, op.characteristic)
                    ?: return GattOperationStart.NotStarted(CharacteristicNotFoundException(op.service, op.characteristic))
                // Check if we have the CCCD descriptor before enabling notifications.
                val desc = char.getDescriptor(CCCD_UUID)
                    ?: return GattOperationStart.NotStarted(
                        Exception("CCCD not found on ${op.characteristic}"))

                // Disable notifications on local gatt client.
                // TODO(ville): To match iOS, we should enable indications if notifications aren't
                // supported by the characteristic. See CBPeripheral setNotifyValue docs.
                if (gatt?.setCharacteristicNotification(char, false) != true) {
                    return GattOperationStart.GattBusy
                }

                // Request the server to enable the disable.
                desc.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                GattOperationStart.fromGattOperation(gatt?.writeDescriptor(desc))
            }
        }
    }

    private fun queueOperation(op: GattOperation<*>) {
        operations.trySend(op)
            .onFailure { t: Throwable? ->
                Log.w(TAG, "failed queue operation", t)
                op.deferred.completeExceptionally(OperationNotQueuedException())
            }
    }

    fun connect(
        callback: Callback,
        autoConnect: Boolean,
        timeoutMs: Integer?
    ) {
        queueOperation(GattOperation.Connect(
            reactContext,
            autoConnect,
            timeoutMs?.toLong(),
            callback))
    }

    fun disconnect(timeoutMs: Integer?, callback: Callback) {
        queueOperation(GattOperation.Disconnect(timeoutMs?.toLong(), callback))
    }

    fun updateData(result: ScanResult) {
        advertisingData = result.scanRecord;
        advertisingDataBytes = advertisingData?.bytes ?: byteArrayOf();
    }

    fun asWritableMap(): WritableMap {
        return Helper.asWritableMap(
            this@MyPeripheral.device,
            advertisingRSSI,
            advertisingDataBytes,
            scanResult,
            advertisingData
        )
    }

    fun isConnected(): Boolean {
        return latestState == BluetoothProfile.STATE_CONNECTED
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun isConnecting(): Boolean {
        // NOTE(ville): Since we don't care enough to keep proper connection state, we'll just
        // pretend that this function is called "isOperating" and act accordingly. E.g. we have
        // operations queued up or are in middle of a operation.
        return !operations.isEmpty || currentOperation != null
    }

    fun updateRssi(rssi: Int) {
        advertisingRSSI = rssi
    }

    fun registerNotify(
        service: UUID,
        characteristic: UUID,
        timeoutMs: Integer?,
        callback: Callback
    ) {
        queueOperation(GattOperation.RegisterNotify(
            service, characteristic, timeoutMs?.toLong(), callback))
    }

    fun removeNotify(service: UUID, characteristic: UUID, timeoutMs: Integer?, callback: Callback) {
        queueOperation(GattOperation.RemoveNotify(service, characteristic, timeoutMs?.toLong(), callback))
    }

    fun read(service: UUID, characteristic: UUID, timeoutMs: Integer?, callback: Callback) {
        queueOperation(GattOperation.Read(service, characteristic, timeoutMs?.toLong(), callback))
    }

    fun retrieveServices(timeoutMs: Integer?, callback: Callback) {
        queueOperation(GattOperation.DiscoverServices(timeoutMs?.toLong(), callback))
    }

    fun write(
        service: UUID,
        characteristic: UUID,
        data: ByteArray,
        timeoutMs: Integer?,
        callback: Callback,
        writeType: Int
    ) {
        if (writeType != BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            callback.invoke("Unsupported write type")
            return
        }

        queueOperation(GattOperation.Write(service, characteristic, data, timeoutMs?.toLong(), callback))
    }

    fun requestMTU(mtu: Int, timeoutMs: Integer?, callback: Callback) {
        queueOperation(GattOperation.RequestMTU(mtu, timeoutMs?.toLong(), callback))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
inline fun <T> CompletableDeferred<T>.chainToJsCallback(
    callback: Callback,
    crossinline transform: (T) -> Any?
) {
    invokeOnCompletion { throwable ->
        if (throwable != null) {
            callback.invoke(throwable.message ?: "Unknown error")
        } else {
            callback.invoke(null, transform(getCompleted()))
        }
    }
}

fun BluetoothGatt.findCharacteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? {
    val serv = getService(service)
    return serv?.getCharacteristic(characteristic)
}

sealed class GattOperationStart<T>() {
    /**
     * Operation was successfully started.
     */
    object Success : GattOperationStart<Unit>()

    /**
     * Operation was no-op (e.g. tried to connect while connected etc.).
     *
     * Constructing will unconditionally complete the operation.
     */
    class Noop<T>(op: GattOperation<T>, result: T) : GattOperationStart<T>() {
        init {
            op.deferred.complete(result)
        }
    }

    /**
     * Operation couldn't be started. The error should be relayed to the operation callback.
     */
    class NotStarted(val cause: Throwable) : GattOperationStart<Unit>()

    /**
     * Gatt object operation failed and we should enter bad state.
     */
    object GattBusy : GattOperationStart<Unit>()

    companion object {
        /**
         * Construct GattOperationStart from result of gatt object's return values.
         *
         * E.g. fromGattOperation(gatt.readCharacteristic(...)).
         */
        fun fromGattOperation(res: Boolean?): GattOperationStart<*> {
            return if (res == true) { Success } else { GattBusy }
        }
    }
}

sealed class GattOperation<T>() {
    abstract val deferred: CompletableDeferred<T>
    abstract val callback: Callback
    abstract val timeoutMs: Long?

    inline fun completeWith(status: Int, value: () -> T) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            this.deferred.complete(value())
        } else {
            this.deferred.completeExceptionally(OperationFailedException(status))
        }
    }

    class Disconnect(
        override val timeoutMs: Long?,
        override val callback: Callback
    ) : GattOperation<Unit>() {
        override val deferred = CompletableDeferred<Unit>()

        init {
            deferred.chainToJsCallback(callback) { null }
        }
    }

    class Connect(
        val context: Context,
        val autoConnect: Boolean,
        override val timeoutMs: Long?,
        override val callback: Callback,
    ) : GattOperation<Unit>() {
        override val deferred = CompletableDeferred<Unit>()

        init {
            deferred.chainToJsCallback(callback) { null }
        }
    }

    // NOTE(ville): Would be nice to get the list of services here, but to construct the response
    // value we need to access to the whole peripheral and it doesn't seem proper to require it
    // here...
    class DiscoverServices(
        override val timeoutMs: Long?,
        override val callback: Callback,
        ) : GattOperation<WritableMap>() {
        override val deferred = CompletableDeferred<WritableMap>()

        init {
            deferred.chainToJsCallback(callback) { it }
        }
    }

    class RequestMTU(
        val mtu: Int,
        override val timeoutMs: Long?,
        override val callback: Callback
    ) : GattOperation<Int>() {
        override val deferred = CompletableDeferred<Int>()

        init {
            deferred.chainToJsCallback(callback) { it }
        }
    }

    class Write(
        val service: UUID,
        val characteristic: UUID,
        val value: ByteArray,
        override val timeoutMs: Long?,
        override val callback: Callback
    ) : GattOperation<Unit>() {
        override val deferred = CompletableDeferred<Unit>()

        init {
            deferred.chainToJsCallback(callback) { null }
        }

        fun match(characteristic: BluetoothGattCharacteristic): Boolean {
            return characteristic.uuid == this.characteristic
                    && characteristic.service.uuid == service
        }
    }

    class RemoveNotify(
        val service: UUID,
        val characteristic: UUID,
        override val timeoutMs: Long?,
        override val callback: Callback
    ) : GattOperation<Unit>() {
        override val deferred = CompletableDeferred<Unit>()

        init {
            deferred.chainToJsCallback(callback) { null }
        }

        fun match(descriptor: BluetoothGattDescriptor): Boolean {
            return descriptor.uuid == CCCD_UUID
                && descriptor.characteristic.uuid == characteristic
                    && descriptor.characteristic.service.uuid == service
        }
    }

    class RegisterNotify(
        val service: UUID,
        val characteristic: UUID,
        override val timeoutMs: Long?,
        override val callback: Callback
    ) : GattOperation<Unit>() {
        override val deferred = CompletableDeferred<Unit>()

        init {
            deferred.chainToJsCallback(callback) { null }
        }

        fun match(descriptor: BluetoothGattDescriptor): Boolean {
            return descriptor.uuid == CCCD_UUID
                    && descriptor.characteristic.uuid == characteristic
                    && descriptor.characteristic.service.uuid == service
        }
    }

    class Read(
        val service: UUID,
        val characteristic: UUID,
        override val timeoutMs: Long?,
        override val callback: Callback
    ) : GattOperation<ByteArray>() {
        override val deferred = CompletableDeferred<ByteArray>()

        init {
            deferred.chainToJsCallback(callback) {
                // Transform byte array to WritableArray
                Arguments.createArray().apply {
                    for (byte in it) {
                        pushInt(byte.toInt() and 0xff)
                    }
                }
            }
        }

        fun match(characteristic: BluetoothGattCharacteristic): Boolean {
            return characteristic.uuid == this.characteristic
                    && characteristic.service.uuid == service
        }
    }
}

package it.innove;

import static android.app.Activity.RESULT_OK;
import static android.bluetooth.BluetoothProfile.GATT;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class BleManager extends ReactContextBaseJavaModule {

    public static final String LOG_TAG = "RNBleManager";
    private static final int ENABLE_REQUEST = 539;

    private static class BondRequest {
        private String uuid;
        private String pin;
        private Callback callback;

        BondRequest(String _uuid, Callback _callback) {
            uuid = _uuid;
            callback = _callback;
        }

        BondRequest(String _uuid, String _pin, Callback _callback) {
            uuid = _uuid;
            pin = _pin;
            callback = _callback;
        }
    }

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothManager bluetoothManager;
    private Context context;
    private ReactApplicationContext reactContext;
    private Callback enableBluetoothCallback;
    private ScanManager scanManager;
    private BondRequest bondRequest;
    private BondRequest removeBondRequest;
    /**
     * Used for companion scanning, if supported.
     */
    private final @Nullable CompanionScanner companionScanner;
    public static ReadableMap moduleOptions;

    public ReactApplicationContext getReactContext() {
        return reactContext;
    }

    private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {

        @Override
        public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
            Log.d(LOG_TAG, "onActivityResult");
            if (requestCode == ENABLE_REQUEST && enableBluetoothCallback != null) {
                if (resultCode == RESULT_OK) {
                    enableBluetoothCallback.invoke();
                } else {
                    enableBluetoothCallback.invoke("User refused to enable");
                }
                enableBluetoothCallback = null;
            }
        }

    };

    // key is the MAC Address
    private final Map<String, MyPeripheral> peripherals = new LinkedHashMap<>();
    // scan session id

    public BleManager(ReactApplicationContext reactContext) {
        super(reactContext);
        context = reactContext;
        this.reactContext = reactContext;

        boolean supportsCompanion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP);
        this.companionScanner = supportsCompanion
                ? new CompanionScanner(reactContext, this)
                : null;

        reactContext.addActivityEventListener(mActivityEventListener);
        Log.d(LOG_TAG, "BleManager created");
    }

    @Override
    public String getName() {
        return "BleManager";
    }

    private BluetoothAdapter getBluetoothAdapter() {
        if (bluetoothAdapter == null) {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = manager.getAdapter();
        }
        return bluetoothAdapter;
    }

    private BluetoothManager getBluetoothManager() {
        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        }
        return bluetoothManager;
    }

    public void sendEvent(String eventName, @Nullable WritableMap params) {
        getReactApplicationContext().getJSModule(RCTNativeAppEventEmitter.class).emit(eventName, params);
    }

    @ReactMethod
    public void start(ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "start");
        if (getBluetoothAdapter() == null) {
            Log.d(LOG_TAG, "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }
        moduleOptions = options;

        scanManager = new DefaultScanManager(reactContext, this);

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        if (Build.VERSION.SDK_INT >= 34){
            // Google in 2023 decides that flag RECEIVER_NOT_EXPORTED or RECEIVER_EXPORTED should be explicit set SDK 34(UPSIDE_DOWN_CAKE) on registering receivers.
            // Also the export flags are available on Android 8 and higher, should be used with caution so that don't break compability with that devices.
            context.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
            context.registerReceiver(mReceiver, intentFilter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(mReceiver, filter);
            context.registerReceiver(mReceiver, intentFilter);
        }

        callback.invoke();
        Log.d(LOG_TAG, "BleManager initialized");

        // On iOS the Bluetooth stack automatically calls the delegate with its current status. To
        // match that functionality, run our check state here which will emit the BleManagerDidUpdateState
        // event.
        checkState(new Callback() {
            @Override
            public void invoke(Object... objects) {
                // noop.
            }
        });
    }

    @SuppressLint("MissingPermission")
    @ReactMethod
    public void enableBluetooth(Callback callback) {
        if (getBluetoothAdapter() == null) {
            Log.d(LOG_TAG, "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }
        if (!getBluetoothAdapter().isEnabled()) {
            enableBluetoothCallback = callback;
            Intent intentEnable = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (getCurrentActivity() == null)
                callback.invoke("Current activity not available");
            else
                {
                    try{
                        getCurrentActivity().startActivityForResult(intentEnable, ENABLE_REQUEST);
                    }catch(Exception e){
                        callback.invoke("Current activity not available");
                    }

                }

        } else
            callback.invoke();
    }

    @ReactMethod
    public void scan(ReadableArray serviceUUIDs, final int scanSeconds, boolean allowDuplicates, ReadableMap options,
                     Callback callback) {
        Log.d(LOG_TAG, "scan");
        if (getBluetoothAdapter() == null) {
            Log.d(LOG_TAG, "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }
        if (!getBluetoothAdapter().isEnabled()) {
            return;
        }

        synchronized (peripherals) {
            for (Iterator<Map.Entry<String, MyPeripheral>> iterator = peripherals.entrySet().iterator(); iterator
                    .hasNext(); ) {
                Map.Entry<String, MyPeripheral> entry = iterator.next();
                if (!(entry.getValue().isConnected() || entry.getValue().isConnecting())) {
                    entry.getValue().close();
                    iterator.remove();
                }
            }
        }

        if (scanManager != null)
            scanManager.scan(serviceUUIDs, scanSeconds, options, callback);
    }

    @SuppressLint("NewApi") // NOTE: constructor checks the API version.
    @ReactMethod
    public void companionScan(ReadableArray serviceUUIDs,  ReadableMap options, Callback callback) {
        if (this.companionScanner == null) {
            callback.invoke("not supported");
        } else {
            this.companionScanner.scan(serviceUUIDs, options, callback);
        }
    }

    @ReactMethod
    public void supportsCompanion(Callback callback) {
        callback.invoke(companionScanner != null);
    }

    @ReactMethod
    public void stopScan(Callback callback) {
        Log.d(LOG_TAG, "Stop scan");
        if (getBluetoothAdapter() == null) {
            Log.d(LOG_TAG, "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }
        if (!getBluetoothAdapter().isEnabled()) {
            callback.invoke();
            return;
        }
        if (scanManager != null) {
            scanManager.stopScan(callback);
            WritableMap map = Arguments.createMap();
            map.putInt("status", 0);
            sendEvent("BleManagerStopScan", map);
        }
    }


    @SuppressLint("MissingPermission")
    @ReactMethod
    public void createBond(String peripheralUUID, String peripheralPin, Callback callback) {
        Log.d(LOG_TAG, "Request bond to: " + peripheralUUID);

        Set<BluetoothDevice> deviceSet = getBluetoothAdapter().getBondedDevices();
        for (BluetoothDevice device : deviceSet) {
            if (peripheralUUID.equalsIgnoreCase(device.getAddress())) {
                callback.invoke();
                return;
            }
        }

        MyPeripheral peripheral = retrieveOrCreatePeripheral(peripheralUUID);
        if (peripheral == null) {
            callback.invoke("Invalid peripheral uuid");
            return;
        } else if (bondRequest != null) {
            callback.invoke("Only allow one bond request at a time");
            return;
        } else if (peripheral.getDevice().createBond()) {
            Log.d(LOG_TAG, "Request bond successful for: " + peripheralUUID);
            bondRequest = new BondRequest(peripheralUUID, peripheralPin, callback); // request bond success, waiting for broadcast
            return;
        }

        callback.invoke("Create bond request fail");
    }

    @ReactMethod
    private void removeBond(String peripheralUUID, Callback callback) {
        Log.d(LOG_TAG, "Remove bond to: " + peripheralUUID);

        MyPeripheral peripheral = retrieveOrCreatePeripheral(peripheralUUID);
        if (peripheral == null) {
            callback.invoke("Invalid peripheral uuid");
            return;
        } else {
            try {
                Method m = peripheral.getDevice().getClass().getMethod("removeBond", (Class[]) null);
                m.invoke(peripheral.getDevice(), (Object[]) null);
                removeBondRequest = new BondRequest(peripheralUUID, callback);
                return;
            } catch (Exception e) {
                Log.d(LOG_TAG, "Error in remove bond: " + peripheralUUID, e);
                callback.invoke("Remove bond request fail");
            }
        }

    }

    @ReactMethod
    public void connect(String peripheralUUID, ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "Connect to: " + peripheralUUID);

        if (getBluetoothAdapter() == null || !getBluetoothAdapter().isEnabled()) {
            callback.invoke("Bluetooth is not enabled");
            return;
        }

        MyPeripheral peripheral = retrieveOrCreatePeripheral(peripheralUUID);
        if (peripheral == null) {
            callback.invoke("Peripheral not found");
            return;
        }
        peripheral.connect(callback, options.getBoolean("autoconnect"), getTimeoutMs(options));
    }

    @ReactMethod
    public void disconnect(String peripheralUUID, ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "Disconnect from: " + peripheralUUID);

        MyPeripheral peripheral = retrievePeripheral(peripheralUUID);
        if (peripheral != null) {
            Integer timeoutMs = getTimeoutMs(options);
            peripheral.disconnect(timeoutMs, callback);
        } else
            callback.invoke("Peripheral not found");
    }

    @ReactMethod
    public void startNotification(String deviceUUID, ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "startNotification");
        String serviceUUID = options.getString("service");
        String characteristicUUID = options.getString("characteristic");
        Integer timeoutMs = getTimeoutMs(options);

        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("service and characteristic required.");
            return;
        }
        MyPeripheral peripheral = retrievePeripheral(deviceUUID);
        if (peripheral != null) {
            peripheral.registerNotify(UUIDHelper.uuidFromString(serviceUUID),
                    UUIDHelper.uuidFromString(characteristicUUID), timeoutMs, callback);
        } else {
            callback.invoke("Peripheral not found");
        }
    }

    @ReactMethod
    public void stopNotification(String deviceUUID, ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "stopNotification");
        String serviceUUID = options.getString("service");
        String characteristicUUID = options.getString("characteristic");
        Integer timeoutMs = getTimeoutMs(options);

        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("service and characteristic required.");
            return;
        }
        MyPeripheral peripheral = retrievePeripheral(deviceUUID);
        if (peripheral != null) {
            peripheral.removeNotify(UUIDHelper.uuidFromString(serviceUUID),
                    UUIDHelper.uuidFromString(characteristicUUID), timeoutMs, callback);
        } else {
            callback.invoke("Peripheral not found");
        }
    }

    @ReactMethod
    public void write(String deviceUUID, ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "Write to: " + deviceUUID);
        String serviceUUID = options.getString("service");
        String characteristicUUID = options.getString("characteristic");
        ReadableArray message = options.getArray("data");
        Integer timeoutMs = getTimeoutMs(options);

        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("service and characteristic required.");
            return;
        }
        if (message == null) {
            callback.invoke("data required.");
            return;
        }
        MyPeripheral peripheral = retrievePeripheral(deviceUUID);
        if (peripheral != null) {
            byte[] decoded = new byte[message.size()];
            for (int i = 0; i < message.size(); i++) {
                decoded[i] = Integer.valueOf(message.getInt(i)).byteValue();
            }
            Log.d(LOG_TAG, "Message(" + decoded.length + "): " + bytesToHex(decoded));
            peripheral.write(UUIDHelper.uuidFromString(serviceUUID), UUIDHelper.uuidFromString(characteristicUUID),
                    decoded, timeoutMs, callback, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            callback.invoke("Peripheral not found");
        }
    }

    @ReactMethod
    public void writeWithoutResponse(String deviceUUID, ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "Write without response to: " + deviceUUID);
        String serviceUUID = options.getString("service");
        String characteristicUUID = options.getString("characteristic");
        ReadableArray message = options.getArray("data");
        Integer timeoutMs = getTimeoutMs(options);

        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("service and characteristic required.");
            return;
        }
        if (message == null) {
            callback.invoke("data required.");
            return;
        }
        MyPeripheral peripheral = retrievePeripheral(deviceUUID);
        if (peripheral != null) {
            byte[] decoded = new byte[message.size()];
            for (int i = 0; i < message.size(); i++) {
                decoded[i] = Integer.valueOf(message.getInt(i)).byteValue();
            }
            Log.d(LOG_TAG, "Message(" + decoded.length + "): " + bytesToHex(decoded));
            peripheral.write(UUIDHelper.uuidFromString(serviceUUID), UUIDHelper.uuidFromString(characteristicUUID),
                    decoded, timeoutMs, callback, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            callback.invoke("Peripheral not found");
        }
    }

    @ReactMethod
    public void read(String deviceUUID, ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "Read from: " + deviceUUID);
        String serviceUUID = options.getString("service");
        String characteristicUUID = options.getString("characteristic");
        Integer timeoutMs = getTimeoutMs(options);

        if (serviceUUID == null || characteristicUUID == null) {
            callback.invoke("service and characteristic required.");
            return;
        }

        MyPeripheral peripheral = retrievePeripheral(deviceUUID);
        if (peripheral != null) {
            peripheral.read(UUIDHelper.uuidFromString(serviceUUID), UUIDHelper.uuidFromString(characteristicUUID),
                    timeoutMs, callback);
        } else {
            callback.invoke("Peripheral not found", null);
        }
    }

    @ReactMethod
    public void readDescriptor(String deviceUUID, String serviceUUID, String characteristicUUID, String descriptorUUID, Callback callback) {
        Log.d(LOG_TAG, "Read descriptor from: " + deviceUUID);
        callback.invoke("Not supported");
    }

    @ReactMethod
    public void writeDescriptor(String deviceUUID, String serviceUUID, String characteristicUUID, String descriptorUUID, ReadableArray message, Callback callback) {
        Log.d(LOG_TAG, "Write descriptor from: " + deviceUUID);
        callback.invoke("Not supported");
    }

    @ReactMethod
    public void retrieveServices(String deviceUUID, ReadableMap options, Callback callback) {
        Log.d(LOG_TAG, "Retrieve services from: " + deviceUUID);
        MyPeripheral peripheral = retrievePeripheral(deviceUUID);
        if (peripheral != null) {
            Integer timeoutMs = getTimeoutMs(options);
            peripheral.retrieveServices(timeoutMs, callback);
        } else {
            callback.invoke("Peripheral not found", null);
        }
    }

    @ReactMethod
    public void refreshCache(String deviceUUID, Callback callback) {
        Log.d(LOG_TAG, "Refreshing cache for: " + deviceUUID);
        callback.invoke("Not supported");
    }

    @ReactMethod
    public void readRSSI(String deviceUUID, Callback callback) {
        Log.d(LOG_TAG, "Read RSSI from: " + deviceUUID);
        callback.invoke("Not supported");
    }

    public MyPeripheral savePeripheral(BluetoothDevice device) {
        String address = device.getAddress();
        synchronized (peripherals) {
            if (!peripherals.containsKey(address)) {
                MyPeripheral peripheral = new MyPeripheral(device, reactContext);
                peripherals.put(device.getAddress(), peripheral);
            }
            return peripherals.get(address);
        }
    }

    public MyPeripheral getPeripheral(BluetoothDevice device) {
        return retrievePeripheral(device.getAddress());
    }

    public MyPeripheral savePeripheral(MyPeripheral peripheral) {
        synchronized (peripherals) {
            peripherals.put(peripheral.getDevice().getAddress(), peripheral);
        }
        return peripheral;
    }

    @ReactMethod
    public void checkState(Callback callback) {
        Log.d(LOG_TAG, "checkState");

        BluetoothAdapter adapter = getBluetoothAdapter();
        String state = "off";
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            state = "unsupported";
        } else if (adapter != null) {
            switch (adapter.getState()) {
                case BluetoothAdapter.STATE_ON:
                    state = "on";
                    break;
                case BluetoothAdapter.STATE_TURNING_ON:
                    state = "turning_on";
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    state = "turning_off";
                    clearPeripherals();
                    if (scanManager != null) {
                        scanManager.setScanning(false);
                    }
                    break;
                case BluetoothAdapter.STATE_OFF:
                default:
                    // should not happen as per https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getState()
                    state = "off";
                    if (scanManager != null) {
                        scanManager.setScanning(false);
                    }
                    break;
            }
        }

        WritableMap map = Arguments.createMap();
        map.putString("state", state);
        Log.d(LOG_TAG, "state:" + state);
        sendEvent("BleManagerDidUpdateState", map);
        callback.invoke(state);
    }

    @ReactMethod
    public void isScanning(Callback callback) {
        if (scanManager != null) {
            callback.invoke(null, scanManager.isScanning());
        } else {
            callback.invoke(null, false);
        }
    }

    @ReactMethod
    @SuppressLint("MissingPermission")
    public void setName(String name) {
        BluetoothAdapter adapter = getBluetoothAdapter();
        adapter.setName(name);
    }

    @SuppressLint("MissingPermission")
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "onReceive");
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                String stringState = "";

                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        stringState = "off";
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        stringState = "turning_off";
                        clearPeripherals();
                        break;
                    case BluetoothAdapter.STATE_ON:
                        stringState = "on";
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        stringState = "turning_on";
                        break;
                    default:
                        // should not happen as per https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#EXTRA_STATE
                        stringState = "off";
                        break;
                }

                WritableMap map = Arguments.createMap();
                map.putString("state", stringState);
                Log.d(LOG_TAG, "state: " + stringState);
                sendEvent("BleManagerDidUpdateState", map);

            } else if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                final int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.ERROR);
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }

                String bondStateStr = "UNKNOWN";
                switch (bondState) {
                    case BluetoothDevice.BOND_BONDED:
                        bondStateStr = "BOND_BONDED";
                        break;
                    case BluetoothDevice.BOND_BONDING:
                        bondStateStr = "BOND_BONDING";
                        break;
                    case BluetoothDevice.BOND_NONE:
                        bondStateStr = "BOND_NONE";
                        break;
                }
                Log.d(LOG_TAG, "bond state: " + bondStateStr);

                if (bondRequest != null && bondRequest.uuid.equals(device.getAddress())) {
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        bondRequest.callback.invoke();
                        bondRequest = null;
                    } else if (bondState == BluetoothDevice.BOND_NONE || bondState == BluetoothDevice.ERROR) {
                        bondRequest.callback.invoke("Bond request has been denied");
                        bondRequest = null;
                    }
                }

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    MyPeripheral peripheral = new MyPeripheral(device, reactContext);
                    WritableMap map = peripheral.asWritableMap();
                    sendEvent("BleManagerPeripheralDidBond", map);
                }

                if (removeBondRequest != null && removeBondRequest.uuid.equals(device.getAddress())
                        && bondState == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDED) {
                    removeBondRequest.callback.invoke();
                    removeBondRequest = null;
                }
            } else if (action.equals(BluetoothDevice.ACTION_PAIRING_REQUEST)) {
                BluetoothDevice bluetoothDevice;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    bluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                if (bondRequest != null && bondRequest.uuid.equals(bluetoothDevice.getAddress()) && bondRequest.pin != null) {
                    bluetoothDevice.setPin(bondRequest.pin.getBytes());
                    bluetoothDevice.createBond();
                }
            }

        }
    };

    private void clearPeripherals() {
        synchronized (peripherals) {
            peripherals.forEach((k, p) -> {
                p.close();
            });
            peripherals.clear();
        }
    }

    @ReactMethod
    public void getDiscoveredPeripherals(Callback callback) {
        Log.d(LOG_TAG, "Get discovered peripherals");
        WritableArray map = Arguments.createArray();
        synchronized (peripherals) {
            for (Map.Entry<String, MyPeripheral> entry : peripherals.entrySet()) {
                MyPeripheral peripheral = entry.getValue();
                WritableMap jsonBundle = peripheral.asWritableMap();
                map.pushMap(jsonBundle);
            }
        }
        callback.invoke(null, map);
    }

    @SuppressLint("MissingPermission")
    @ReactMethod
    public void getConnectedPeripherals(ReadableArray serviceUUIDs, Callback callback) {
        Log.d(LOG_TAG, "Get connected peripherals");
        WritableArray map = Arguments.createArray();

        if (getBluetoothAdapter() == null) {
            Log.d(LOG_TAG, "No bluetooth support");
            callback.invoke("No bluetooth support");
            return;
        }

        List<BluetoothDevice> peripherals = getBluetoothManager().getConnectedDevices(GATT);
        for (BluetoothDevice entry : peripherals) {
            MyPeripheral peripheral = savePeripheral(entry);
            WritableMap jsonBundle = peripheral.asWritableMap();
            map.pushMap(jsonBundle);
        }
        callback.invoke(null, map);
    }

    @SuppressLint("MissingPermission")
    @ReactMethod
    public void getBondedPeripherals(Callback callback) {
        Log.d(LOG_TAG, "Get bonded peripherals");
        WritableArray map = Arguments.createArray();
        Set<BluetoothDevice> deviceSet = getBluetoothAdapter().getBondedDevices();
        for (BluetoothDevice device : deviceSet) {
            MyPeripheral peripheral = new MyPeripheral(device, reactContext);
            WritableMap jsonBundle = peripheral.asWritableMap();
            map.pushMap(jsonBundle);
        }
        callback.invoke(null, map);
    }

    @ReactMethod
    public void removePeripheral(String deviceUUID, Callback callback) {
        Log.d(LOG_TAG, "Removing from list: " + deviceUUID);
        synchronized (peripherals) {
            MyPeripheral peripheral = peripherals.remove(deviceUUID);
            if (peripheral != null) {
                peripheral.close();
                callback.invoke();
            } else {
                callback.invoke("Peripheral not found");
            }
        }
    }

    @ReactMethod
    public void requestConnectionPriority(String deviceUUID, int connectionPriority, Callback callback) {
        Log.d(LOG_TAG, "Request connection priority of " + connectionPriority + " from: " + deviceUUID);
        callback.invoke("Not supported");
    }

    @ReactMethod
    public void requestMTU(String deviceUUID, ReadableMap options, Callback callback) {
        int mtu = options.getInt("mtu");
        Integer timeoutMs = getTimeoutMs(options);
        Log.d(LOG_TAG, "Request MTU of " + mtu + " bytes from: " + deviceUUID);
        MyPeripheral peripheral = retrievePeripheral(deviceUUID);
        if (peripheral != null) {
            peripheral.requestMTU(mtu, timeoutMs, callback);
        } else {
            callback.invoke("Peripheral not found", null);
        }
    }

    @ReactMethod
    public void getAssociatedPeripherals(Callback callback) {
        Log.d(LOG_TAG, "Get associated peripherals");
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            callback.invoke("Not supported");
            return;
        }

        WritableArray peripherals = Arguments.createArray();
        for (String address : ((CompanionDeviceManager) getCompanionDeviceManager()).getAssociations()) {
            peripherals.pushMap(retrieveOrCreatePeripheral(address).asWritableMap());
        }

        callback.invoke(null, peripherals);
    }

    @ReactMethod
    public void removeAssociatedPeripheral(String address, Callback callback) {
        Log.d(LOG_TAG, "Remove associated peripheral: " + address);
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            callback.invoke("Not supported");
            return;
        }

        CompanionDeviceManager manager = (CompanionDeviceManager) getCompanionDeviceManager();
        for (String association : manager.getAssociations()) {
            if (association.equals(address)) {
                manager.disassociate(address);
                callback.invoke();
                return;
            }
        }

        callback.invoke("device not found");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public Object getCompanionDeviceManager() {
        return reactContext
                .getCurrentActivity().getSystemService(Context.COMPANION_DEVICE_SERVICE);
    }

    private static @Nullable Integer getTimeoutMs(ReadableMap options) {
        if (options.hasKey("timeoutMs") && !options.isNull("timeoutMs")) {
            return options.getInt("timeoutMs");
        }
        return null;
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static WritableArray bytesToWritableArray(byte[] bytes) {
        WritableArray value = Arguments.createArray();
        for (int i = 0; i < bytes.length; i++)
            value.pushInt((bytes[i] & 0xFF));
        return value;
    }

    private MyPeripheral retrievePeripheral(String peripheralUUID) {
        synchronized (peripherals) {
            return peripherals.get(peripheralUUID);
        }
    }

    private MyPeripheral retrieveOrCreatePeripheral(String peripheralUUID) {
        synchronized (peripherals) {
            MyPeripheral peripheral = peripherals.get(peripheralUUID);
            if (peripheral == null) {
                if (peripheralUUID != null) {
                    peripheralUUID = peripheralUUID.toUpperCase();
                }
                if (BluetoothAdapter.checkBluetoothAddress(peripheralUUID)) {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(peripheralUUID);
                    peripheral = new MyPeripheral(device, reactContext);
                    peripherals.put(peripheralUUID, peripheral);
                }
            }
            return peripheral;
        }
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @Override
    public void onCatalystInstanceDestroy() {
        clearPeripherals();

        try {
            context.unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException e) {
            // Not registered, ignore.
        }

        if (scanManager != null) {
            // Stop scan in case one was started to stop events from being emitted after destroy
            scanManager.stopScan(args -> {
            });
        }
    }
}

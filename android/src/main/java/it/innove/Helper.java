package it.innove;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.SparseArray;

import androidx.annotation.RequiresPermission;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import org.json.JSONException;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Helper {

	static WritableMap byteArrayToWritableMap(byte[] bytes) throws JSONException {
		WritableMap object = Arguments.createMap();
		object.putString("CDVType", "ArrayBuffer");
		object.putString("data", bytes != null ? Base64.encodeToString(bytes, Base64.NO_WRAP) : null);
		object.putArray("bytes", bytes != null ? BleManager.bytesToWritableArray(bytes) : null);
		return object;
	}

	@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public static WritableMap asWritableMap(
			BluetoothDevice device,
			int advertisingRSSI,
			byte[] advertisingDataBytes,
			ScanResult scanResult,
			ScanRecord advertisingData
	) {
		WritableMap map = Arguments.createMap();
		WritableMap advertising = Arguments.createMap();

		try {
			map.putString("name", device.getName());
			map.putString("id", device.getAddress()); // mac address
			map.putInt("rssi", advertisingRSSI);

			String name = device.getName();
			if (name != null)
				advertising.putString("localName", name);

			advertising.putMap("rawData", byteArrayToWritableMap(advertisingDataBytes));

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				// We can check if peripheral is connectable using the scanresult
				if (scanResult != null) {
					advertising.putBoolean("isConnectable", scanResult.isConnectable());
				}
			} else {
				// We can't check if peripheral is connectable
				advertising.putBoolean("isConnectable", true);
			}

			if (advertisingData != null) {
				String deviceName = advertisingData.getDeviceName();
				if (deviceName != null)
					advertising.putString("localName", deviceName.replace("\0", ""));

				WritableArray serviceUuids = Arguments.createArray();
				if (advertisingData.getServiceUuids() != null && advertisingData.getServiceUuids().size() != 0) {
					for (ParcelUuid uuid : advertisingData.getServiceUuids()) {
						serviceUuids.pushString(UUIDHelper.uuidToString(uuid.getUuid()));
					}
				}
				advertising.putArray("serviceUUIDs", serviceUuids);

				WritableMap serviceData = Arguments.createMap();
				if (advertisingData.getServiceData() != null) {
					for (Map.Entry<ParcelUuid, byte[]> entry : advertisingData.getServiceData().entrySet()) {
						if (entry.getValue() != null) {
							serviceData.putMap(UUIDHelper.uuidToString((entry.getKey()).getUuid()), byteArrayToWritableMap(entry.getValue()));
						}
					}
				}
				advertising.putMap("serviceData", serviceData);

				WritableMap manufacturerData = Arguments.createMap();
				SparseArray<byte[]> manufacturerRawData = advertisingData.getManufacturerSpecificData();
				byte[] manufacturerRawBytes = new byte[0];
				if (manufacturerRawData != null && manufacturerRawData.size() > 0) {
					int key = manufacturerRawData.keyAt(0);
					byte[] data = manufacturerRawData.valueAt(0);
					manufacturerData.putMap(String.format("%04x", key), byteArrayToWritableMap(data));

					ByteBuffer keyBuffer = ByteBuffer.allocate(Integer.SIZE / Byte.SIZE);
					keyBuffer.putInt(key);
					byte[] keyBytes = keyBuffer.array();
					manufacturerRawBytes = new byte[keyBytes.length + data.length];
					System.arraycopy(keyBytes, 0, manufacturerRawBytes, 0, keyBytes.length);
					System.arraycopy(data, 0, manufacturerRawBytes, keyBytes.length, data.length);
				}
				advertising.putMap("manufacturerData", manufacturerData);
				advertising.putMap("manufacturerRawData", byteArrayToWritableMap(manufacturerRawBytes));

				advertising.putInt("txPowerLevel", advertisingData.getTxPowerLevel());
			}

			map.putMap("advertising", advertising);
		} catch (Exception e) { // this shouldn't happen
			e.printStackTrace();
		}

		return map;
	}

	public static void addServiceInfoToWritableMap(WritableMap map, List<BluetoothGattService> services) {
		WritableArray servicesArray = Arguments.createArray();
		WritableArray characteristicsArray = Arguments.createArray();

		for (Iterator<BluetoothGattService> it = services.iterator(); it.hasNext();) {
			BluetoothGattService service = it.next();
			WritableMap serviceMap = Arguments.createMap();
			serviceMap.putString("uuid", UUIDHelper.uuidToString(service.getUuid()));

			for (Iterator<BluetoothGattCharacteristic> itCharacteristic = service.getCharacteristics()
					.iterator(); itCharacteristic.hasNext();) {
				BluetoothGattCharacteristic characteristic = itCharacteristic.next();
				WritableMap characteristicsMap = Arguments.createMap();

				characteristicsMap.putString("service", UUIDHelper.uuidToString(service.getUuid()));
				characteristicsMap.putString("characteristic", UUIDHelper.uuidToString(characteristic.getUuid()));

				characteristicsMap.putMap("properties", Helper.decodeProperties(characteristic));

				if (characteristic.getPermissions() > 0) {
					characteristicsMap.putMap("permissions", Helper.decodePermissions(characteristic));
				}

				WritableArray descriptorsArray = Arguments.createArray();

				for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
					WritableMap descriptorMap = Arguments.createMap();
					descriptorMap.putString("uuid", UUIDHelper.uuidToString(descriptor.getUuid()));
					if (descriptor.getValue() != null) {
						descriptorMap.putString("value",
								Base64.encodeToString(descriptor.getValue(), Base64.NO_WRAP));
					} else {
						descriptorMap.putString("value", null);
					}

					if (descriptor.getPermissions() > 0) {
						descriptorMap.putMap("permissions", Helper.decodePermissions(descriptor));
					}
					descriptorsArray.pushMap(descriptorMap);
				}
				if (descriptorsArray.size() > 0) {
					characteristicsMap.putArray("descriptors", descriptorsArray);
				}
				characteristicsArray.pushMap(characteristicsMap);
			}
			servicesArray.pushMap(serviceMap);
		}

		map.putArray("services", servicesArray);
		map.putArray("characteristics", characteristicsArray);
	}
	public static WritableMap decodeProperties(BluetoothGattCharacteristic characteristic) {

		// NOTE: props strings need to be consistent across iOS and Android
		WritableMap props = Arguments.createMap();
		int properties = characteristic.getProperties();

		if ((properties & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0x0 ) {
			props.putString("Broadcast", "Broadcast");
		}

		if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0x0 ) {
			props.putString("Read", "Read");
		}

		if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0x0 ) {
			props.putString("WriteWithoutResponse", "WriteWithoutResponse");
		}

		if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0x0 ) {
			props.putString("Write", "Write");
		}

		if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0x0 ) {
			props.putString("Notify", "Notify");
		}

		if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0x0 ) {
			props.putString("Indicate", "Indicate");
		}

		if ((properties & BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0x0 ) {
			// Android calls this "write with signature", using iOS name for now
			props.putString("AuthenticateSignedWrites", "AuthenticateSignedWrites");
		}

		if ((properties & BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0x0 ) {
			props.putString("ExtendedProperties", "ExtendedProperties");
		}

//      iOS only?
//
//            if ((p & CBCharacteristicPropertyNotifyEncryptionRequired) != 0x0) {  // 0x100
//                [props addObject:@"NotifyEncryptionRequired"];
//            }
//
//            if ((p & CBCharacteristicPropertyIndicateEncryptionRequired) != 0x0) { // 0x200
//                [props addObject:@"IndicateEncryptionRequired"];
//            }

		return props;
	}

	public static WritableMap decodePermissions(BluetoothGattCharacteristic characteristic) {

		// NOTE: props strings need to be consistent across iOS and Android
		WritableMap props = Arguments.createMap();
		int permissions = characteristic.getPermissions();

		if ((permissions & BluetoothGattCharacteristic.PERMISSION_READ) != 0x0 ) {
			props.putString("Read", "Read");
		}

		if ((permissions & BluetoothGattCharacteristic.PERMISSION_WRITE) != 0x0 ) {
			props.putString("Write", "Write");
		}

		if ((permissions & BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED) != 0x0 ) {
			props.putString("ReadEncrypted", "ReadEncrypted");
		}

		if ((permissions & BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED) != 0x0 ) {
			props.putString("WriteEncrypted", "WriteEncrypted");
		}

		if ((permissions & BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM) != 0x0 ) {
			props.putString("ReadEncryptedMITM", "ReadEncryptedMITM");
		}

		if ((permissions & BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM) != 0x0 ) {
			props.putString("WriteEncryptedMITM", "WriteEncryptedMITM");
		}

		if ((permissions & BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED) != 0x0 ) {
			props.putString("WriteSigned", "WriteSigned");
		}

		if ((permissions & BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM) != 0x0 ) {
			props.putString("WriteSignedMITM", "WriteSignedMITM");
		}

		return props;
	}

	public static WritableMap decodePermissions(BluetoothGattDescriptor descriptor) {

		// NOTE: props strings need to be consistent across iOS and Android
		WritableMap props = Arguments.createMap();
		int permissions = descriptor.getPermissions();

		if ((permissions & BluetoothGattDescriptor.PERMISSION_READ) != 0x0 ) {
			props.putString("Read", "Read");
		}

		if ((permissions & BluetoothGattDescriptor.PERMISSION_WRITE) != 0x0 ) {
			props.putString("Write", "Write");
		}

		if ((permissions & BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED) != 0x0 ) {
			props.putString("ReadEncrypted", "ReadEncrypted");
		}

		if ((permissions & BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED) != 0x0 ) {
			props.putString("WriteEncrypted", "WriteEncrypted");
		}

		if ((permissions & BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED_MITM) != 0x0 ) {
			props.putString("ReadEncryptedMITM", "ReadEncryptedMITM");
		}

		if ((permissions & BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM) != 0x0 ) {
			props.putString("WriteEncryptedMITM", "WriteEncryptedMITM");
		}

		if ((permissions & BluetoothGattDescriptor.PERMISSION_WRITE_SIGNED) != 0x0 ) {
			props.putString("WriteSigned", "WriteSigned");
		}

		if ((permissions & BluetoothGattDescriptor.PERMISSION_WRITE_SIGNED_MITM) != 0x0 ) {
			props.putString("WriteSignedMITM", "WriteSignedMITM");
		}

		return props;
	}

}
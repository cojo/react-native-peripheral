package com.reactnative.peripheral

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat.startActivityForResult
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.*

class BluetoothRequest(val device: BluetoothDevice, val requestId: Int, val offset: Int, val value: ByteArray)

class RnBlePeripheralModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private var servicesMap: MutableMap<String, BluetoothGattService> = mutableMapOf()
    private var devices = HashSet<BluetoothDevice>()
    private var manager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingCallback: AdvertiseCallback? = null
    private var isAdvertising = false
    private val pendingRequests: MutableMap<String, BluetoothRequest> = mutableMapOf()
    private val statusMap: Map<String, Int> = mapOf(
            "success" to BluetoothGatt.GATT_SUCCESS,
            "invalidHandle" to 0x01,
            "readNotPermitted" to BluetoothGatt.GATT_READ_NOT_PERMITTED,
            "writeNotPermitted" to BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
            "invalidPdu" to 0x04,
            "insufficientAuthentication" to BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
            "requestNotSupported" to BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
            "invalidOffset" to BluetoothGatt.GATT_INVALID_OFFSET,
            "insufficientAuthorization" to 0x08,
            "prepareQueueFull" to 0x09,
            "attributeNotFound" to 0x0A,
            "attributeNotLong" to 0x0B,
            "insufficientEncryptionKeySize" to 0x0C,
            "invalidAttributeValueLength" to 0x0D,
            "unlikelyError" to 0x0E,
            "insufficientEncryption" to 0x0F,
            "unsupportedGroupType" to 0x10,
            "insufficientResources" to 0x11,
    )

    private val gattServerCallback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    devices.add(device)
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    devices.remove(device)
                }
            } else {
                devices.remove(device)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            val request = BluetoothRequest(device, requestId, offset, characteristic.value)
            val uuid = UUID.randomUUID().toString()
            pendingRequests.put(uuid, request)
            sendEvent(READ_REQUEST, Arguments.makeNativeMap(mapOf(
                    "requestId" to uuid.toString(),
                    "offset" to offset,
                    "characteristicUuid" to characteristic.uuid.toString(),
                    "serviceUuid" to characteristic.service.uuid.toString()
            )))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                                                  characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
                                                  offset: Int, value: ByteArray) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                    responseNeeded, offset, value)
            characteristic.value = value
            val request = BluetoothRequest(device, requestId, offset, characteristic.value)
            val uuid = UUID.randomUUID().toString()
            pendingRequests.put(uuid, request)
            sendEvent(WRITE_REQUEST, Arguments.makeNativeMap(mapOf(
                    "requestId" to uuid.toString(),
                    "offset" to offset,
                    "value" to Base64.encodeToString(value, Base64.DEFAULT),
                    "characteristicUuid" to characteristic.uuid.toString(),
                    "serviceUuid" to characteristic.service.uuid.toString()
            )))
        }
    }

    companion object {
        const val REQUEST_ENABLE_BT = 12
        const val READ_REQUEST = "BlePeripheral:ReadRequest"
        const val WRITE_REQUEST = "BlePeripheral:WriteRequest"
        const val STATE_CHANGED = "BlePeripheral:StateChanged"
        const val SUBSCRIBED = "BlePeripheral:Subscribed"
        const val UNSUBSCRIBED = "BlePeripheral:Unsubscribed"
    }
    override fun initialize() {
        super.initialize()

    }
    override fun getName(): String = "RNBlePeripheral"

    override fun getConstants(): MutableMap<String, Any> = mutableMapOf(
            "READ_REQUEST" to READ_REQUEST,
            "WRITE_REQUEST" to WRITE_REQUEST,
            "STATE_CHANGED" to STATE_CHANGED,
            "SUBSCRIBED" to SUBSCRIBED,
            "UNSUBSCRIBED" to UNSUBSCRIBED,
    )

    fun sendEvent(eventName: String, params: WritableMap?)
        = reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(eventName, params)

    @ReactMethod
    fun getState(promise: Promise) {
        if (manager == null) {
            manager = reactApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        }
        if (adapter == null) {
            adapter = manager?.adapter
        }
        if (adapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(this.currentActivity!!, enableBtIntent, REQUEST_ENABLE_BT, null)
        }
        val stateString = when (adapter?.state) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_TURNING_ON -> "poweredOff"
            BluetoothAdapter.STATE_ON -> "poweredOn"
            else -> "unknown"
        }
        Log.i("RnBlePeripheral", "Bluetooth Adapter state: $stateString")
        promise.resolve(stateString)
    }

    @ReactMethod
    fun addService(service: ReadableMap, promise: Promise) {
        val uuid = service.getString("uuid")!!
        val serviceUuid: UUID = UUID.fromString(uuid)
        val type = BluetoothGattService.SERVICE_TYPE_PRIMARY
        val tempService = BluetoothGattService(serviceUuid, type)
        if (!this.servicesMap.containsKey(uuid)) this.servicesMap.put(uuid, tempService)
        val characteristics = service.getArray("characteristics")!!
        for (idx in 0 until characteristics.size()) {
            val characteristic = characteristics.getMap(idx)
            val charUuid = UUID.fromString(characteristic.getString("uuid"))
            // NOTE: temporary force write-only property / permission -cojo
            val tempChar = BluetoothGattCharacteristic(charUuid, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
            servicesMap[uuid]!!.addCharacteristic(tempChar)
        }
        promise.resolve(true)
    }

    @ReactMethod
    fun respond(requestId: String, status: String, valueBase64: String?, promise: Promise) {
        val request = pendingRequests[requestId]
        if (request == null) {
            promise.reject("invalid_request", "Request with the given id does not exist.", null)
            return
        }
        val value = valueBase64?.let { Base64.decode(it, Base64.DEFAULT) } ?: request.value
        gattServer?.sendResponse(request.device, request.requestId, BluetoothGatt.GATT_SUCCESS, request.offset, value)

        pendingRequests.remove(requestId)

        promise.resolve(null)
    }

    @ReactMethod
    fun startAdvertising(data: ReadableMap, promise: Promise) {
        val name = data.getString("name")
        if (manager == null) {
            manager = reactApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        }
        if (adapter == null) {
            adapter = manager?.adapter
        }
        adapter?.name = name
        devices = HashSet()
        gattServer = manager?.openGattServer(reactApplicationContext, gattServerCallback)
        for (service in servicesMap.values) {
            gattServer?.addService(service)
        }
        advertiser = adapter?.getBluetoothLeAdvertiser()
        val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()


        val dataBuilder = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
        for (service in servicesMap.values) {
            dataBuilder.addServiceUuid(ParcelUuid(service.uuid))
        }
        val data = dataBuilder.build()
        Log.i("RnBlePeripheralModule", data.toString())

        advertisingCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                isAdvertising = true
                promise.resolve("Success, Started Advertising")
            }

            override fun onStartFailure(errorCode: Int) {
                isAdvertising = false
                Log.e("RnBlePeripheralModule", "Advertising onStartFailure: $errorCode")
                promise.reject("Advertising onStartFailure: $errorCode")
                super.onStartFailure(errorCode)
            }
        }

        advertiser?.startAdvertising(settings, data, advertisingCallback)
    }
}
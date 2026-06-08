package com.example.walkingphoneguard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID

data class ImuValues(
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f
)

class BleImuManager(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onValuesChanged: (ImuValues) -> Unit,
    private val onRawTextChanged: (String) -> Unit
) {
    companion object {
        private val SERVICE_UUID =
            UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")

        private val ACCEL_UUID =
            UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")

        private val LED_UUID =
            UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")

        private val CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val TARGET_DEVICE_NAME = "FocusGuardNano33"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private var accelCharacteristic: BluetoothGattCharacteristic? = null
    private var ledCharacteristic: BluetoothGattCharacteristic? = null

    private var latestAx = 0f
    private var latestAy = 0f
    private var latestAz = 0f

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBlePermissions()) {
            onStatusChanged("Bluetooth権限がありません")
            return
        }

        if (!isBluetoothEnabled()) {
            onStatusChanged("BluetoothがOFFです")
            return
        }

        if (isScanning) return

        onStatusChanged("スキャン中...")
        isScanning = true

        try {
            scanner?.startScan(scanCallback)
        } catch (_: Exception) {
            isScanning = false
            onStatusChanged("スキャン開始に失敗しました")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }

        isScanning = false
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (isScanning) stopScan()

        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {
        }

        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {
        }

        bluetoothGatt = null
        accelCharacteristic = null
        ledCharacteristic = null
        onStatusChanged("未接続")
    }

    @SuppressLint("MissingPermission")
    fun sendLedCommand(command: String) {
        val gatt = bluetoothGatt
        val characteristic = ledCharacteristic

        if (gatt == null || characteristic == null) {
            onStatusChanged("LED送信不可：未接続")
            return
        }

        try {
            val bytes = command.toByteArray(Charsets.UTF_8)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: Exception) {
            onStatusChanged("LED送信に失敗しました")
        }
    }

    fun ledOn() {
        sendLedCommand("1")
    }

    fun ledOff() {
        sendLedCommand("0")
    }

    fun ledBlink() {
        sendLedCommand("B")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = try {
                result.device.name
            } catch (_: Exception) {
                null
            }

            onStatusChanged("発見: ${deviceName ?: "名前なし"}")

            if (deviceName == TARGET_DEVICE_NAME) {
                stopScan()
                connectToDevice(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            onStatusChanged("スキャン失敗: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(result: ScanResult) {
        if (!hasBlePermissions()) {
            onStatusChanged("Bluetooth権限がありません")
            return
        }

        onStatusChanged("接続中...")

        try {
            bluetoothGatt = result.device.connectGatt(context, false, gattCallback)
        } catch (_: Exception) {
            onStatusChanged("接続開始に失敗しました")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatusChanged("接続成功・サービス確認中...")
                    try {
                        gatt.discoverServices()
                    } catch (_: Exception) {
                        onStatusChanged("サービス確認に失敗しました")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStatusChanged("切断されました")
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }

                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                        accelCharacteristic = null
                        ledCharacteristic = null
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID)

            if (service == null) {
                onStatusChanged("サービスが見つかりません")
                return
            }

            accelCharacteristic = service.getCharacteristic(ACCEL_UUID)
            ledCharacteristic = service.getCharacteristic(LED_UUID)

            if (accelCharacteristic == null) {
                onStatusChanged("加速度特性が見つかりません")
                return
            }

            if (ledCharacteristic == null) {
                onStatusChanged("LED特性が見つかりません")
                return
            }

            enableNotification(gatt, accelCharacteristic!!)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged("通知有効化失敗: $status")
                return
            }

            onStatusChanged("受信中...")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristic(characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristic(characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        try {
            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(CCCD_UUID)

            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } else {
                onStatusChanged("通知descriptorが見つかりません")
            }
        } catch (_: Exception) {
            onStatusChanged("通知設定に失敗しました")
        }
    }

    private fun handleCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray
    ) {
        if (characteristic.uuid != ACCEL_UUID) return

        val raw = bytes.toString(Charsets.UTF_8)
            .replace("\u0000", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()

        if (raw.isBlank()) return

        val parts = raw.split(",").map { it.trim() }

        if (parts.size >= 3) {
            latestAx = parts[0].toFloatOrNull() ?: latestAx
            latestAy = parts[1].toFloatOrNull() ?: latestAy
            latestAz = parts[2].toFloatOrNull() ?: latestAz
        }

        val imu = ImuValues(
            ax = latestAx,
            ay = latestAy,
            az = latestAz
        )

        onValuesChanged(imu)

        onRawTextChanged(
            """
AX(raw): $latestAx
AY(raw): $latestAy
AZ(raw): $latestAz
            """.trimIndent()
        )

        onStatusChanged("受信中...")
    }
}
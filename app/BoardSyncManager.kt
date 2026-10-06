package com.ideawav.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * 持续 BLE 连接管理器：用于向开发板写入车速、读/写换挡开关。
 *
 * 与 CalibrationManager（一次性"连接-操作-断开"）不同，本类维护一个长期 GATT
 * 连接，不断开；车速由 MainActivity 的 GPS 回调持续写入，换挡开关由设置页读写。
 * 连接与系统 HID 连接复用同一 ACL，写入（下行）不影响 HID 油门（上行）。
 */
class BoardSyncManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: BoardSyncManager? = null

        fun getInstance(context: Context): BoardSyncManager =
            instance ?: synchronized(this) {
                instance ?: BoardSyncManager(context.applicationContext).also { instance = it }
            }

        private const val DEVICE_NAME = "模拟声浪1.0"
        private const val SVC_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
        private const val SPEED_CHAR_UUID = "0000ffe3-0000-1000-8000-00805f9b34fb"
        private const val SHIFT_CHAR_UUID = "0000ffe4-0000-1000-8000-00805f9b34fb"
        private const val THROTTLE_CHAR_UUID = "0000ffe5-0000-1000-8000-00805f9b34fb"
        private const val MODE_CHAR_UUID = "0000ffe6-0000-1000-8000-00805f9b34fb"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private var speedCharacteristic: BluetoothGattCharacteristic? = null
    private var shiftCharacteristic: BluetoothGattCharacteristic? = null
    private var throttleCharacteristic: BluetoothGattCharacteristic? = null
    private var modeCharacteristic: BluetoothGattCharacteristic? = null

    private var connected = false
    private var connecting = false
    private var lastSpeed = -1
    private var onReady: (() -> Unit)? = null
    private var onShiftState: ((Boolean) -> Unit)? = null
    private var throttleChannelGatt = false

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /** 建立并保持连接。连接成功后回调 onReady（可能已被连接，则立即回调）。 */
    fun connect(onReady: (() -> Unit)? = null) {
        this.onReady = onReady
        if (connected) {
            onReady?.invoke()
            return
        }
        if (connecting) return
        connecting = true
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            connecting = false
            return
        }
        // 优先从已配对设备找：HID 连接后设备已配对且不再广播，扫描会找不到
        val device = findBondedDevice(adapter)
        if (device != null) {
            connectDevice(device)
        } else {
            startScan()
        }
    }

    /**
     * 从已配对设备中查找目标开发板。
     * Android 12+ 读取已配对设备需要 BLUETOOTH_CONNECT 权限，缺失或发生异常时返回 null，
     * 交由 startScan() 处理（其内部已捕获 BLUETOOTH_SCAN 权限异常，不会崩溃）。
     */
    private fun findBondedDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            adapter.bondedDevices.firstOrNull { it.name == DEVICE_NAME }
        } catch (_: SecurityException) {
            null
        }
    }

    /** 当前车速（KM/H），由 GPS 更新，供 MainActivity 读取。 */
    var currentSpeedKmh = 0f
    /** 车速变化回调（MainActivity 前台时用于更新表盘 + 声浪）。 */
    var onSpeedChanged: ((Float) -> Unit)? = null

    /** 油门回调（0~255），GATT 通道模式下由开发板 notify，后台也能收到。 */
    var onThrottleChanged: ((Int) -> Unit)? = null

    /** GPS 车速统一入口：记录当前值、发送给开发板、通知监听者。 */
    fun updateSpeed(kmh: Float) {
        currentSpeedKmh = kmh
        sendSpeed(kmh.toInt())
        onSpeedChanged?.invoke(kmh)
    }

    /** 写入车速（KM/H，0~255）。仅在值变化时写，减少无谓的 GATT 操作。 */
    fun sendSpeed(kmh: Int) {
        val value = kmh.coerceIn(0, 255)
        if (value == lastSpeed) return
        lastSpeed = value
        writeByte(speedCharacteristic, value)
    }

    /** 写入换挡开关（true=开启换挡，false=关闭）。 */
    fun setShiftEnabled(enabled: Boolean) {
        writeByte(shiftCharacteristic, if (enabled) 1 else 0)
    }

    /**
     * 设置油门通道模式并同步到开发板（换挡信号与通道相反：GATT 通道 → 关换挡，HID 通道 → 开换挡）。
     * @param gatt true=GATT 通道（后台播放，App 收油门）；false=HID 通道（前台 revheadz 收 RT）。
     * 未连接时先记录，连接成功后（onServicesDiscovered）自动补写。
     */
    fun setThrottleChannel(gatt: Boolean) {
        throttleChannelGatt = gatt
        syncThrottleChannel()
    }

    /** 同步通道模式到开发板（换挡信号在模式写完成后由 onCharacteristicWrite 补写，避免连续写竞争丢包）。 */
    private fun syncThrottleChannel() {
        writeByte(modeCharacteristic, if (throttleChannelGatt) 1 else 0)
    }

    /** 读取换挡开关状态（连接成功后读特征值并回调）。 */
    fun readShiftEnabled(callback: (Boolean) -> Unit) {
        onShiftState = callback
        connect {
            val ch = shiftCharacteristic
            if (ch != null && connected) {
                try {
                    gatt?.readCharacteristic(ch)
                } catch (_: SecurityException) {
                    callback(false)
                }
            } else {
                callback(false)
            }
        }
    }

    fun release() {
        stopScan()
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        speedCharacteristic = null
        shiftCharacteristic = null
        throttleCharacteristic = null
        modeCharacteristic = null
        connected = false
        connecting = false
        onReady = null
        onShiftState = null
        onSpeedChanged = null
        onThrottleChanged = null
        instance = null
    }

    // ==================== 内部：扫描 / 连接 / 回调 ====================

    private fun writeByte(ch: BluetoothGattCharacteristic?, value: Int) {
        if (ch == null || !connected) return
        ch.value = byteArrayOf(value.toByte())
        try {
            gatt?.writeCharacteristic(ch)
        } catch (_: SecurityException) {
        }
    }

    /** 订阅油门特征值的 notify（写 CCCD 描述符使能通知）。 */
    private fun enableThrottleNotification() {
        val ch = throttleCharacteristic ?: return
        try {
            gatt?.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID.fromString(CCCD_UUID))
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt?.writeDescriptor(cccd)
        } catch (_: SecurityException) {
        }
    }

    private fun startScan() {
        stopScan()
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            connecting = false
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == DEVICE_NAME) {
                    stopScan()
                    connectDevice(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                connecting = false
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(callback)
            handler.postDelayed({
                if (scanCallback != null) {
                    stopScan()
                    connecting = false
                }
            }, SCAN_TIMEOUT_MS)
        } catch (_: SecurityException) {
            connecting = false
        }
    }

    private fun stopScan() {
        scanCallback?.let { cb ->
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Exception) {
            }
        }
        scanCallback = null
    }

    private fun connectDevice(device: BluetoothDevice) {
        try {
            gatt = device.connectGatt(context, false, gattCallback)
        } catch (_: SecurityException) {
            connecting = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                try {
                    gatt.discoverServices()
                } catch (_: SecurityException) {
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                connecting = false
                speedCharacteristic = null
                shiftCharacteristic = null
                throttleCharacteristic = null
                modeCharacteristic = null
                lastSpeed = -1
                // 断线后延迟重连
                handler.postDelayed({
                    if (!connected && !connecting) {
                        connect()
                    }
                }, RECONNECT_DELAY_MS)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false
                return
            }
            val service = gatt.getService(UUID.fromString(SVC_UUID))
            speedCharacteristic = service?.getCharacteristic(UUID.fromString(SPEED_CHAR_UUID))
            shiftCharacteristic = service?.getCharacteristic(UUID.fromString(SHIFT_CHAR_UUID))
            throttleCharacteristic = service?.getCharacteristic(UUID.fromString(THROTTLE_CHAR_UUID))
            modeCharacteristic = service?.getCharacteristic(UUID.fromString(MODE_CHAR_UUID))
            connecting = false
            connected = true
            // 订阅油门通知 + 补写通道模式与换挡信号（防止开发板重启后复位）
            enableThrottleNotification()
            syncThrottleChannel()
            onReady?.invoke()
            onReady = null
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == UUID.fromString(SHIFT_CHAR_UUID)) {
                val value = characteristic.value
                val enabled = status == BluetoothGatt.GATT_SUCCESS &&
                        value != null && value.isNotEmpty() && value[0].toInt() != 0
                val cb = onShiftState
                onShiftState = null
                cb?.invoke(enabled)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UUID.fromString(THROTTLE_CHAR_UUID)) {
                val value = characteristic.value
                val v = if (value != null && value.isNotEmpty()) value[0].toInt() and 0xFF else 0
                onThrottleChanged?.invoke(v)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == UUID.fromString(THROTTLE_CHAR_UUID)) {
                val v = if (value.isNotEmpty()) value[0].toInt() and 0xFF else 0
                onThrottleChanged?.invoke(v)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // 订阅 CCCD 写完成后补写一次通道模式：
            // 开发板重启后默认 HID 模式，连接时订阅写（CCCD）与模式写（FFE6）连续下发可能竞争丢包，
            // 导致模式没切到 GATT、油门收不到；这里在订阅写完成后兜底再写一次（幂等，写相同值无副作用）。
            if (descriptor.uuid == UUID.fromString(CCCD_UUID)) {
                syncThrottleChannel()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            // 模式写（FFE6）完成后，再写换挡信号（FFE4）：
            // 换挡信号与通道相反（GATT 通道 → 关换挡，HID 通道 → 开换挡），
            // 必须等模式写完成再写，避免连续写两个特征值竞争导致换挡开关丢失。
            if (characteristic.uuid == UUID.fromString(MODE_CHAR_UUID)) {
                setShiftEnabled(!throttleChannelGatt)
            }
        }
    }
}

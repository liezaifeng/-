package com.ideawav.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * 通过 BLE 连接开发板，向其自定义特征值写入标定命令：
 *  - 0x01：记录油门最大值
 *  - 0x02：记录油门最小值
 * 流程：扫描（按设备名过滤）→ 连接 → 发现服务 → 写特征值 → 断开。
 */
class CalibrationManager(private val context: Context) {

    companion object {
        private const val DEVICE_NAME = "模拟声浪1.0"
        private const val CALIB_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
        private const val CALIB_CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val CMD_RECORD_MAX: Byte = 0x01
        private const val CMD_RECORD_MIN: Byte = 0x02
        private const val CALIB_STATUS_CHAR_UUID = "0000ffe2-0000-1000-8000-00805f9b34fb"
        private const val OP_WRITE = 1
        private const val OP_READ = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var scanCallbackRef: ScanCallback? = null
    private var onResult: ((Boolean, String) -> Unit)? = null
    private var onReadStatus: ((Boolean) -> Unit)? = null
    private var pendingCommand: Byte = CMD_RECORD_MAX
    private var successMessage: String = ""
    private var pendingOperation: Int = OP_WRITE

    private val scanTimeout = Runnable {
        stopScan()
        finish(false, "未找到设备，请确认开发板已上电")
    }

    private val connectTimeout = Runnable {
        disconnect()
        finish(false, "连接超时")
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun recordMax(callback: (Boolean, String) -> Unit) {
        calibrate(CMD_RECORD_MAX, "已记录油门最大值", callback)
    }

    fun recordMin(callback: (Boolean, String) -> Unit) {
        calibrate(CMD_RECORD_MIN, "已记录油门最小值", callback)
    }

    fun readCalibrationStatus(callback: (Boolean) -> Unit) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            callback(false)
            return
        }
        onReadStatus = callback
        pendingOperation = OP_READ

        // 优先从已配对设备找：HID 连接后设备已配对且不再广播，扫描会找不到
        val bonded = adapter.bondedDevices
        val device = bonded.firstOrNull { it.name == DEVICE_NAME }
        if (device != null) {
            connect(device)
        } else {
            startScan()
        }
    }

    private fun calibrate(command: Byte, successMessage: String, callback: (Boolean, String) -> Unit) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            callback(false, "请先开启蓝牙")
            return
        }
        onResult = callback
        pendingCommand = command
        this.successMessage = successMessage
        pendingOperation = OP_WRITE

        // 优先从已配对设备找：HID 连接后设备已配对且不再广播，扫描会找不到
        val bonded = adapter.bondedDevices
        val device = bonded.firstOrNull { it.name == DEVICE_NAME }
        if (device != null) {
            connect(device)
        } else {
            startScan()
        }
    }

    fun release() {
        stopScan()
        disconnect()
        onResult = null
        onReadStatus = null
    }

    private fun startScan() {
        stopScan()
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            finish(false, "蓝牙不可用")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.name == DEVICE_NAME) {
                    stopScan()
                    connect(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                finish(false, "扫描失败（错误码 $errorCode）")
            }
        }
        scanCallbackRef = callback
        try {
            scanner.startScan(callback)
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (e: SecurityException) {
            finish(false, "缺少蓝牙权限")
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scanCallbackRef?.let { cb ->
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Exception) {
            }
        }
        scanCallbackRef = null
    }

    private fun connect(device: BluetoothDevice) {
        handler.removeCallbacks(scanTimeout)
        try {
            gatt = device.connectGatt(context, false, gattCallback)
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        } catch (e: SecurityException) {
            finish(false, "缺少蓝牙权限")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(false, "连接失败（错误码 $status）")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.removeCallbacks(connectTimeout)
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    finish(false, "缺少蓝牙权限")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                finish(false, "连接已断开")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(false, "服务发现失败")
                return
            }
            val service = gatt.getService(UUID.fromString(CALIB_SERVICE_UUID))
            if (pendingOperation == OP_READ) {
                val statusChar = service?.getCharacteristic(UUID.fromString(CALIB_STATUS_CHAR_UUID))
                if (statusChar == null) {
                    finishRead(false)
                    return
                }
                try {
                    if (!gatt.readCharacteristic(statusChar)) {
                        finishRead(false)
                    }
                } catch (e: SecurityException) {
                    finishRead(false)
                }
                return
            }
            val characteristic = service?.getCharacteristic(UUID.fromString(CALIB_CHAR_UUID))
            if (characteristic == null) {
                finish(false, "未找到标定特征值，请确认开发板固件已更新")
                return
            }
            characteristic.value = byteArrayOf(pendingCommand)
            try {
                if (!gatt.writeCharacteristic(characteristic)) {
                    finish(false, "写入失败")
                }
            } catch (e: SecurityException) {
                finish(false, "缺少蓝牙权限")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finish(true, successMessage)
            } else {
                finish(false, "写入失败")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val value = characteristic.value
            val calibrated = status == BluetoothGatt.GATT_SUCCESS &&
                    value != null && value.isNotEmpty() && value[0].toInt() == 1
            finishRead(calibrated)
        }
    }

    private fun disconnect() {
        handler.removeCallbacks(connectTimeout)
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun finish(success: Boolean, message: String) {
        stopScan()
        val cb = onResult
        onResult = null
        disconnect()
        cb?.invoke(success, message)
    }

    private fun finishRead(calibrated: Boolean) {
        stopScan()
        val cb = onReadStatus
        onReadStatus = null
        disconnect()
        cb?.invoke(calibrated)
    }
}

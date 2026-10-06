package com.ideawav.app

import android.Manifest
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val CMD_MAX = 1
        private const val CMD_MIN = 2
    }

    private lateinit var soundManager: SoundManager
    private lateinit var prefs: SharedPreferences
    private lateinit var calibrationManager: CalibrationManager
    private lateinit var boardSync: BoardSyncManager

    private var pendingCalibrationType = CMD_MAX

    private val calibrationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startCalibration(pendingCalibrationType)
        } else {
            Toast.makeText(this, "需要蓝牙权限才能记录油门", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        soundManager = SoundManager.getInstance(this)
        boardSync = BoardSyncManager.getInstance(this)
        prefs = getSharedPreferences("sound_prefs", MODE_PRIVATE)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // 后台继续播放开关（本地设置，存 prefs；同时切换油门通道 + 换挡信号）
        val switchBackgroundContinue = findViewById<Switch>(R.id.switchBackgroundContinue)
        switchBackgroundContinue.isChecked = soundManager.isBackgroundContinueEnabled
        switchBackgroundContinue.setOnCheckedChangeListener { _, checked ->
            soundManager.applyBackgroundContinueEnabled(checked)
            prefs.edit().putBoolean("background_continue_enabled", checked).apply()
            // 开=GATT 通道（后台 App 收油门，自动关闭换挡信号）；关=HID 通道（前台 revheadz 收 RT，自动开启换挡信号）
            boardSync.setThrottleChannel(checked)
        }

        val switchEngine = findViewById<Switch>(R.id.switchEngineEffect)
        val switchHold = findViewById<Switch>(R.id.switchHoldEffect)
        val switchIdle = findViewById<Switch>(R.id.switchIdleEffect)
        val switchLoopSpeedControl = findViewById<Switch>(R.id.switchLoopSpeedControl)
        val switchSingleUp = findViewById<Switch>(R.id.switchSingleUp)
        val switchSingleDown = findViewById<Switch>(R.id.switchSingleDown)

        switchEngine.isChecked = soundManager.isEngineEnabled
        switchEngine.setOnCheckedChangeListener { _, checked ->
            soundManager.isEngineEnabled = checked
            prefs.edit().putBoolean("engine_enabled", checked).apply()
        }
        switchHold.isChecked = soundManager.isKeepEnabled
        switchHold.setOnCheckedChangeListener { _, checked ->
            soundManager.isKeepEnabled = checked
            prefs.edit().putBoolean("enable_hold_effect", checked).apply()
        }
        switchIdle.isChecked = soundManager.isIdleEnabled
        switchIdle.setOnCheckedChangeListener { _, checked ->
            soundManager.isIdleEnabled = checked
            prefs.edit().putBoolean("enable_idle_effect", checked).apply()
        }
        switchLoopSpeedControl.isChecked = soundManager.isLoopSpeedControlled
        switchLoopSpeedControl.setOnCheckedChangeListener { _, checked ->
            soundManager.isLoopSpeedControlled = checked
            prefs.edit().putBoolean("loop_speed_control", checked).apply()
        }
        switchSingleUp.isChecked = soundManager.isSingleUpEnabled
        switchSingleUp.setOnCheckedChangeListener { _, checked ->
            soundManager.isSingleUpEnabled = checked
            prefs.edit().putBoolean("enable_single_up", checked).apply()
        }
        switchSingleDown.isChecked = soundManager.isSingleDownEnabled
        switchSingleDown.setOnCheckedChangeListener { _, checked ->
            soundManager.isSingleDownEnabled = checked
            prefs.edit().putBoolean("enable_single_down", checked).apply()
        }

        setupSettingSeekBars()

        calibrationManager = CalibrationManager(this)
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener {
            launchCalibration(CMD_MAX)
        }
        findViewById<Button>(R.id.btnCalibrateMin).setOnClickListener {
            launchCalibration(CMD_MIN)
        }
    }

    private fun setupSettingSeekBars() {
        // 背景音速度
        setupSeekBarWithRange(R.id.seekMinSpeed, R.id.tvMinSpeed, 0.5f, 1.0f, soundManager.minSpeed) { value ->
            soundManager.minSpeed = value
            prefs.edit().putFloat("bg_min_speed", value).apply()
            soundManager.refreshEngineParams()
        }
        setupSeekBarWithRange(R.id.seekMaxSpeed, R.id.tvMaxSpeed, 1.2f, 2.2f, soundManager.maxSpeed) { value ->
            soundManager.maxSpeed = value
            prefs.edit().putFloat("bg_max_speed", value).apply()
            soundManager.refreshEngineParams()
        }
        // 背景音音量
        setupSeekBarWithRange(R.id.seekMinVol, R.id.tvMinVol, 0.1f, 0.3f, soundManager.minVolume) { value ->
            soundManager.minVolume = value
            prefs.edit().putFloat("bg_min_vol", value).apply()
            soundManager.refreshEngineParams()
        }
        setupSeekBarWithRange(R.id.seekMaxVol, R.id.tvMaxVol, 0.3f, 1.0f, soundManager.maxVolume) { value ->
            soundManager.maxVolume = value
            prefs.edit().putFloat("bg_max_vol", value).apply()
            soundManager.refreshEngineParams()
        }
        // 保持音效
        setupSeekBarWithRange(R.id.seekLoopUpVol, R.id.tvLoopUpVol, 0.2f, 0.6f, soundManager.loopUpMinVolume) { value ->
            soundManager.loopUpMinVolume = value
            prefs.edit().putFloat("hold_min_vol", value).apply()
            if (soundManager.isHoldUpActive) soundManager.updateLoopUpParamsNow()
        }
        setupSeekBarWithRange(R.id.seekLoopUpSpeed, R.id.tvLoopUpSpeed, 1.2f, 2.2f, soundManager.loopUpMaxSpeed) { value ->
            soundManager.loopUpMaxSpeed = value
            prefs.edit().putFloat("hold_max_speed", value).apply()
            if (soundManager.isHoldUpActive) soundManager.updateLoopUpParamsNow()
        }
        // 怠速音效
        setupSeekBarWithRange(R.id.seekLoopDownVol, R.id.tvLoopDownVol, 0.0f, 1.0f, soundManager.loopDownVolume) { value ->
            soundManager.loopDownVolume = value
            prefs.edit().putFloat("idle_vol", value).apply()
            if (soundManager.isHoldDownActive) soundManager.updateLoopDownParams()
        }
        setupSeekBarWithRange(R.id.seekLoopDownSpeed, R.id.tvLoopDownSpeed, 0.7f, 1.0f, soundManager.loopDownSpeed) { value ->
            soundManager.loopDownSpeed = value
            prefs.edit().putFloat("idle_speed", value).apply()
            if (soundManager.isHoldDownActive) soundManager.updateLoopDownParams()
        }
        // 油门音效（up/down 共用）
        setupSeekBarWithRange(R.id.seekSingleMinVol, R.id.tvSingleMinVol, 0.1f, 0.5f, soundManager.singleUpMinVolume) { value ->
            soundManager.singleUpMinVolume = value
            soundManager.singleDownMinVolume = value
            prefs.edit().putFloat("single_min_vol", value).apply()
        }
        setupSeekBarWithRange(R.id.seekSingleMaxVol, R.id.tvSingleMaxVol, 0.5f, 1.0f, soundManager.singleUpMaxVolume) { value ->
            soundManager.singleUpMaxVolume = value
            soundManager.singleDownMaxVolume = value
            prefs.edit().putFloat("single_max_vol", value).apply()
        }
        setupSeekBarWithRange(R.id.seekSingleMaxSpeed, R.id.tvSingleMaxSpeed, 1.0f, 1.5f, soundManager.singleUpMaxSpeed) { value ->
            soundManager.singleUpMaxSpeed = value
            soundManager.singleDownMaxSpeed = value
            prefs.edit().putFloat("single_max_speed", value).apply()
        }
        setupSeekBarWithRange(R.id.seekDuckFactor, R.id.tvDuckFactor, 0f, 1f, soundManager.duckFactor) { value ->
            soundManager.duckFactor = value
            prefs.edit().putFloat("duck_factor", value).apply()
        }
        // 油门灵敏度（内部 8~28 数字越小越灵敏；滑条无段位，界面显示 0%~100% 数字越大越灵敏）
        setupSensitivitySeekBar()
    }

    /**
     * 油门灵敏度滑条 + 颜色块：
     * 滑条 0%~100% 对应内部值 8~28（28=蓝/最迟钝，8=红/最灵敏）。
     * 点击颜色块可手动输入任意内部值（输入几就是几），此时滑条变灰、色块变方形彩虹；
     * 再次拖动滑条则恢复圆形 + 对应颜色。
     */
    private fun setupSensitivitySeekBar() {
        val seekBar = findViewById<SeekBar>(R.id.seekThrottleSensitivity)
        val colorBlock = findViewById<ColorBlockView>(R.id.colorBlock)

        fun internalToPercent(internal: Int): Int = ((28 - internal) * 5).coerceIn(0, 100)
        fun percentToInternal(percent: Int): Int = (28 - percent * 20 / 100).coerceIn(8, 28)

        val current = soundManager.throttleSensitivity
        val inSliderRange = current in 8..28
        val percent = internalToPercent(current.coerceIn(8, 28))
        seekBar.progress = percent
        if (inSliderRange) setSliderMode(percent) else setManualMode()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = progress.coerceIn(0, 100)
                val internal = percentToInternal(p)
                soundManager.throttleSensitivity = internal
                prefs.edit().putInt("throttle_sensitivity", internal).apply()
                setSliderMode(p)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        colorBlock.setOnClickListener { showSensitivityInputDialog() }
    }

    /** 滑条模式：色块圆形 + 蓝→红，滑条正常颜色。 */
    private fun setSliderMode(percent: Int) {
        findViewById<ColorBlockView>(R.id.colorBlock).setSliderColor(percent / 100f)
        setSeekBarGray(false)
    }

    /** 手动输入模式：色块方形 + 彩虹，滑条置灰。 */
    private fun setManualMode() {
        findViewById<ColorBlockView>(R.id.colorBlock).setRainbowSquare()
        setSeekBarGray(true)
    }

    private fun setSeekBarGray(gray: Boolean) {
        val seekBar = findViewById<SeekBar>(R.id.seekThrottleSensitivity)
        val filter = if (gray) {
            val m = ColorMatrix()
            m.setSaturation(0f)
            ColorMatrixColorFilter(m)
        } else null
        seekBar.progressDrawable?.colorFilter = filter
        seekBar.thumb?.colorFilter = filter
        seekBar.invalidate()
    }

    /** 点击颜色块弹出输入框，直接输入内部值。 */
    private fun showSensitivityInputDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "范围4-36"
        input.setText(soundManager.throttleSensitivity.toString())
        input.setSelection(input.text.length)

        val dialog = AlertDialog.Builder(this)
            .setTitle("修改内部值")
            .setMessage("直接输入，数字越小越灵敏。")
            .setView(input)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().toIntOrNull()
                if (value != null && value in 4..36) {
                    soundManager.throttleSensitivity = value
                    prefs.edit().putInt("throttle_sensitivity", value).apply()
                    setManualMode()
                    dialog.dismiss()   // 合法才关闭
                } else {
                    Toast.makeText(this, "请输入 4~36 的整数", Toast.LENGTH_SHORT).show()
                    // 非法不关闭，留在原地重新输入
                }
            }
        }
        dialog.show()
    }

    private fun setupSeekBarWithRange(
        seekBarId: Int,
        valueTextId: Int,
        min: Float,
        max: Float,
        defaultValue: Float,
        onChanged: (Float) -> Unit
    ) {
        val seekBar = findViewById<SeekBar>(seekBarId)
        val valueText = findViewById<TextView>(valueTextId)
        val defaultProgress = ((defaultValue - min) / (max - min) * 100).toInt().coerceIn(0, 100)
        seekBar.progress = defaultProgress
        valueText.text = String.format("%.1f", defaultValue)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = min + (progress / 100f) * (max - min)
                valueText.text = String.format("%.1f", value)
                onChanged(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ==================== 油门标定 ====================
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun launchCalibration(type: Int) {
        pendingCalibrationType = type
        if (hasAllPermissions()) {
            startCalibration(type)
        } else {
            calibrationPermissionLauncher.launch(requiredPermissions())
        }
    }

    private fun startCalibration(type: Int) {
        if (!calibrationManager.isBluetoothEnabled()) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        if (type == CMD_MIN) {
            Toast.makeText(this, "请保持油门在最小位置（怠速），正在连接…", Toast.LENGTH_SHORT).show()
            calibrationManager.recordMin { success, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        prefs.edit().putBoolean("min_calibrated", true).apply()
                    }
                }
            }
        } else {
            Toast.makeText(this, "请保持油门在最大位置，正在连接…", Toast.LENGTH_SHORT).show()
            calibrationManager.recordMax { success, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        prefs.edit().putBoolean("max_calibrated", true).apply()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        calibrationManager.release()
        super.onDestroy()
    }
}

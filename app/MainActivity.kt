package com.ideawav.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var soundManager: SoundManager
    private lateinit var calibrationManager: CalibrationManager
    private lateinit var boardSync: BoardSyncManager
    private var isRunning = false
    private var currentThrottle = 0
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoShutdownRunnable: Runnable? = null
    private lateinit var spinnerEngine: AlwaysSelectSpinner
    private var isEngineSpinnerReady = false
    private lateinit var engineAdapter: EngineSpinnerAdapter
    private val engineNames = mutableListOf<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            refreshCalibStatusFromDevice()
            boardSync.connect()
            startSpeedSyncService()
        } else {
            Toast.makeText(this, "定位或蓝牙权限未授予，无法获取车速", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val AUTO_START_THROTTLE_MIN = 5
        private const val AUTO_SHUTDOWN_DELAY_MS = 10 * 60 * 1000L
        private const val REQUEST_CUSTOM_MUSIC = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        soundManager = SoundManager.getInstance(this)
        calibrationManager = CalibrationManager(this)
        boardSync = BoardSyncManager.getInstance(this)
        prefs = getSharedPreferences("sound_prefs", MODE_PRIVATE)

        // 同步油门通道到开发板：后台播放开=GATT，关=HID（连接成功后自动补写）
        boardSync.setThrottleChannel(soundManager.isBackgroundContinueEnabled)

        val savedEngineIndex = prefs.getInt("engine_index", 0)
        val savedEffectIndex = prefs.getInt("effect_index", 0)

        soundManager.switchEngine(AudioConfig.enginePacks[savedEngineIndex].rawResId)

        val initialPack = AudioConfig.effectPacks[savedEffectIndex]
        soundManager.switchEffect(
            loopUpResId = initialPack.loopUpRawResId,
            loopDownResId = initialPack.loopDownRawResId,
            singleUpResId = initialPack.upRawResId,
            singleDownResId = initialPack.downRawResId
        )

        val btnToggle = findViewById<Button>(R.id.btnEngineToggle)
        spinnerEngine = findViewById(R.id.spinnerEngine)
        val spinnerEffect = findViewById<Spinner>(R.id.spinnerEffect)
        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)

        currentThrottle = 0
        updateThrottleDisplay(0)
        soundManager.setThrottle(0, isRunning)

        btnToggle.setOnClickListener {
            if (isRunning) manualStopEngine(btnToggle)
            else manualStartEngine(btnToggle, true)
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 背景音乐选择（第一个是"自定义音乐"）
        engineNames.clear()
        engineNames.add("自定义音乐")
        engineNames.addAll(AudioConfig.enginePackNames)
        engineAdapter = EngineSpinnerAdapter(this, engineNames)
        engineAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerEngine.adapter = engineAdapter
        spinnerEngine.setSelection(savedEngineIndex + 1)
        spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!isEngineSpinnerReady) return
                if (position == 0) {
                    // 自定义音乐：弹出设置页面
                    startActivityForResult(
                        Intent(this@MainActivity, CustomMusicActivity::class.java),
                        REQUEST_CUSTOM_MUSIC
                    )
                } else {
                    val packIndex = position - 1
                    val pack = AudioConfig.enginePacks[packIndex]
                    soundManager.switchEngine(pack.rawResId)
                    prefs.edit().putInt("engine_index", packIndex).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerEngine.post { isEngineSpinnerReady = true }

        // 油门音效选择
        val effectAdapter = ArrayAdapter(this, R.layout.spinner_item, AudioConfig.effectPackNames)
        effectAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerEffect.adapter = effectAdapter
        spinnerEffect.setSelection(savedEffectIndex)
        spinnerEffect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val pack = AudioConfig.effectPacks[position]
                soundManager.switchEffect(
                    loopUpResId = pack.loopUpRawResId,
                    loopDownResId = pack.loopDownRawResId,
                    singleUpResId = pack.upRawResId,
                    singleDownResId = pack.downRawResId
                )
                prefs.edit().putInt("effect_index", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 启动后申请所有权限（蓝牙 + 定位 + 通知）
        if (hasAllPermissions()) {
            boardSync.connect()
            startSpeedSyncService()
        } else {
            permissionLauncher.launch(allPermissions())
        }

        // 注册车速回调（前台时更新表盘 + 声浪；车速发送由前台服务负责）
        boardSync.onSpeedChanged = { kmh ->
            runOnUiThread { onSpeedChanged(kmh) }
        }

        // 注册油门回调（GATT 通道，后台也能收到；HID 通道下忽略，避免与 HID RT 冲突）
        boardSync.onThrottleChanged = { v ->
            if (soundManager.isBackgroundContinueEnabled) {
                val throttle = (v * 100 / 255).coerceIn(0, 100)
                runOnUiThread { handleThrottleChange(throttle) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        soundManager.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        soundManager.onAppBackground()
    }

    override fun onResume() {
        super.onResume()
        val localCalibrated = prefs.getBoolean("min_calibrated", false) &&
                prefs.getBoolean("max_calibrated", false)
        updateCalibStatus(localCalibrated)
        refreshCalibStatusFromDevice()
    }

    // ==================== 油门标定状态提示 ====================
    private fun updateCalibStatus(calibrated: Boolean) {
        val tv = findViewById<TextView>(R.id.tvCalibStatus)
        if (calibrated) {
            tv.text = "油门已校准，如更换油门需重新校准。"
            tv.setTextColor(getColor(R.color.calib_ready))
        } else {
            tv.text = "请在设置中校准油门"
            tv.setTextColor(getColor(R.color.calib_not_ready))
        }
    }

    private fun refreshCalibStatusFromDevice() {
        if (!hasAllPermissions()) return
        calibrationManager.readCalibrationStatus { calibrated ->
            runOnUiThread { updateCalibStatus(calibrated) }
        }
    }

    private fun allPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.BLUETOOTH)
            list.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // 不再请求 ACCESS_BACKGROUND_LOCATION：
        // 1) SpeedSyncService 是带 FOREGROUND_SERVICE_TYPE_LOCATION 的前台服务，
        //    已在后台持续定位，不需要后台定位权限；
        // 2) Android 11+ 无法通过合并弹窗授予该权限，若把它列入必授权项，
        //    会导致 hasAllPermissions() 永远不满足，GPS 前台服务始终不启动，车速恒为 0。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean =
        allPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    // ==================== 手动控制引擎 ====================
    private fun manualStartEngine(btnToggle: Button, playFlashEffect: Boolean = false) {
        if (!isRunning) {
            isRunning = true
            soundManager.startEngine()
            btnToggle.setText(R.string.stop_engine)
            soundManager.setThrottle(currentThrottle, true)
            if (playFlashEffect) {
                findViewById<GaugeView>(R.id.gaugeView).flash()
            }
        }
    }

    private fun manualStopEngine(btnToggle: Button) {
        if (isRunning) {
            isRunning = false
            soundManager.stopEngine()
            btnToggle.setText(R.string.start_engine)
            if (soundManager.isHoldUpActive) soundManager.stopLoopUpImmediate()
            if (soundManager.isHoldDownActive) soundManager.stopLoopDownImmediate()
            cancelAutoShutdown()
        }
    }

    // ==================== 自动启停 ====================
    private fun checkAutoStart(newThrottle: Int) {
        if (!isRunning && newThrottle > AUTO_START_THROTTLE_MIN) {
            val btnToggle = findViewById<Button>(R.id.btnEngineToggle)
            manualStartEngine(btnToggle)
            Log.d("MainActivity", "自动启动引擎（油门=$newThrottle）")
        }
    }

    private fun checkAutoShutdown(newThrottle: Int) {
        if (isRunning) {
            if (newThrottle in 0..AUTO_START_THROTTLE_MIN) {
                if (autoShutdownRunnable == null) {
                    val runnable = Runnable {
                        if (isRunning && currentThrottle in 0..AUTO_START_THROTTLE_MIN) {
                            val btnToggle = findViewById<Button>(R.id.btnEngineToggle)
                            manualStopEngine(btnToggle)
                            Log.d("MainActivity", "自动熄火（油门长时间为$currentThrottle）")
                        }
                    }
                    autoShutdownRunnable = runnable
                    mainHandler.postDelayed(runnable, AUTO_SHUTDOWN_DELAY_MS)
                    Log.d("MainActivity", "自动熄火计时开始（10分钟）")
                }
            } else {
                cancelAutoShutdown()
            }
        }
    }

    private fun cancelAutoShutdown() {
        autoShutdownRunnable?.let { mainHandler.removeCallbacks(it) }
        autoShutdownRunnable = null
    }

    // ==================== 屏蔽手柄上下键 ====================
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_DPAD) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_DPAD)) {
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hatY <= -0.5f || hatY >= 0.5f) {
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ==================== 手柄输入（读取油门） ====================
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
            return super.onGenericMotionEvent(event)
        }
        // GATT 通道模式（后台播放打开）时油门走 GATT 通知，忽略 HID RT，避免两者冲突
        if (soundManager.isBackgroundContinueEnabled) {
            return true
        }
        val rtValue = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        val throttleValue = event.getAxisValue(MotionEvent.AXIS_THROTTLE)
        val axisValue = maxOf(rtValue, throttleValue)
        val newThrottle = (axisValue * 100).toInt().coerceIn(0, 100)
        if (newThrottle != currentThrottle) {
            runOnUiThread { handleThrottleChange(newThrottle) }
        }
        return true
    }

    // ==================== 油门变化统一处理 ====================
    private fun handleThrottleChange(newThrottle: Int) {
        currentThrottle = newThrottle
        updateThrottleDisplay(newThrottle)
        checkAutoStart(newThrottle)
        checkAutoShutdown(newThrottle)

        if (!isRunning) return

        soundManager.setThrottle(newThrottle, true)
    }

    // ==================== 车速同步服务 ====================
    private fun startSpeedSyncService() {
        val intent = Intent(this, SpeedSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ==================== 车速更新（由前台服务回调） ====================
    private fun onSpeedChanged(kmh: Float) {
        findViewById<GaugeView>(R.id.gaugeView).setSpeed(kmh)
        soundManager.setSpeed(kmh)
    }

    // ==================== 辅助方法 ====================
    private fun updateThrottleDisplay(throttle: Int) {
        findViewById<GaugeView>(R.id.gaugeView).setProgress(throttle)
    }

    private fun updateEngineFirstName(name: String) {
        engineAdapter.customDisplayName = name
        engineAdapter.notifyDataSetChanged()
        spinnerEngine.post {
            (spinnerEngine.selectedView as? TextView)?.isSelected = true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CUSTOM_MUSIC) {
            if (resultCode == RESULT_OK) {
                val uri = data?.getStringExtra("custom_uri")
                val name = data?.getStringExtra("custom_name")
                if (uri != null) {
                    prefs.edit().putString("custom_music_uri", uri).apply()
                    if (name != null) {
                        prefs.edit().putString("custom_music_name", name).apply()
                        updateEngineFirstName(name)
                    }
                }
            } else {
                // 取消：自动切到下一个选项（第一个内置音效包）
                spinnerEngine.setSelection(1)
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopService(Intent(this, SpeedSyncService::class.java))
        calibrationManager.release()
        boardSync.release()
        soundManager.release()
        super.onDestroy()
    }
}

package com.ideawav.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.min

class SoundManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: SoundManager? = null

        fun getInstance(context: Context): SoundManager =
            instance ?: synchronized(this) {
                instance ?: SoundManager(context.applicationContext).also {
                    it.init()
                    instance = it
                }
            }
    }

    private var enginePlayer: ExoPlayer? = null
    private var loopUpPlayer: ExoPlayer? = null
    private var loopDownPlayer: ExoPlayer? = null
    private var singleUpPlayer: ExoPlayer? = null
    private var singleDownPlayer: ExoPlayer? = null

    // 循环音效资源 ID（ExoPlayer 用）
    private var upSoundId = 0
    private var downSoundId = 0

    // 淡入控制（单次音效）
    private val fadeHandler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null
    private var fadeStartTime = 0L
    private var fadeTargetVolume = 0f
    private var fadeDurationMs = 200L
    private var fadeSilenceMs = 50L
    private var fadePlayer: ExoPlayer? = null
    private var fadeCompleteCallback: (() -> Unit)? = null

    // 闪避控制（Ducking）
    private val duckHandler = Handler(Looper.getMainLooper())
    private var duckRunnable: Runnable? = null
    private var originalEngineVolume = 1.0f
    private var isDucking = false
    // 播放油门音效时背景音乐的音量系数（0=静音，1=不变）
    var duckFactor = 0.4f

    // 循环音效（loopUp）淡入淡出控制
    private val loopUpFadeHandler = Handler(Looper.getMainLooper())
    private var loopUpFadeRunnable: Runnable? = null
    private var loopUpFadeTargetVolume = 0f
    private var loopUpFadeStartTime = 0L
    private val LOOP_UP_FADE_DURATION_MS = 30L
    private val LOOP_UP_FADE_INTERVAL_MS = 10L

    // 单次up→up循环延迟控制
    private val restartLoopUpHandler = Handler(Looper.getMainLooper())
    private var restartLoopUpRunnable: Runnable? = null
    private val RESTART_LOOP_UP_DELAY_MS = 600L

    // ★ 怠速阈值常量（原35，现改为10）
    private val IDLE_THRESHOLD = 10

    // 可调节参数
    var minSpeed = 0.7f
    var maxSpeed = 1.4f
    var minVolume = 0.2f
    var maxVolume = 1.0f
    var loopUpMinVolume = 0.4f
    var loopUpMaxSpeed = 1.7f
    var loopDownVolume = 0.7f
    var loopDownSpeed = 0.8f

    var singleUpMinVolume = 0.5f
    var singleUpMaxVolume = 1.0f
    var singleDownMinVolume = 0.5f
    var singleDownMaxVolume = 1.0f

    var singleUpMaxSpeed = 1.3f
    var singleDownMaxSpeed = 1.3f

    // 油门灵敏度：单次 up/down 音效的触发阈值（同方向累积油门变化 ≥ 该值才触发），范围 4~36（滑条对应 8~28），数字越小越灵敏
    var throttleSensitivity = 8

    // 后台继续播放：开启后切到后台仍继续播放；关闭则切后台自动暂停
    var isBackgroundContinueEnabled = true

    // 油门单次音效开关：分别控制油门上升 / 减少时是否播放单次 up / down 音效
    var isSingleUpEnabled = true
    var isSingleDownEnabled = true

    private var isRunning = false
    var isHoldUpActive = false
    var isHoldDownActive = false

    // ★★★ 引擎音效开关 ★★★
    var isEngineEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                enginePlayer?.volume = 0f
                enginePlayer?.pause()
            } else {
                if (isRunning) {
                    enginePlayer?.play()
                    setSpeed(currentSpeedKmh)
                }
            }
        }

    var isIdleEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                if (isHoldDownActive) {
                    Log.d("SoundManager", "isIdleEnabled=false: stopping loopDown")
                    stopLoopDownImmediate()
                }
                cancelIdleTimer()
            } else {
                if (isIdleConditionMet() && isRunning) {
                    Log.d("SoundManager", "isIdleEnabled=true: starting idle timer")
                    startIdleTimer()
                }
            }
        }

    var isKeepEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                if (isHoldUpActive) {
                    Log.d("SoundManager", "isKeepEnabled=false: stopping loopUp")
                    stopLoopUpImmediate()
                }
            } else {
                if (isHoldConditionMet() && !isHoldUpActive && !isHoldDownActive) {
                    Log.d("SoundManager", "isKeepEnabled=true: starting loopUp")
                    startLoopUp()
                }
            }
        }

    // 保持/怠速音效控制模式：false=油门（转把）控制，true=车速控制（默认）
    var isLoopSpeedControlled: Boolean = true

    private val idleTimerHandler = Handler(Looper.getMainLooper())
    private var idleTimerRunnable: Runnable? = null
    private val IDLE_ENTER_DELAY_MS = 2000L

    // 单次音效节流控制
    private var lastSinglePlayTime = 0L
    private val SINGLE_PLAY_COOLDOWN_MS = 150L

    // 记录当前油门值 / 当前车速（KM/H）
    private var currentThrottle = 0
    private var currentSpeedKmh = 0f

    // 累积同一方向的油门变化（缓慢拧/松油门也能触发单次音效，灵敏度决定触发阈值）
    private var accumulatedThrottleDelta = 0

    // 上一次循环状态是否处于怠速（用于检测进入怠速的跳变，触发延迟计时）
    private var wasInIdle = false

    private fun init() {
        enginePlayer = createExoPlayer(Player.REPEAT_MODE_ALL)
        loopUpPlayer = createExoPlayer(Player.REPEAT_MODE_ALL)
        loopDownPlayer = createExoPlayer(Player.REPEAT_MODE_ALL)
        singleUpPlayer = createExoPlayer(Player.REPEAT_MODE_OFF)
        singleDownPlayer = createExoPlayer(Player.REPEAT_MODE_OFF)

        // 单次音效播完停止并复位到开头，避免循环播放
        singleUpPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    singleUpPlayer?.pause()
                    singleUpPlayer?.seekTo(0)
                }
            }
        })
        singleDownPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    singleDownPlayer?.pause()
                    singleDownPlayer?.seekTo(0)
                }
            }
        })

        loadPrefs()
    }

    private fun loadPrefs() {
        val prefs = context.getSharedPreferences("sound_prefs", Context.MODE_PRIVATE)
        minSpeed = prefs.getFloat("bg_min_speed", 0.7f)
        maxSpeed = prefs.getFloat("bg_max_speed", 1.4f)
        minVolume = prefs.getFloat("bg_min_vol", 0.2f)
        maxVolume = prefs.getFloat("bg_max_vol", 1.0f)
        loopUpMinVolume = prefs.getFloat("hold_min_vol", 0.4f)
        loopUpMaxSpeed = prefs.getFloat("hold_max_speed", 1.7f)
        loopDownVolume = prefs.getFloat("idle_vol", 0.7f)
        loopDownSpeed = prefs.getFloat("idle_speed", 0.8f)
        val singleMinVol = prefs.getFloat("single_min_vol", 0.5f)
        singleUpMinVolume = singleMinVol
        singleDownMinVolume = singleMinVol
        val singleMaxVol = prefs.getFloat("single_max_vol", 1.0f)
        singleUpMaxVolume = singleMaxVol
        singleDownMaxVolume = singleMaxVol
        val singleMaxSpeed = prefs.getFloat("single_max_speed", 1.3f)
        singleUpMaxSpeed = singleMaxSpeed
        singleDownMaxSpeed = singleMaxSpeed

        duckFactor = prefs.getFloat("duck_factor", 0.4f).coerceIn(0f, 1f)

        throttleSensitivity = prefs.getInt("throttle_sensitivity", 8).coerceIn(4, 36)

        isBackgroundContinueEnabled = prefs.getBoolean("background_continue_enabled", true)

        isSingleUpEnabled = prefs.getBoolean("enable_single_up", true)
        isSingleDownEnabled = prefs.getBoolean("enable_single_down", true)

        isKeepEnabled = prefs.getBoolean("enable_hold_effect", true)
        isIdleEnabled = prefs.getBoolean("enable_idle_effect", true)
        isEngineEnabled = prefs.getBoolean("engine_enabled", true)
        isLoopSpeedControlled = prefs.getBoolean("loop_speed_control", true)
    }

    private fun createExoPlayer(repeatMode: Int = Player.REPEAT_MODE_ALL): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
            .build()
            .apply {
                this.repeatMode = repeatMode
                prepare()
            }

    fun switchEngine(resId: Int) {
        enginePlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(
                Uri.parse("android.resource://${context.packageName}/$resId")
            ))
            prepare()
            if (isRunning && isEngineEnabled) play()
        }
    }

    /** 播放外部 Uri（本地文件或网络链接），同步设置，不校验结果。 */
    fun switchEngineUri(uriString: String) {
        enginePlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(uriString))
            prepare()
            if (isRunning && isEngineEnabled) play()
        }
    }

    /** 加载外部自定义音乐并回调结果（成功/失败）。 */
    fun loadCustomMusic(uriString: String, onResult: (Boolean) -> Unit) {
        val player = enginePlayer ?: run { onResult(false); return }
        player.stop()
        player.setMediaItem(MediaItem.fromUri(uriString))
        player.prepare()

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    player.removeListener(this)
                    if (isRunning && isEngineEnabled) player.play()
                    onResult(true)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                player.removeListener(this)
                onResult(false)
            }
        }
        player.addListener(listener)
    }

    /**
     * 切换全套音效（循环 + 单次）
     * @param loopUpResId   循环 up 资源 ID（ExoPlayer）
     * @param loopDownResId 循环 down 资源 ID（ExoPlayer）
     * @param singleUpResId 单次 up 资源 ID（ExoPlayer），null 则复用 loopUpResId
     * @param singleDownResId 单次 down 资源 ID（ExoPlayer），null 则复用 loopDownResId
     */
    fun switchEffect(
        loopUpResId: Int,
        loopDownResId: Int,
        singleUpResId: Int? = null,
        singleDownResId: Int? = null
    ) {
        // 设置循环音效（ExoPlayer）
        loopUpPlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(
                Uri.parse("android.resource://${context.packageName}/$loopUpResId")
            ))
            prepare()
            if (isHoldUpActive) play()
        }
        loopDownPlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(
                Uri.parse("android.resource://${context.packageName}/$loopDownResId")
            ))
            prepare()
            if (isHoldDownActive) play()
        }

        // 设置单次音效（ExoPlayer）
        val finalSingleUpResId = singleUpResId ?: loopUpResId
        val finalSingleDownResId = singleDownResId ?: loopDownResId

        singleUpPlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(
                Uri.parse("android.resource://${context.packageName}/$finalSingleUpResId")
            ))
            prepare()
        }
        singleDownPlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(
                Uri.parse("android.resource://${context.packageName}/$finalSingleDownResId")
            ))
            prepare()
        }

        // 保留旧的循环音效 ID（不再用于单次播放，但保留以备其他用途）
        upSoundId = loopUpResId
        downSoundId = loopDownResId

        cancelFade()
        cancelDuck()
    }

    fun startEngine() {
        isRunning = true
        accumulatedThrottleDelta = 0
        wasInIdle = false
        if (isEngineEnabled) {
            enginePlayer?.play()
        }
        setSpeed(currentSpeedKmh)
    }

    fun stopEngine() {
        isRunning = false
        enginePlayer?.pause()
        cancelDuck()
    }

    fun refreshEngineParams() {
        if (isRunning) setSpeed(currentSpeedKmh)
    }

    // ==================== 后台自动暂停 ====================

    private var isPausedForBackground = false
    private var isAppInForeground = true

    /** 切到后台时调用：根据开关决定是否暂停声浪（开关关闭则后台暂停）。 */
    fun onAppBackground() {
        isAppInForeground = false
        if (isBackgroundContinueEnabled) return
        pauseForBackground()
    }

    /** 回到前台时调用：恢复之前因切后台而暂停的声浪。 */
    fun onAppForeground() {
        isAppInForeground = true
        if (!isPausedForBackground) return
        resumeFromBackground()
    }

    /**
     * 设置「后台继续播放」并立即生效（供设置页开关切换时调用）。
     * 打开：若之前因后台而暂停则立即恢复；关闭：若当前已在后台则立即暂停。
     */
    fun applyBackgroundContinueEnabled(enabled: Boolean) {
        if (isBackgroundContinueEnabled == enabled) return
        isBackgroundContinueEnabled = enabled
        if (enabled) {
            if (isPausedForBackground) resumeFromBackground()
        } else {
            if (!isAppInForeground) pauseForBackground()
        }
    }

    private fun pauseForBackground() {
        isPausedForBackground = true
        enginePlayer?.pause()
        if (isHoldUpActive) loopUpPlayer?.pause()
        if (isHoldDownActive) loopDownPlayer?.pause()
        Log.d("SoundManager", "后台暂停声浪")
    }

    private fun resumeFromBackground() {
        isPausedForBackground = false
        if (isRunning && isEngineEnabled) enginePlayer?.play()
        if (isHoldUpActive) loopUpPlayer?.play()
        if (isHoldDownActive) loopDownPlayer?.play()
        Log.d("SoundManager", "恢复声浪")
    }

    // ★ 油门更新入口：仅负责单次 up/down 音效（受转把信号控制）+ 循环状态切换（怠速判断）
    fun setThrottle(throttle: Int, engineRunning: Boolean) {
        val oldThrottle = currentThrottle
        val delta = throttle - oldThrottle
        currentThrottle = throttle

        // 累积同一方向的油门变化；方向反转时清零（避免上下运动互相抵消）
        if (delta > 0) {
            if (accumulatedThrottleDelta < 0) accumulatedThrottleDelta = 0
            accumulatedThrottleDelta += delta
        } else if (delta < 0) {
            if (accumulatedThrottleDelta > 0) accumulatedThrottleDelta = 0
            accumulatedThrottleDelta += delta
        }

        // 单次音效（累积变化触发，阈值由 throttleSensitivity 控制；上升/减少可分别开关）
        val now = System.currentTimeMillis()
        val canPlaySingle = now - lastSinglePlayTime >= SINGLE_PLAY_COOLDOWN_MS
        var willPlaySingle = false

        if (canPlaySingle) {
            if (accumulatedThrottleDelta >= throttleSensitivity) {
                if (isSingleUpEnabled) {
                    willPlaySingle = true
                    playSingleUp()
                    lastSinglePlayTime = now
                }
                accumulatedThrottleDelta = 0
            } else if (accumulatedThrottleDelta <= -throttleSensitivity) {
                if (isSingleDownEnabled) {
                    willPlaySingle = true
                    playSingleDown()
                    lastSinglePlayTime = now
                }
                accumulatedThrottleDelta = 0
            }
        }

        // 循环状态切换（怠速=车速0，保持=油门或车速，取决于控制模式）
        updateLoopState(willPlaySingle)

        Log.d("SoundManager", "setThrottle: old=$oldThrottle, new=$throttle, delta=$delta, acc=$accumulatedThrottleDelta, willPlaySingle=$willPlaySingle")
    }

    // ★ 车速更新入口：控制引擎音效 + 保持音效(loopUp)的音量/速度（0~60KM/H，超出钳位到 60）
    fun setSpeed(speedKmh: Float) {
        currentSpeedKmh = speedKmh
        val clamped = speedKmh.coerceIn(0f, 60f)
        val ratio = clamped / 60f

        // 引擎音效音量/速度
        val speed = minSpeed + ratio * (maxSpeed - minSpeed)
        enginePlayer?.playbackParameters = PlaybackParameters(speed, 1.0f)

        val normalVolume = (minVolume + ratio * (maxVolume - minVolume)).coerceIn(0f, 1f)
        if (isDucking) {
            originalEngineVolume = normalVolume
        } else if (isEngineEnabled) {
            enginePlayer?.volume = normalVolume
        } else {
            enginePlayer?.volume = 0f
        }

        // 保持音效(loopUp)音量/速度
        updateLoopUpParamsByRatio(ratio)

        // 循环状态切换（怠速由车速控制）
        if (isRunning) updateLoopState()
    }

    fun notifyThrottleChange(newThrottle: Int, oldThrottle: Int) {
        setThrottle(newThrottle, isRunning)
    }

    /** 保持（loopUp）触发条件是否满足：油门控制看油门阈值，车速控制看车速>0。 */
    private fun isHoldConditionMet(): Boolean =
        if (isLoopSpeedControlled) currentSpeedKmh > 0f else currentThrottle > IDLE_THRESHOLD

    /** 怠速（loopDown）触发条件是否满足：油门控制看油门怠速区间，车速控制看车速=0。 */
    private fun isIdleConditionMet(): Boolean =
        if (isLoopSpeedControlled) currentSpeedKmh <= 0f else currentThrottle in 0..IDLE_THRESHOLD

    private fun updateLoopState(skipStartLoop: Boolean = false) {
        // 后台暂停时不切换循环状态（防止车速变化在后台重启循环音效）
        if (isPausedForBackground) return
        val isHold = isHoldConditionMet()
        val isIdle = isIdleConditionMet()

        if (isHold) {
            // 保持状态：播放 loopUp
            cancelIdleTimer()
            if (isHoldDownActive) {
                stopLoopDownImmediate()
            }
            if (isKeepEnabled && !isHoldUpActive && !skipStartLoop) {
                Log.d("SoundManager", "updateLoopState: starting loopUp")
                startLoopUp()
            }
            if (!isKeepEnabled && isHoldUpActive) {
                Log.d("SoundManager", "updateLoopState: keep disabled, stopping loopUp")
                stopLoopUpImmediate()
            }
        } else if (isIdle) {
            // 怠速状态：延迟后播放 loopDown
            if (isHoldUpActive) {
                Log.d("SoundManager", "updateLoopState: entering idle, stopping loopUp")
                stopLoopUpImmediate()
            }
            if (isIdleEnabled && !wasInIdle) {
                startIdleTimer()
            }
        } else {
            // 未达保持/怠速条件：无循环音效
            cancelIdleTimer()
            if (isHoldDownActive) {
                stopLoopDownImmediate()
            }
            if (isHoldUpActive) {
                stopLoopUpImmediate()
            }
        }

        wasInIdle = isIdle
    }

    private fun startIdleTimer() {
        cancelIdleTimer()
        idleTimerRunnable = Runnable {
            if (isIdleConditionMet() && isIdleEnabled) {
                if (isHoldUpActive) {
                    stopLoopUpImmediate()
                }
                if (!isHoldDownActive) {
                    Log.d("SoundManager", "idleTimer: starting loopDown")
                    startLoopDown()
                }
            }
        }
        idleTimerHandler.postDelayed(idleTimerRunnable!!, IDLE_ENTER_DELAY_MS)
    }

    private fun cancelIdleTimer() {
        idleTimerRunnable?.let { idleTimerHandler.removeCallbacks(it) }
        idleTimerRunnable = null
    }

    // ==================== 单次音效 ====================

    fun playSingleUp() {
        cancelRestartLoopUp()
        playSingleUpInternal()
    }

    private fun playSingleUpInternal() {
        if (isHoldDownActive) {
            stopLoopDownImmediate()
        }
        cancelFade()
        applyDuck()

        val ratio = currentThrottle / 100f
        val speed = 1.0f + ratio * (singleUpMaxSpeed - 1.0f)
        val dynamicVolume = singleUpMinVolume + ratio * (singleUpMaxVolume - singleUpMinVolume)

        singleUpPlayer?.apply {
            seekTo(0)
            volume = 0f
            playbackParameters = PlaybackParameters(speed, 1.0f)
            play()
        }
        startFade(singleUpPlayer, dynamicVolume) {
            checkAndRestartLoopAfterSingleUp()
        }
    }

    fun playSingleDown() {
        playSingleDownInternal()
    }

    private fun playSingleDownInternal() {
        if (isHoldUpActive) {
            stopLoopUpImmediate()
        }
        cancelFade()
        applyDuck()

        val ratio = currentThrottle / 100f
        val speed = 1.0f + ratio * (singleDownMaxSpeed - 1.0f)
        val dynamicVolume = singleDownMinVolume + ratio * (singleDownMaxVolume - singleDownMinVolume)

        singleDownPlayer?.apply {
            seekTo(0)
            volume = 0f
            playbackParameters = PlaybackParameters(speed, 1.0f)
            play()
        }
        startFade(singleDownPlayer, dynamicVolume) {
            checkAndRestartLoopAfterSingleDown()
        }
    }

    private fun checkAndRestartLoopAfterSingleUp() {
        if (isHoldUpActive) {
            Log.d("SoundManager", "checkAndRestartLoopAfterSingleUp: already in hold up, skip restart")
            return
        }
        cancelRestartLoopUp()
        restartLoopUpRunnable = Runnable {
            if (isHoldConditionMet() && isKeepEnabled && !isHoldDownActive && !isHoldUpActive) {
                Log.d("SoundManager", "checkAndRestartLoopAfterSingleUp (delayed): starting loopUp")
                startLoopUp()
            } else {
                Log.d("SoundManager", "checkAndRestartLoopAfterSingleUp (delayed): condition not met, skip")
            }
        }
        restartLoopUpHandler.postDelayed(restartLoopUpRunnable!!, RESTART_LOOP_UP_DELAY_MS)
    }

    private fun checkAndRestartLoopAfterSingleDown() {
        // 不做任何事，由计时器管理
    }

    private fun cancelRestartLoopUp() {
        restartLoopUpRunnable?.let { restartLoopUpHandler.removeCallbacks(it) }
        restartLoopUpRunnable = null
    }

    // ==================== 闪避方法 ====================

    private fun applyDuck() {
        if (isDucking) return
        isDucking = true
        enginePlayer?.let { player ->
            originalEngineVolume = player.volume
            val duckedVolume = originalEngineVolume * duckFactor
            player.volume = duckedVolume.coerceIn(0f, 1f)
            Log.d("SoundManager", "applyDuck: original=$originalEngineVolume, ducked=$duckedVolume")
        }
    }

    private fun cancelDuck() {
        duckRunnable?.let { duckHandler.removeCallbacks(it) }
        duckRunnable = null
        isDucking = false
    }

    private fun restoreEngineVolumeImmediately() {
        if (!isDucking) return
        enginePlayer?.volume = originalEngineVolume
        isDucking = false
        cancelDuck()
        Log.d("SoundManager", "restoreEngineVolumeImmediately: restored to $originalEngineVolume")
    }

    private fun restoreEngineVolumeGradual() {
        val player = enginePlayer ?: return
        val startVolume = player.volume
        val endVolume = originalEngineVolume
        val steps = 10
        val stepVolume = (endVolume - startVolume) / steps
        var currentStep = 0

        val runnable = object : Runnable {
            override fun run() {
                currentStep++
                if (currentStep < steps) {
                    val newVol = startVolume + stepVolume * currentStep
                    player.volume = newVol.coerceIn(0f, 1f)
                    duckHandler.postDelayed(this, 12)
                } else {
                    player.volume = endVolume
                    isDucking = false
                    duckRunnable = null
                    Log.d("SoundManager", "restoreEngineVolumeGradual: restored to $endVolume")
                }
            }
        }
        duckRunnable = runnable
        duckHandler.post(runnable)
    }

    // ==================== 淡入方法（单次音效） ====================

    private fun startFade(player: ExoPlayer?, targetVolume: Float, onComplete: (() -> Unit)? = null) {
        if (player == null) return
        fadePlayer = player
        fadeTargetVolume = targetVolume
        fadeStartTime = System.currentTimeMillis()
        fadeCompleteCallback = onComplete

        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - fadeStartTime
                when {
                    elapsed < fadeSilenceMs -> {
                        player.volume = 0f
                        fadeHandler.postDelayed(this, 16)
                    }
                    elapsed < fadeSilenceMs + fadeDurationMs -> {
                        val progress = (elapsed - fadeSilenceMs).toFloat() / fadeDurationMs
                        val currentVolume = min(progress, 1.0f) * fadeTargetVolume
                        player.volume = currentVolume
                        fadeHandler.postDelayed(this, 16)
                    }
                    else -> {
                        player.volume = fadeTargetVolume
                        fadeRunnable = null
                        fadeCompleteCallback?.invoke()
                        fadeCompleteCallback = null
                        if (isDucking) {
                            restoreEngineVolumeGradual()
                        }
                    }
                }
            }
        }
        fadeRunnable = runnable
        fadeHandler.post(runnable)
    }

    private fun cancelFade() {
        fadeRunnable?.let { fadeHandler.removeCallbacks(it) }
        fadeRunnable = null
        fadeCompleteCallback = null
        fadePlayer?.volume = 0f
        fadePlayer = null
    }

    // ==================== 循环音效（ExoPlayer） ====================

    fun startLoopUp() {
        if (isPausedForBackground) return
        if (!isKeepEnabled) return
        loopUpPlayer?.apply {
            playbackParameters = PlaybackParameters(1.0f, 1.0f)
            volume = 0f
            play()
        }
        isHoldUpActive = true
        startLoopUpFadeIn(loopUpMinVolume)
    }

    fun stopLoopUp() {
        if (loopUpPlayer?.isPlaying == true) {
            fastFadeOutAndStopLoopUp()
        } else {
            loopUpPlayer?.pause()
            loopUpPlayer?.seekTo(0)
            isHoldUpActive = false
        }
        cancelRestartLoopUp()
    }

    fun stopLoopUpImmediate() {
        cancelLoopUpFade()
        loopUpPlayer?.apply {
            pause()
            seekTo(0)
        }
        isHoldUpActive = false
        cancelRestartLoopUp()
    }

    fun stopLoopDownImmediate() {
        loopDownPlayer?.apply {
            pause()
            seekTo(0)
        }
        isHoldDownActive = false
    }

    fun updateLoopUpParamsByRatio(ratio: Float) {
        if (!isHoldUpActive) return
        val newVol = (loopUpMinVolume + ratio * (1.0f - loopUpMinVolume)).coerceIn(0f, 1f)
        val newSpeed = (1.0f + ratio * (loopUpMaxSpeed - 1.0f)).coerceAtLeast(0.01f)

        val currentVol = loopUpPlayer?.volume ?: newVol
        val currentSpeed = loopUpPlayer?.playbackParameters?.speed ?: newSpeed

        val volDiff = kotlin.math.abs(newVol - currentVol)
        val speedDiff = kotlin.math.abs(newSpeed - currentSpeed)
        if (volDiff < 0.02f && speedDiff < 0.02f) {
            loopUpPlayer?.volume = newVol
            loopUpPlayer?.playbackParameters = PlaybackParameters(newSpeed, 1.0f)
            return
        }

        cancelLoopUpFade()
        startLoopUpFadeOut(newVol, newSpeed)
    }

    fun updateLoopUpParamsNow() {
        updateLoopUpParamsByRatio(currentSpeedKmh.coerceIn(0f, 60f) / 60f)
    }

    private fun startLoopUpFadeOut(targetVolume: Float, targetSpeed: Float) {
        val player = loopUpPlayer ?: return
        val startVolume = player.volume

        loopUpFadeTargetVolume = targetVolume
        loopUpFadeStartTime = System.currentTimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - loopUpFadeStartTime
                if (elapsed < LOOP_UP_FADE_DURATION_MS) {
                    val progress = elapsed.toFloat() / LOOP_UP_FADE_DURATION_MS
                    val currentVol = startVolume * (1f - progress)
                    player.volume = currentVol.coerceIn(0f, 1f)
                    loopUpFadeHandler.postDelayed(this, LOOP_UP_FADE_INTERVAL_MS)
                } else {
                    player.volume = 0f
                    player.playbackParameters = PlaybackParameters(targetSpeed, 1.0f)
                    startLoopUpFadeIn(targetVolume)
                }
            }
        }
        loopUpFadeRunnable = runnable
        loopUpFadeHandler.post(runnable)
    }

    private fun startLoopUpFadeIn(targetVolume: Float) {
        val player = loopUpPlayer ?: return
        loopUpFadeStartTime = System.currentTimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - loopUpFadeStartTime
                if (elapsed < LOOP_UP_FADE_DURATION_MS) {
                    val progress = elapsed.toFloat() / LOOP_UP_FADE_DURATION_MS
                    val currentVol = targetVolume * progress
                    player.volume = currentVol.coerceIn(0f, 1f)
                    loopUpFadeHandler.postDelayed(this, LOOP_UP_FADE_INTERVAL_MS)
                } else {
                    player.volume = targetVolume
                    loopUpFadeRunnable = null
                }
            }
        }
        loopUpFadeRunnable = runnable
        loopUpFadeHandler.post(runnable)
    }

    private fun cancelLoopUpFade() {
        loopUpFadeRunnable?.let { loopUpFadeHandler.removeCallbacks(it) }
        loopUpFadeRunnable = null
    }

    private fun fastFadeOutAndStopLoopUp() {
        val player = loopUpPlayer ?: return
        val startVolume = player.volume
        val startTime = System.currentTimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < LOOP_UP_FADE_DURATION_MS) {
                    val progress = elapsed.toFloat() / LOOP_UP_FADE_DURATION_MS
                    player.volume = (startVolume * (1f - progress)).coerceIn(0f, 1f)
                    loopUpFadeHandler.postDelayed(this, LOOP_UP_FADE_INTERVAL_MS)
                } else {
                    player.volume = 0f
                    player.pause()
                    player.seekTo(0)
                    isHoldUpActive = false
                }
            }
        }
        cancelLoopUpFade()
        loopUpFadeRunnable = runnable
        loopUpFadeHandler.post(runnable)
    }

    fun startLoopDown() {
        if (isPausedForBackground) return
        loopDownPlayer?.apply {
            playbackParameters = PlaybackParameters(loopDownSpeed, 1.0f)
            volume = loopDownVolume
            play()
        }
        isHoldDownActive = true
    }

    fun stopLoopDown() {
        loopDownPlayer?.pause()
        loopDownPlayer?.seekTo(0)
        isHoldDownActive = false
    }

    fun restartLoopDown() {
        if (isHoldDownActive) {
            stopLoopDown()
            startLoopDown()
        }
    }

    fun updateLoopDownParams() {
        if (isHoldDownActive) {
            loopDownPlayer?.apply {
                playbackParameters = PlaybackParameters(loopDownSpeed, 1.0f)
                volume = loopDownVolume
            }
        }
    }

    fun release() {
        cancelFade()
        cancelDuck()
        cancelLoopUpFade()
        cancelIdleTimer()
        cancelRestartLoopUp()
        enginePlayer?.release()
        loopUpPlayer?.release()
        loopDownPlayer?.release()
        singleUpPlayer?.release()
        singleDownPlayer?.release()
        instance = null
    }
}
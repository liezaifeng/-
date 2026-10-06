package com.ideawav.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 扇形油门进度表：底部留空的圆弧，颜色随油门从蓝→绿→黄→红彩虹渐变。
 * 中央在油门超过 25% 后渐显"注意安全"四字（田字格）。
 * 提供 flash() 闪光动画：进度冲满 + 字全浮现 + 发光，随后淡出。
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0f // 0-100
    private var speedKmh = 0f // 当前车速 KM/H
    private var flash = 0f // 0-1 闪光强度，0 表示无闪光
    private var flashAnimator: ValueAnimator? = null

    private val startAngle = 135f
    private val sweepAngle = 270f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E0D0B0")
    }

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E94560")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("serif", Typeface.BOLD)
    }

    fun setProgress(percent: Int) {
        progress = percent.coerceIn(0, 100).toFloat()
        invalidate()
    }

    /** 更新当前车速（KM/H），中央数字显示真实值，不钳位。 */
    fun setSpeed(kmh: Float) {
        speedKmh = kmh
        invalidate()
    }

    /** 触发一次约 800ms 的闪光：冲满 → 保持 → 淡出。 */
    fun flash() {
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(0f, 1f, 1f, 0f).apply {
            duration = 800L
            addUpdateListener { anim ->
                flash = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    flash = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val strokeWidth = min(width, height) * 0.08f
        bgPaint.strokeWidth = strokeWidth
        fgPaint.strokeWidth = strokeWidth

        val padding = strokeWidth / 2f + dp(6f)
        val rect = RectF(
            padding,
            padding,
            width - padding,
            height - padding
        )

        val isFlashing = flash > 0f
        val effectiveRatio = if (isFlashing) flash else progress / 100f

        // 背景弧
        canvas.drawArc(rect, startAngle, sweepAngle, false, bgPaint)

        // 前景弧：颜色随油门从蓝(240°)→绿(120°)→黄(60°)→红(0°)彩虹渐变
        val hue = 240f * (1f - effectiveRatio)
        val fgColor = Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
        fgPaint.color = fgColor

        // 发光效果（闪光时）
        if (isFlashing) {
            fgPaint.setShadowLayer(flash * dp(14f), 0f, 0f, fgColor)
        } else {
            fgPaint.clearShadowLayer()
        }

        val sweep = sweepAngle * effectiveRatio
        if (sweep > 0f) {
            canvas.drawArc(rect, startAngle, sweep, false, fgPaint)
        }

        // 中央车速数字（真实车速，不钳位；颜色随车速彩虹渐变）
        drawSpeedText(canvas)
    }

    private fun drawSpeedText(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val strokeWidth = min(width, height) * 0.08f
        val padding = strokeWidth / 2f + dp(6f)
        val innerRadius = min(width, height) / 2f - padding - strokeWidth

        // 颜色随车速（钳位到 0~60）彩虹渐变，与前景弧一致；不透明、无渐变效果
        val clamped = speedKmh.coerceIn(0f, 60f)
        val ratio = clamped / 60f
        val hue = 240f * (1f - ratio)
        textPaint.color = Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
        textPaint.alpha = 255
        textPaint.clearShadowLayer()

        // 数字大小：与"注意安全"四字田字格面积相当
        val cell = innerRadius * 1.3f / 2f
        textPaint.textSize = cell * 1.5f

        // 真实车速（不钳位，超过 60 仍显示真实值）
        val text = speedKmh.toInt().toString()
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

package com.ideawav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 灵敏度颜色块：
 * - 滑条模式：圆形 + 蓝→红渐变（28=蓝 → 8=红，参照油门表盘颜色）。
 * - 手动输入模式：正方形 + 彩虹渐变。
 */
class ColorBlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hue = 240f
    private var rainbow = false
    private var square = false

    /** 滑条模式：圆形 + 蓝→红（percent 0..1，0=蓝/最迟钝，1=红/最灵敏）。 */
    fun setSliderColor(percent: Float) {
        rainbow = false
        square = false
        hue = 240f * (1f - percent.coerceIn(0f, 1f))
        invalidate()
    }

    /** 手动输入模式：正方形 + 彩虹渐变。 */
    fun setRainbowSquare() {
        rainbow = true
        square = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val inset = size * 0.12f
        val cx = size / 2f
        val cy = size / 2f
        val radius = (size - inset * 2f) / 2f

        if (rainbow) {
            val colors = intArrayOf(
                Color.RED, Color.MAGENTA, Color.BLUE,
                Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
            )
            paint.shader = SweepGradient(cx, cy, colors, null)
        } else {
            paint.shader = null
            paint.color = Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
        }

        if (square) {
            canvas.drawRect(RectF(inset, inset, size - inset, size - inset), paint)
        } else {
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.shader = null
    }
}

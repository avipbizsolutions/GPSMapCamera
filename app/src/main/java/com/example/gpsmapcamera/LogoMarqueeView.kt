package com.example.gpsmapcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** One line of brand logos that scrolls slowly and endlessly from right to left. */
class LogoMarqueeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val gap = 28 * density
    private val speed = 28 * density // pixels per second
    private var logos: List<Drawable> = emptyList()
    private var widths = FloatArray(0)
    private var cycle = 0f
    private var offset = 0f
    private var lastFrame = 0L

    fun setLogos(ids: List<Int>) {
        logos = ids.mapNotNull { ContextCompat.getDrawable(context, it) }
        measureLogos()
        invalidate()
    }

    private fun measureLogos() {
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (h <= 0) return
        widths = FloatArray(logos.size) { i ->
            val d = logos[i]
            val iw = d.intrinsicWidth.coerceAtLeast(1)
            val ih = d.intrinsicHeight.coerceAtLeast(1)
            h * iw / ih
        }
        cycle = widths.sum() + gap * logos.size
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureLogos()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrame = 0L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (logos.isEmpty() || cycle <= 0f) return
        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L) offset = (offset + speed * (now - lastFrame) / 1000f) % cycle
        lastFrame = now

        val top = paddingTop
        val bottom = height - paddingBottom
        var x = -offset
        var i = 0
        while (x < width) {
            val w = widths[i]
            if (x + w > 0) {
                logos[i].setBounds(x.toInt(), top, (x + w).toInt(), bottom)
                logos[i].draw(canvas)
            }
            x += w + gap
            i = (i + 1) % logos.size
        }
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        lastFrame = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}

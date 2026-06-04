package com.laserforge.lpbf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.laserforge.lpbf.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mirrors the HTML's `.visual-joystick-container` + `.joystick-handle`. Reports a normalized
 * (-1..1, -1..1) delta to [onMove] whenever the handle is dragged. `setActive(true)` is
 * called when the laser is firing and shows the red gradient + glow.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#33FFFFFF")
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#19646464")
    }
    private val innerRingBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#26FFFFFF")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(3f), dp(2f)), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }

    private var handleDx: Float = 0f
    private var handleDy: Float = 0f
    private var active: Boolean = false
    private var touching: Boolean = false

    /** (-1..1, -1..1) in joystick space. */
    var onMove: ((Float, Float) -> Unit)? = null
    var onEnd: (() -> Unit)? = null

    private val centerColor = Color.parseColor("#667eea")
    private val centerColorEnd = Color.parseColor("#764ba2")
    private val activeColorStart = Color.parseColor("#ff6b6b")
    private val activeColorEnd = Color.parseColor("#ff0000")

    private val baseInterpolator = AccelerateDecelerateInterpolator()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setActive(a: Boolean) {
        if (active == a) return
        active = a
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f

        // Base circle (radial gradient background).
        basePaint.shader = RadialGradient(
            cx, cy, radius,
            Color.parseColor("#2a2a2a"), Color.parseColor("#1a1a1a"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius - borderPaint.strokeWidth, basePaint)
        canvas.drawCircle(cx, cy, radius - borderPaint.strokeWidth / 2f, borderPaint)

        // Inner dashed ring (joystick base).
        val innerR = radius * 0.5f
        canvas.drawCircle(cx, cy, innerR, innerRingPaint)
        canvas.drawCircle(cx, cy, innerR, innerRingBorderPaint)

        // Handle.
        val handleR = radius * 0.4f
        val hx = cx + handleDx
        val hy = cy + handleDy
        handlePaint.shader = if (active) {
            // Red gradient with outer glow.
            handleGlowPaint.color = ColorUtils.setAlphaComponent(activeColorEnd, 100)
            canvas.drawCircle(hx, hy, handleR * 1.2f, handleGlowPaint)
            android.graphics.LinearGradient(
                hx - handleR, hy - handleR, hx + handleR, hy + handleR,
                activeColorStart, activeColorEnd, Shader.TileMode.CLAMP
            )
        } else {
            // Purple gradient.
            android.graphics.LinearGradient(
                hx - handleR, hy - handleR, hx + handleR, hy + handleR,
                centerColor, centerColorEnd, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(hx, hy, handleR, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Prevent parent ScrollView from stealing touch events during joystick drag
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                val rectW = width.toFloat()
                val rectH = height.toFloat()
                val cx = rectW / 2f
                val cy = rectH / 2f
                val maxDistance = min(rectW, rectH) / 2f - dp(20f)
                var dx = event.x - cx
                var dy = event.y - cy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > maxDistance) {
                    dx = dx / dist * maxDistance
                    dy = dy / dist * maxDistance
                }
                handleDx = dx
                handleDy = dy
                invalidate()
                onMove?.invoke(dx / maxDistance, dy / maxDistance)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!touching) return true
                touching = false
                handleDx = 0f
                handleDy = 0f
                animate().interpolator = baseInterpolator
                animate().setDuration(200).start()
                invalidate()
                onEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

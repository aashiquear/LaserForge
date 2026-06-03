package com.laserforge.lpbf.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.laserforge.lpbf.R
import java.util.Locale

/**
 * The two coordinate status bars in the HTML: top bar (X / Y + pointer indicator) and
 * bottom bar (LAYER / HEIGHT / LASER).
 */
class CoordinateBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Mode { TOP, BOTTOM }

    private var mode: Mode = Mode.TOP

    private val coordXValue: TextView
    private val coordYValue: TextView
    private val coordLayerValue: TextView
    private val coordHeightValue: TextView
    private val laserStatusValue: TextView
    private val pointerIndicator: TextView
    private val pointerStatusText: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        coordXValue = makeValue("#00ffff")
        coordYValue = makeValue("#ffff00")
        coordLayerValue = makeValue("#ff66ff")
        coordHeightValue = makeValue("#66ff66")
        laserStatusValue = TextView(context).apply {
            text = "READY"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#66ff66"))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setBackgroundResource(R.drawable.laser_status_ready_bg)
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        }
        pointerIndicator = TextView(context).apply {
            text = "●"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor("#444444"))
            setPadding(0, 0, dp(6f), 0)
        }
        pointerStatusText = TextView(context).apply {
            text = "Ready"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.WHITE)
        }
        configure(Mode.TOP)
    }

    fun configure(mode: Mode) {
        this.mode = mode
        removeAllViews()

        // Detach internal views from their previous parents (leftGroup/rightGroup)
        // to avoid "specified child already has a parent" IllegalStateException
        listOf(
            coordXValue, coordYValue, coordLayerValue, coordHeightValue,
            laserStatusValue, pointerIndicator, pointerStatusText
        ).forEach { (it.parent as? ViewGroup)?.removeView(it) }

        when (mode) {
            Mode.TOP -> {
                gravity = Gravity.CENTER_VERTICAL
                val leftGroup = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(makeLabel("X:", "#00ffff"))
                    addView(coordXValue.apply { setPadding(dp(6f), 0, dp(12f), 0) })
                    addView(makeLabel("Y:", "#ffff00"))
                    addView(coordYValue)
                }
                val spacer = Space(context).also { it.layoutParams = LayoutParams(0, 1, 1f) }
                val rightGroup = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(pointerIndicator)
                    addView(pointerStatusText)
                }
                addView(leftGroup)
                addView(spacer)
                addView(rightGroup)
            }
            Mode.BOTTOM -> {
                gravity = Gravity.CENTER_VERTICAL
                val leftGroup = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(makeLabel("LAYER:", "#ff66ff"))
                    addView(coordLayerValue.apply { setPadding(dp(6f), 0, dp(12f), 0) })
                    addView(makeLabel("HEIGHT:", "#66ff66"))
                    addView(coordHeightValue)
                }
                val spacer = Space(context).also { it.layoutParams = LayoutParams(0, 1, 1f) }
                val rightGroup = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(makeLabel("LASER:", "#ff9933"))
                    addView(laserStatusValue.apply {
                        setPaddingRelative(dp(6f), dp(2f), dp(6f), dp(2f))
                    })
                }
                addView(leftGroup)
                addView(spacer)
                addView(rightGroup)
            }
        }
    }

    fun setCoordX(v: Float) { coordXValue.text = String.format(Locale.US, "%.2f", v) }
    fun setCoordY(v: Float) { coordYValue.text = String.format(Locale.US, "%.2f", v) }
    fun setLayer(n: Int) { coordLayerValue.text = n.toString() }
    fun setHeightMm(v: Float) { coordHeightValue.text = String.format(Locale.US, "%.1fmm", v) }

    fun setLaserStatus(status: String) {
        laserStatusValue.text = status
        when (status) {
            "FIRING", "ARMED" -> {
                laserStatusValue.setTextColor(Color.parseColor("#ff4444"))
                laserStatusValue.setBackgroundResource(R.drawable.laser_status_firing_bg)
            }
            "READY", "DONE" -> {
                laserStatusValue.setTextColor(Color.parseColor("#66ff66"))
                laserStatusValue.setBackgroundResource(R.drawable.laser_status_ready_bg)
            }
            else -> {
                laserStatusValue.setTextColor(Color.parseColor("#ffaa44"))
                laserStatusValue.setBackgroundResource(R.drawable.laser_status_wait_bg)
            }
        }
    }

    fun setPointerStatus(status: String) {
        when (status) {
            "firing" -> {
                pointerIndicator.setTextColor(Color.parseColor("#ff0000"))
                pointerStatusText.text = "Firing"
            }
            "active" -> {
                pointerIndicator.setTextColor(Color.parseColor("#00ff00"))
                pointerStatusText.text = "Active"
            }
            else -> {
                pointerIndicator.setTextColor(Color.parseColor("#444444"))
                pointerStatusText.text = "Ready"
            }
        }
    }

    private fun makeLabel(text: String, color: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor(color))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
    }

    private fun makeValue(color: String): TextView {
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor(color))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setBackgroundResource(R.drawable.coord_value_bg)
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            text = "0.00"
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}

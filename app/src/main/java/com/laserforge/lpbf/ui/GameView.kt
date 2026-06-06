package com.laserforge.lpbf.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import com.laserforge.lpbf.game.GameEngine
import com.laserforge.lpbf.render.GameRenderer
import com.laserforge.lpbf.render.Light
import com.laserforge.lpbf.render.MatrixUtil

/**
 * The main 3D viewport. Hosts a [GameRenderer] and forwards touch events to the
 * [GameEngine] for raycasting/drawing. Camera input is also handled here.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val engine: GameEngine = GameEngine()
    val renderer: GameRenderer
    private var lastTouch: Pair<Float, Float> = 0f to 0f
    private var isTwoFinger: Boolean = false
    private var twoFingerStartDist: Float = 0f
    private var twoFingerStartAngle: Float = 0f
    private var prevTheta: Float = 0f
    private var isOrbiting: Boolean = false
    private var lastPinch: Float = 1f

    init {
        setEGLContextClientVersion(2)
        renderer = GameRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Prevent ScrollView from intercepting touches while we are interacting with the 3D view
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch = event.x to event.y
                if (event.pointerCount == 1) {
                    // Left or right? We treat single-finger as drawing during DRAWING.
                    if (engine.gameState == com.laserforge.lpbf.game.GameState.DRAWING) {
                        raycastAndDraw(event.x, event.y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isTwoFinger = true
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    twoFingerStartDist = kotlin.math.sqrt(dx * dx + dy * dy)
                    twoFingerStartAngle = kotlin.math.atan2(dy, dx)
                    prevTheta = engine.cameraTheta
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTwoFinger && event.pointerCount == 2) {
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    val a = kotlin.math.atan2(dy, dx)
                    val delta = (twoFingerStartDist - d) * 0.05f
                    engine.cameraDistance = (engine.cameraDistance + delta).coerceIn(engine.cameraDistance - 1, engine.cameraDistance + 1)
                    engine.cameraDistance = engine.cameraDistance.coerceIn(12f, 40f)
                    twoFingerStartDist = d
                    val rotDelta = a - twoFingerStartAngle
                    engine.cameraTheta = prevTheta - rotDelta
                    twoFingerStartAngle = a
                } else if (!isTwoFinger && event.pointerCount == 1) {
                    if (engine.gameState == com.laserforge.lpbf.game.GameState.DRAWING) {
                        raycastAndDraw(event.x, event.y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) isTwoFinger = false
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTwoFinger = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Convert screen coords → normalized device coords → world (x, z) on the powder plane
     * (y = buildSurfaceY) using the current camera. This is the same raycast the HTML
     * does against the powderLayer plane.
     */
    private fun raycastAndDraw(screenX: Float, screenY: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val ndcX = (screenX / w) * 2f - 1f
        val ndcY = 1f - (screenY / h) * 2f

        val view = FloatArray(16)
        MatrixUtil.lookAt(view, engine.cameraPosition(),
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        val aspect = w / h
        val proj = FloatArray(16)
        MatrixUtil.perspective(proj, 50f, aspect, 0.1f, 1000f)
        val invVP = FloatArray(16)
        if (!MatrixUtil.invert(invVP, multiplyMat(proj, view))) return
        val nearWorld = transformPoint(invVP, ndcX, ndcY, -1f)
        val farWorld = transformPoint(invVP, ndcX, ndcY, 1f)
        // Intersect with plane y = buildSurfaceY.
        val dx = farWorld[0] - nearWorld[0]
        val dy = farWorld[1] - nearWorld[1]
        val dz = farWorld[2] - nearWorld[2]
        if (kotlin.math.abs(dy) < 1e-6f) return
        val t = (engine.buildSurfaceY - nearWorld[1]) / dy
        val wx = nearWorld[0] + t * dx
        val wz = nearWorld[2] + t * dz
        
        // Clamp to build plate (chamberSize - 1) / 2
        val maxPos = (engine.chamberSize - 1f) / 2f
        val localX = wx.coerceIn(-maxPos, maxPos)
        val localZ = wz.coerceIn(-maxPos, maxPos)

        engine.onDrawing(localX, localZ)
    }

    private fun multiplyMat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        MatrixUtil.multiply(out, a, b)
        return out
    }

    private fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val tx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val ty = m[1] * x + m[5] * y + m[9] * z + m[13]
        val tz = m[2] * x + m[6] * y + m[10] * z + m[14]
        val tw = m[3] * x + m[7] * y + m[11] * z + m[15]
        return floatArrayOf(tx / tw, ty / tw, tz / tw)
    }
}

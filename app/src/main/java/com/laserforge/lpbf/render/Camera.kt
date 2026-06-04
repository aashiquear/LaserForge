package com.laserforge.lpbf.render

import kotlin.math.cos
import kotlin.math.sin

/**
 * Orbit camera matching the HTML's `cameraAngle.theta/phi` + `cameraDistance` model.
 * - theta rotates around the Y axis.
 * - phi tilts the camera from straight up.
 * - lookAt target is the scene origin.
 *
 * HTML defaults: theta = π/4 (camera in the +X,+Z quadrant looking toward origin),
 * phi = π/4 (45° elevation above the platform).
 */
class Camera {
    var theta: Float = (Math.PI / 4).toFloat()
    var phi: Float = (Math.PI / 4).toFloat()
    var distance: Float = 22f

    val minDistance = 12f
    val maxDistance = 40f

    val eyeX: Float get() = distance * sin(phi.toDouble()).toFloat() * cos(theta.toDouble()).toFloat()
    val eyeY: Float get() = distance * cos(phi.toDouble()).toFloat()
    val eyeZ: Float get() = distance * sin(phi.toDouble()).toFloat() * sin(theta.toDouble()).toFloat()

    fun viewMatrix(out: FloatArray) {
        MatrixUtil.lookAt(out,
            floatArrayOf(eyeX, eyeY, eyeZ),
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f))
    }

    fun zoomIn() { distance = (distance - 2f).coerceAtLeast(minDistance) }
    fun zoomOut() { distance = (distance + 2f).coerceAtMost(maxDistance) }
    fun rotateLeft() { theta += 0.2f }
    fun rotateRight() { theta -= 0.2f }
    fun reset() {
        theta = (Math.PI / 4).toFloat()
        phi = (Math.PI / 4).toFloat()
        distance = 22f
    }
}

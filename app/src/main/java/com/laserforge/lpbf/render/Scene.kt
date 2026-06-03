package com.laserforge.lpbf.render

import android.opengl.GLES20

/**
 * Holds a list of directional + point + ambient lights to feed to the lit shader.
 * Mirrors the HTML's lighting setup almost exactly.
 */
class SceneLights {
    val ambient: FloatArray = floatArrayOf(0.376f, 0.376f, 0.376f)  // 0x606060, intensity 1
    val directional: MutableList<Light.Directional> = mutableListOf()
    var point: Light.Point? = null

    fun clear() {
        directional.clear()
        point = null
    }
}

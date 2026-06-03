package com.laserforge.lpbf.render

/**
 * Light types used in the scene graph. Mirrors Three.js's lights used in the HTML.
 */
sealed class Light {
    var intensity: Float = 1f

    class Ambient(intensity: Float = 1f) : Light() {
        init { this.intensity = intensity }
    }

    class Directional(
        val x: Float, val y: Float, val z: Float,
        var r: Float = 1f, var g: Float = 1f, var b: Float = 1f,
        intensity: Float = 0.8f
    ) : Light() {
        init { this.intensity = intensity }
    }

    class Point(
        val x: Float, val y: Float, val z: Float,
        var r: Float = 1f, var g: Float = 1f, var b: Float = 1f,
        var range: Float = 30f,
        intensity: Float = 1f
    ) : Light() {
        init { this.intensity = intensity }
    }
}

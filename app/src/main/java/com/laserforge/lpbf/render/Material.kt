package com.laserforge.lpbf.render

/**
 * Material colors in linear sRGB (we use the HTML's literal hex values, treating them as linear).
 * The renderer doesn't do gamma correction; matches the original's look.
 */
class Material(
    val r: Float,
    val g: Float,
    val b: Float,
    val alpha: Float = 1f,
    val emissiveR: Float = 0f,
    val emissiveG: Float = 0f,
    val emissiveB: Float = 0f,
    val metalness: Float = 0f,
    val roughness: Float = 0.5f
) {
    companion object {
        fun fromHex(hex: Long, alpha: Float = 1f): Material {
            val r = ((hex shr 16) and 0xFF) / 255f
            val g = ((hex shr 8) and 0xFF) / 255f
            val b = (hex and 0xFF) / 255f
            return Material(r, g, b, alpha)
        }

        fun emissive(hex: Long, alpha: Float = 1f, intensity: Float = 1f): Material {
            val r = ((hex shr 16) and 0xFF) / 255f
            val g = ((hex shr 8) and 0xFF) / 255f
            val b = (hex and 0xFF) / 255f
            return Material(r, g, b, alpha, r * intensity, g * intensity, b * intensity)
        }
    }
}

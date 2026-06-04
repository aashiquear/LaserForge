package com.laserforge.lpbf.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of the HTML's `calculateMatch()` function. Given the user's [layers] and
 * [targetVoxels], computes a 0..100 match score.
 *
 *   - For 2D mode, distance threshold = 0.4 (one layer is ignored).
 *   - For 3D mode, distance threshold = 0.5 and layer tolerance is ±1.
 *   - Score is then `min(100, raw * 1.8)`, rounded to int.
 */
object MatchCalculator {

    data class Voxel(val x: Float, val z: Float, val layer: Int)

    /**
     * @param layers     user layers; each is a list of (x, z) points.
     * @param target     target voxels with (x, z, layer).
     * @param is2D       true for 2D mode (relaxed distance & no layer check).
     */
    fun calculate(layers: List<List<Pair<Float, Float>>>, target: List<Voxel>, is2D: Boolean): Int {
        if (target.isEmpty()) return 0
        val user = ArrayList<Voxel>()
        for ((idx, layer) in layers.withIndex()) {
            for (p in layer) user.add(Voxel(p.first, p.second, idx))
        }
        if (user.isEmpty()) return 0
        val threshold = if (is2D) 0.4f else 0.5f

        var matches = 0
        for (t in target) {
            val found = user.any { u ->
                val dx = u.x - t.x
                val dz = u.z - t.z
                val layerOk = is2D || kotlin.math.abs((u.layer) - (t.layer)) <= 1
                sqrt(dx * dx + dz * dz) < threshold && layerOk
            }
            if (found) matches++
        }
        val raw = (matches.toFloat() / target.size) * 100f
        return raw.toDouble().times(1.8).coerceAtMost(100.0).toInt()
    }
}

/**
 * Generates target shapes. Port of `generate2DShape` and `generate3DShape` from the HTML.
 */
object TargetShape {

    private val alphanumeric = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
    )

    data class Emoji(val char: String, val name: String)
    private val emojis = listOf(
        Emoji("☺", "Smiley"),
        Emoji("○", "Circle"),
        Emoji("△", "Triangle"),
        Emoji("□", "Square"),
        Emoji("♡", "Heart"),
        Emoji("★", "Star"),
        Emoji("◇", "Diamond"),
        Emoji("⬢", "Hexagon")
    )

    data class SolidShape(val name: String, val layers: Int)
    private val solids = listOf(
        SolidShape("Pyramid", 8),
        SolidShape("Cube", 6),
        SolidShape("Cylinder", 8),
        SolidShape("Cone", 8),
        SolidShape("Sphere", 10)
    )
    private val frames = listOf(
        SolidShape("Pyramid Frame", 8),
        SolidShape("Cube Frame", 6),
        SolidShape("Cylinder Frame", 8),
        SolidShape("Cone Frame", 8)
    )

    /** Returns a string of (x, z) voxels for a 2D shape. */
    data class Shape2D(val name: String, val symbol: String, val voxels: List<Pair<Float, Float>>)

    /** Returns a [MatchCalculator.Voxel] list for a 3D shape, plus its [name] and [layers]. */
    data class Shape3D(val name: String, val layers: Int, val voxels: List<MatchCalculator.Voxel>)

    fun generate2D(type: String): Shape2D {
        return if (type == "alphanumeric") {
            val ch = alphanumeric.random()
            Shape2D(ch, ch, sampleShape(ch, isAlphanumeric = true))
        } else {
            val e = emojis.random()
            Shape2D(e.name, e.char, sampleShape(e.char, isAlphanumeric = false))
        }
    }

    fun generate3D(type: String): Shape3D {
        val list = if (type == "solid") solids else frames
        val s = list.random()
        return Shape3D(s.name, s.layers, generate3DVoxels(s, type))
    }

    /**
     * Rasterize the character into a 200x150 alpha mask, then sample every 2px
     * and return the matched (x, z) world coordinates. Mirrors HTML's
     * `ctx.fillText` → `getImageData` → voxel generation.
     */
    private fun sampleShape(char: String, isAlphanumeric: Boolean): List<Pair<Float, Float>> {
        val bmp = android.graphics.Bitmap.createBitmap(200, 150, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.BLACK)
        val p = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = if (isAlphanumeric) 120f else 100f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        c.drawText(char, 100f, 75f + p.textSize / 3f, p)
        val scale = 4.5f / 100f
        val result = ArrayList<Pair<Float, Float>>()
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val idx = y * w + x
                val a = (pixels[idx] ushr 24) and 0xFF
                if (a > 128) {
                    result.add(Pair((x - 100) * scale, (75 - y) * scale))
                }
            }
        }
        bmp.recycle()
        return result
    }

    /** Mirrors `generateTargetVoxels3D` from the HTML. */
    private fun generate3DVoxels(s: SolidShape, type: String): List<MatchCalculator.Voxel> {
        val out = ArrayList<MatchCalculator.Voxel>()
        val layers = s.layers
        val radius = 2f
        when {
            s.name.contains("Pyramid") -> {
                for (layer in 0 until layers) {
                    val size = radius * (1f - layer.toFloat() / layers)
                    if (type == "solid") {
                        var x = -size
                        while (x <= size) { var z = -size; while (z <= size) { out.add(MatchCalculator.Voxel(x, z, layer)); z += 0.3f }; x += 0.3f }
                    } else {
                        var x = -size
                        while (x <= size) {
                            out.add(MatchCalculator.Voxel(x, -size, layer))
                            out.add(MatchCalculator.Voxel(x, size, layer))
                            x += 0.3f
                        }
                        var z = -size
                        while (z <= size) {
                            out.add(MatchCalculator.Voxel(-size, z, layer))
                            out.add(MatchCalculator.Voxel(size, z, layer))
                            z += 0.3f
                        }
                    }
                }
            }
            s.name.contains("Cube") -> {
                for (layer in 0 until layers) {
                    if (type == "solid") {
                        var x = -radius
                        while (x <= radius) { var z = -radius; while (z <= radius) { out.add(MatchCalculator.Voxel(x, z, layer)); z += 0.3f }; x += 0.3f }
                    } else {
                        var x = -radius
                        while (x <= radius) {
                            out.add(MatchCalculator.Voxel(x, -radius, layer))
                            out.add(MatchCalculator.Voxel(x, radius, layer))
                            x += 0.3f
                        }
                        var z = -radius
                        while (z <= radius) {
                            out.add(MatchCalculator.Voxel(-radius, z, layer))
                            out.add(MatchCalculator.Voxel(radius, z, layer))
                            z += 0.3f
                        }
                    }
                }
            }
            s.name.contains("Cylinder") -> {
                for (layer in 0 until layers) {
                    var a = 0.0
                    while (a < 2 * PI) {
                        if (type == "solid") {
                            var r = 0f
                            while (r <= radius) {
                                out.add(MatchCalculator.Voxel((cos(a) * r).toFloat(), (sin(a) * r).toFloat(), layer))
                                r += 0.3f
                            }
                        } else {
                            out.add(MatchCalculator.Voxel(
                                (cos(a) * radius).toFloat(),
                                (sin(a) * radius).toFloat(),
                                layer))
                        }
                        a += 0.2
                    }
                }
            }
            s.name.contains("Cone") -> {
                for (layer in 0 until layers) {
                    val r = radius * (1f - layer.toFloat() / layers)
                    var a = 0.0
                    while (a < 2 * PI) {
                        if (type == "solid") {
                            var rr = 0f
                            while (rr <= r) {
                                out.add(MatchCalculator.Voxel((cos(a) * rr).toFloat(), (sin(a) * rr).toFloat(), layer))
                                rr += 0.3f
                            }
                        } else {
                            out.add(MatchCalculator.Voxel(
                                (cos(a) * r).toFloat(),
                                (sin(a) * r).toFloat(),
                                layer))
                        }
                        a += 0.2
                    }
                }
            }
            s.name.contains("Sphere") -> {
                for (layer in 0 until layers) {
                    val t = layer.toFloat() / (layers - 1).coerceAtLeast(1)
                    val ang = PI * t
                    val r = sin(ang).toFloat() * radius
                    var a = 0.0
                    while (a < 2 * PI) {
                        if (type == "solid") {
                            var rr = 0f
                            while (rr <= r) {
                                out.add(MatchCalculator.Voxel((cos(a) * rr).toFloat(), (sin(a) * rr).toFloat(), layer))
                                rr += 0.3f
                            }
                        } else {
                            out.add(MatchCalculator.Voxel(
                                (cos(a) * r).toFloat(),
                                (sin(a) * r).toFloat(),
                                layer))
                        }
                        a += 0.2
                    }
                }
            }
        }
        return out
    }
}

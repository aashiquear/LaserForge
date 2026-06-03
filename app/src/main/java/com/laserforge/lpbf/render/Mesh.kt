package com.laserforge.lpbf.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * A drawable mesh: interleaved position+normal (6 floats per vertex) plus a short index buffer.
 * Material is per-mesh; world transform is via [transform] (column-major 4x4).
 */
class Mesh(
    val vertexData: FloatArray,
    val indexData: ShortArray,
    var material: Material,
    var transform: FloatArray = MatrixUtil.let { FloatArray(16).also { MatrixUtil.identity(it) } }
) {
    val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(vertexData).position(0) }

    val indexBuffer: ShortBuffer = ByteBuffer
        .allocateDirect(indexData.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        .apply { put(indexData).position(0) }

    val indexCount: Int get() = indexData.size

    /** Lazily-built GL buffers; created in [upload]. */
    private var vbo: Int = 0
    private var ibo: Int = 0
    private var uploaded: Boolean = false

    fun upload() {
        if (uploaded) return
        val bufs = IntArray(2)
        GLES20.glGenBuffers(2, bufs, 0)
        vbo = bufs[0]; ibo = bufs[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexData.size * 4, vertexBuffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexData.size * 2, indexBuffer, GLES20.GL_STATIC_DRAW)
        uploaded = true
    }

    fun release() {
        if (uploaded) {
            GLES20.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
            uploaded = false
        }
    }

    fun draw(shader: ShaderProgram, viewProj: FloatArray) {
        if (!uploaded) upload()
        val aPos = shader.attrib("aPos")
        val aNormal = shader.attrib("aNormal")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 6 * 4, 0)
        if (aNormal >= 0) {
            GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 6 * 4, 3 * 4)
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        // Compute MVP
        val mvp = FloatArray(16)
        MatrixUtil.multiply(mvp, viewProj, transform)
        GLES20.glUniformMatrix4fv(shader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uniform("uModel"), 1, false, transform, 0)
        // For uniform scale transforms, the normal matrix is the model matrix itself.
        // We use the model matrix directly (no shearing in this game), but transpose 3x3
        // to be safe for the rare non-uniform scale.
        val nm = FloatArray(16)
        MatrixUtil.transpose(nm, transform)
        GLES20.glUniformMatrix4fv(shader.uniform("uNormalMat"), 1, false, nm, 0)
        // Material
        GLES20.glUniform3f(shader.uniform("uAlbedo"), material.r, material.g, material.b)
        GLES20.glUniform1f(shader.uniform("uAlpha"), material.alpha)
        GLES20.glUniform3f(shader.uniform("uEmissive"),
            material.emissiveR, material.emissiveG, material.emissiveB)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        if (aPos >= 0) GLES20.glDisableVertexAttribArray(aPos)
        if (aNormal >= 0) GLES20.glDisableVertexAttribArray(aNormal)
    }
}

/** Mesh builder helpers. */
object MeshBuilder {

    /**
     * Axis-aligned box centered at origin with dimensions (w, h, d).
     * 24 vertices (4 per face) for flat shading.
     */
    fun box(w: Float, h: Float, d: Float): Mesh {
        val hw = w / 2f; val hh = h / 2f; val hd = d / 2f
        // 6 faces, each: normal + 4 vertices
        val faces = arrayOf(
            // +X
            floatArrayOf(1f, 0f, 0f) to arrayOf(
                floatArrayOf( hw, -hh, -hd), floatArrayOf( hw, -hh,  hd),
                floatArrayOf( hw,  hh,  hd), floatArrayOf( hw,  hh, -hd)),
            // -X
            floatArrayOf(-1f, 0f, 0f) to arrayOf(
                floatArrayOf(-hw, -hh,  hd), floatArrayOf(-hw, -hh, -hd),
                floatArrayOf(-hw,  hh, -hd), floatArrayOf(-hw,  hh,  hd)),
            // +Y
            floatArrayOf(0f, 1f, 0f) to arrayOf(
                floatArrayOf(-hw,  hh,  hd), floatArrayOf( hw,  hh,  hd),
                floatArrayOf( hw,  hh, -hd), floatArrayOf(-hw,  hh, -hd)),
            // -Y
            floatArrayOf(0f, -1f, 0f) to arrayOf(
                floatArrayOf(-hw, -hh, -hd), floatArrayOf( hw, -hh, -hd),
                floatArrayOf( hw, -hh,  hd), floatArrayOf(-hw, -hh,  hd)),
            // +Z
            floatArrayOf(0f, 0f, 1f) to arrayOf(
                floatArrayOf( hw, -hh,  hd), floatArrayOf(-hw, -hh,  hd),
                floatArrayOf(-hw,  hh,  hd), floatArrayOf( hw,  hh,  hd)),
            // -Z
            floatArrayOf(0f, 0f, -1f) to arrayOf(
                floatArrayOf(-hw, -hh, -hd), floatArrayOf( hw, -hh, -hd),
                floatArrayOf( hw,  hh, -hd), floatArrayOf(-hw,  hh, -hd))
        )
        val verts = FloatArray(6 * 4 * 6) // 6 faces × 4 verts × (pos.xyz + normal.xyz)
        val idx = ShortArray(6 * 6)        // 6 faces × 2 tris × 3 verts
        var vi = 0; var ii = 0; var base = 0
        for ((normal, corners) in faces) {
            for (c in corners) {
                verts[vi++] = c[0]; verts[vi++] = c[1]; verts[vi++] = c[2]
                verts[vi++] = normal[0]; verts[vi++] = normal[1]; verts[vi++] = normal[2]
            }
            idx[ii++] = (base + 0).toShort()
            idx[ii++] = (base + 1).toShort()
            idx[ii++] = (base + 2).toShort()
            idx[ii++] = (base + 0).toShort()
            idx[ii++] = (base + 2).toShort()
            idx[ii++] = (base + 3).toShort()
            base += 4
        }
        return Mesh(verts, idx, Material(1f, 1f, 1f))
    }

    /**
     * Plane in XY at z=0 with size (w, h) and optional UVs. Used both for the powder bed
     * and as the basis for text sprites.
     */
    fun plane(w: Float, h: Float): Mesh {
        val hw = w / 2f; val hh = h / 2f
        val n = floatArrayOf(0f, 0f, 1f)
        val verts = floatArrayOf(
            -hw, -hh, 0f, n[0], n[1], n[2],
             hw, -hh, 0f, n[0], n[1], n[2],
             hw,  hh, 0f, n[0], n[1], n[2],
            -hw,  hh, 0f, n[0], n[1], n[2]
        )
        val idx = shortArrayOf(0, 1, 2, 0, 2, 3)
        return Mesh(verts, idx, Material(1f, 1f, 1f))
    }

    /**
     * Cylinder along Y axis with given top/bottom radius and height.
     * Normals point outward from the cylinder axis (used for the recoater).
     */
    fun cylinder(rTop: Float, rBot: Float, height: Float, segs: Int = 16): Mesh {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        val hh = height / 2f
        for (i in 0..segs) {
            val a = (i.toDouble() / segs) * Math.PI * 2.0
            val nx = cos(a).toFloat()
            val nz = sin(a).toFloat()
            val xt = nx * rTop
            val xb = nx * rBot
            val zt = nz * rTop
            val zb = nz * rBot
            // Top vertex
            verts.add(xt); verts.add(hh); verts.add(zt)
            verts.add(nx); verts.add(0f); verts.add(nz)
            // Bottom vertex
            verts.add(xb); verts.add(-hh); verts.add(zb)
            verts.add(nx); verts.add(0f); verts.add(nz)
        }
        for (i in 0 until segs) {
            val a = (i * 2).toShort()
            val b = (i * 2 + 1).toShort()
            val c = ((i + 1) * 2).toShort()
            val d = ((i + 1) * 2 + 1).toShort()
            idx.add(a); idx.add(b); idx.add(d)
            idx.add(a); idx.add(d); idx.add(c)
        }
        return Mesh(verts.toFloatArray(), idx.toShortArray(), Material(1f, 1f, 1f))
    }

    /**
     * Flat ring in the XY plane (used for the preview marker).
     */
    fun ring(innerR: Float, outerR: Float, segs: Int = 32): Mesh {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        for (i in 0..segs) {
            val a = (i.toDouble() / segs) * Math.PI * 2.0
            val nx = cos(a).toFloat()
            val ny = sin(a).toFloat()
            verts.add(nx * innerR); verts.add(ny * innerR); verts.add(0f)
            verts.add(0f); verts.add(0f); verts.add(1f)
            verts.add(nx * outerR); verts.add(ny * outerR); verts.add(0f)
            verts.add(0f); verts.add(0f); verts.add(1f)
        }
        for (i in 0 until segs) {
            val a = (i * 2).toShort()
            val b = (i * 2 + 1).toShort()
            val c = ((i + 1) * 2).toShort()
            val d = ((i + 1) * 2 + 1).toShort()
            idx.add(a); idx.add(b); idx.add(d)
            idx.add(a); idx.add(d); idx.add(c)
        }
        return Mesh(verts.toFloatArray(), idx.toShortArray(), Material(1f, 1f, 1f))
    }

    /**
     * Low-poly sphere centered at origin, radius r. Used for powder particles.
     */
    fun sphere(r: Float, segs: Int = 8, rings: Int = 6): Mesh {
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()
        for (lat in 0..rings) {
            val theta = (lat.toDouble() / rings) * Math.PI
            val sinT = sin(theta).toFloat()
            val cosT = cos(theta).toFloat()
            for (lon in 0..segs) {
                val phi = (lon.toDouble() / segs) * 2 * Math.PI
                val sinP = sin(phi).toFloat()
                val cosP = cos(phi).toFloat()
                val x = r * sinT * cosP
                val y = r * cosT
                val z = r * sinT * sinP
                val nx = sinT * cosP
                val ny = cosT
                val nz = sinT * sinP
                verts.add(x); verts.add(y); verts.add(z)
                verts.add(nx); verts.add(ny); verts.add(nz)
            }
        }
        val cols = segs + 1
        for (lat in 0 until rings) {
            for (lon in 0 until segs) {
                val a = (lat * cols + lon).toShort()
                val b = (lat * cols + lon + 1).toShort()
                val c = ((lat + 1) * cols + lon).toShort()
                val d = ((lat + 1) * cols + lon + 1).toShort()
                idx.add(a); idx.add(c); idx.add(b)
                idx.add(b); idx.add(c); idx.add(d)
            }
        }
        return Mesh(verts.toFloatArray(), idx.toShortArray(), Material(1f, 1f, 1f))
    }

    /**
     * Line-based grid in the XZ plane (mimics Three.js GridHelper).
     * Returns a flat list of line segments; we render them via GL_LINES.
     */
    fun grid(size: Float, divisions: Int): LineMesh {
        val verts = ArrayList<Float>()
        val half = size / 2f
        val step = size / divisions
        val n = floatArrayOf(0f, 1f, 0f)
        for (i in 0..divisions) {
            val p = -half + i * step
            // Line along X
            verts.add(-half); verts.add(0f); verts.add(p); verts.add(n[0]); verts.add(n[1]); verts.add(n[2])
            verts.add( half); verts.add(0f); verts.add(p); verts.add(n[0]); verts.add(n[1]); verts.add(n[2])
            // Line along Z
            verts.add(p); verts.add(0f); verts.add(-half); verts.add(n[0]); verts.add(n[1]); verts.add(n[2])
            verts.add(p); verts.add(0f); verts.add( half); verts.add(n[0]); verts.add(n[1]); verts.add(n[2])
        }
        return LineMesh(verts.toFloatArray(), Material(0.4f, 0.4f, 0.4f))
    }
}

/** A line-segment mesh, rendered with GL_LINES. */
class LineMesh(val vertexData: FloatArray, var material: Material) {
    val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(vertexData).position(0) }

    val vertexCount: Int get() = vertexData.size / 6
    private var vbo: Int = 0
    private var uploaded: Boolean = false

    fun upload() {
        if (uploaded) return
        val bufs = IntArray(1)
        GLES20.glGenBuffers(1, bufs, 0)
        vbo = bufs[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexData.size * 4, vertexBuffer, GLES20.GL_STATIC_DRAW)
        uploaded = true
    }

    fun release() {
        if (uploaded) {
            GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
            uploaded = false
        }
    }

    fun draw(shader: ShaderProgram, viewProj: FloatArray, modelMatrix: FloatArray) {
        if (!uploaded) upload()
        val aPos = shader.attrib("aPos")
        val aNormal = shader.attrib("aNormal")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 6 * 4, 0)
        if (aNormal >= 0) {
            GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 6 * 4, 3 * 4)
        }
        val mvp = FloatArray(16)
        MatrixUtil.multiply(mvp, viewProj, modelMatrix)
        GLES20.glUniformMatrix4fv(shader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uniform("uModel"), 1, false, modelMatrix, 0)
        val nm = FloatArray(16)
        MatrixUtil.transpose(nm, modelMatrix)
        GLES20.glUniformMatrix4fv(shader.uniform("uNormalMat"), 1, false, nm, 0)
        GLES20.glUniform3f(shader.uniform("uAlbedo"), material.r, material.g, material.b)
        GLES20.glUniform1f(shader.uniform("uAlpha"), material.alpha)
        GLES20.glUniform3f(shader.uniform("uEmissive"),
            material.emissiveR, material.emissiveG, material.emissiveB)
        GLES20.glLineWidth(1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        if (aPos >= 0) GLES20.glDisableVertexAttribArray(aPos)
        if (aNormal >= 0) GLES20.glDisableVertexAttribArray(aNormal)
    }
}

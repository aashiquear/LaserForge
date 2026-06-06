package com.laserforge.lpbf.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Minimal 4x4 column-major matrix utilities (mirrors the GLSL / OpenGL ES 2.0 convention).
 * All matrices are stored as FloatArray(16) in column-major order.
 */
object MatrixUtil {

    fun identity(out: FloatArray) {
        for (i in 0 until 16) out[i] = 0f
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
    }

    fun copy(src: FloatArray, dst: FloatArray) {
        System.arraycopy(src, 0, dst, 0, 16)
    }

    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        val tmp = FloatArray(16)
        for (i in 0..3) {
            for (j in 0..3) {
                tmp[j * 4 + i] =
                    a[0 * 4 + i] * b[j * 4 + 0] +
                    a[1 * 4 + i] * b[j * 4 + 1] +
                    a[2 * 4 + i] * b[j * 4 + 2] +
                    a[3 * 4 + i] * b[j * 4 + 3]
            }
        }
        System.arraycopy(tmp, 0, out, 0, 16)
    }

    fun translate(out: FloatArray, x: Float, y: Float, z: Float) {
        identity(out)
        out[12] = x; out[13] = y; out[14] = z
    }

    fun scale(out: FloatArray, sx: Float, sy: Float, sz: Float) {
        for (i in 0 until 16) out[i] = 0f
        out[0] = sx; out[5] = sy; out[10] = sz; out[15] = 1f
    }

    fun rotateX(out: FloatArray, rad: Float) {
        val c = cos(rad.toDouble()).toFloat()
        val s = sin(rad.toDouble()).toFloat()
        identity(out)
        out[5] = c; out[6] = s; out[9] = -s; out[10] = c
    }

    fun rotateY(out: FloatArray, rad: Float) {
        val c = cos(rad.toDouble()).toFloat()
        val s = sin(rad.toDouble()).toFloat()
        identity(out)
        out[0] = c; out[2] = -s; out[8] = s; out[10] = c
    }

    fun rotateZ(out: FloatArray, rad: Float) {
        val c = cos(rad.toDouble()).toFloat()
        val s = sin(rad.toDouble()).toFloat()
        identity(out)
        out[0] = c; out[1] = s; out[4] = -s; out[5] = c
    }

    /**
     * Right-handed perspective projection.
     * fovYDeg: vertical FOV in degrees.
     * aspect: width/height.
     */
    fun perspective(out: FloatArray, fovYDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan((fovYDeg * Math.PI / 180.0 / 2.0).toFloat().toDouble()).toFloat()
        for (i in 0 until 16) out[i] = 0f
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = (2f * far * near) / (near - far)
    }

    /**
     * Right-handed lookAt matrix.
     * eye: camera position, center: target, up: world up (typically (0,1,0)).
     */
    fun lookAt(out: FloatArray, eye: FloatArray, center: FloatArray, up: FloatArray) {
        val fx = center[0] - eye[0]
        val fy = center[1] - eye[1]
        val fz = center[2] - eye[2]
        val rlf = 1f / kotlin.math.sqrt((fx * fx + fy * fy + fz * fz).toDouble()).toFloat()
        val fX = fx * rlf; val fY = fy * rlf; val fZ = fz * rlf

        // s = f x up (Right vector)
        var sX = fY * up[2] - fZ * up[1]
        var sY = fZ * up[0] - fX * up[2]
        var sZ = fX * up[1] - fY * up[0]
        val rls = 1f / kotlin.math.sqrt((sX * sX + sY * sY + sZ * sZ).toDouble()).toFloat()
        sX *= rls; sY *= rls; sZ *= rls

        // u = s x f (Up vector)
        val uX = sY * fZ - sZ * fY
        val uY = sZ * fX - sX * fZ
        val uZ = sX * fY - sY * fX

        identity(out)
        out[0] = sX; out[4] = sY; out[8] = sZ
        out[1] = uX; out[5] = uY; out[9] = uZ
        out[2] = -fX; out[6] = -fY; out[10] = -fZ
        out[12] = -(sX * eye[0] + sY * eye[1] + sZ * eye[2])
        out[13] = -(uX * eye[0] + uY * eye[1] + uZ * eye[2])
        out[14] = fX * eye[0] + fY * eye[1] + fZ * eye[2]
    }

    fun transpose(out: FloatArray, m: FloatArray) {
        for (i in 0..3) for (j in 0..3) out[i * 4 + j] = m[j * 4 + i]
    }

    fun invert(out: FloatArray, m: FloatArray): Boolean {
        // Operate on 4 column vectors of length 4 in a 2D FloatArray.
        val m00 = m[0];  val m01 = m[4];  val m02 = m[8];  val m03 = m[12]
        val m10 = m[1];  val m11 = m[5];  val m12 = m[9];  val m13 = m[13]
        val m20 = m[2];  val m21 = m[6];  val m22 = m[10]; val m23 = m[14]
        val m30 = m[3];  val m31 = m[7];  val m32 = m[11]; val m33 = m[15]

        val a = FloatArray(16) { i -> floatArrayOf(m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23, m30, m31, m32, m33)[i] }
        val inv = FloatArray(16).also { identity(it) }
        // Gaussian elimination on a 4x4 with FloatArray-as-matrix.
        val M = Array(4) { FloatArray(4) }
        for (i in 0..3) for (j in 0..3) M[i][j] = a[j * 4 + i]
        val I = Array(4) { FloatArray(4) }
        for (i in 0..3) I[i][i] = 1f
        for (i in 0..3) {
            var pivot = M[i][i]
            if (kotlin.math.abs(pivot) < 1e-6f) {
                var swap = -1
                for (r in i + 1..3) if (kotlin.math.abs(M[r][i]) > 1e-6f) { swap = r; break }
                if (swap < 0) return false
                val tmprow = M[i]; M[i] = M[swap]; M[swap] = tmprow
                val tirow = I[i]; I[i] = I[swap]; I[swap] = tirow
                pivot = M[i][i]
            }
            for (c in 0..3) { M[i][c] /= pivot; I[i][c] /= pivot }
            for (r in 0..3) if (r != i) {
                val factor = M[r][i]
                for (c in 0..3) {
                    M[r][c] -= factor * M[i][c]
                    I[r][c] -= factor * I[i][c]
                }
            }
        }
        for (i in 0..3) for (j in 0..3) out[i * 4 + j] = I[i][j]
        return true
    }
}

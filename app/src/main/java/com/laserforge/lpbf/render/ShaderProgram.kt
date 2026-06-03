package com.laserforge.lpbf.render

import android.opengl.GLES20
import android.util.Log

/**
 * Compiles a vertex/fragment shader pair, links them, and exposes attribute/uniform locations.
 */
class ShaderProgram(vertexSrc: String, fragmentSrc: String) {

    val program: Int

    private val uniformLocations = HashMap<String, Int>()
    private val attribLocations = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Link failed: $log")
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Shader link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun use() {
        GLES20.glUseProgram(program)
    }

    fun attrib(name: String): Int {
        return attribLocations.getOrPut(name) {
            val loc = GLES20.glGetAttribLocation(program, name)
            if (loc < 0) Log.w(TAG, "Attribute $name not found in shader")
            loc
        }
    }

    fun uniform(name: String): Int {
        return uniformLocations.getOrPut(name) {
            val loc = GLES20.glGetUniformLocation(program, name)
            if (loc < 0) Log.w(TAG, "Uniform $name not found in shader")
            loc
        }
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(sh)
            Log.e(TAG, "Shader compile failed: $log\n$src")
            GLES20.glDeleteShader(sh)
            throw RuntimeException("Shader compile failed: $log")
        }
        return sh
    }

    companion object {
        private const val TAG = "ShaderProgram"

        /** Lit vertex shader: takes world position + normal, passes through to fragment. */
        val LIT_VERT = """
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform mat4 uNormalMat;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            void main() {
                vec4 worldPos = uModel * vec4(aPos, 1.0);
                vWorldPos = worldPos.xyz;
                vNormal = mat3(uNormalMat) * aNormal;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """.trimIndent()

        /** Lit fragment shader: Lambert + ambient + emissive for up to 3 directional + 1 point light. */
        val LIT_FRAG = """
            precision mediump float;
            uniform vec3 uAlbedo;
            uniform float uAlpha;
            uniform vec3 uEmissive;

            uniform vec3 uAmbient;

            uniform vec3 uDirLightPos[3];
            uniform vec3 uDirLightColor[3];
            uniform float uDirLightIntensity[3];
            uniform int uDirLightCount;

            uniform vec3 uPointLightPos;
            uniform vec3 uPointLightColor;
            uniform float uPointLightIntensity;
            uniform float uPointLightRange;
            uniform int uHasPointLight;

            varying vec3 vWorldPos;
            varying vec3 vNormal;

            void main() {
                vec3 N = normalize(vNormal);
                vec3 result = uAmbient * uAlbedo;
                for (int i = 0; i < 3; i++) {
                    if (i >= uDirLightCount) break;
                    vec3 L = normalize(uDirLightPos[i]);
                    float diff = max(dot(N, L), 0.0);
                    result += diff * uDirLightColor[i] * uDirLightIntensity[i] * uAlbedo;
                }
                if (uHasPointLight == 1) {
                    vec3 Lvec = uPointLightPos - vWorldPos;
                    float d = length(Lvec);
                    if (d < uPointLightRange) {
                        vec3 L = Lvec / d;
                        float diff = max(dot(N, L), 0.0);
                        float atten = max(0.0, 1.0 - d / uPointLightRange);
                        result += diff * atten * uPointLightColor * uPointLightIntensity * uAlbedo;
                    }
                }
                result += uEmissive;
                gl_FragColor = vec4(result, uAlpha);
            }
        """.trimIndent()

        /** Unlit vertex shader (textures, sprites). */
        val UNLIT_VERT = """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            attribute vec2 aUV;
            varying vec2 vUV;
            void main() {
                vUV = aUV;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """.trimIndent()

        /** Unlit fragment shader that samples a 2D texture. */
        val UNLIT_TEX_FRAG = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            varying vec2 vUV;
            void main() {
                vec4 c = texture2D(uTex, vUV);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """.trimIndent()

        /** Unlit fragment shader with a flat color (sprites that aren't textured). */
        val UNLIT_COLOR_FRAG = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            void main() {
                gl_FragColor = vec4(uColor, uAlpha);
            }
        """.trimIndent()
    }
}

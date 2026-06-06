package com.laserforge.lpbf.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Shared rendering surface that owns shader programs, the camera, the scene lights,
 * and a list of opaque objects to draw. Multiple GLSurfaceViews (the main game view
 * and the small target preview) can each have their own renderer instance.
 */
class GameRenderer(
    private val isTargetPreview: Boolean = false
) : GLSurfaceView.Renderer {

    val camera: Camera = Camera()
    val sceneLights: SceneLights = SceneLights()
    private val objects: MutableList<RenderObject.MeshObject> = mutableListOf()
    private val lines: MutableList<RenderLine> = mutableListOf()

    var aspect: Float = 1f
    private var width: Int = 1
    private var height: Int = 1

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    private var litShader: ShaderProgram? = null
    var onSurfaceCreated: (() -> Unit)? = null
    
    private val labelCache = HashMap<String, Int>()

    /** Background color in clear, set by GameEngine depending on state. */
    @Volatile var clearColor: FloatArray = floatArrayOf(0.102f, 0.102f, 0.180f, 1f)
    
    val isInitialized: Boolean get() = litShader != null

    fun addMesh(mesh: Mesh, model: FloatArray = mesh.transform) {
        mesh.labelText?.let { text ->
            mesh.textureId = labelCache.getOrPut(text) {
                TextureUtil.createTextTexture(text)
            }
        }
        objects.add(RenderObject.MeshObject(mesh, model))
    }

    fun addLine(line: LineMesh, model: FloatArray) {
        lines.add(RenderLine(line, model))
    }

    fun clearObjects() {
        for (o in objects) {
            o.mesh.release()
        }
        for (l in lines) l.line.release()
        objects.clear()
        lines.clear()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        litShader = ShaderProgram(ShaderProgram.LIT_VERT, ShaderProgram.LIT_FRAG)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glFrontFace(GLES20.GL_CCW)
        labelCache.clear()
        onSurfaceCreated?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
        aspect = w.toFloat() / h.coerceAtLeast(1).toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        camera.viewMatrix(viewMatrix)
        MatrixUtil.perspective(projMatrix, 50f, aspect, 0.1f, 1000f)

        // Compose view-projection.
        val vp = FloatArray(16)
        MatrixUtil.multiply(vp, projMatrix, viewMatrix)

        val shader = litShader ?: return
        shader.use()
        applyLights(shader)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Split into opaque and transparent for basic sorting
        val opaque = objects.filter { it.mesh.material.alpha >= 1f }
        val transparent = objects.filter { it.mesh.material.alpha < 1f }

        // Render opaque first
        GLES20.glDepthMask(true)
        for (ro in opaque) {
            ro.mesh.draw(shader, vp)
        }
        
        // Render lines
        for (rl in lines) {
            rl.line.draw(shader, vp, rl.model)
        }

        // Render transparent second
        // For best results we'd sort these back-to-front, but for this scene
        // let's at least disable depth writing to avoid artifacts.
        GLES20.glDepthMask(false)
        for (ro in transparent) {
            ro.mesh.draw(shader, vp)
        }
        GLES20.glDepthMask(true)
    }

    private fun applyLights(shader: ShaderProgram) {
        GLES20.glUniform3fv(shader.uniform("uAmbient"), 1,
            floatArrayOf(
                sceneLights.ambient[0],
                sceneLights.ambient[1],
                sceneLights.ambient[2]
            ), 0)
        val n = sceneLights.directional.size.coerceAtMost(3)
        val pos = FloatArray(3 * 3)
        val col = FloatArray(3 * 3)
        val ints = FloatArray(3)
        for (i in 0 until n) {
            val d = sceneLights.directional[i]
            pos[i * 3] = d.x; pos[i * 3 + 1] = d.y; pos[i * 3 + 2] = d.z
            col[i * 3] = d.r; col[i * 3 + 1] = d.g; col[i * 3 + 2] = d.b
            ints[i] = d.intensity
        }
        GLES20.glUniform3fv(shader.uniform("uDirLightPos"), 3, pos, 0)
        GLES20.glUniform3fv(shader.uniform("uDirLightColor"), 3, col, 0)
        GLES20.glUniform1fv(shader.uniform("uDirLightIntensity"), 3, ints, 0)
        GLES20.glUniform1i(shader.uniform("uDirLightCount"), n)
        val p = sceneLights.point
        if (p != null) {
            GLES20.glUniform3f(shader.uniform("uPointLightPos"), p.x, p.y, p.z)
            GLES20.glUniform3f(shader.uniform("uPointLightColor"), p.r, p.g, p.b)
            GLES20.glUniform1f(shader.uniform("uPointLightIntensity"), p.intensity)
            GLES20.glUniform1f(shader.uniform("uPointLightRange"), p.range)
            GLES20.glUniform1i(shader.uniform("uHasPointLight"), 1)
        } else {
            GLES20.glUniform1i(shader.uniform("uHasPointLight"), 0)
        }
    }

    private sealed class RenderObject {
        data class MeshObject(val mesh: Mesh, val model: FloatArray) : RenderObject()
    }

    private data class RenderLine(val line: LineMesh, val model: FloatArray)
}

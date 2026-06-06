package com.laserforge.lpbf.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.laserforge.lpbf.game.GameEngine
import com.laserforge.lpbf.render.GameRenderer
import com.laserforge.lpbf.render.Light
import com.laserforge.lpbf.render.MatrixUtil
import com.laserforge.lpbf.render.Mesh

/**
 * Smaller GLSurfaceView used to preview a 3D target shape. Renders the target voxel
 * cloud with a slight rotation, exactly like the HTML's `#target3DCanvas`.
 */
class TargetPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val previewEngine: GameEngine = GameEngine()
    val renderer: GameRenderer
    private var angle: Float = 0f
    private var currentTargetMeshes: List<Mesh> = emptyList()
    private var lastTargetKey: String? = null

    init {
        setEGLContextClientVersion(2)
        renderer = GameRenderer(isTargetPreview = true)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        renderer.camera.distance = 7f
        renderer.camera.theta = (-3 * Math.PI / 4).toFloat()
        renderer.camera.phi = (Math.PI / 3).toFloat()
        renderer.clearColor = floatArrayOf(0.102f, 0.102f, 0.102f, 1f)
        // Configure lights for the preview.
        renderer.onSurfaceCreated = {
            val l = renderer.sceneLights
            l.ambient[0] = 0.376f; l.ambient[1] = 0.376f; l.ambient[2] = 0.376f
            l.directional.clear()
            l.directional.add(Light.Directional(5f, 10f, 5f, 1f, 1f, 1f, 0.8f))
            l.directional.add(Light.Directional(-5f, 10f, -5f, 0.5f, 0.5f, 0.7f, 0.3f))
        }
    }

    /** Update the shape to preview. */
    fun setTarget(targetMeshes: List<Mesh>, key: String) {
        if (key == lastTargetKey) return
        lastTargetKey = key
        
        // Center meshes
        if (targetMeshes.isNotEmpty()) {
            var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
            var minZ = Float.MAX_VALUE; var maxZ = Float.MIN_VALUE
            
            for (m in targetMeshes) {
                val tx = m.transform[12]; val ty = m.transform[13]; val tz = m.transform[14]
                minX = minOf(minX, tx); maxX = maxOf(maxX, tx)
                minY = minOf(minY, ty); maxY = maxOf(maxY, ty)
                minZ = minOf(minZ, tz); maxZ = maxOf(maxZ, tz)
            }
            
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f
            val cz = (minZ + maxZ) / 2f
            
            for (m in targetMeshes) {
                m.transform[12] -= cx
                m.transform[13] -= cy
                m.transform[14] -= cz
            }
        }

        currentTargetMeshes = targetMeshes
        // Tell renderer to repopulate.
        queueEvent {
            renderer.clearObjects()
            for (m in currentTargetMeshes) {
                renderer.addMesh(m)
            }
        }
    }

    /** Called once per frame from a Choreographer callback. */
    fun step() {
        angle += 0.01f
        renderer.camera.theta = angle
        requestRender()
    }
}

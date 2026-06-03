package com.laserforge.lpbf.game

import com.laserforge.lpbf.render.Light
import com.laserforge.lpbf.render.Material
import com.laserforge.lpbf.render.MatrixUtil
import com.laserforge.lpbf.render.Mesh
import com.laserforge.lpbf.render.MeshBuilder
import com.laserforge.lpbf.render.LineMesh
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The full port of the HTML's game state machine + scene graph.
 *
 * Holds the [GameRenderer]-agnostic model (voxel lists, particle positions, transforms).
 * The [GameView] calls [tick] once per GL frame to advance the simulation, then
 * [rebuildRenderObjects] is called to push the current state into a renderer.
 *
 * This separation lets the same engine drive either the main game view or a
 * target preview view.
 */
class GameEngine {

    // ----- Public state mirrors the HTML globals -----
    var gameMode: String = "free"          // "free" | "2d" | "3d"
    var laserShape: String = "gaussian"    // "gaussian" | "tophat" | "doughnut" | "elliptical"
    var targetShape: String? = null        // name of selected shape, e.g. "A", "Pyramid"
    var shapeType: String? = null          // "alphanumeric" | "emoji" | "solid" | "frame"
    val targetVoxels: MutableList<MatchCalculator.Voxel> = mutableListOf()

    // User-drawn layers, indexed by layer number, each a list of (x, z) world coords.
    val layers: MutableList<MutableList<Pair<Float, Float>>> = mutableListOf(mutableListOf())
    var currentLayer: Int = 0

    @Volatile var gameState: GameState = GameState.SPREADING
    @Volatile var animationProgress: Float = 0f

    var layerThickness: Float = 0.12f
    var laserWidth: Float = 2.0f
    @Volatile var laserPower: Int = 100

    @Volatile var laserFiring: Boolean = false
    @Volatile var laserPosition: Pair<Float, Float> = 0f to 0f
    @Volatile var joystickActive: Boolean = false
    @Volatile var joystickDelta: Pair<Float, Float> = 0f to 0f

    // ----- Constants from the HTML -----
    val chamberSize: Float = 10f
    val buildSurfaceY: Float = 0.5f
    val voxelSize: Float = 0.15f

    // Dynamic objects owned by the engine.
    private val particles: MutableList<Particle> = mutableListOf()
    private val voxels: MutableList<Mesh> = mutableListOf()
    private val ghostVoxels: MutableList<Mesh> = mutableListOf()
    private val metalMaterial = Material(0.752f, 0.752f, 0.752f, 1f).apply { /* silver */ }
    private val ghostMaterial = Material(0f, 1f, 0f, 0.4f)
    private val powderMaterial = Material(0xa8 / 255f, 0x90 / 255f, 0x70 / 255f, 0.85f)
    private val powderParticleMaterial = Material(0xa8 / 255f, 0x90 / 255f, 0x70 / 255f, 1f)
    private val chamberMaterial = Material(0.1f, 0.1f, 0.1f, 1f)
    private val wallMaterial = Material(0.165f, 0.165f, 0.165f, 0.3f)
    private val platformMaterial = Material(0.2f, 0.2f, 0.2f, 1f)
    private val recoaterBodyMaterial = Material(0.4f, 0.4f, 0.4f, 1f)
    private val recoaterBladeMaterial = Material(0.533f, 0.533f, 0.533f, 1f)
    private val powderStockMaterial = Material(0x8b / 255f, 0x73 / 255f, 0x55 / 255f, 1f)
    private val previewMaterial = Material(0f, 0.831f, 1f, 0.9f)
    private val laserBeamMaterial = Material(1f, 0f, 0f, 0.6f)
    private val previewMarkerMaterial = Material(1f, 1f, 0f, 0.8f)

    // Platform group transform (the build platform moves down as layers are added).
    var platformY: Float = buildSurfaceY
        private set

    // Recoater transform (only visible during spreading).
    var recoaterX: Float = -(chamberSize / 2) - 1f
        private set
    val recoaterY: Float get() = buildSurfaceY
    private val recoaterStartX = -(chamberSize / 2) - 1f
    private val recoaterEndX = (chamberSize / 2) + 1f

    // Whether the static machine components (chamber, walls, recoater, stock) are visible.
    var machineComponentsVisible: Boolean = true
        private set

    // Showcase mode: rotating camera + brighter lights.
    var showcaseActive: Boolean = false
    var showcaseAngle: Float = 0f

    // Laser beam visualization. We rebuild the laser beam mesh every time the position changes.
    private var laserBeamMesh: Mesh? = null
    var laserBeamActive: Boolean = false

    // Powder layer (a flat plane at the build surface during DRAWING).
    private var powderLayerMesh: Mesh? = null

    // Preview marker (yellow ring) — visible when in DRAWING and not firing.
    var previewMarkerVisible: Boolean = false

    // Camera reset
    var cameraTheta: Float = (Math.PI / 4).toFloat()
    var cameraPhi: Float = (Math.PI / 4).toFloat()
    var cameraDistance: Float = 22f

    init {
        // Start with one empty layer.
        layers.clear()
        layers.add(mutableListOf())
    }

    // -----------------------------------------------------------------------
    // Public actions — wired to buttons / joystick / sliders
    // -----------------------------------------------------------------------

    fun nextLayer() {
        synchronized(this) {
            if (gameState != GameState.DRAWING) return
            currentLayer++
            layers.add(mutableListOf())
            platformY -= layerThickness
            gameState = GameState.SPREADING
            animationProgress = 0f
            spawnPowderParticles()
            // Powder layer is removed (we'll recreate it when spreading finishes).
            powderLayerMesh = null
            ghostVoxels.clear()
            laserBeamMesh = null
            laserBeamActive = false
        }
    }

    fun finish() {
        synchronized(this) {
            if (gameState == GameState.FINISHED) return
            gameState = GameState.FINISHED
            machineComponentsVisible = false
            powderLayerMesh = null
            ghostVoxels.clear()
            laserBeamMesh = null
            laserBeamActive = false
            previewMarkerVisible = false
            showcaseActive = true
            showcaseAngle = 0f
        }
    }

    fun reset() {
        synchronized(this) {
            // Clear voxels.
            for (v in voxels) v.release()
            voxels.clear()
            for (g in ghostVoxels) g.release()
            ghostVoxels.clear()
            laserBeamMesh = null
            laserBeamActive = false
            powderLayerMesh = null
            previewMarkerVisible = false
            machineComponentsVisible = true
            showcaseActive = false
            layers.clear()
            layers.add(mutableListOf())
            currentLayer = 0
            platformY = buildSurfaceY
            gameState = GameState.SPREADING
            animationProgress = 0f
            laserFiring = false
            laserPosition = 0f to 0f
            joystickDelta = 0f to 0f
            spawnPowderParticles()
            cameraTheta = (Math.PI / 4).toFloat()
            cameraPhi = (Math.PI / 4).toFloat()
            cameraDistance = 22f
        }
    }

    fun onDrawing(x: Float, z: Float) {
        synchronized(this) {
            if (gameState != GameState.DRAWING) return
            addVoxel(x, z)
            createLaserBeam(x, z)
        }
    }

    // -----------------------------------------------------------------------
    // Per-frame update (called once per render frame)
    // -----------------------------------------------------------------------

    fun tick() {
        synchronized(this) {
            when (gameState) {
                GameState.SPREADING -> {
                    animationProgress += 0.015f
                    recoaterX = recoaterStartX + (recoaterEndX - recoaterStartX) * animationProgress
                    updatePowderParticles()
                    if (animationProgress >= 1f) {
                        animationProgress = 0f
                        gameState = GameState.DRAWING
                        powderLayerMesh = createPowderPlane()
                        if (currentLayer > 0) rebuildGhostLayer()
                        recoaterX = recoaterStartX
                        particles.clear()
                    }
                }
                GameState.DRAWING -> {
                    updateJoystickInternal()
                }
                GameState.FINISHED -> {
                    if (showcaseActive) {
                        showcaseAngle += 0.015f
                        cameraTheta = showcaseAngle
                        cameraPhi = (Math.PI / 3).toFloat()
                    }
                }
            }
        }
    }

    fun updateJoystickInput(dx: Float, dz: Float) {
        joystickDelta = dx to dz
    }

    /** Mirrors the HTML's `updateJoystick()` — moves the laser position and fires voxels. */
    private fun updateJoystickInternal() {
        if (gameState != GameState.DRAWING) return
        val (dx, dz) = joystickDelta
        if (dx == 0f && dz == 0f) return
        val speed = 0.08f
        val maxPos = (chamberSize - 1) / 2
        var nx = laserPosition.first + dx * speed
        var nz = laserPosition.second + dz * speed
        nx = nx.coerceIn(-maxPos, maxPos)
        nz = nz.coerceIn(-maxPos, maxPos)
        laserPosition = nx to nz
        if (laserFiring) {
            createLaserBeam(nx, nz)
            addVoxel(nx, nz)
        }
    }

    // -----------------------------------------------------------------------
    // Voxel & laser mechanics — ported line-for-line from the HTML
    // -----------------------------------------------------------------------

    private fun addVoxel(x: Float, z: Float) {
        val baseRadius = (laserWidth / 10f) * 0.5f
        val powerFactor = laserPower / 100f
        val voxelHeight = voxelSize * (0.8f + powerFactor * 0.4f)
        val numVoxels = kotlin.math.ceil((baseRadius / (voxelSize * 0.7f)).toDouble()).toInt()
        val pattern = ArrayList<Pair<Float, Float>>()
        for (i in -numVoxels..numVoxels) {
            for (j in -numVoxels..numVoxels) {
                val offsetX = i * voxelSize * 0.7f
                val offsetZ = j * voxelSize * 0.7f
                val distance = kotlin.math.sqrt((offsetX * offsetX + offsetZ * offsetZ).toDouble()).toFloat()
                var shouldAdd = false
                when (laserShape) {
                    "gaussian" -> if (distance <= baseRadius) shouldAdd = true
                    "tophat" -> if (distance <= baseRadius * 0.9f) shouldAdd = true
                    "doughnut" -> {
                        val inner = baseRadius * 0.55f
                        val outer = baseRadius * 1.1f
                        if (distance >= inner && distance <= outer) shouldAdd = true
                    }
                    "elliptical" -> {
                        val ex = offsetX / (baseRadius * 1.5f)
                        val ez = offsetZ / (baseRadius * 0.7f)
                        if (ex * ex + ez * ez <= 1f) shouldAdd = true
                    }
                }
                if (shouldAdd) pattern.add(offsetX to offsetZ)
            }
        }
        for ((ox, oz) in pattern) {
            val mesh = MeshBuilder.box(voxelSize, voxelHeight, voxelSize)
            mesh.material = metalMaterial
            val baseY = currentLayer * layerThickness + voxelHeight / 2f
            val penetration = (powerFactor - 1f) * layerThickness * 0.5f
            val localY = baseY + penetration
            // Apply transform: translate (x+ox, localY, z+oz)
            MatrixUtil.translate(mesh.transform, x + ox, localY, z + oz)
            voxels.add(mesh)

            // Track in [layers]
            val px = x + ox
            val pz = z + oz
            val key = String.format("%.2f,%.2f", px, pz)
            if (layers[currentLayer].none { String.format("%.2f,%.2f", it.first, it.second) == key }) {
                layers[currentLayer].add(px to pz)
            }
        }
    }

    private fun createLaserBeam(x: Float, z: Float) {
        val beamRadius = 0.03f + (laserWidth - 1f) * 0.01f
        val beam = MeshBuilder.cylinder(beamRadius, beamRadius, 10f, 8)
        beam.material = laserBeamMaterial
        // Cylinder is along Y in our builder; translate to (x, buildSurfaceY+5, z).
        MatrixUtil.translate(beam.transform, x, buildSurfaceY + 5f, z)
        laserBeamMesh = beam
        laserBeamActive = true
    }

    private fun createPowderPlane(): Mesh {
        val m = MeshBuilder.plane(chamberSize - 1f, chamberSize - 1f)
        m.material = powderMaterial
        // Rotate -90° around X (lay flat on XZ plane).
        val m1 = FloatArray(16); MatrixUtil.rotateX(m1, -(Math.PI / 2).toFloat())
        val m2 = FloatArray(16); MatrixUtil.translate(m2, 0f, buildSurfaceY + 0.01f, 0f)
        val out = FloatArray(16); MatrixUtil.multiply(out, m2, m1)
        m.transform = out
        return m
    }

    private fun spawnPowderParticles() {
        particles.clear()
        for (i in 0 until 50) {
            particles.add(Particle(
                x = recoaterStartX,
                y = buildSurfaceY + Random.nextFloat() * 0.3f,
                z = (Random.nextFloat() - 0.5f) * (chamberSize - 1f),
                vx = 0.05f + Random.nextFloat() * 0.05f,
                vy = -0.01f - Random.nextFloat() * 0.02f,
                vz = (Random.nextFloat() - 0.5f) * 0.02f
            ))
        }
    }

    private fun updatePowderParticles() {
        for (p in particles) {
            p.x += p.vx
            p.y += p.vy
            p.z += p.vz
            p.vy -= 0.001f
            if (p.x > chamberSize / 2 || p.y < buildSurfaceY - 0.5f) {
                p.x = recoaterStartX
                p.y = buildSurfaceY + Random.nextFloat() * 0.3f
                p.z = (Random.nextFloat() - 0.5f) * (chamberSize - 1f)
                p.vx = 0.05f + Random.nextFloat() * 0.05f
                p.vy = -0.01f - Random.nextFloat() * 0.02f
                p.vz = (Random.nextFloat() - 0.5f) * 0.02f
            }
        }
    }

    private fun rebuildGhostLayer() {
        for (g in ghostVoxels) g.release()
        ghostVoxels.clear()
        if (currentLayer <= 0) return
        val prevIndex = currentLayer - 1
        val prevLayer = layers.getOrNull(prevIndex) ?: return
        for ((x, z) in prevLayer) {
            val m = MeshBuilder.box(voxelSize, voxelSize, voxelSize)
            m.material = ghostMaterial
            val localY = prevIndex * layerThickness + 0.08f
            MatrixUtil.translate(m.transform, x, localY, z)
            ghostVoxels.add(m)
        }
    }

    // -----------------------------------------------------------------------
    // Camera + UI callbacks
    // -----------------------------------------------------------------------

    fun camZoomIn() { cameraDistance = (cameraDistance - 2f).coerceAtLeast(12f) }
    fun camZoomOut() { cameraDistance = (cameraDistance + 2f).coerceAtMost(40f) }
    fun camRotateLeft() { cameraTheta += 0.2f }
    fun camRotateRight() { cameraTheta -= 0.2f }
    fun camReset() {
        cameraTheta = (Math.PI / 4).toFloat()
        cameraPhi = (Math.PI / 4).toFloat()
        cameraDistance = 22f
    }

    fun calculateMatch(): Int {
        if (gameMode == "free") return 0
        return MatchCalculator.calculate(layers, targetVoxels, is2D = gameMode == "2d")
    }

    // -----------------------------------------------------------------------
    // Accessors used by GameRenderer
    // -----------------------------------------------------------------------

    /**
     * Snapshot of the engine state used by the GL thread. Returns pre-built scene data
     * plus a camera/light configuration, all under a single lock to avoid races with
     * UI-thread mutations.
     */
    data class Snapshot(
        val machineVisible: Boolean,
        val cameraTheta: Float,
        val cameraPhi: Float,
        val cameraDistance: Float,
        val staticMeshes: List<Mesh>,
        val staticLines: List<Pair<LineMesh, FloatArray>>,
        val coordinateLabels: List<Mesh>,
        val voxels: List<Mesh>,
        val ghostVoxels: List<Mesh>,
        val particles: List<Mesh>,
        val laserBeam: Mesh?,
        val powderLayer: Mesh?,
        val previewMarker: Mesh?,
        val ambient: FloatArray,
        val directionalLights: List<Light.Directional>,
        val pointLight: Light.Point?
    )

    fun takeSnapshot(useShowcaseLighting: Boolean): Snapshot {
        synchronized(this) {
            val ambient = if (useShowcaseLighting)
                floatArrayOf(0.752f, 0.752f, 0.752f)
            else
                floatArrayOf(0.376f, 0.376f, 0.376f)
            val dirs = ArrayList<Light.Directional>()
            if (useShowcaseLighting) {
                dirs.add(Light.Directional(10f, 20f, 10f, 1f, 1f, 1f, 1.5f))
                dirs.add(Light.Directional(-10f, 10f, -10f, 0.7f, 0.7f, 1f, 0.5f))
                dirs.add(Light.Directional(0f, 15f, 0f, 1f, 1f, 1f, 0.5f))
            } else {
                dirs.add(Light.Directional(10f, 20f, 10f, 1f, 1f, 1f, 0.8f))
                dirs.add(Light.Directional(-10f, 10f, -10f, 0.533f, 0.533f, 1f, 0.3f))
            }
            return Snapshot(
                machineVisible = machineComponentsVisible,
                cameraTheta = cameraTheta,
                cameraPhi = cameraPhi,
                cameraDistance = cameraDistance,
                staticMeshes = if (machineComponentsVisible) buildStaticMeshesSnapshot() else emptyList(),
                staticLines = if (machineComponentsVisible) buildStaticLinesSnapshot() else emptyList(),
                coordinateLabels = if (machineComponentsVisible) buildCoordinateLabelsSnapshot() else emptyList(),
                voxels = ArrayList(voxels),
                ghostVoxels = ArrayList(ghostVoxels),
                particles = buildParticleMeshesSnapshot(),
                laserBeam = laserBeamMesh,
                powderLayer = powderLayerMesh,
                previewMarker = buildPreviewMarkerMesh(),
                ambient = ambient,
                directionalLights = dirs,
                pointLight = null
            )
        }
    }

    private fun buildStaticMeshesSnapshot(): List<Mesh> = buildStaticMeshes()
    private fun buildStaticLinesSnapshot(): List<Pair<LineMesh, FloatArray>> = buildStaticLines()
    private fun buildCoordinateLabelsSnapshot(): List<Mesh> = buildCoordinateLabels()
    private fun buildParticleMeshesSnapshot(): List<Mesh> = buildParticleMeshes()

    fun cameraPosition(): FloatArray {
        val ex = cameraDistance * sin(cameraPhi.toDouble()).toFloat() * cos(cameraTheta.toDouble()).toFloat()
        val ey = cameraDistance * cos(cameraPhi.toDouble()).toFloat()
        val ez = cameraDistance * sin(cameraPhi.toDouble()).toFloat() * sin(cameraTheta.toDouble()).toFloat()
        return floatArrayOf(ex, ey, ez)
    }

    fun setTargetShape2D(shape: TargetShape.Shape2D) {
        targetShape = shape.name
        targetVoxels.clear()
        for (v in shape.voxels) {
            targetVoxels.add(MatchCalculator.Voxel(v.first, v.second, 0))
        }
    }

    fun setTargetShape3D(shape: TargetShape.Shape3D) {
        targetShape = shape.name
        targetVoxels.clear()
        targetVoxels.addAll(shape.voxels)
    }

    /** Returns a list of meshes that should be drawn for the *target preview*. */
    fun buildTargetPreviewMeshes(): List<Mesh> {
        if (targetVoxels.isEmpty()) return emptyList()
        val out = ArrayList<Mesh>()
        val voxelSize = 0.15f
        val mat = previewMaterial
        // Group by layer to keep the size down.
        for (v in targetVoxels) {
            val m = MeshBuilder.box(voxelSize, voxelSize, voxelSize)
            m.material = mat
            MatrixUtil.translate(m.transform, v.x, v.layer * voxelSize, v.z)
            out.add(m)
        }
        return out
    }

    /** List of all current voxels (used for the main renderer's scene). */
    fun getVoxels(): List<Mesh> = voxels
    fun getGhostVoxels(): List<Mesh> = ghostVoxels
    fun getLaserBeam(): Mesh? = laserBeamMesh
    fun getPowderLayer(): Mesh? = powderLayerMesh

    /** Preview marker ring on the powder surface. */
    fun buildPreviewMarkerMesh(): Mesh? {
        if (gameState != GameState.DRAWING || laserFiring) return null
        val ring = MeshBuilder.ring(0.15f, 0.2f, 32)
        ring.material = previewMarkerMaterial
        // Lay flat (rotate -90° around X) and place at buildSurfaceY + 0.02.
        val rot = FloatArray(16); MatrixUtil.rotateX(rot, -(Math.PI / 2).toFloat())
        val trans = FloatArray(16); MatrixUtil.translate(trans, laserPosition.first, buildSurfaceY + 0.02f, laserPosition.second)
        val out = FloatArray(16); MatrixUtil.multiply(out, trans, rot)
        ring.transform = out
        return ring
    }

    fun buildStaticMeshes(): List<Mesh> {
        val out = ArrayList<Mesh>()

        // Chamber floor
        val chamber = MeshBuilder.box(chamberSize, 0.5f, chamberSize)
        chamber.material = chamberMaterial
        MatrixUtil.translate(chamber.transform, 0f, -5f, 0f)
        out.add(chamber)

        // 4 walls
        val wallL = MeshBuilder.box(0.2f, 8f, chamberSize)
        wallL.material = wallMaterial
        MatrixUtil.translate(wallL.transform, -chamberSize / 2, 0f, 0f)
        out.add(wallL)

        val wallR = MeshBuilder.box(0.2f, 8f, chamberSize)
        wallR.material = wallMaterial
        MatrixUtil.translate(wallR.transform, chamberSize / 2, 0f, 0f)
        out.add(wallR)

        val wallB = MeshBuilder.box(chamberSize, 8f, 0.2f)
        wallB.material = wallMaterial
        MatrixUtil.translate(wallB.transform, 0f, 0f, -chamberSize / 2)
        out.add(wallB)

        val wallF = MeshBuilder.box(chamberSize, 8f, 0.2f)
        wallF.material = wallMaterial
        MatrixUtil.translate(wallF.transform, 0f, 0f, chamberSize / 2)
        out.add(wallF)

        // Build platform (note: the platform is normally inside the platformGroup that
        // moves down per layer, but we render it at the *current* platformY; the engine
        // exposes the latest voxel positions which already account for the lowered
        // platform, so we just position the platform at platformY).
        val platform = MeshBuilder.box(chamberSize - 1f, 0.3f, chamberSize - 1f)
        platform.material = platformMaterial
        MatrixUtil.translate(platform.transform, 0f, platformY - 0.15f, 0f)
        out.add(platform)

        // Powder stock
        val stock = MeshBuilder.box(2f, 4f, chamberSize - 1f)
        stock.material = powderStockMaterial
        MatrixUtil.translate(stock.transform, -chamberSize / 2 - 1.5f, 2f, 0f)
        out.add(stock)

        // Recoater
        val recoaterBody = MeshBuilder.box(0.3f, 0.5f, chamberSize - 1f)
        recoaterBody.material = recoaterBodyMaterial
        MatrixUtil.translate(recoaterBody.transform, recoaterX, recoaterY, 0f)
        out.add(recoaterBody)

        val recoaterBlade = MeshBuilder.box(0.1f, 0.8f, chamberSize - 1f)
        recoaterBlade.material = recoaterBladeMaterial
        MatrixUtil.translate(recoaterBlade.transform, recoaterX, recoaterY - 0.4f, 0f)
        out.add(recoaterBlade)

        return out
    }

    fun buildStaticLines(): List<Pair<LineMesh, FloatArray>> {
        val out = ArrayList<Pair<LineMesh, FloatArray>>()
        val grid = MeshBuilder.grid(chamberSize - 1f, 10)
        grid.material = Material(0.4f, 0.4f, 0.4f, 1f)
        val model = FloatArray(16); MatrixUtil.translate(model, 0f, buildSurfaceY, 0f)
        out.add(grid to model)
        return out
    }

    fun buildParticleMeshes(): List<Mesh> {
        val out = ArrayList<Mesh>()
        for (p in particles) {
            val m = MeshBuilder.sphere(0.05f, 4, 4)
            m.material = powderParticleMaterial
            MatrixUtil.translate(m.transform, p.x, p.y, p.z)
            out.add(m)
        }
        return out
    }

    fun buildCoordinateLabels(): List<Mesh> {
        // Coordinate labels are X/Y numeric markers painted on small planes. We omit the
        // text rendering and instead draw a few short tick lines as a stand-in.
        val out = ArrayList<Mesh>()
        for (i in -4..4) {
            val x = i * ((chamberSize - 1) / 10)
            val tick = MeshBuilder.box(0.05f, 0.01f, 0.15f)
            tick.material = Material(0f, 1f, 1f, 1f)
            MatrixUtil.translate(tick.transform, x, buildSurfaceY + 0.005f, chamberSize / 2 + 0.5f)
            out.add(tick)
        }
        for (i in -4..4) {
            val z = i * ((chamberSize - 1) / 10)
            val tick = MeshBuilder.box(0.15f, 0.01f, 0.05f)
            tick.material = Material(1f, 1f, 0f, 1f)
            MatrixUtil.translate(tick.transform, chamberSize / 2 + 0.5f, buildSurfaceY + 0.005f, z)
            out.add(tick)
        }
        return out
    }

    /** Clears all runtime state used by [buildTargetPreviewMeshes]. */
    fun clearTargetPreview() {
        targetShape = null
        targetVoxels.clear()
    }
}

private data class Particle(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float
)

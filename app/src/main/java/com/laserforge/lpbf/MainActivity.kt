package com.laserforge.lpbf

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.slider.Slider
import com.laserforge.lpbf.game.GameEngine
import com.laserforge.lpbf.game.GameState
import com.laserforge.lpbf.game.MatchCalculator
import com.laserforge.lpbf.game.TargetShape
import com.laserforge.lpbf.render.MatrixUtil
import com.laserforge.lpbf.render.Mesh
import com.laserforge.lpbf.render.MeshBuilder
import com.laserforge.lpbf.ui.CoordinateBarView
import com.laserforge.lpbf.ui.GameView
import com.laserforge.lpbf.ui.JoystickView
import com.laserforge.lpbf.ui.TargetPreviewView

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var topBar: CoordinateBarView
    private lateinit var bottomBar: CoordinateBarView
    private lateinit var joystick: JoystickView
    private lateinit var fireBtn: Button
    private lateinit var nextLayerBtn: Button
    private lateinit var finishBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var laserWidthSlider: Slider
    private lateinit var laserPowerSlider: Slider
    private lateinit var layerThicknessSlider: Slider
    private lateinit var laserWidthValue: TextView
    private lateinit var laserPowerValue: TextView
    private lateinit var layerThicknessValue: TextView
    private lateinit var statusMessage: TextView
    private lateinit var matchResultContainer: FrameLayout
    private lateinit var instructionsContent: View
    private lateinit var toggleArrow: TextView
    private lateinit var targetDisplay: View
    private lateinit var targetLabel: TextView
    private lateinit var target2dImage: ImageView
    private lateinit var target3dView: TargetPreviewView
    private lateinit var shapeChoice2d: View
    private lateinit var shapeChoice3d: View

    private val engine: GameEngine get() = gameView.engine
    private val choreographer = Choreographer.getInstance()

    private var lastStatusMessage: String = ""
    private var showPreview: Boolean = false
    private var choreographerCallback: Choreographer.FrameCallback? = null

    // Last frame's laser position (used to redraw the beam when the joystick moves).
    private var lastLaserX: Float = 0f
    private var lastLaserZ: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen must be installed before super.onCreate() so the AndroidX
        // SplashScreen library (backporting the Android 12+ SplashScreen API) can
        // take over the window background and switch to Theme.LaserForge once the
        // first frame is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find views
        gameView = findViewById(R.id.gameView)
        topBar = findViewById(R.id.topStatusBar)
        bottomBar = findViewById(R.id.bottomStatusBar)
        topBar.configure(CoordinateBarView.Mode.TOP)
        bottomBar.configure(CoordinateBarView.Mode.BOTTOM)

        joystick = findViewById(R.id.joystickView)
        fireBtn = findViewById(R.id.fireBtn)
        nextLayerBtn = findViewById(R.id.nextLayerBtn)
        finishBtn = findViewById(R.id.finishBtn)
        resetBtn = findViewById(R.id.resetBtn)
        laserWidthSlider = findViewById(R.id.laserWidthSlider)
        laserPowerSlider = findViewById(R.id.laserPowerSlider)
        layerThicknessSlider = findViewById(R.id.layerThicknessSlider)
        laserWidthValue = findViewById(R.id.laserWidthValue)
        laserPowerValue = findViewById(R.id.laserPowerValue)
        layerThicknessValue = findViewById(R.id.layerThicknessValue)
        statusMessage = findViewById(R.id.statusMessage)
        matchResultContainer = findViewById(R.id.matchResultContainer)
        instructionsContent = findViewById(R.id.instructionsContent)
        toggleArrow = findViewById(R.id.toggleArrow)
        targetDisplay = findViewById(R.id.targetDisplay)
        targetLabel = findViewById(R.id.targetLabel)
        target2dImage = findViewById(R.id.target2dImage)
        target3dView = findViewById(R.id.target3dView)
        shapeChoice2d = findViewById(R.id.shapeChoice2d)
        shapeChoice3d = findViewById(R.id.shapeChoice3d)

        // Title with gradient
        val title = findViewById<TextView>(R.id.titleText)
        title.paint.shader = android.graphics.LinearGradient(
            0f, 0f, title.paint.measureText(title.text.toString()), 0f,
            Color.parseColor("#00d4ff"), Color.parseColor("#7b2cbf"),
            android.graphics.Shader.TileMode.CLAMP
        )

        setupModeButtons()
        setupShapeChoiceButtons()
        setupLaserShapeButtons()
        setupSliders()
        setupActionButtons()
        setupCameraButtons()
        setupJoystickAndFire()
        setupInstructionsToggle()

        // Initialize scene lights on the main renderer's GL thread.
        gameView.queueEvent { initSceneLights() }

        // Initial state mirrors a fresh HTML load.
        engine.reset()
        updateStatusMessage(getString(R.string.status_default))
        bottomBar.setLaserStatus(getString(R.string.laser_ready))
        topBar.setPointerStatus("ready")

        // Per-frame update via Choreographer (drives the main game's GL update + UI).
        choreographerCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                onFrame()
                choreographer.postFrameCallback(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        gameView.onResume()
        choreographerCallback?.let { choreographer.postFrameCallback(it) }
    }

    override fun onPause() {
        super.onPause()
        gameView.onPause()
        choreographerCallback?.let { choreographer.removeFrameCallback(it) }
    }

    // -----------------------------------------------------------------------
    // Per-frame update: advance the engine and rebuild the renderer's objects
    // -----------------------------------------------------------------------
    private fun onFrame() {
        // Advance simulation on the UI thread.
        engine.tick()
        // Step target preview if visible.
        if (target3dView.visibility == View.VISIBLE) target3dView.step()
        // Push the snapshot to the GL thread.
        val snap = engine.takeSnapshot(useShowcaseLighting = engine.gameState == GameState.FINISHED)
        gameView.queueEvent { applySnapshot(snap) }
        // Update UI text.
        updateUi()
    }

    private fun applySnapshot(snap: GameEngine.Snapshot) {
        val r = gameView.renderer
        r.camera.theta = snap.cameraTheta
        r.camera.phi = snap.cameraPhi
        r.camera.distance = snap.cameraDistance
        r.sceneLights.clear()
        r.sceneLights.ambient[0] = snap.ambient[0]
        r.sceneLights.ambient[1] = snap.ambient[1]
        r.sceneLights.ambient[2] = snap.ambient[2]
        for (d in snap.directionalLights) r.sceneLights.directional.add(d)
        r.clearObjects()
        for (m in snap.staticMeshes) r.addMesh(m, m.transform)
        for ((line, model) in snap.staticLines) r.addLine(line, model)
        for (m in snap.coordinateLabels) r.addMesh(m, m.transform)
        for (m in snap.voxels) r.addMesh(m, m.transform)
        for (m in snap.ghostVoxels) r.addMesh(m, m.transform)
        for (m in snap.particles) r.addMesh(m, m.transform)
        snap.laserBeam?.let { r.addMesh(it, it.transform) }
        snap.powderLayer?.let { r.addMesh(it, it.transform) }
        snap.previewMarker?.let { r.addMesh(it, it.transform) }
    }

    private fun initSceneLights() {
        val renderer = gameView.renderer
        renderer.clearColor = floatArrayOf(0.102f, 0.102f, 0.180f, 1f)
    }

    // -----------------------------------------------------------------------
    // UI text updates
    // -----------------------------------------------------------------------
    private fun updateUi() {
        topBar.setCoordX(engine.laserPosition.first)
        topBar.setCoordY(engine.laserPosition.second)
        bottomBar.setLayer(engine.currentLayer + 1)
        bottomBar.setHeightMm((engine.currentLayer + 1) * engine.layerThickness)
        if (engine.gameState == GameState.DRAWING) {
            if (engine.laserFiring) {
                bottomBar.setLaserStatus(getString(R.string.laser_firing))
                topBar.setPointerStatus("firing")
            } else {
                bottomBar.setLaserStatus(getString(R.string.laser_armed))
                topBar.setPointerStatus("active")
            }
        } else if (engine.gameState == GameState.SPREADING) {
            bottomBar.setLaserStatus(getString(R.string.laser_wait))
            topBar.setPointerStatus("ready")
        } else {
            bottomBar.setLaserStatus(getString(R.string.laser_done))
            topBar.setPointerStatus("ready")
        }
        if (engine.laserPosition.first != lastLaserX || engine.laserPosition.second != lastLaserZ) {
            lastLaserX = engine.laserPosition.first
            lastLaserZ = engine.laserPosition.second
        }
    }

    private fun updateStatusMessage(text: String) {
        if (text == lastStatusMessage) return
        lastStatusMessage = text
        statusMessage.text = text
    }

    // -----------------------------------------------------------------------
    // Setup: mode buttons
    // -----------------------------------------------------------------------
    private fun setupModeButtons() {
        val freeBtn = findViewById<Button>(R.id.modeFreeBtn)
        val d2Btn = findViewById<Button>(R.id.mode2dBtn)
        val d3Btn = findViewById<Button>(R.id.mode3dBtn)
        val setActive = { active: Button, inactive: List<Button> ->
            active.setBackgroundResource(R.drawable.btn_mode_active)
            for (b in inactive) b.setBackgroundResource(R.drawable.btn_mode)
        }
        freeBtn.setOnClickListener {
            setActive(freeBtn, listOf(d2Btn, d3Btn))
            engine.gameMode = "free"
            targetDisplay.visibility = View.GONE
            shapeChoice2d.visibility = View.GONE
            shapeChoice3d.visibility = View.GONE
            engine.clearTargetPreview()
            updateStatusMessage(getString(R.string.status_free))
            doReset()
        }
        d2Btn.setOnClickListener {
            setActive(d2Btn, listOf(freeBtn, d3Btn))
            engine.gameMode = "2d"
            targetDisplay.visibility = View.VISIBLE
            shapeChoice2d.visibility = View.VISIBLE
            shapeChoice3d.visibility = View.GONE
            target2dImage.visibility = View.GONE
            target3dView.visibility = View.GONE
            updateStatusMessage(getString(R.string.status_2d_pick))
        }
        d3Btn.setOnClickListener {
            setActive(d3Btn, listOf(freeBtn, d2Btn))
            engine.gameMode = "3d"
            targetDisplay.visibility = View.VISIBLE
            shapeChoice2d.visibility = View.GONE
            shapeChoice3d.visibility = View.VISIBLE
            target2dImage.visibility = View.GONE
            target3dView.visibility = View.GONE
            updateStatusMessage(getString(R.string.status_3d_pick))
        }
    }

    private fun setupShapeChoiceButtons() {
        val btnAlphanumeric = findViewById<Button>(R.id.btnAlphanumeric)
        val btnEmoji = findViewById<Button>(R.id.btnEmoji)
        val btnSolid = findViewById<Button>(R.id.btnSolid)
        val btnFrame = findViewById<Button>(R.id.btnFrame)

        btnAlphanumeric.setOnClickListener {
            val shape = TargetShape.generate2D("alphanumeric")
            engine.setTargetShape2D(shape)
            show2DTarget(shape)
            updateStatusMessage(getString(R.string.status_match_2d))
            doReset()
        }
        btnEmoji.setOnClickListener {
            val shape = TargetShape.generate2D("emoji")
            engine.setTargetShape2D(shape)
            show2DTarget(shape)
            updateStatusMessage(getString(R.string.status_match_2d))
            doReset()
        }
        btnSolid.setOnClickListener {
            val shape = TargetShape.generate3D("solid")
            engine.setTargetShape3D(shape)
            show3DTarget(shape)
            updateStatusMessage(getString(R.string.status_match_3d))
            doReset()
        }
        btnFrame.setOnClickListener {
            val shape = TargetShape.generate3D("frame")
            engine.setTargetShape3D(shape)
            show3DTarget(shape)
            updateStatusMessage(getString(R.string.status_match_3d))
            doReset()
        }
    }

    private fun show2DTarget(shape: TargetShape.Shape2D) {
        targetLabel.text = getString(R.string.target_label_prefix) + shape.name + getString(R.string.target_layers_prefix) + "1-2"
        target2dImage.visibility = View.VISIBLE
        target3dView.visibility = View.GONE
        val bmp = android.graphics.Bitmap.createBitmap(200, 150, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.BLACK)
        val p = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 100f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT
        }
        val ch = shape.symbol
        p.textSize = if (ch.length == 1 && ch[0].isDigit() || (ch.length == 1 && ch[0] in 'A'..'Z')) 120f else 100f
        if (ch.length == 1 && ch[0] in 'A'..'Z') p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText(ch, 100f, 75f + p.textSize / 3f, p)
        target2dImage.setImageBitmap(bmp)
    }

    private fun show3DTarget(shape: TargetShape.Shape3D) {
        targetLabel.text = getString(R.string.target_label_prefix) + shape.name + getString(R.string.target_layers_prefix) + shape.layers
        target2dImage.visibility = View.GONE
        target3dView.visibility = View.VISIBLE
        // Build voxel meshes for the preview.
        val meshes = engine.buildTargetPreviewMeshes()
        // Center the group vertically.
        if (meshes.isNotEmpty()) {
            val voxelSize = 0.15f
            val minY = 0f
            val maxY = (engine.targetVoxels.maxOfOrNull { it.layer } ?: 0) * voxelSize
            val cy = (minY + maxY) / 2f
            for (m in meshes) {
                MatrixUtil.translate(m.transform, m.transform[12], m.transform[13] - cy, m.transform[14])
            }
        }
        target3dView.setTarget(meshes, shape.name)
    }

    // -----------------------------------------------------------------------
    // Setup: laser shape chips
    // -----------------------------------------------------------------------
    private fun setupLaserShapeButtons() {
        val bGauss = findViewById<Button>(R.id.shapeGaussianBtn)
        val bTop = findViewById<Button>(R.id.shapeTophatBtn)
        val bDough = findViewById<Button>(R.id.shapeDoughnutBtn)
        val bEll = findViewById<Button>(R.id.shapeEllipticalBtn)
        val setActive = { active: Button ->
            for (b in listOf(bGauss, bTop, bDough, bEll)) b.setBackgroundResource(R.drawable.btn_shape_option)
            active.setBackgroundResource(R.drawable.btn_shape_option_active)
        }
        bGauss.setOnClickListener { setActive(it as Button); engine.laserShape = "gaussian" }
        bTop.setOnClickListener { setActive(it as Button); engine.laserShape = "tophat" }
        bDough.setOnClickListener { setActive(it as Button); engine.laserShape = "doughnut" }
        bEll.setOnClickListener { setActive(it as Button); engine.laserShape = "elliptical" }
    }

    // -----------------------------------------------------------------------
    // Setup: sliders
    // -----------------------------------------------------------------------
    private fun setupSliders() {
        laserWidthSlider.addOnChangeListener { _, value, _ ->
            engine.laserWidth = value
            laserWidthValue.text = String.format("%.1fmm", value)
        }
        laserPowerSlider.addOnChangeListener { _, value, _ ->
            engine.laserPower = value.toInt()
            laserPowerValue.text = "${value.toInt()}W"
        }
        layerThicknessSlider.addOnChangeListener { _, value, _ ->
            engine.layerThickness = value
            layerThicknessValue.text = String.format("%.2fmm", value)
        }
        // Initial values
        laserWidthValue.text = String.format("%.1fmm", engine.laserWidth)
        laserPowerValue.text = "${engine.laserPower}W"
        layerThicknessValue.text = String.format("%.2fmm", engine.layerThickness)
    }

    // -----------------------------------------------------------------------
    // Setup: action buttons (Next / Finish / Reset)
    // -----------------------------------------------------------------------
    private fun setupActionButtons() {
        nextLayerBtn.setOnClickListener {
            engine.nextLayer()
            updateStatusMessage(getString(R.string.status_spreading))
            bottomBar.setLayer(engine.currentLayer + 1)
            bottomBar.setHeightMm((engine.currentLayer + 1) * engine.layerThickness)
        }
        finishBtn.setOnClickListener {
            engine.finish()
            updateStatusMessage(getString(R.string.status_finished))
            // Show match result if not free mode.
            matchResultContainer.removeAllViews()
            if (engine.gameMode != "free" && engine.targetVoxels.isNotEmpty()) {
                val pct = engine.calculateMatch()
                val (emoji, msg) = when {
                    pct >= 90 -> "🎉" to "Excellent!"
                    pct >= 75 -> "👍" to "Great Job!"
                    pct >= 60 -> "👌" to "Good Effort!"
                    else -> "💪" to "Keep Trying!"
                }
                val tv = TextView(this).apply {
                    text = "$emoji Score: $pct% $emoji"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.match_result_bg)
                    setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                }
                val sub = TextView(this).apply {
                    text = msg
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(tv)
                    addView(sub)
                }
                matchResultContainer.addView(col, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        resetBtn.setOnClickListener { doReset() }
    }

    private fun doReset() {
        engine.reset()
        matchResultContainer.removeAllViews()
        bottomBar.setLayer(1)
        bottomBar.setHeightMm(0f)
        topBar.setCoordX(0f)
        topBar.setCoordY(0f)
        topBar.setPointerStatus("ready")
        bottomBar.setLaserStatus(getString(R.string.laser_ready))
        // Status message based on mode.
        val msg = when {
            engine.gameMode == "free" -> getString(R.string.status_free)
            engine.gameMode == "2d" && engine.targetVoxels.isNotEmpty() -> getString(R.string.status_match_2d)
            engine.gameMode == "3d" && engine.targetVoxels.isNotEmpty() -> getString(R.string.status_match_3d)
            else -> getString(R.string.status_pick_first)
        }
        updateStatusMessage(msg)
    }

    // -----------------------------------------------------------------------
    // Setup: camera buttons
    // -----------------------------------------------------------------------
    private fun setupCameraButtons() {
        findViewById<Button>(R.id.camZoomIn).setOnClickListener { engine.camZoomIn() }
        findViewById<Button>(R.id.camZoomOut).setOnClickListener { engine.camZoomOut() }
        findViewById<Button>(R.id.camRotateLeft).setOnClickListener { engine.camRotateLeft() }
        findViewById<Button>(R.id.camRotateRight).setOnClickListener { engine.camRotateRight() }
        findViewById<Button>(R.id.camReset).setOnClickListener { engine.camReset() }
    }

    // -----------------------------------------------------------------------
    // Setup: joystick + fire
    // -----------------------------------------------------------------------
    private fun setupJoystickAndFire() {
        joystick.onMove = { dx, dz ->
            engine.updateJoystickInput(dx, dz)
        }
        joystick.onEnd = {
            engine.updateJoystickInput(0f, 0f)
            joystick.setActive(false)
        }
        fireBtn.setOnClickListener {
            if (engine.gameState != GameState.DRAWING) return@setOnClickListener
            engine.laserFiring = !engine.laserFiring
            if (engine.laserFiring) {
                fireBtn.setBackgroundResource(R.drawable.btn_laser_fire_active)
                joystick.setActive(true)
                bottomBar.setLaserStatus(getString(R.string.laser_armed))
            } else {
                fireBtn.setBackgroundResource(R.drawable.btn_laser_fire)
                joystick.setActive(false)
                bottomBar.setLaserStatus(getString(R.string.laser_ready))
            }
        }
    }

    // -----------------------------------------------------------------------
    // Setup: instructions toggle
    // -----------------------------------------------------------------------
    private fun setupInstructionsToggle() {
        findViewById<View>(R.id.instructionsToggle).setOnClickListener {
            val visible = instructionsContent.visibility == View.VISIBLE
            instructionsContent.visibility = if (visible) View.GONE else View.VISIBLE
            toggleArrow.text = if (visible) getString(R.string.instructions_arrow_down) else getString(R.string.instructions_arrow_up)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}

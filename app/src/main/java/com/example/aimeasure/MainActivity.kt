package com.example.aimeasure

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var detailText: TextView
    private lateinit var actionBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var unitBtn: Button
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var settingsBtn: Button
    private lateinit var askBtn: Button

    // AR
    private var session: Session? = null
    private var installRequested = false
    private val background = BackgroundRenderer()
    private var textureSet = false
    private var viewW = 0
    private var viewH = 0
    @Volatile private var geometryDirty = false
    private var depthSupported = false
    private var sensorOrientation = 90
    @Volatile private var trackingIssue = false

    // UI state
    private enum class Mode { AI, TAPE }
    @Volatile private var mode = Mode.AI
    @Volatile private var useInches = false
    @Volatile private var lastStatus = ""

    // Tape mode
    private val taps = ConcurrentLinkedQueue<FloatArray>()
    private val anchors = CopyOnWriteArrayList<Anchor>()

    // AI mode pipeline: CAPTURE -> ASKING (Claude) -> SAMPLING (depth) -> COMPUTING -> DONE
    private enum class AiState { IDLE, CAPTURE, ASKING, SAMPLING, COMPUTING, DONE }
    @Volatile private var aiState = AiState.IDLE
    @Volatile private var aiBox: RectF? = null      // camera-image normalized
    @Volatile private var texBox: RectF? = null     // texture normalized (depth image space)
    @Volatile private var aiBoxView: FloatArray? = null
    @Volatile private var measurement: Measurement? = null
    @Volatile private var detection: AiDetection? = null
    private val depthFrames = ArrayList<DepthSnapshot>() // GL thread only

    @Volatile private var lastJpeg: ByteArray? = null

    // Claude connection: the key typed in the app wins; a key baked in at build time is the fallback.
    private val prefs by lazy { getSharedPreferences("claude", MODE_PRIVATE) }
    private fun apiKey(): String =
        prefs.getString("api_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.ANTHROPIC_API_KEY
    private fun model(): String =
        prefs.getString("model", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.CLAUDE_MODEL
    private fun claudeReady() = apiKey().isNotBlank() || !BuildConfig.ANTHROPIC_BASE_URL.contains("api.anthropic.com")
    private fun claude() = ClaudeClient(apiKey(), BuildConfig.ANTHROPIC_BASE_URL, model())

    companion object {
        private const val DEPTH_FRAMES = 12
        private const val CAMERA_REQ = 7
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.surfaceView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status)
        resultText = findViewById(R.id.result)
        detailText = findViewById(R.id.detail)
        actionBtn = findViewById(R.id.actionBtn)
        undoBtn = findViewById(R.id.undoBtn)
        clearBtn = findViewById(R.id.clearBtn)
        unitBtn = findViewById(R.id.unitBtn)
        modeGroup = findViewById(R.id.modeGroup)
        settingsBtn = findViewById(R.id.settingsBtn)
        askBtn = findViewById(R.id.askBtn)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (mode == Mode.TAPE) taps.add(floatArrayOf(e.x, e.y))
                return true
            }
        })
        surfaceView.setOnTouchListener { v, e ->
            detector.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        modeGroup.check(R.id.modeAi)
        modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mode = if (id == R.id.modeAi) Mode.AI else Mode.TAPE
            lastStatus = ""
            refreshUi()
        }
        actionBtn.setOnClickListener { startAiMeasure() }
        undoBtn.setOnClickListener { undo() }
        clearBtn.setOnClickListener { clearAll() }
        unitBtn.setOnClickListener { useInches = !useInches; refreshUi() }
        settingsBtn.setOnClickListener { showClaudeSettings(null) }
        askBtn.setOnClickListener { showAsk() }
        refreshUi()
        if (!claudeReady()) showClaudeSettings("Connect Claude to use AI object measuring. Tape mode works without it.")
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
            return
        }
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) ==
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                val s = Session(this)
                configureSession(s)
                session = s
            } catch (e: UnavailableException) {
                showFatal("ARCore isn’t available on this phone (${e.javaClass.simpleName}).")
                return
            } catch (e: Exception) {
                showFatal("Couldn’t start AR: ${e.message}")
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            showFatal("The camera is being used by another app. Close it and reopen AI Measure.")
            session = null
            return
        }
        surfaceView.onResume()
        geometryDirty = true
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQ &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "AI Measure needs the camera to measure objects.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun configureSession(s: Session) {
        val config = Config(s)
        config.focusMode = Config.FocusMode.AUTO
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        if (depthSupported) config.depthMode = Config.DepthMode.AUTOMATIC
        s.configure(config)

        sensorOrientation = try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.getCameraCharacteristics(s.cameraConfig.cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (e: Exception) { 90 }

        if (!depthSupported) {
            runOnUiThread {
                modeGroup.check(R.id.modeTape)
                Toast.makeText(this, "This phone doesn’t support ARCore Depth, so AI object measuring is off. Tape mode still works.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- GL thread

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        textureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!textureSet) { s.setCameraTextureName(background.textureId); textureSet = true }
        if (geometryDirty && viewW > 0) {
            @Suppress("DEPRECATION")
            s.setDisplayGeometry(windowManager.defaultDisplay.rotation, viewW, viewH)
            geometryDirty = false
            aiBoxView = null
        }

        val frame = try { s.update() } catch (e: Exception) { return }
        background.draw(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            trackingIssue = true
            setStatus(trackingMessage(camera.trackingFailureReason))
            overlay.clearFrame()
            return
        }
        if (trackingIssue) {
            trackingIssue = false
            lastStatus = ""
            runOnUiThread { refreshUi() }
        }

        taps.poll()?.let { handleTap(frame, it) }

        // Map Claude's box into screen pixels (for drawing) and texture space (for depth sampling).
        val box = aiBox
        if (box != null && (aiBoxView == null || texBox == null)) {
            val corners = floatArrayOf(box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom)
            val view = FloatArray(8)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, corners, Coordinates2d.VIEW, view)
            aiBoxView = view
            val tex = FloatArray(8)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, corners, Coordinates2d.TEXTURE_NORMALIZED, tex)
            texBox = RectF(
                minOf(tex[0], tex[2], tex[4], tex[6]), minOf(tex[1], tex[3], tex[5], tex[7]),
                maxOf(tex[0], tex[2], tex[4], tex[6]), maxOf(tex[1], tex[3], tex[5], tex[7])
            )
        }

        when (aiState) {
            AiState.CAPTURE -> captureForAi(frame)
            AiState.SAMPLING -> sampleDepth(frame, s)
            else -> {}
        }

        if (mode == Mode.AI && aiState == AiState.IDLE) {
            setStatus(
                if (hasSurface(s)) "Aim the center dot at an object, then tap Measure object."
                else "Slowly move the phone over the floor or table the object sits on."
            )
        }

        val viewM = FloatArray(16)
        val projM = FloatArray(16)
        camera.getViewMatrix(viewM, 0)
        camera.getProjectionMatrix(projM, 0, 0.05f, 100f)
        val tapePts = if (mode == Mode.TAPE)
            anchors.filter { it.trackingState == TrackingState.TRACKING }
                .map { floatArrayOf(it.pose.tx(), it.pose.ty(), it.pose.tz()) }
        else emptyList()
        val showBox = mode == Mode.AI && aiState != AiState.IDLE && aiState != AiState.DONE
        overlay.setFrame(
            OverlayView.FrameData(
                view = viewM,
                proj = projM,
                tapePoints = tapePts,
                box = if (mode == Mode.AI) measurement else null,
                aiBoxView = if (showBox) aiBoxView else null,
                crosshair = mode == Mode.AI && (aiState == AiState.IDLE || aiState == AiState.DONE),
                inches = useInches
            )
        )
    }

    private fun hasSurface(s: Session) = s.getAllTrackables(Plane::class.java).any {
        it.trackingState == TrackingState.TRACKING &&
            it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.subsumedBy == null
    }

    private fun trackingMessage(r: TrackingFailureReason) = when (r) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on a light."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Move the phone more slowly."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at surfaces with more texture or detail."
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera isn’t available."
        TrackingFailureReason.BAD_STATE -> "AR is restarting…"
        else -> "Move the phone slowly to start tracking."
    }

    // ---------------------------------------------------------------- tape mode

    private fun handleTap(frame: Frame, xy: FloatArray) {
        val hit = frame.hitTest(xy[0], xy[1]).firstOrNull { h ->
            when (val t = h.trackable) {
                is DepthPoint -> true
                is Plane -> t.isPoseInPolygon(h.hitPose)
                is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        }
        if (hit == null) {
            setStatus("No surface found there yet. Move the phone a little, then tap again.")
            return
        }
        anchors.add(hit.createAnchor())
        lastStatus = ""
        runOnUiThread { refreshUi() }
    }

    // ---------------------------------------------------------------- AI mode

    private fun startAiMeasure() {
        if (mode != Mode.AI) return
        if (!depthSupported) {
            showStatusNow("This phone doesn’t support ARCore Depth, which AI measuring needs. Use Tape mode.")
            return
        }
        if (!claudeReady()) {
            showClaudeSettings("Add your Claude API key to measure objects with AI.")
            return
        }
        if (aiState in setOf(AiState.CAPTURE, AiState.ASKING, AiState.SAMPLING, AiState.COMPUTING)) return
        measurement = null
        detection = null
        aiState = AiState.CAPTURE
        refreshUi()
    }

    /** GL thread: grab the CPU camera image and send it to Claude in the background. */
    private fun captureForAi(frame: Frame) {
        val captured = try {
            frame.acquireCameraImage().use { img -> Triple(ImageUtil.yuvToNv21(img), img.width, img.height) }
        } catch (e: NotYetAvailableException) {
            return // try again next frame
        }
        depthFrames.clear()
        aiBox = null; texBox = null; aiBoxView = null
        aiState = AiState.ASKING
        setStatus("Asking Claude what you’re pointing at…")
        lifecycleScope.launch(Dispatchers.IO) { askClaude(captured.first, captured.second, captured.third) }
    }

    private fun askClaude(nv21: ByteArray, w: Int, h: Int) {
        try {
            val jpeg = ImageUtil.nv21ToUprightJpeg(nv21, w, h, sensorOrientation, 1280)
            lastJpeg = jpeg
            val det = claude().detect(jpeg)
            val box = det.box
            if (!det.found || box == null) {
                failAi(det.tip.ifBlank { "Claude couldn’t find an object at the center dot. Aim at it and try again." })
                return
            }
            detection = det
            aiBox = ImageUtil.unrotateBox(box, sensorOrientation)
            aiState = AiState.SAMPLING
            setStatus("Found: ${det.label}. Hold still while I read depth…")
        } catch (e: Exception) {
            failAi("AI request failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** GL thread: collect several depth frames, then compute the box in the background. */
    private fun sampleDepth(frame: Frame, s: Session) {
        if (texBox == null) return
        try {
            frame.acquireDepthImage16Bits().use { img ->
                depthFrames.add(DepthSnapshot.from(img, frame.camera.pose, frame.camera.textureIntrinsics))
            }
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            failAi("Depth isn’t available right now: ${e.message}")
            return
        }
        setStatus("Hold still… reading depth ${depthFrames.size}/$DEPTH_FRAMES")
        if (depthFrames.size < DEPTH_FRAMES) return

        aiState = AiState.COMPUTING
        val frames = depthFrames.toList()
        depthFrames.clear()
        val planeYs = s.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.subsumedBy == null }
            .map { it.centerPose.ty() }
        val box = texBox ?: return failAi("Lost track of the object. Try again.")
        setStatus("Calculating size…")
        lifecycleScope.launch(Dispatchers.Default) {
            val m = Measurer.measure(frames, box, planeYs)
            if (m == null) {
                failAi("Not enough depth on the object. Stand 0.5–1.5 m away, make sure the surface around it is visible, and try again.")
                return@launch
            }
            measurement = m
            aiState = AiState.DONE
            lastStatus = ""
            runOnUiThread { refreshUi() }
        }
    }

    private fun failAi(msg: String) {
        aiState = AiState.DONE
        measurement = null
        aiBox = null; texBox = null; aiBoxView = null
        runOnUiThread { refreshUi(); showStatusNow(msg) }
    }

    // ---------------------------------------------------------------- buttons

    private fun undo() {
        if (mode == Mode.TAPE) {
            if (anchors.isNotEmpty()) anchors.removeAt(anchors.size - 1).detach()
        } else {
            measurement = null; detection = null; aiState = AiState.IDLE
        }
        lastStatus = ""
        refreshUi()
    }

    private fun clearAll() {
        anchors.forEach { it.detach() }
        anchors.clear()
        measurement = null; detection = null
        aiBox = null; texBox = null; aiBoxView = null
        aiState = AiState.IDLE
        lastStatus = ""
        refreshUi()
    }

    // ---------------------------------------------------------------- UI (main thread)

    private fun refreshUi() {
        val inch = useInches
        unitBtn.text = Units.unit(inch)
        val busy = aiState in setOf(AiState.CAPTURE, AiState.ASKING, AiState.SAMPLING, AiState.COMPUTING)
        actionBtn.visibility = if (mode == Mode.AI) View.VISIBLE else View.GONE
        actionBtn.isEnabled = !busy
        askBtn.isEnabled = !busy && (measurement != null || (mode == Mode.TAPE && anchors.size >= 2))
        actionBtn.text = when {
            busy -> "Measuring…"
            measurement != null -> "Measure again"
            else -> "Measure object"
        }

        when (mode) {
            Mode.TAPE -> {
                val poses = anchors.map { it.pose }
                if (poses.size < 2) {
                    resultText.text = "—"
                    detailText.text = if (poses.isEmpty()) "Tap any surface or object to drop a point." else "Tap a second point."
                } else {
                    fun d(i: Int): Float {
                        val a = poses[i - 1]; val b = poses[i]
                        val dx = a.tx() - b.tx(); val dy = a.ty() - b.ty(); val dz = a.tz() - b.tz()
                        return sqrt(dx * dx + dy * dy + dz * dz)
                    }
                    val total = (1 until poses.size).sumOf { d(it).toDouble() }.toFloat()
                    resultText.text = Units.fmt(d(poses.size - 1), inch)
                    detailText.text = if (poses.size > 2) "Total over ${poses.size - 1} segments: ${Units.fmt(total, inch)}"
                    else "Straight-line 3D distance"
                }
                showStatusNow(if (anchors.isEmpty()) "Tap to drop the first point." else "Tap to add the next point.")
            }
            Mode.AI -> {
                val m = measurement
                val det = detection
                if (m == null) {
                    resultText.text = "—"
                    detailText.text = if (depthSupported) "Claude finds the object; depth sensing measures it in 3D."
                    else "AI measuring needs ARCore Depth support."
                } else {
                    resultText.text = "${Units.num(m.length, inch)} × ${Units.num(m.width, inch)} × ${Units.num(m.height, inch)} ${Units.unit(inch)}"
                    detailText.text = buildDetail(m, det, inch)
                    showStatusNow(det?.tip?.takeIf { it.isNotBlank() }
                        ?: "Length × width × height. Walk around it and tap Measure again to double-check.")
                }
            }
        }
    }

    private fun buildDetail(m: Measurement, det: AiDetection?, inch: Boolean): String {
        val parts = mutableListOf<String>()
        val name = det?.label ?: "Object"
        parts += name.replaceFirstChar { it.uppercase() }
        if (det?.shape == "cylinder" || det?.shape == "sphere") {
            parts += "diameter ≈ ${Units.fmt((m.length + m.width) / 2f, inch)}"
        }
        det?.typicalCm?.let { t ->
            val measuredMax = max(m.length, max(m.width, m.height)) * 100f
            val typicalMax = t.max()
            val ratio = measuredMax / typicalMax
            parts += if (ratio > 2f || ratio < 0.5f)
                "unusual for a $name (typically ~${typicalMax.toInt()} cm), so rescan from another angle"
            else "typical size ${t[0].toInt()} × ${t[1].toInt()} × ${t[2].toInt()} cm"
        }
        parts += "±1–2 cm"
        return parts.joinToString(" · ")
    }

    // ---------------------------------------------------------------- Claude integration

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showClaudeSettings(reason: String?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        if (reason != null) box.addView(TextView(this).apply { text = reason; setPadding(0, 0, 0, dp(12)) })
        val keyInput = EditText(this).apply {
            hint = "Claude API key (sk-ant-…)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("api_key", ""))
        }
        val modelInput = EditText(this).apply {
            hint = "Model (default: ${BuildConfig.CLAUDE_MODEL})"
            isSingleLine = true
            setText(prefs.getString("model", ""))
        }
        box.addView(keyInput)
        box.addView(modelInput)
        box.addView(TextView(this).apply {
            text = "Create a key at console.anthropic.com under API keys. It’s saved only on this phone and sent only to Claude."
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })

        MaterialAlertDialogBuilder(this)
            .setTitle("Connect to Claude")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save and test") { _, _ ->
                prefs.edit()
                    .putString("api_key", keyInput.text.toString().trim())
                    .putString("model", modelInput.text.toString().trim())
                    .apply()
                testConnection()
            }
            .show()
    }

    private fun testConnection() {
        if (!claudeReady()) { showStatusNow("No API key saved. AI object measuring is off."); return }
        showStatusNow("Testing the Claude connection…")
        val client = claude()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { client.ping() }
            withContext(Dispatchers.Main) {
                lastStatus = ""
                result.onSuccess { Toast.makeText(this@MainActivity, "Connected to Claude (${model()})", Toast.LENGTH_SHORT).show() }
                result.onFailure { showClaudeSettings("Connection failed: ${it.message}") }
                refreshUi()
            }
        }
    }

    /** Plain-text description of what was measured, sent along with the question. */
    private fun measurementContext(): String? {
        fun both(m: Float) = "${Units.fmt(m, false)} (${Units.fmt(m, true)})"
        val m = measurement
        if (mode == Mode.AI && m != null) {
            val det = detection
            return buildString {
                append("Object: ${det?.label ?: "unknown"} (shape: ${det?.shape ?: "unknown"})\n")
                append("Footprint length: ${both(m.length)}\n")
                append("Footprint width: ${both(m.width)}\n")
                append("Height above the surface it stands on: ${both(m.height)}")
            }
        }
        if (mode == Mode.TAPE && anchors.size >= 2) {
            val poses = anchors.map { it.pose }
            val segs = (1 until poses.size).map { i ->
                val a = poses[i - 1]; val b = poses[i]
                val dx = a.tx() - b.tx(); val dy = a.ty() - b.ty(); val dz = a.tz() - b.tz()
                sqrt(dx * dx + dy * dy + dz * dz)
            }
            return buildString {
                append("Point-to-point 3D distances in order:\n")
                segs.forEachIndexed { i, d -> append("Segment ${i + 1}: ${both(d)}\n") }
                if (segs.size > 1) append("Total: ${both(segs.sum())}")
            }
        }
        return null
    }

    private fun showAsk() {
        if (!claudeReady()) { showClaudeSettings("Add your Claude API key to ask questions."); return }
        val context = measurementContext() ?: run {
            Toast.makeText(this, "Measure something first.", Toast.LENGTH_SHORT).show(); return
        }
        val input = EditText(this).apply {
            hint = "e.g. Will this fit on a 40 cm shelf?"
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Ask Claude")
            .setMessage(context)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Ask") { _, _ ->
                val q = input.text.toString().trim().ifBlank { "What could I do with this measurement? Any practical tips?" }
                askClaudeQuestion(q, context)
            }
            .show()
    }

    private fun askClaudeQuestion(question: String, context: String) {
        val thinking = MaterialAlertDialogBuilder(this)
            .setTitle("Claude is thinking…")
            .setMessage(question)
            .setCancelable(false)
            .show()
        val client = claude()
        val photo = if (mode == Mode.AI) lastJpeg else null
        lifecycleScope.launch(Dispatchers.IO) {
            val answer = runCatching { client.ask(question, context, photo) }
            withContext(Dispatchers.Main) {
                thinking.dismiss()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Claude")
                    .setMessage(answer.getOrElse { "Couldn’t get an answer: ${it.message}" })
                    .setPositiveButton("Done", null)
                    .setNeutralButton("Ask another") { _, _ -> showAsk() }
                    .show()
            }
        }
    }

    /** Thread-safe status update that skips repeats. */
    private fun setStatus(msg: String) {
        if (msg == lastStatus) return
        lastStatus = msg
        runOnUiThread { statusText.text = msg }
    }

    private fun showStatusNow(msg: String) {
        lastStatus = msg
        statusText.text = msg
    }

    private fun showFatal(msg: String) {
        showStatusNow(msg)
        actionBtn.isEnabled = false
    }
}

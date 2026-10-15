package com.raunak.wareye

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.raunak.wareye.databinding.ActivityScanBinding
import java.util.concurrent.Executors

class ScanActivity : ComponentActivity() {
    private lateinit var b: ActivityScanBinding
    private val main = Handler(Looper.getMainLooper())
    private val metrics = Metrics()
    private val ramExecutor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraRequested = false

    @Volatile private var analyzing = false
    @Volatile private var paused = false
    @Volatile private var mirror = false

    private enum class Screen { NONE, LOADING, PERMISSION, FAILED }
    private var screen = Screen.NONE
    private var askedPermission = false

    // UI state
    private var hudExpanded = false
    private var slidersOpen = false
    private var torchOn = false
    private val zoomSteps = floatArrayOf(1f, 2f, 5f)
    private var zoomIndex = 0
    private var lastResult: FrameResult? = null
    private var lastNonEmptyMs = 0L
    private var lowFps = false
    private var lowSinceMs = 0L
    private var okSinceMs = 0L
    private var bannerDismissed = false
    private var tick = 0
    private var lastAnnounceMs = 0L
    private var lastSummary = ""
    private val chipViews = LinkedHashMap<Int, TextView>()

    private val engineListener: () -> Unit = { onEngineChanged() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { proceed() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = updateTargetRotation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        edgeToEdge(lightBars = false)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        b = ActivityScanBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets()
        setupControls()
        buildChips()
        applyOrientationLayout()

        Engine.setSelected(Settings.selected)
        Engine.addListener(engineListener)
        Engine.ensureLoaded(this, Settings.useGpu)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        proceed()
    }

    override fun onResume() {
        super.onResume()
        // Returning from system settings re-checks the permission automatically.
        if (screen == Screen.PERMISSION && hasCameraPermission()) proceed()
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        analyzing = false
        Engine.removeListener(engineListener)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        ramExecutor.shutdown()
        super.onDestroy()
    }

    // ---- Flow: permission -> model -> camera ------------------------------------------------

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun proceed() {
        if (!hasCameraPermission()) {
            if (!askedPermission) {
                askedPermission = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                showPermission()
            }
            return
        }
        when (Engine.state) {
            Engine.State.IDLE, Engine.State.LOADING -> showLoading()
            Engine.State.FAILED -> showFailed()
            Engine.State.READY -> {
                if (Engine.gpuError != null && Settings.useGpu && !Engine.gpuFailureAcknowledged) {
                    showFailed()
                } else {
                    startCamera()
                }
            }
        }
    }

    private fun onEngineChanged() {
        if (isFinishing || isDestroyed) return
        if (screen == Screen.LOADING) renderLoading()
        if (Engine.state != Engine.State.LOADING && screen != Screen.PERMISSION) proceed()
    }

    private fun startCamera() {
        if (camera != null) { onCameraReady(); return }
        if (cameraRequested) return
        cameraRequested = true
        if (screen == Screen.LOADING) renderLoading()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            bindCamera()
        }, mainExecutor)
    }

    private fun bindCamera() {
        val p = provider ?: return
        if (isDestroyed) return
        if (!hasLens(lensFacing)) lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val rotation = b.root.display?.rotation ?: Surface.ROTATION_0
        // Same 4:3 field of view for preview and analysis, so boxes line up with the preview.
        val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val prev = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
            .setTargetRotation(rotation)
            .build()
        val ana = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(rotation)
            .build()
        ana.setAnalyzer(Engine.executor, ::analyze)
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, selector, prev, ana)
            prev.setSurfaceProvider(b.preview.surfaceProvider)
            preview = prev
            analysis = ana
            mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
            Log.i(Detector.TAG, "camera bound: ${if (mirror) "front" else "back"}, analysis ${ana.resolutionInfo?.resolution}")
        } catch (e: Exception) {
            Log.e(Detector.TAG, "camera bind failed", e)
            Toast.makeText(this, e.message ?: "Camera failed", Toast.LENGTH_LONG).show()
            return
        }
        torchOn = false
        zoomIndex = 0
        onCameraReady()
    }

    private fun onCameraReady() {
        if (Engine.state != Engine.State.READY) return
        hideStatus()
        updateCameraControls()
        metrics.reset()
        lastNonEmptyMs = SystemClock.uptimeMillis()
        analyzing = true
    }

    private fun hasLens(facing: Int): Boolean = try {
        provider?.hasCamera(CameraSelector.Builder().requireLensFacing(facing).build()) == true
    } catch (e: Exception) { false }

    private fun updateTargetRotation() {
        val r = b.root.display?.rotation ?: return
        preview?.targetRotation = r
        analysis?.targetRotation = r
    }

    // ---- Inference (Engine.executor thread) -------------------------------------------------

    private fun analyze(image: ImageProxy) {
        val det = Engine.detector
        if (!analyzing || paused || det == null) { image.close(); return }
        val start = System.nanoTime()
        val result = try {
            det.detect(image, mirror, Settings.conf, Settings.iou)
        } catch (t: Throwable) {
            Log.e(Detector.TAG, "inference failed", t)
            null
        } finally {
            image.close()
        }
        if (result == null) return
        metrics.onFrame(start, result)
        main.post { onResult(result) }
    }

    private fun onResult(r: FrameResult) {
        if (!analyzing || paused) return
        lastResult = r
        b.overlay.setResults(r)
        if (r.detections.isNotEmpty()) {
            lastNonEmptyMs = SystemClock.uptimeMillis()
            if (b.hint.visibility == View.VISIBLE) b.hint.visibility = View.GONE
        }
    }

    // ---- 4 Hz UI tick: HUD, counts, low-FPS and nothing-detected states ---------------------

    private val ticker = object : Runnable {
        override fun run() {
            updateHud()
            main.postDelayed(this, 250)
        }
    }

    private fun updateHud() {
        metrics.refresh()
        if (tick++ % 4 == 0) ramExecutor.execute { metrics.sampleRam() }
        val now = SystemClock.uptimeMillis()
        val live = analyzing && !paused && screen == Screen.NONE

        // Low FPS: under 8 for 3 s -> warn; clears at 10+ for 3 s.
        if (live && metrics.fps > 0f) {
            if (!lowFps) {
                if (metrics.fps < LOW_FPS) {
                    if (lowSinceMs == 0L) lowSinceMs = now
                    if (now - lowSinceMs >= HOLD_MS) { lowFps = true; okSinceMs = 0L }
                } else lowSinceMs = 0L
            } else {
                if (metrics.fps >= OK_FPS) {
                    if (okSinceMs == 0L) okSinceMs = now
                    if (now - okSinceMs >= HOLD_MS) { lowFps = false; lowSinceMs = 0L; bannerDismissed = false }
                } else okSinceMs = 0L
            }
        }

        // HUD pill
        val show = Settings.showMetrics
        b.hudPill.visibility = if (show) View.VISIBLE else View.GONE
        b.hudCard.visibility = if (show && hudExpanded) View.VISIBLE else View.GONE
        val pillText = when {
            paused -> "❚❚ " + getString(R.string.paused)
            else -> "%.1f FPS · %.1f ms".format(java.util.Locale.US, metrics.fps, metrics.totalMs)
        }
        b.hudPill.text = pillText
        b.hudPill.isActivated = lowFps && !paused
        b.hudPill.setTextColor(getColor(if (lowFps && !paused) R.color.warn_on_dark else R.color.cream))
        val lead = when {
            paused -> null
            lowFps -> getDrawable(R.drawable.ic_warning)?.mutate()?.also { it.setTint(getColor(R.color.warn_on_dark)); it.setBounds(0, 0, dp(16f), dp(16f)) }
            else -> getDrawable(R.drawable.dot)?.mutate()?.also { it.setTint(getColor(R.color.live_dot_dark)); it.setBounds(0, 0, dp(8f), dp(8f)) }
        }
        b.hudPill.setCompoundDrawablesRelative(lead, null, null, null)

        if (hudExpanded && show) {
            b.hudModel.text = Engine.chipLabel
            b.hudDetections.text = "${metrics.detections} detections"
            b.hudBig.text = "%.1f FPS  %.1f ms".format(java.util.Locale.US, metrics.fps, metrics.totalMs)
            b.hudStages.text = "%.1f pre · %.1f inference · %.1f post".format(java.util.Locale.US, metrics.preMs, metrics.inferMs, metrics.postMs)
            b.hudPct.text = "P50 %.0f ms · P95 %.0f ms · App RAM %d MB".format(java.util.Locale.US, metrics.p50, metrics.p95, metrics.appRamMb)
        }

        // Low-FPS banner offers GPU only when running on CPU.
        b.banner.visibility = if (live && lowFps && Engine.accelerator == "CPU" && !bannerDismissed) View.VISIBLE else View.GONE

        // Nothing detected for 2 s -> hint card.
        val nothing = live && now - lastNonEmptyMs > NOTHING_MS
        if (nothing && b.hint.visibility != View.VISIBLE) {
            b.hintBody.text = getString(R.string.nothing_body, Settings.conf)
            b.hintAction.visibility = if (Settings.conf > LOWER_TO + 0.001f) View.VISIBLE else View.GONE
            b.hint.visibility = View.VISIBLE
        } else if (!nothing && b.hint.visibility == View.VISIBLE) {
            b.hint.visibility = View.GONE
        }

        updateCounts(now)
    }

    private fun updateCounts(now: Long) {
        val dets = lastResult?.detections.orEmpty()
        val counts = HashMap<Int, Int>()
        for (d in dets) counts[d.cls] = (counts[d.cls] ?: 0) + 1
        val n = dets.size
        b.countTitle.text = resources.getQuantityString(R.plurals.objects_detected, n, n)
        for ((id, chip) in chipViews) styleChip(chip, id, counts[id] ?: 0)
        // TalkBack: one summary, at most once per second, only when it changes.
        val summary = "$n objects" + if (n > 0) ": " + counts.entries.joinToString(", ") { "${it.value} ${ClassRegistry.label(it.key)}" } else ""
        if (summary != lastSummary && now - lastAnnounceMs >= 1000) {
            lastSummary = summary
            lastAnnounceMs = now
            b.countTitle.contentDescription = summary
        }
    }

    // ---- Count chips (display only) ----------------------------------------------------------

    private fun buildChips() {
        b.chips.removeAllViews()
        chipViews.clear()
        for (c in ClassRegistry.all) {
            if (c.id !in Settings.selected) continue
            val tv = TextView(this).apply {
                setTextColor(getColor(R.color.cream))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), 0, dp(12f), 0)
                compoundDrawablePadding = dp(6f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            b.chips.addView(tv, LinearLayout.LayoutParams(-2, dp(30f)).apply { marginEnd = dp(6f) })
            chipViews[c.id] = tv
            styleChip(tv, c.id, 0)
        }
        b.classesOn.text = getString(R.string.classes_on, chipViews.size)
    }

    private fun styleChip(tv: TextView, id: Int, count: Int) {
        val text = "${ClassRegistry[id]?.title} ×$count"
        if (tv.text.toString() == text && tv.tag == (count > 0)) return
        tv.text = text
        tv.tag = count > 0
        val color = ClassRegistry.color(id)
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(15f).toFloat()
            if (count > 0) setStroke(dp(1.5f), color)
            else setStroke(dp(1.5f), getColor(R.color.on_forest_muted), dp(4f).toFloat(), dp(3f).toFloat())
        }
        val dot = if (count > 0) GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setSize(dp(8f), dp(8f))
        } else null
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
    }

    // ---- Controls ---------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        b.back.setOnClickListener { finish() }
        b.hudPill.setOnClickListener { hudExpanded = !hudExpanded; updateHud() }
        b.hudCard.setOnClickListener { openMetrics() }
        b.pause.setOnClickListener { setPaused(!paused) }
        b.torch.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            updateCameraControls()
        }
        b.flip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            analyzing = false
            b.overlay.clear()
            camera = null
            bindCamera()
        }
        b.zoom.setOnClickListener {
            zoomIndex = (zoomIndex + 1) % zoomSteps.size
            setZoom(zoomSteps[zoomIndex])
        }
        b.tune.setOnClickListener { toggleSliders() }
        b.handle.setOnClickListener { toggleSliders() }
        b.moreSettings.setOnClickListener { openSettings() }
        SettingsSheet.bindSlider(b.confSlider, b.confValue, { Settings.conf }) { Settings.conf = it }
        SettingsSheet.bindSlider(b.iouSlider, b.iouValue, { Settings.iou }) { Settings.iou = it }
        b.hintAction.setOnClickListener {
            Settings.conf = LOWER_TO
            SettingsSheet.setSlider(b.confSlider, b.confValue, LOWER_TO)
            b.hint.visibility = View.GONE
            lastNonEmptyMs = SystemClock.uptimeMillis()
        }
        b.bannerText.setOnClickListener { bannerDismissed = true; b.banner.visibility = View.GONE }
        b.bannerAction.setOnClickListener {
            Settings.useGpu = true
            reloadModel()
        }

        // Pinch to zoom on the preview.
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val current = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: return false
                setZoom(current * d.scaleFactor)
                return true
            }
        })
        b.overlay.setOnTouchListener { _, e -> scaleDetector.onTouchEvent(e); true }
    }

    private fun toggleSliders() {
        slidersOpen = !slidersOpen
        b.sliders.visibility = if (slidersOpen) View.VISIBLE else View.GONE
        b.tune.isActivated = slidersOpen
        SettingsSheet.setSlider(b.confSlider, b.confValue, Settings.conf)
        SettingsSheet.setSlider(b.iouSlider, b.iouValue, Settings.iou)
    }

    private fun openSettings() {
        SettingsSheet(this, onDelegateChanged = { reloadModel() }, onChanged = {
            SettingsSheet.setSlider(b.confSlider, b.confValue, Settings.conf)
            SettingsSheet.setSlider(b.iouSlider, b.iouValue, Settings.iou)
            updateHud()
        }).show()
    }

    /** Metrics sheet pauses analysis while open (stable snapshot) and resumes on close. */
    private fun openMetrics() {
        val wasPaused = paused
        if (!wasPaused) setPaused(true)
        MetricsSheet(this, metrics) { if (!wasPaused && !isFinishing) setPaused(false) }.show()
    }

    private fun setZoom(requested: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value
        val ratio = if (zs != null) requested.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else requested
        cam.cameraControl.setZoomRatio(ratio)
        b.zoom.text = "%.1fx".format(java.util.Locale.US, ratio)
        b.zoom.contentDescription = getString(R.string.zoom, b.zoom.text)
    }

    private fun setPaused(p: Boolean) {
        paused = p
        if (p) {
            // Freeze the frame: snapshot of what the preview shows, analysis stops.
            b.frozen.setImageBitmap(b.preview.bitmap)
            b.frozen.visibility = View.VISIBLE
            b.hint.visibility = View.GONE
        } else {
            b.frozen.visibility = View.GONE
            b.frozen.setImageDrawable(null)
            metrics.resetInterval()
            lastNonEmptyMs = SystemClock.uptimeMillis()
        }
        b.overlay.paused = p
        b.pause.setImageResource(if (p) R.drawable.ic_play else R.drawable.ic_pause)
        b.pause.contentDescription = getString(if (p) R.string.resume else R.string.pause)
        updateHud()
    }

    private fun updateCameraControls() {
        val cam = camera
        val flash = cam?.cameraInfo?.hasFlashUnit() == true
        b.torch.isEnabled = flash
        b.torch.isActivated = torchOn && flash
        ViewCompat.setStateDescription(b.torch, if (torchOn) "On" else "Off")
        b.flip.isEnabled = hasLens(CameraSelector.LENS_FACING_BACK) && hasLens(CameraSelector.LENS_FACING_FRONT)
        setZoom(zoomSteps[zoomIndex])
    }

    private fun reloadModel() {
        analyzing = false
        b.overlay.clear()
        lastResult = null
        lowFps = false; lowSinceMs = 0L; okSinceMs = 0L
        Engine.ensureLoaded(this, Settings.useGpu, force = true)
        proceed()
    }

    // ---- Status screens ---------------------------------------------------------------------

    private fun showStatus(s: Screen) {
        screen = s
        analyzing = false
        b.status.root.visibility = View.VISIBLE
        b.status.statusBack.setOnClickListener { finish() }
        edgeToEdge(lightBars = true)
    }

    private fun hideStatus() {
        if (screen == Screen.NONE) return
        screen = Screen.NONE
        b.status.statusIcon.clearAnimation()
        b.status.root.visibility = View.GONE
        edgeToEdge(lightBars = false)
    }

    private fun showLoading() {
        if (screen != Screen.LOADING) showStatus(Screen.LOADING)
        renderLoading()
    }

    private fun renderLoading() {
        val s = b.status
        val gpu = Settings.useGpu
        s.statusDisc.setBackgroundResource(R.drawable.disc_lavender)
        s.statusIcon.setImageResource(R.drawable.ic_eye)
        s.statusIcon.imageTintList = null
        s.statusTitle.setText(R.string.loading_title)
        s.statusBody.setText(if (gpu) R.string.loading_body_gpu else R.string.loading_body_cpu)
        s.statusDetail.visibility = View.GONE
        s.statusProgress.visibility = View.VISIBLE
        s.statusPrimary.visibility = View.GONE
        s.statusSecondary.visibility = View.VISIBLE
        s.statusSecondary.setText(R.string.cancel)
        s.statusSecondary.setOnClickListener { finish() }
        s.statusTertiary.visibility = View.GONE

        val stage = if (Engine.state == Engine.State.READY) Detector.STAGE_CAMERA else Engine.stage
        s.statusProgress.progress = stage
        val labels = intArrayOf(R.string.step_file, R.string.step_runtime,
            if (gpu) R.string.step_gpu else R.string.step_cpu, R.string.step_camera)
        s.statusSteps.removeAllViews()
        labels.forEachIndexed { i, label ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(36f) }
            val icon = ImageView(this)
            when {
                i < stage -> icon.setImageResource(R.drawable.ic_check_badge)
                i == stage -> {
                    icon.setImageResource(R.drawable.ic_spinner)
                    icon.startAnimation(RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
                        duration = 900; repeatCount = Animation.INFINITE; interpolator = LinearInterpolator()
                    })
                }
                else -> icon.setImageResource(R.drawable.ic_step_pending)
            }
            val text = TextView(this).apply {
                setText(label)
                setTextAppearance(if (i == stage) R.style.T_Label else R.style.T_BodyLarge)
                textSize = 15f
                if (i > stage) setTextColor(getColor(R.color.ink_muted))
                setPadding(dp(12f), 0, 0, 0)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(22f), dp(22f)))
            row.addView(text)
            s.statusSteps.addView(row)
        }
    }

    private fun showPermission() {
        showStatus(Screen.PERMISSION)
        val s = b.status
        s.statusDisc.setBackgroundResource(R.drawable.disc_lavender)
        s.statusIcon.setImageResource(R.drawable.ic_camera_off)
        s.statusIcon.imageTintList = null
        s.statusTitle.setText(R.string.perm_title)
        s.statusBody.setText(R.string.perm_body)
        s.statusProgress.visibility = View.GONE
        s.statusSteps.removeAllViews()
        s.statusDetail.visibility = View.VISIBLE
        s.statusDetail.setBackgroundResource(R.drawable.box_tile)
        s.statusDetailLabel.setText(R.string.perm_steps_label)
        s.statusDetailLabel.setTextColor(getColor(R.color.ink_muted))
        s.statusDetailText.setText(R.string.perm_steps)
        s.statusDetailText.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        s.statusPrimary.visibility = View.VISIBLE
        s.statusPrimary.setText(R.string.open_settings)
        s.statusPrimary.setOnClickListener {
            startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        s.statusSecondary.visibility = View.GONE
        s.statusTertiary.visibility = View.VISIBLE
        s.statusTertiary.setText(R.string.not_now)
        s.statusTertiary.setTextColor(getColor(R.color.ink))
        s.statusTertiary.setOnClickListener { finish() }
    }

    private fun showFailed() {
        showStatus(Screen.FAILED)
        val s = b.status
        val cpuWorks = Engine.state == Engine.State.READY && Engine.accelerator == "CPU"
        s.statusDisc.setBackgroundResource(R.drawable.disc_error)
        s.statusIcon.setImageResource(R.drawable.ic_warning)
        s.statusIcon.imageTintList = ColorStateList.valueOf(getColor(R.color.on_error))
        s.statusTitle.setText(R.string.failed_title)
        s.statusBody.setText(if (cpuWorks) R.string.failed_body_gpu else R.string.failed_body_all)
        s.statusProgress.visibility = View.GONE
        s.statusSteps.removeAllViews()
        s.statusDetail.visibility = View.VISIBLE
        s.statusDetail.setBackgroundResource(R.drawable.box_error)
        s.statusDetailLabel.setText(R.string.details)
        s.statusDetailLabel.setTextColor(getColor(R.color.on_error))
        s.statusDetailText.text = Engine.errorDetails()
        s.statusDetailText.typeface = android.graphics.Typeface.MONOSPACE
        s.statusPrimary.visibility = View.VISIBLE
        s.statusPrimary.setText(R.string.run_on_cpu)
        s.statusPrimary.setOnClickListener {
            Settings.useGpu = false
            if (cpuWorks) {
                Engine.gpuFailureAcknowledged = true
                proceed()
            } else {
                reloadModel()
            }
        }
        s.statusSecondary.visibility = View.VISIBLE
        s.statusSecondary.setText(R.string.try_gpu_again)
        s.statusSecondary.setOnClickListener {
            Settings.useGpu = true
            reloadModel()
        }
        s.statusTertiary.visibility = View.VISIBLE
        s.statusTertiary.setText(R.string.copy_details)
        s.statusTertiary.setTextColor(getColorStateList(R.color.text_button_red))
        s.statusTertiary.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Wareye error", Engine.errorDetails()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Insets and rotation -------------------------------------------------------------------

    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetTop = bars.top; insetBottom = bars.bottom; insetLeft = bars.left; insetRight = bars.right
            applyOrientationLayout()
            b.status.root.setPadding(dp(24f) + bars.left, bars.top, dp(24f) + bars.right, dp(24f) + bars.bottom)
            insets
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // No recreation: the model, camera session and metrics history survive. Re-layout only.
        updateTargetRotation()
        b.overlay.clear()
        applyOrientationLayout()
        ViewCompat.requestApplyInsets(b.root)
    }

    /** Portrait: bottom panel. Landscape: preview left of a side panel on the right. */
    private fun applyOrientationLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val panelW = dp(280f)
        val panelLp = b.panel.layoutParams as FrameLayout.LayoutParams
        val previewLp = b.preview.layoutParams as FrameLayout.LayoutParams
        if (landscape) {
            panelLp.width = panelW + insetRight
            panelLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            panelLp.gravity = Gravity.END
            b.panel.background = GradientDrawable().apply {
                setColor(getColor(R.color.forest))
                val r = dp(28f).toFloat()
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            b.panel.setPadding(dp(16f), dp(8f) + insetTop, dp(16f) + insetRight, dp(8f) + insetBottom)
            previewLp.marginEnd = panelW + insetRight
        } else {
            panelLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            panelLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            panelLp.gravity = Gravity.BOTTOM
            b.panel.setBackgroundResource(R.drawable.panel_forest)
            b.panel.setPadding(dp(16f) + insetLeft, 0, dp(16f) + insetRight, dp(8f) + insetBottom)
            previewLp.marginEnd = 0
        }
        b.panel.layoutParams = panelLp
        b.preview.layoutParams = previewLp
        (b.frozen.layoutParams as FrameLayout.LayoutParams).marginEnd = previewLp.marginEnd
        (b.overlay.layoutParams as FrameLayout.LayoutParams).marginEnd = previewLp.marginEnd
        (b.top.layoutParams as FrameLayout.LayoutParams).marginEnd = previewLp.marginEnd
        b.top.setPadding(dp(16f) + insetLeft, insetTop + dp(4f), dp(16f) + if (landscape) 0 else insetRight, 0)
        b.frozen.requestLayout()
        b.overlay.requestLayout()
        b.top.requestLayout()
        // Expanded HUD card stays under 25% of the preview; in landscape it gets a fixed width.
        b.hudCard.layoutParams = (b.hudCard.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (landscape) dp(361f) else ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    companion object {
        private const val LOW_FPS = 8f
        private const val OK_FPS = 10f
        private const val HOLD_MS = 3000L
        private const val NOTHING_MS = 2000L
        private const val LOWER_TO = 0.15f
    }
}

package com.raunak.wareye

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide model holder, so the model stays warm across screens and rotations.
 * Loading and inference share one thread, so a reload can never race a running frame.
 */
object Engine {
    enum class State { IDLE, LOADING, READY, FAILED }

    const val MODEL_NAME = "YOLO26n"

    val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "wareye-infer") }

    @Volatile var state = State.IDLE; private set
    @Volatile var stage = Detector.STAGE_FILE; private set
    @Volatile var detector: Detector? = null; private set
    /** "GPU" or "CPU": what actually runs, which can differ from the request after a fallback. */
    @Volatile var accelerator = ""; private set
    /** Set when GPU was requested but failed and the ladder fell back to CPU. */
    @Volatile var gpuError: String? = null; private set
    @Volatile var cpuError: String? = null; private set
    /** The user saw the GPU failure screen and chose to continue on CPU. */
    @Volatile var gpuFailureAcknowledged = false

    private var requestedGpu: Boolean? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    /** Loads (or reloads) the model unless it is already loaded/loading for this request. */
    fun ensureLoaded(context: Context, gpu: Boolean, force: Boolean = false) {
        if (!force && requestedGpu == gpu && (state == State.LOADING || state == State.READY)) return
        // e.g. "Run on CPU" after a GPU fallback: the CPU model is already warm.
        if (!force && state == State.READY && accelerator == (if (gpu) "GPU" else "CPU")) { requestedGpu = gpu; return }
        requestedGpu = gpu
        state = State.LOADING
        stage = Detector.STAGE_FILE
        notifyChanged()
        val app = context.applicationContext
        executor.execute { load(app, gpu) }
    }

    private fun load(context: Context, gpu: Boolean) {
        detector?.close()
        detector = null
        gpuError = null
        cpuError = null
        gpuFailureAcknowledged = false
        setStage(Detector.STAGE_FILE)
        var result: Detector? = null
        if (gpu) {
            try {
                result = Detector.create(context, gpu = true, onStage = ::setStage)
            } catch (t: Throwable) {
                gpuError = describe(t)
                Log.w(Detector.TAG, "GPU failed, falling back to CPU: $gpuError", t)
            }
        }
        if (result == null) {
            try {
                result = Detector.create(context, gpu = false, onStage = ::setStage)
            } catch (t: Throwable) {
                cpuError = describe(t)
                Log.e(Detector.TAG, "CPU load failed: $cpuError", t)
            }
        }
        result?.setSelected(Settings.selected)
        detector = result
        accelerator = result?.accelerator ?: ""
        state = if (result != null) State.READY else State.FAILED
        Log.i(Detector.TAG, "engine ${state.name}: accelerator=${accelerator.ifEmpty { "none" }} (requested ${if (gpu) "GPU" else "CPU"})")
        notifyChanged()
    }

    private fun setStage(s: Int) { stage = s; notifyChanged() }

    fun setSelected(ids: Set<Int>) = executor.execute { detector?.setSelected(ids) }

    /** "YOLO26n · GPU" */
    val chipLabel: String get() = "$MODEL_NAME · $accelerator"

    fun errorDetails(): String = buildString {
        append("model: $MODEL_NAME (w8a32)\n")
        gpuError?.let { append("delegate: GPU (LiteRT)\nresult: $it\n") }
        cpuError?.let { append("delegate: CPU (LiteRT)\nresult: $it\n") }
    }.trim()

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message ?: "initialization failed"}"
}

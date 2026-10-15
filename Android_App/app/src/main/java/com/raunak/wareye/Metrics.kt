package com.raunak.wareye

import android.os.Debug

/** Frame timing written by the inference thread, read by the UI at about 4 Hz. */
class Metrics {
    private val ring = FloatArray(WINDOW)
    private val sorted = FloatArray(WINDOW)
    private var ringCount = 0
    private var ringPos = 0
    private var lastFrameStart = 0L
    private var avgIntervalNs = 0.0

    var fps = 0f; private set
    var totalMs = 0f; private set
    var preMs = 0f; private set
    var inferMs = 0f; private set
    var postMs = 0f; private set
    var p50 = 0f; private set
    var p95 = 0f; private set
    var detections = 0; private set
    /** This process's PSS in MB (app RAM, not GPU memory). */
    @Volatile var appRamMb = 0; private set

    @Synchronized
    fun onFrame(frameStartNs: Long, r: FrameResult) {
        if (lastFrameStart != 0L) {
            val interval = (frameStartNs - lastFrameStart).toDouble()
            avgIntervalNs = if (avgIntervalNs == 0.0) interval else avgIntervalNs * 0.95 + interval * 0.05
        }
        lastFrameStart = frameStartNs
        preMs = r.preMs; inferMs = r.inferMs; postMs = r.postMs
        val total = r.preMs + r.inferMs + r.postMs
        ring[ringPos] = total
        ringPos = (ringPos + 1) % WINDOW
        if (ringCount < WINDOW) ringCount++
        detections = r.detections.size
        totalMs = total
    }

    /** Called after a pause so the paused gap is not counted as a frame interval. */
    @Synchronized
    fun resetInterval() { lastFrameStart = 0L }

    /** Refreshes fps and percentiles from a sorted copy; call from the UI tick. */
    @Synchronized
    fun refresh() {
        fps = if (avgIntervalNs > 0) (1e9 / avgIntervalNs).toFloat() else 0f
        if (ringCount == 0) return
        System.arraycopy(ring, 0, sorted, 0, ringCount)
        java.util.Arrays.sort(sorted, 0, ringCount)
        p50 = sorted[((ringCount - 1) * 0.50f).toInt()]
        p95 = sorted[((ringCount - 1) * 0.95f).toInt()]
    }

    /** Copies the last <= 100 total-ms values, oldest first, into [out]; returns the count. */
    @Synchronized
    fun history(out: FloatArray): Int {
        val start = if (ringCount < WINDOW) 0 else ringPos
        for (i in 0 until ringCount) out[i] = ring[(start + i) % WINDOW]
        return ringCount
    }

    /** Debug.getMemoryInfo takes a few ms; call off the UI thread about once a second. */
    fun sampleRam() {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        appRamMb = info.totalPss / 1024
    }

    @Synchronized
    fun reset() {
        ringCount = 0; ringPos = 0; lastFrameStart = 0L; avgIntervalNs = 0.0
        fps = 0f; totalMs = 0f; p50 = 0f; p95 = 0f; detections = 0
    }

    companion object { const val WINDOW = 100 }
}

package com.raunak.wareye

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.raunak.wareye.databinding.SheetMetricsBinding
import java.util.Locale

/**
 * Metrics sheet (opened from the expanded HUD card). Analysis is paused while it is open,
 * so it shows a stable snapshot; [onDismiss] resumes it.
 */
class MetricsSheet(
    private val activity: Activity,
    private val metrics: Metrics,
    private val onDismiss: () -> Unit,
) {
    private val dialog = Dialog(activity, R.style.Theme_Wareye_Sheet)
    private val b = SheetMetricsBinding.inflate(activity.layoutInflater)
    private val history = FloatArray(Metrics.WINDOW)

    init {
        dialog.setContentView(b.root)
        dialog.asBottomSheet(activity, b.scrim, b.sheet)
        dialog.setOnDismissListener { onDismiss() }
        b.close.setOnClickListener { dialog.dismiss() }
        b.done.setOnClickListener { dialog.dismiss() }
        b.copy.setOnClickListener {
            activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Wareye metrics", asText()))
            Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        render()
    }

    fun show() = dialog.show()

    private fun render() {
        metrics.refresh()
        b.subtitle.text = "${Engine.chipLabel} · LiteRT"
        b.fps.text = f1(metrics.fps)
        b.total.text = f1(metrics.totalMs) + " ms"
        b.fpsTile.contentDescription = "FPS ${f1(metrics.fps)}"
        b.totalTile.contentDescription = "Total ${f1(metrics.totalMs)} milliseconds"

        // Stage bar: widths proportional to pre / inference / post.
        listOf(b.barPre to metrics.preMs, b.barInfer to metrics.inferMs, b.barPost to metrics.postMs).forEach { (v, ms) ->
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { weight = ms.coerceAtLeast(0.01f) }
        }
        b.stageBar.clipToOutline = true
        b.stages.text = "■ ${f1(metrics.preMs)} pre   ■ ${f1(metrics.inferMs)} inference   ■ ${f1(metrics.postMs)} post"
        colorizeLegend()

        val n = metrics.history(history)
        b.sparkline.set(history, n, metrics.p50, metrics.p95)

        b.smallTiles.removeAllViews()
        listOf(
            "P50" to "%.0f ms".format(Locale.US, metrics.p50),
            "P95" to "%.0f ms".format(Locale.US, metrics.p95),
            "RAM" to "${metrics.appRamMb} MB",
            activity.getString(R.string.found) to "${metrics.detections}",
        ).forEachIndexed { i, (label, value) ->
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.box_tile)
                setPadding(dp(10f), dp(8f), dp(6f), dp(8f))
                isFocusable = true
                contentDescription = "$label $value"
            }
            tile.addView(TextView(activity).apply { setTextAppearance(R.style.T_LabelSmall); text = label })
            tile.addView(TextView(activity).apply {
                setTextAppearance(R.style.T_Mono); setTextColor(activity.getColor(R.color.ink)); text = value
                gravity = Gravity.START; maxLines = 1
            })
            b.smallTiles.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i > 0) marginStart = dp(8f)
            })
        }
    }

    private fun colorizeLegend() {
        val s = android.text.SpannableString(b.stages.text)
        val colors = intArrayOf(0xFFB9A3D6.toInt(), activity.getColor(R.color.forest), activity.getColor(R.color.red))
        var from = 0
        for (c in colors) {
            val i = s.indexOf('■', from)
            if (i < 0) break
            s.setSpan(android.text.style.ForegroundColorSpan(c), i, i + 1, 0)
            from = i + 1
        }
        b.stages.text = s
    }

    private fun asText() = buildString {
        append("Wareye metrics: ${Engine.chipLabel} (LiteRT)\n")
        append("FPS ${f1(metrics.fps)}, total ${f1(metrics.totalMs)} ms\n")
        append("pre ${f1(metrics.preMs)} ms, inference ${f1(metrics.inferMs)} ms, post ${f1(metrics.postMs)} ms\n")
        append("P50 %.0f ms, P95 %.0f ms (last %d frames)\n".format(Locale.US, metrics.p50, metrics.p95, metrics.history(history)))
        append("App RAM ${metrics.appRamMb} MB, detections ${metrics.detections}")
    }

    private fun dp(v: Float) = activity.dp(v)
    private fun f1(v: Float) = "%.1f".format(Locale.US, v)
}

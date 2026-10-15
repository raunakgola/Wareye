package com.raunak.wareye

import android.app.Activity
import android.app.Dialog
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.raunak.wareye.databinding.SheetSettingsBinding

/**
 * Settings sheet: CPU/GPU delegate, thresholds (applied live), show metrics.
 * [onDelegateChanged] reloads the model; [onChanged] lets the caller refresh its own UI.
 */
class SettingsSheet(
    private val activity: Activity,
    private val onDelegateChanged: () -> Unit,
    private val onChanged: () -> Unit = {},
) {
    private val dialog = Dialog(activity, R.style.Theme_Wareye_Sheet)
    private val b = SheetSettingsBinding.inflate(activity.layoutInflater)

    init {
        dialog.setContentView(b.root)
        dialog.asBottomSheet(activity, b.scrim, b.sheet)
        b.close.setOnClickListener { dismiss() }
        b.done.setOnClickListener { dismiss() }
        b.segCpu.setOnClickListener { setGpu(false) }
        b.segGpu.setOnClickListener { setGpu(true) }
        b.metricsRow.setOnClickListener {
            Settings.showMetrics = !Settings.showMetrics
            render(); onChanged()
        }
        b.reset.setOnClickListener {
            Settings.resetTuning()
            render(); onChanged()
            if (!Settings.useGpu) setGpu(true)
        }
        bindSlider(b.confSlider, b.confValue, { Settings.conf }) { Settings.conf = it; onChanged() }
        bindSlider(b.iouSlider, b.iouValue, { Settings.iou }) { Settings.iou = it; onChanged() }
        render()
    }

    fun show() = dialog.show()

    private fun dismiss() = dialog.dismiss()

    private fun setGpu(gpu: Boolean) {
        if (Settings.useGpu == gpu) return
        Settings.useGpu = gpu
        render()
        onDelegateChanged()
    }

    private fun render() {
        b.segCpu.isActivated = !Settings.useGpu
        b.segGpu.isActivated = Settings.useGpu
        val check = activity.getDrawable(R.drawable.ic_check)
        b.segCpu.setCompoundDrawablesRelativeWithIntrinsicBounds(if (!Settings.useGpu) check else null, null, null, null)
        b.segGpu.setCompoundDrawablesRelativeWithIntrinsicBounds(if (Settings.useGpu) check else null, null, null, null)
        b.segCpu.isSelected = !Settings.useGpu
        b.segGpu.isSelected = Settings.useGpu
        val running = if (Engine.state == Engine.State.READY) " " + activity.getString(R.string.running_on, Engine.accelerator) else ""
        b.delegateHint.text = activity.getString(R.string.delegate_hint) + running
        b.metricsSwitch.isChecked = Settings.showMetrics
        b.metricsRow.stateDescription(Settings.showMetrics)
        setSlider(b.confSlider, b.confValue, Settings.conf)
        setSlider(b.iouSlider, b.iouValue, Settings.iou)
    }

    companion object {
        /** Slider step 0.01 over 0.01..1.00 (SeekBar max is 99). */
        fun bindSlider(bar: SeekBar, label: TextView, get: () -> Float, set: (Float) -> Unit) {
            bar.max = 99
            setSlider(bar, label, get())
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = (p + 1) / 100f
                    label.text = "%.2f".fmt2(v)
                    s.stateDescriptionCompat("%.2f".fmt2(v))
                    if (fromUser) set(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }

        fun setSlider(bar: SeekBar, label: TextView, v: Float) {
            bar.progress = (v * 100 + 0.5f).toInt() - 1
            label.text = "%.2f".fmt2(v)
            bar.stateDescriptionCompat("%.2f".fmt2(v))
        }
    }
}

private fun android.view.View.stateDescription(on: Boolean) =
    ViewCompat.setStateDescription(this, if (on) "On" else "Off")

private fun android.view.View.stateDescriptionCompat(s: String) = ViewCompat.setStateDescription(this, s)

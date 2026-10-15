package com.raunak.wareye

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.raunak.wareye.databinding.ActivityHomeBinding

class HomeActivity : ComponentActivity() {
    private lateinit var b: ActivityHomeBinding
    private val engineListener: () -> Unit = { renderChip() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        edgeToEdge(lightBars = true)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()

        // Warm the model now so the scan screen usually skips the loading state.
        Engine.ensureLoaded(this, Settings.useGpu)
        Engine.addListener(engineListener)

        b.modelChip.setOnClickListener {
            SettingsSheet(this, onDelegateChanged = {
                Engine.ensureLoaded(this, Settings.useGpu, force = true)
            }).show()
        }
        b.scanButton.setOnClickListener { startActivity(Intent(this, ClassPickerActivity::class.java)) }
        renderChip()
    }

    override fun onDestroy() {
        Engine.removeListener(engineListener)
        super.onDestroy()
    }

    /** ModelChip: ready (GPU), CPU (fallback or chosen), loading, failed. */
    private fun renderChip() {
        val (text, dot) = when (Engine.state) {
            Engine.State.READY -> Engine.chipLabel to
                if (Engine.accelerator == "GPU") getColor(R.color.live_dot) else 0xFFD08A1C.toInt()
            Engine.State.FAILED -> getString(R.string.chip_failed) to getColor(R.color.red)
            else -> getString(R.string.chip_loading) to getColor(R.color.outline)
        }
        b.chipText.text = text
        b.chipDot.backgroundTintList = ColorStateList.valueOf(dot)
        b.modelChip.contentDescription = if (Engine.state == Engine.State.READY)
            getString(R.string.cd_model_chip, Engine.MODEL_NAME, Engine.accelerator) else text
    }
}

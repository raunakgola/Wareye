package com.raunak.wareye

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * One detectable class. [id] MUST equal the model's output class index
 * (order of `names` in kitchenware.yaml: plate, spoon, fork, knife, cup, glass).
 */
data class DetClass(
    val id: Int,
    val label: String,
    val category: String,
    val color: Int = ClassColors.generated(id),
) {
    val title: String get() = label.replaceFirstChar { it.uppercase() }
}

/**
 * The single class registry. List order is display order inside the picker; ids come from the model.
 * To add a class: append a DetClass with the model's index; the color is generated if omitted.
 */
object ClassRegistry {
    val all: List<DetClass> = listOf(
        DetClass(2, "fork", "Cutlery", 0xFFFF6B5E.toInt()),
        DetClass(3, "knife", "Cutlery", 0xFF4DB8FF.toInt()),
        DetClass(1, "spoon", "Cutlery", 0xFF7BE07F.toInt()),
        DetClass(0, "plate", "Tableware", 0xFFFFC93C.toInt()),
        DetClass(4, "cup", "Tableware", 0xFFC58CFF.toInt()),
        DetClass(5, "glass", "Tableware", 0xFF2EE6D0.toInt()),
    )

    private val byId: Map<Int, DetClass> = all.associateBy { it.id }

    /** Categories in first-appearance order. */
    val categories: List<Pair<String, List<DetClass>>> =
        all.groupBy { it.category }.toList()

    val ids: Set<Int> = byId.keys

    operator fun get(id: Int): DetClass? = byId[id]

    fun color(id: Int): Int = byId[id]?.color ?: ClassColors.generated(id)

    fun label(id: Int): String = byId[id]?.label ?: "class $id"
}

/** Stable per-id colors for ids without a design color: OKLCH, hue = id * 137.5 deg. */
object ClassColors {
    private val lightness = floatArrayOf(0.78f, 0.70f, 0.86f)

    fun generated(id: Int): Int {
        val l = lightness[(id / 12) % lightness.size].toDouble()
        val h = Math.toRadians((id * 137.5) % 360.0)
        return oklchToArgb(l, 0.14, h)
    }

    private fun oklchToArgb(lightness: Double, chroma: Double, hueRad: Double): Int {
        val a = chroma * cos(hueRad)
        val b = chroma * sin(hueRad)
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return (0xFF shl 24) or (gamma(r) shl 16) or (gamma(g) shl 8) or gamma(bl)
    }

    private fun gamma(x: Double): Int {
        val c = x.coerceIn(0.0, 1.0)
        val v = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        return (v * 255 + 0.5).toInt().coerceIn(0, 255)
    }
}

package vx.ignis.util

import org.bukkit.Bukkit
import org.bukkit.Color
import vx.ignis.Ignis.Companion.plugin
import kotlin.math.roundToInt

object RainbowColorTicker {

    var rainbowColor: Color = Color.fromRGB(255, 165, 0) // Initial: Soft Orange

    private var progress = 0f
    private val step = 0.01f
    private var taskId = -1

    private val colors = arrayOf(
        Color.fromRGB(255, 165, 0),    // Soft Orange (warm, energetic)
        Color.fromRGB(152, 251, 152),  // Mint Green (cool, refreshing; analogous complement to orange)
        Color.fromRGB(221, 160, 221)   // Plum Purple (soft violet; triadic harmony with orange and green for balanced vibrancy)
    )

    fun init() {
        this.start()
    }

    private fun start() {
        if (taskId != -1) return
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
            if (plugin.server.isStopping) {
                Bukkit.getScheduler().cancelTask(taskId)
                taskId = -1
                return@Runnable
            }
            progress = (progress + step) % 1f
            val segment = (progress * 3f).toInt() % 3
            val frac = (progress * 3f) % 1f
            rainbowColor = lerpColor(colors[segment], colors[(segment + 1) % 3], frac)
        }, 0L, 1L).taskId
    }

    private fun lerpColor(c1: Color, c2: Color, t: Float): Color {
        val r = ((1 - t) * c1.red + t * c2.red).roundToInt().coerceIn(0, 255)
        val g = ((1 - t) * c1.green + t * c2.green).roundToInt().coerceIn(0, 255)
        val b = ((1 - t) * c1.blue + t * c2.blue).roundToInt().coerceIn(0, 255)
        return Color.fromRGB(r, g, b)
    }

}
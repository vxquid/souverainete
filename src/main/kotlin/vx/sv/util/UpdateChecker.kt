package vx.sv.util

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import vx.sv.Souverainete.Companion.plugin
import java.io.IOException
import java.net.URL
import java.util.*
import java.util.function.Consumer
import kotlin.math.max

class UpdateChecker(private val resourceId: Int) {

    fun getVersion(consumer: Consumer<String>) {
        Bukkit.getScheduler().runTaskAsynchronously(JavaPlugin.getProvidingPlugin(javaClass)) { _ ->
            try {
                URL("https://api.spigotmc.org/legacy/update.php?resource=$resourceId/~").openStream().use { inputStream ->
                    Scanner(inputStream).use { scanner ->
                        if (scanner.hasNext()) {
                            consumer.accept(scanner.next())
                        }
                    }
                }
            } catch (e: IOException) {
                plugin.logger.info("Failed to check for updates: ${e.message}")
            }
        }
    }

    companion object {
        fun compareVersions(v1: String, v2: String): Int {
            val parts1 = v1.split(".")
            val parts2 = v2.split(".")
            val length = max(parts1.size, parts2.size)
            for (i in 0 until length) {
                val part1 = if (i < parts1.size) parts1[i].toInt() else 0
                val part2 = if (i < parts2.size) parts2[i].toInt() else 0
                if (part1 != part2) {
                    return part1.compareTo(part2)
                }
            }
            return 0
        }
    }
}
package vx.sv.command.override

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.SettlementManager
import kotlin.math.sqrt

class LocateCommandOverrideListener : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val message = event.message.lowercase()

        if (isVillageLocateCommand(message)) {
            event.isCancelled = true
            handleLocateVillage(event.player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        val message = event.command.lowercase()

        if (isVillageLocateCommand(message)) {
            event.isCancelled = true
            val msgConsole = plugin.language.getString(
                "locate-structure.disabled-console",
                "§c[Souverainete] Vanilla village search is disabled. Use the search command in-game."
            ) ?: "§c[Souverainete] Vanilla village search is disabled. Use the search command in-game."
            event.sender.sendMessage(msgConsole)
        }
    }

    private fun isVillageLocateCommand(cmd: String): Boolean {
        if (!cmd.startsWith("/locate") && !cmd.startsWith("locate")) return false

        return cmd.contains("village") ||
                cmd.contains("plains_village") ||
                cmd.contains("desert_village") ||
                cmd.contains("savanna_village") ||
                cmd.contains("snowy_village") ||
                cmd.contains("taiga_village")
    }

    private fun handleLocateVillage(player: Player) {
        val world = player.world
        val playerLoc = player.location

        val settlements = SettlementManager.settlements[world] ?: emptyList()
        val nearestSettlement = settlements.minByOrNull { it.data.center.distanceSquared(playerLoc) }

        val bestLoc: Location? = nearestSettlement?.data?.center
        val defaultName = plugin.language.getString("locate-structure.default-settlement-name", "Settlement") ?: "Settlement"
        val bestName = nearestSettlement?.data?.settlementName ?: defaultName

        if (bestLoc != null) {
            val minDistanceSq = bestLoc.distanceSquared(playerLoc)
            val distance = Math.round(sqrt(minDistanceSq)).toInt()
            val x = bestLoc.blockX
            val y = bestLoc.blockY
            val z = bestLoc.blockZ

            val prefix = plugin.language.getString("locate-structure.found-prefix", "§aNearest settlement (") ?: "§aNearest settlement ("
            val middle = plugin.language.getString("locate-structure.found-middle", "§a) is located at ") ?: "§a) is located at "
            val hoverText = plugin.language.getString("locate-structure.hover-teleport", "Click to teleport") ?: "Click to teleport"
            val suffixTemplate = plugin.language.getString("locate-structure.found-suffix", " §a({distance} blocks away)") ?: " §a({distance} blocks away)"
            val suffix = suffixTemplate.replace("{distance}", distance.toString())

            val message = Component.text(prefix)
                .append(Component.text(bestName).color(NamedTextColor.GOLD))
                .append(Component.text(middle))
                .append(
                    Component.text("[$x, ~$y, $z]")
                        .color(NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text(hoverText)))
                        .clickEvent(ClickEvent.runCommand("/tp @s $x $y $z"))
                )
                .append(Component.text(suffix))

            player.sendMessage(message)
        } else {
            val notFoundMsg = plugin.language.getString("locate-structure.not-found", "§cNo settlements found nearby.") ?: "§cNo settlements found nearby."
            player.sendMessage(Component.text(notFoundMsg))
        }
    }
}
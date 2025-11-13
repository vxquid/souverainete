package vx.ignis.gameplay.humanoid

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import vx.ignis.Ignis.Companion.plugin

class HumanoidInfoDisplayManager : Listener {

    private val displays = mutableMapOf<Pair<LivingEntity, Player>, TextDisplay>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        val config = plugin.gameplayManager.config
        if (config.humanoidDisplay.enabled) {
            startViewerUpdater()
        }
    }

    private fun createPersonalDisplay(npc: LivingEntity, player: Player): TextDisplay {
        val config = plugin.gameplayManager.config
        val location = npc.location.clone()
        val display = npc.world.spawn(location, TextDisplay::class.java) { textDisplay ->
            textDisplay.billboard = config.humanoidDisplay.billboard
            textDisplay.isSeeThrough = config.humanoidDisplay.seeThrough
            textDisplay.isVisibleByDefault = false
            val bgColor = config.humanoidDisplay.backgroundColor
            textDisplay.backgroundColor = Color.fromARGB(bgColor[0], bgColor[1], bgColor[2], bgColor[3])
            updateDisplayText(textDisplay, npc, player)

            val scale = config.humanoidDisplay.displayScale
            val transformation = Transformation(
                Vector3f(0f, config.humanoidDisplay.displayOffsetY, 0f),
                AxisAngle4f(),
                Vector3f(scale[0], scale[1], scale[2]),
                AxisAngle4f()
            )
            textDisplay.transformation = transformation
        }

        npc.addPassenger(display)
        player.showEntity(plugin, display)
        val pair = npc to player
        displays[pair] = display
        return display
    }

    private fun updateDisplayText(display: TextDisplay, npc: LivingEntity, player: Player) {
        val config = plugin.gameplayManager.config
        if (npc !is Villager) return

        val name = npc.customName ?: "Unknown"
        val level = npc.villagerLevel
        // Use language lookup for profession name, with fallback to hardcoded formatting.
        val professionKey = npc.profession.key.key.lowercase()
        val profession = plugin.language.getString("villager-professions.$professionKey")!!.replace("_", " ").capitalizeWords()
        val health = npc.health
        val maxHealth = npc.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0

        val reputationManager = plugin.gameplayManager.reputationManager
        val repValue = reputationManager.getReputationMap(npc)[player.uniqueId] ?: 0
        val repStatus = reputationManager.getPlayerReputationStatus(npc, player)

        val line1 = config.humanoidDisplay.nameProfessionLevelTemplate.format(name, profession, level)
        val line2 = config.humanoidDisplay.reputationTemplate.format(repValue, repStatus.localizedName) + " §7|§r " + config.humanoidDisplay.healthTemplate.format(health, maxHealth)
        display.text = "$line1\n$line2"
    }

    private fun startViewerUpdater() {
        val config = plugin.gameplayManager.config
        val viewDistance = config.humanoidDisplay.viewDistance
        val viewDistanceSquared = viewDistance * viewDistance
        val updateIntervalTicks = config.humanoidDisplay.updateIntervalTicks

        object : BukkitRunnable() {
            override fun run() {
                val cfg = plugin.gameplayManager.config
                if (!cfg.humanoidDisplay.enabled || !cfg.humanoid.humanoidVillagers) {
                    displays.values.forEach { if (it.isValid) it.remove() }
                    displays.clear()
                    return
                }

                val onlinePlayers = Bukkit.getOnlinePlayers().filter {
                    cfg.worlds.allowedWorlds.contains(it.world.name)
                }.toList()

                for (player in onlinePlayers) {
                    val nearbyEntities = player.getNearbyEntities(viewDistance, viewDistance, viewDistance)
                    val nearbyVillagers = nearbyEntities.filterIsInstance<Villager>()
                        .filter { it.location.distanceSquared(player.location) <= viewDistanceSquared }

                    for (npc in nearbyVillagers) {
                        val pair = npc to player
                        if (!displays.containsKey(pair)) {
                            createPersonalDisplay(npc, player)
                        } else {
                            updateDisplayText(displays[pair]!!, npc, player)
                        }
                    }
                }

                // Cleanup
                val toRemove = mutableListOf<Pair<LivingEntity, Player>>()
                displays.forEach { (pair, display) ->
                    val (npc, p) = pair
                    if (!npc.isValid || !display.isValid || !p.isOnline ||
                        !cfg.worlds.allowedWorlds.contains(p.world.name) ||
                        npc.location.distanceSquared(p.location) > viewDistanceSquared
                    ) {
                        toRemove.add(pair)
                        removePersonalDisplay(npc, p, display)
                    }
                }
                toRemove.forEach { displays.remove(it) }
            }
        }.runTaskTimer(plugin, 0L, updateIntervalTicks)
    }

    private fun removePersonalDisplay(npc: LivingEntity, player: Player, display: TextDisplay) {
        player.hideEntity(plugin, display)
        if (display.isValid) {
            npc.removePassenger(display)
            display.remove()
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val npc = event.entity
        val toRemove = displays.filterKeys { (n, _) -> n == npc }.keys.toList()
        toRemove.forEach { pair ->
            val (n, p) = pair
            val display = displays[pair]!!
            removePersonalDisplay(n, p, display)
            displays.remove(pair)
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val config = plugin.gameplayManager.config
        if (!config.humanoidDisplay.enabled) return
        if (!config.worlds.allowedWorlds.contains(event.world.name)) return
        if (!config.humanoid.humanoidVillagers) return

        event.chunk.entities.filterIsInstance<LivingEntity>().forEach { npc ->
            val toRemove = displays.filterKeys { (n, _) -> n == npc }.keys.toList()
            toRemove.forEach { pair ->
                val (n, p) = pair
                val display = displays[pair]!!
                removePersonalDisplay(n, p, display)
                displays.remove(pair)
            }
        }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.capitalize() }
}
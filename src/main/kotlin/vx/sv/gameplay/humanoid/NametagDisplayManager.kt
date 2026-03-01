package vx.sv.gameplay.humanoid

import com.cryptomorin.xseries.XAttribute
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import vx.sv.Souverainete.Companion.plugin
import vx.sv.persistent.LivingEntityExtend.hunger
import vx.sv.persistent.LivingEntityExtend.settlement
import kotlin.math.max

class NametagDisplayManager : Listener {

    private val displays = mutableMapOf<Pair<LivingEntity, Player>, DisplayData>()

    data class DisplayData(
        val entity: TextDisplay,
        var lastFocusState: Boolean? = null,
        var lastCeilingState: Boolean? = null,
        var lastBgColor: Color? = null,
        var lastText: String = "",
        val randomYOffset: Float
    )

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        val config = plugin.gameplayManager.config
        if (config.nametag.enabled) {
            startViewerUpdater()
        }
    }

    companion object {
        val RAIDER_KEY = NamespacedKey(plugin, "is_raider")
    }

    private fun createPersonalDisplay(npc: LivingEntity, player: Player): DisplayData {
        val config = plugin.gameplayManager.config
        val location = npc.location.clone()

        val display = npc.world.spawn(location, TextDisplay::class.java) { textDisplay ->
            textDisplay.isPersistent = false
            textDisplay.billboard = config.nametag.billboard
            textDisplay.isSeeThrough = config.nametag.seeThrough
            textDisplay.isVisibleByDefault = false

            val bgColor = config.nametag.backgroundColor
            textDisplay.backgroundColor = Color.fromARGB(bgColor[0], bgColor[1], bgColor[2], bgColor[3])
        }

        npc.addPassenger(display)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline && display.isValid) player.showEntity(plugin, display)
        }, 5L)

        // Deterministic Y-offset based on UUID to prevent Z-fighting when multiple NPCs cluster
        val randomYOffset = (npc.uniqueId.hashCode() % 16) / 100f

        val displayData = DisplayData(display, randomYOffset = randomYOffset)
        val pair = npc to player
        displays[pair] = displayData

        // Initial setup
        updateDisplayText(displayData, npc, player, isFocused = false)
        return displayData
    }

    /**
     * @param isFocused True if the player is looking directly at this NPC.
     */
    private fun updateDisplayText(data: DisplayData, npc: LivingEntity, player: Player, isFocused: Boolean) {
        val display = data.entity
        val config = plugin.gameplayManager.config
        if (npc !is Villager) return

        // 1. --- CEILING FIX (Dynamic Y-Offset) ---
        val headBlock = npc.location.clone().add(0.0, 2.2, 0.0).block
        val isCeilingLow = headBlock.type.isSolid

        // 2. --- VISUAL STATE & INTERPOLATION ---
        val targetBgColor = determineBackgroundColor(npc, player, config.nametag.backgroundColor, isFocused)

        val focusGained = isFocused && data.lastFocusState != true
        val visualStateChanged = data.lastFocusState != isFocused ||
                data.lastCeilingState != isCeilingLow ||
                data.lastBgColor != targetBgColor

        if (visualStateChanged) {
            val baseOffsetY = config.nametag.displayOffsetY.toFloat()
            val targetOffsetY = (if (isCeilingLow) baseOffsetY - 0.45f else baseOffsetY) + data.randomYOffset

            val baseScale = config.nametag.displayScale
            val targetScale = if (isFocused) 1.0f else 0.65f // Shrink to 65% when not in focus

            val newTransform = Transformation(
                Vector3f(0f, targetOffsetY, 0f),
                AxisAngle4f(),
                Vector3f(
                    baseScale[0].toFloat() * targetScale,
                    baseScale[1].toFloat() * targetScale,
                    baseScale[2].toFloat() * targetScale
                ),
                AxisAngle4f()
            )

            if (focusGained) {
                // INSTANT TEXT POP-IN:
                // Set text to fully visible immediately so the player can read it without waiting for animation.
                display.interpolationDuration = 0
                display.textOpacity = 255.toByte()

                // Schedule the background/scale expansion for the next tick to allow smooth transition
                // without overriding the instant text alpha change.
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (!display.isValid) return@Runnable
                    display.interpolationDelay = 0
                    display.interpolationDuration = 10
                    display.backgroundColor = targetBgColor
                    display.transformation = newTransform
                }, 1L)
            } else {
                // FADING OUT / GENERAL UPDATE:
                // When looking away, smooth fade everything out.
                display.interpolationDelay = 0
                display.interpolationDuration = 10
                display.textOpacity = if (isFocused) 255.toByte() else 90.toByte()
                display.backgroundColor = targetBgColor
                display.transformation = newTransform
            }

            // Cache new states
            data.lastFocusState = isFocused
            data.lastCeilingState = isCeilingLow
            data.lastBgColor = targetBgColor
        }

        // 3. --- TEXT ASSEMBLY ---
        val npcSettlement = npc.settlement
        val settlementLine = if (npcSettlement != null) "§6«${npcSettlement.data.settlementName}»\n" else ""

        val name = npc.customName ?: "Unknown"
        val level = npc.villagerLevel
        val isRaider = npc.persistentDataContainer.has(RAIDER_KEY, PersistentDataType.BYTE)

        val profession = if (isRaider) {
            plugin.language.getString("villager-professions.raider") ?: "§cRaider"
        } else {
            val professionKey = npc.profession.key.key.lowercase()
            (plugin.language.getString("villager-professions.$professionKey") ?: "ERR")
                .replace("_", " ").capitalizeWords()
        }

        val line1 = config.nametag.nameProfessionLevelTemplate.format(name, profession, level)
        var text = "$settlementLine$line1"

        // Render full info ONLY when looking directly at them
        if (isFocused) {
            val reputationManager = plugin.gameplayManager.reputationManager
            val personalRep = reputationManager.getReputationMap(npc)[player.uniqueId] ?: 0
            val settlementRep = npcSettlement?.data?.reputation?.get(player.uniqueId) ?: 0
            val finalRepScore = personalRep + settlementRep
            val repStatus = reputationManager.getReputationStatusFromScore(finalRepScore)

            val health = npc.health
            val maxHealth = npc.getAttribute(XAttribute.MAX_HEALTH.get()!!)?.value ?: 20.0

            val line2 = config.nametag.reputationTemplate.format(finalRepScore, repStatus.getLocalizedName()) +
                    " §7|§r " + config.nametag.healthTemplate.format(health, maxHealth)
            text += "\n$line2"

            val hungerValue = npc.hunger
            if (hungerValue <= config.hunger.eatThreshold) {
                val statusKey = if (hungerValue <= config.hunger.starvationThreshold) "starving" else "hungry"
                val status = plugin.language.getString("hunger-status.$statusKey")!!
                val line3 = config.nametag.hungerTemplate.format(status, hungerValue, config.hunger.max)
                text += "\n$line3"
            }

            val partyManager = plugin.gameplayManager.partyManager
            val leaderUUID = partyManager.getLeaderUUID(npc)
            if (leaderUUID != null) {
                val leaderName = Bukkit.getPlayer(leaderUUID)?.name
                    ?: Bukkit.getOfflinePlayerIfCached(leaderUUID.toString())?.name ?: "Unknown"
                val partyTemplate = plugin.language.getString("party.member") ?: "§b{playerName} Party Member"
                text += "\n${partyTemplate.replace("{playerName}", leaderName)}"
            }

            val tracker = plugin.gameplayManager.reputationTracker
            val state = tracker.getNPCState(npc, player)
            if (state != null) {
                val stateDisplayName = try {
                    plugin.language.getString(state.translationKey) ?: state.name
                } catch (e: Exception) { state.name }
                text += "\n${state.color}$stateDisplayName"
            }
        } else {
            // Subtle indicator that the NPC has something for the player when not focused
            val tracker = plugin.gameplayManager.reputationTracker
            val state = tracker.getNPCState(npc, player)
            if (state != null) {
                text = "${state.color}✦§r\n$text"
            }
        }

        // Apply text update only if changed
        if (data.lastText != text) {
            display.text = text
            data.lastText = text
        }
    }

    /**
     * Determines an expressive background color based on NPC roles.
     * Uses darker, muted shades for a cleaner, non-intrusive RPG aesthetic.
     */
    private fun determineBackgroundColor(npc: Villager, player: Player, configColor: List<Int>, isFocused: Boolean): Color {
        val isRaider = npc.persistentDataContainer.has(RAIDER_KEY, PersistentDataType.BYTE)
        val partyManager = plugin.gameplayManager.partyManager
        val leaderUUID = partyManager.getLeaderUUID(npc)

        val reputationManager = plugin.gameplayManager.reputationManager
        val personalRep = reputationManager.getReputationMap(npc)[player.uniqueId] ?: 0
        val settlementRep = npc.settlement?.data?.reputation?.get(player.uniqueId) ?: 0
        val totalRep = personalRep + settlementRep

        // Dramatic alpha fade for unfocused NPCs
        val baseAlpha = configColor[0]
        val targetAlpha = if (isFocused) baseAlpha else max(20, baseAlpha - 130)

        // Dark, distinguished color palette
        return when {
            isRaider -> Color.fromARGB(targetAlpha, 130, 20, 20) // Enemies -> Dark Red
            leaderUUID == player.uniqueId -> Color.fromARGB(targetAlpha, 20, 130, 20) // Your Party -> Dark Forest Green
            leaderUUID != null -> Color.fromARGB(targetAlpha, 20, 90, 140) // Other Party -> Dark Azure
            totalRep > 100 -> Color.fromARGB(targetAlpha, 30, 110, 30) // High Rep -> Muted Green
            totalRep < -50 -> Color.fromARGB(targetAlpha, 120, 50, 20) // Hostile Rep -> Dark Rust
            else -> {
                val profName = npc.profession.key.key.lowercase()
                when {
                    profName in listOf("armorer", "weaponsmith", "toolsmith", "guard") ->
                        Color.fromARGB(targetAlpha, 60, 65, 75) // Guards/Smiths -> Dark Steel
                    profName in listOf("cleric", "librarian", "mage") ->
                        Color.fromARGB(targetAlpha, 90, 30, 120) // Scholars/Magic -> Dark Violet
                    profName in listOf("farmer", "shepherd", "fisherman", "fletcher") ->
                        Color.fromARGB(targetAlpha, 70, 100, 30) // Gatherers -> Dark Moss Green
                    profName in listOf("butcher", "leatherworker") ->
                        Color.fromARGB(targetAlpha, 100, 45, 30) // Meat/Leather -> Dark Maroon
                    else -> Color.fromARGB(targetAlpha, configColor[1], configColor[2], configColor[3])
                }
            }
        }
    }

    private fun startViewerUpdater() {
        val config = plugin.gameplayManager.config
        val viewDistance = config.nametag.viewDistance
        val viewDistanceSquared = viewDistance * viewDistance

        // Very fast tick rate (150ms) for snappy responsive gaze tracking
        val updateIntervalTicks = 3L
        var tickCounter = 0

        object : BukkitRunnable() {
            override fun run() {
                tickCounter += updateIntervalTicks.toInt()
                val cfg = plugin.gameplayManager.config
                if (!cfg.nametag.enabled || !cfg.humanoid.humanoidVillagers) {
                    displays.values.forEach { if (it.entity.isValid) it.entity.remove() }
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

                    var bestFocus: Villager? = null
                    var bestScore = -Double.MAX_VALUE
                    val playerEyeLoc = player.eyeLocation
                    val playerDir = playerEyeLoc.direction

                    for (npc in nearbyVillagers) {
                        val toNpcVector = npc.eyeLocation.toVector().subtract(playerEyeLoc.toVector())
                        val distSq = toNpcVector.lengthSquared()
                        if (distSq < 0.1) continue

                        val dist = Math.sqrt(distSq)
                        val dot = playerDir.dot(toNpcVector.normalize())

                        if (dot > 0.5) {
                            val score = (dot * 10.0) - dist
                            if (score > bestScore) {
                                bestScore = score
                                bestFocus = npc
                            }
                        }
                    }

                    for (npc in nearbyVillagers) {
                        val pair = npc to player
                        val currentData = displays[pair]

                        val isFocused = (npc == bestFocus) ||
                                (nearbyVillagers.size == 1 && npc.location.distanceSquared(player.location) < 9.0)

                        if (currentData == null || !currentData.entity.isValid) {
                            if (currentData != null) displays.remove(pair)
                            createPersonalDisplay(npc, player)
                        } else {
                            updateDisplayText(currentData, npc, player, isFocused)
                        }
                    }
                }

                // Garbage Collection runs every ~20 ticks (1 sec) to save CPU
                if (tickCounter >= 20) {
                    tickCounter = 0
                    val iterator = displays.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val (npc, p) = entry.key
                        val data = entry.value

                        if (!npc.isValid || !data.entity.isValid || !p.isOnline ||
                            !cfg.worlds.allowedWorlds.contains(p.world.name) ||
                            npc.world != p.world ||
                            npc.location.distanceSquared(p.location) > viewDistanceSquared
                        ) {
                            removePersonalDisplay(npc, p, data.entity)
                            iterator.remove()
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, updateIntervalTicks)
    }

    private fun removePersonalDisplay(npc: LivingEntity, player: Player, display: TextDisplay) {
        if (player.isOnline) player.hideEntity(plugin, display)
        if (display.isValid) {
            npc.removePassenger(display)
            display.remove()
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val npc = event.entity
        if (npc !is Villager) return

        val iterator = displays.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.first == npc) {
                removePersonalDisplay(npc, entry.key.second, entry.value.entity)
                iterator.remove()
            }
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val config = plugin.gameplayManager.config
        if (!config.nametag.enabled) return

        val iterator = displays.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val npc = entry.key.first

            val npcChunkX = npc.location.blockX shr 4
            val npcChunkZ = npc.location.blockZ shr 4

            if (event.chunk.x == npcChunkX && event.chunk.z == npcChunkZ) {
                removePersonalDisplay(npc, entry.key.second, entry.value.entity)
                iterator.remove()
            }
        }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}
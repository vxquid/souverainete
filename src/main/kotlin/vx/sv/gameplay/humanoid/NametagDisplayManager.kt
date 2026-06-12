package vx.sv.gameplay.humanoid

import com.cryptomorin.xseries.XAttribute
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.event.HumanoidInitializationEvent
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.settlement.isSettlementLeader
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import vx.sv.persistent.LivingEntityExtend.hunger
import vx.sv.persistent.LivingEntityExtend.settlement
import vx.sv.persistent.NametagMode
import vx.sv.persistent.PlayerPreferencesManager.preferences
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt
import com.github.retrooper.packetevents.util.Vector3f as PEVector3f

/**
 * Manages custom packet-based nametags (Text Display entities) for Humanoid NPCs.
 * Handles spawning, destroying, and dynamically updating nametags based on player distance and line-of-sight.
 */
class NametagDisplayManager : Listener {

    // Maps Player UUID -> (Base Entity ID -> Display Data)
    private val activeDisplays = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, DisplayData>>()

    // Stores the Entity IDs of fake players that have been successfully initialized and shown to a specific player.
    private val initializedHumanoids = ConcurrentHashMap<UUID, MutableSet<Int>>()

    // Safe starting ID for fake text display entities to prevent collision with actual server entities
    private val entityIdGenerator = AtomicInteger(2_000_000)

    /**
     * Holds the current state of a rendered nametag to avoid sending redundant packets.
     */
    data class DisplayData(
        val displayEntityId: Int,
        var lastFocusState: Boolean? = null,
        var lastCeilingState: Boolean? = null,
        var lastBgColor: Int? = null,
        var lastText: String = "",
        val randomYOffset: Float,
        @Volatile var isSpawned: Boolean = false
    )

    /**
     * Cached data for an NPC at a specific tick to avoid redundant calculations across multiple players.
     */
    data class NpcSnapshot(
        val entityId: Int,
        val uuid: UUID,
        val isCeilingLow: Boolean,
        val name: String,
        val professionName: String,
        val customProfessionDisplay: String?,
        val level: Int,
        val isRaider: Boolean,
        val settlementName: String?,
        val health: Double,
        val maxHealth: Double,
        val hungerValue: Int,
        val partyLeaderUuid: UUID?,
        val randomYOffset: Float,
        val settlementRepMap: Map<UUID, Int>
    )

    /**
     * Player-specific perspective of an NPC, used to determine if the nametag should be expanded (focused) or compact.
     */
    data class PlayerViewData(
        val snapshot: NpcSnapshot,
        val personalRep: Int,
        val stateName: String?,
        val stateColor: String?,
        val focusScore: Double,
        val distanceSq: Double,
        val isSingleClose: Boolean
    )

    /**
     * Text Display Entity metadata indexes (refer to wiki.vg for standard protocol values).
     */
    object DisplayMeta {
        const val INTERPOLATION_DELAY = 8
        const val INTERPOLATION_DURATION = 9
        const val TRANSLATION = 11
        const val SCALE = 12
        const val BILLBOARD = 15
        const val TEXT = 23
        const val BG_COLOR = 25
        const val OPACITY = 26
        const val FLAGS = 27 // Bitmask (1 = shadow)
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        val config = plugin.gameplayManager.config
        if (config.nametag.enabled) {
            startSnapshotTick()
        }

        // Register a packet listener to instantly remove the nametag
        // whenever the fake player is destroyed (e.g., when the player walks away or the villager despawns).
        PacketEvents.getAPI().eventManager.registerListener(object : SimplePacketListenerAbstract(PacketListenerPriority.MONITOR) {

            override fun onPacketPlaySend(event: PacketPlaySendEvent) {
                // Ignore if it's not a DESTROY_ENTITIES packet
                if (event.packetType != PacketType.Play.Server.DESTROY_ENTITIES) return

                val packet = WrapperPlayServerDestroyEntities(event)
                val player = event.getPlayer<Player>() ?: return

                val initializedSet = initializedHumanoids[player.uniqueId]
                val displaysMap = activeDisplays[player.uniqueId]

                val displaysToDestroy = mutableListOf<Int>()

                // Check if any of the destroyed entities have an attached nametag
                for (entityId in packet.entityIds) {
                    initializedSet?.remove(entityId)

                    val displayData = displaysMap?.remove(entityId)
                    if (displayData != null && displayData.isSpawned) {
                        displaysToDestroy.add(displayData.displayEntityId)
                    }
                }

                // Append the nametag entities to a new destroy packet
                if (displaysToDestroy.isNotEmpty() && player.isOnline) {
                    val destroyPacket = WrapperPlayServerDestroyEntities(*displaysToDestroy.toIntArray())
                    PacketEvents.getAPI().playerManager.getUser(player)?.sendPacket(destroyPacket)
                }
            }
        })
    }

    companion object {
        val RAIDER_KEY = NamespacedKey(plugin, "is_raider")
    }

    @EventHandler
    fun onHumanoidInit(event: HumanoidInitializationEvent) {
        // Allow spawning the nametag as soon as the ProtocolListener has successfully shown the fake player model.
        initializedHumanoids.computeIfAbsent(event.player.uniqueId) { ConcurrentHashMap.newKeySet() }
            .add(event.entity.entityId)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        // Clear memory on disconnect
        val uuid = event.player.uniqueId
        initializedHumanoids.remove(uuid)
        activeDisplays.remove(uuid)
    }

    /**
     * Starts a repeating task that captures the state of nearby NPCs and calculates player perspectives.
     */
    private fun startSnapshotTick() {
        val updateIntervalTicks = 3L

        object : BukkitRunnable() {
            override fun run() {
                val cfg = plugin.gameplayManager.config
                if (!cfg.nametag.enabled || !cfg.humanoid.humanoidVillagers) {
                    cleanupAll()
                    return
                }

                val viewDistance = cfg.nametag.viewDistance.toDouble()
                val viewDistanceSq = viewDistance * viewDistance

                // Cache snapshots so we don't query Bukkit API multiple times for the same NPC
                val npcCache = mutableMapOf<Int, NpcSnapshot>()
                val dispatchMap = mutableMapOf<Player, List<PlayerViewData>>()

                val onlinePlayers = Bukkit.getOnlinePlayers().filter {
                    cfg.worlds.allowedWorlds.contains(it.world.name)
                }

                for (player in onlinePlayers) {
                    val initializedIds = initializedHumanoids[player.uniqueId] ?: emptySet()

                    val nearbyVillagers = player.location.getNearbyEntitiesByType(Villager::class.java, viewDistance)
                    val playerEyeLoc = player.eyeLocation
                    val playerDir = playerEyeLoc.direction

                    val viewList = mutableListOf<PlayerViewData>()

                    for (npc in nearbyVillagers) {
                        // SKIP VILLAGERS that haven't been replaced with fake players by the ProtocolListener yet!
                        if (!initializedIds.contains(npc.entityId)) continue

                        val toNpcVector = npc.eyeLocation.toVector().subtract(playerEyeLoc.toVector())
                        val distSq = toNpcVector.lengthSquared()
                        if (distSq !in 0.1..viewDistanceSq) continue

                        val dist = sqrt(distSq)
                        val dot = playerDir.dot(toNpcVector.normalize())

                        // Calculate focus score using dot product.
                        // High score means the player is looking directly at the NPC.
                        val focusScore = if (dot > 0.5) (dot * 10.0) - dist else -Double.MAX_VALUE

                        // Build or retrieve the NPC snapshot
                        val snapshot = npcCache.getOrPut(npc.entityId) {
                            val isCeilingLow = npc.location.clone().add(0.0, 2.2, 0.0).block.type.isSolid
                            val isRaider = npc.persistentDataContainer.has(RAIDER_KEY, PersistentDataType.BYTE)
                            val settlementData = npc.settlement?.data

                            // Динамически заменяем профессию на "Builder", если запущен процесс строительства
                            val nmsVillager = (npc as? CraftVillager)?.handle as? HumanoidVillager
                            val isBuilding = nmsVillager?.activeBuildJob != null

                            val profName = if (isBuilding) "builder" else npc.profession.key.key.lowercase()
                            val customProf = if (npc.isSettlementLeader()) npc.race.leaderTitle else null

                            NpcSnapshot(
                                entityId = npc.entityId,
                                uuid = npc.uniqueId,
                                isCeilingLow = isCeilingLow,
                                name = npc.customName ?: "Unknown",
                                professionName = profName,
                                customProfessionDisplay = customProf,
                                level = npc.villagerLevel,
                                isRaider = isRaider,
                                settlementName = settlementData?.settlementName,
                                health = npc.health,
                                maxHealth = npc.getAttribute(XAttribute.MAX_HEALTH.get()!!)?.value ?: 20.0,
                                hungerValue = npc.hunger.toInt(),
                                partyLeaderUuid = plugin.gameplayManager.partyManager.getLeaderUUID(npc),
                                randomYOffset = (npc.uniqueId.hashCode() % 16) / 100f, // Adds slight variance to prevent z-fighting
                                settlementRepMap = settlementData?.reputation?.toMap() ?: emptyMap()
                            )
                        }

                        // Collect player-specific data regarding this NPC
                        val personalRep = plugin.gameplayManager.reputationManager.getReputationMap(npc)[player.uniqueId] ?: 0
                        val npcState = plugin.gameplayManager.reputationTracker.getNPCState(npc, player)

                        viewList.add(
                            PlayerViewData(
                                snapshot = snapshot,
                                personalRep = personalRep,
                                stateName = npcState?.translationKey,
                                stateColor = npcState?.color,
                                focusScore = focusScore,
                                distanceSq = distSq,
                                isSingleClose = (nearbyVillagers.size == 1 && distSq < 9.0) // Auto-focus if it's the only one nearby
                            )
                        )
                    }
                    dispatchMap[player] = viewList
                }

                // Process the collected views off the main thread to save tick time
                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    processAsyncDisplayUpdates(dispatchMap)
                })
            }
        }.runTaskTimer(plugin, 0L, updateIntervalTicks)
    }

    /**
     * Determines which nametags need to be spawned, updated, or removed based on the collected view data.
     */
    private fun processAsyncDisplayUpdates(dispatchMap: Map<Player, List<PlayerViewData>>) {
        val config = plugin.gameplayManager.config
        val baseConfigColor = config.nametag.backgroundColor

        for ((player, viewList) in dispatchMap) {
            if (!player.isOnline) continue

            val playerDisplays = activeDisplays.getOrPut(player.uniqueId) { ConcurrentHashMap() }
            val currentVisibleIds = mutableSetOf<Int>()
            val isVanillaMode = player.preferences.nametagMode == NametagMode.VANILLA

            // Find the NPC the player is looking at most directly
            var bestFocusId = -1
            var bestScore = -Double.MAX_VALUE
            for (view in viewList) {
                if (view.focusScore > bestScore) {
                    bestScore = view.focusScore
                    bestFocusId = view.snapshot.entityId
                }
            }

            for (view in viewList) {
                val snapshot = view.snapshot
                val isFocused = (snapshot.entityId == bestFocusId) || view.isSingleClose
                currentVisibleIds.add(snapshot.entityId)

                // Render the text depending on the player's preference
                val text = if (isVanillaMode) {
                    val nameToShow = if (snapshot.name != "Unknown") snapshot.name else {
                        when {
                            snapshot.isRaider -> plugin.language.getString("villager-professions.raider") ?: "Raider"
                            snapshot.customProfessionDisplay != null -> snapshot.customProfessionDisplay
                            else -> (plugin.language.getString("villager-professions.${snapshot.professionName}") ?: snapshot.professionName).replace("_", " ").capitalizeWords()
                        }
                    }
                    "&f$nameToShow" // White text like vanilla nametags
                } else {
                    buildText(view, isFocused, player.uniqueId)
                }

                val targetBgColor = if (isVanillaMode) {
                    toARGBInt(64, 0, 0, 0) // Standard vanilla translucent black background
                } else {
                    determineBackgroundColor(snapshot, player.uniqueId, baseConfigColor, isFocused)
                }

                val baseOffsetY = config.nametag.displayOffsetY.toFloat()
                // Lower the nametag slightly if the NPC is standing under a low ceiling
                val targetOffsetY = (if (snapshot.isCeilingLow) baseOffsetY - 0.45f else baseOffsetY) + snapshot.randomYOffset

                val baseScaleCfg = config.nametag.displayScale
                // In vanilla mode, scale is static so it doesn't jump
                val targetScaleMult = if (isVanillaMode) 0.85f else if (isFocused) 1.0f else 0.65f
                val scale = PEVector3f(
                    baseScaleCfg[0] * targetScaleMult,
                    baseScaleCfg[1] * targetScaleMult,
                    baseScaleCfg[2] * targetScaleMult
                )

                val translation = PEVector3f(0f, targetOffsetY, 0f)
                val opacity = if (isVanillaMode) 255.toByte() else if (isFocused) 255.toByte() else 90.toByte()

                val data = playerDisplays[snapshot.entityId]

                // If nametag doesn't exist yet, spawn it
                if (data == null) {
                    val newDisplayId = entityIdGenerator.incrementAndGet()
                    val newData = DisplayData(newDisplayId, randomYOffset = snapshot.randomYOffset)
                    playerDisplays[snapshot.entityId] = newData

                    newData.lastFocusState = isFocused
                    newData.lastCeilingState = snapshot.isCeilingLow
                    newData.lastBgColor = targetBgColor
                    newData.lastText = text

                    // Delay the spawn slightly to ensure the base entity passenger update registers
                    plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable {
                        if (player.isOnline && playerDisplays[snapshot.entityId] == newData) {
                            sendSpawnPackets(player, snapshot.entityId, newDisplayId)
                            sendMetadataPacket(player, newDisplayId, newData.lastText, newData.lastBgColor ?: targetBgColor, opacity, scale, translation, 0, 0)
                            newData.isSpawned = true
                        }
                    }, 5L)
                } else {
                    // Update existing nametag if needed
                    if (!data.isSpawned) {
                        data.lastFocusState = isFocused
                        data.lastCeilingState = snapshot.isCeilingLow
                        data.lastBgColor = targetBgColor
                        data.lastText = text
                        continue
                    }

                    val focusGained = isFocused && data.lastFocusState != true
                    val visualChanged = data.lastFocusState != isFocused || data.lastCeilingState != snapshot.isCeilingLow || data.lastBgColor != targetBgColor
                    val textChanged = data.lastText != text

                    if (focusGained && !isVanillaMode) {
                        // When gaining focus, instantly snap to full opacity to make it feel responsive
                        sendMetadataPacket(player, data.displayEntityId, text, targetBgColor, opacity, scale, translation, 0, 0)

                        // Follow up with a smooth interpolation for scale/translation
                        plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable {
                            if (player.isOnline && playerDisplays.contains(snapshot.entityId)) {
                                sendMetadataPacket(player, data.displayEntityId, text, targetBgColor, opacity, scale, translation, 10, 0)
                            }
                        }, 1L)
                    } else if (visualChanged || textChanged) {
                        sendMetadataPacket(player, data.displayEntityId, text, targetBgColor, opacity, scale, translation, 10, 0)
                    }

                    // Update cached state
                    data.lastFocusState = isFocused
                    data.lastCeilingState = snapshot.isCeilingLow
                    data.lastBgColor = targetBgColor
                    data.lastText = text
                }
            }

            // Remove displays for entities that are no longer visible or out of range
            val oldIds = playerDisplays.keys().toList()
            val toRemove = oldIds.filter { it !in currentVisibleIds }

            if (toRemove.isNotEmpty()) {
                val destroyEntityIds = toRemove.mapNotNull { playerDisplays.remove(it)?.displayEntityId }.toIntArray()
                if (destroyEntityIds.isNotEmpty() && player.isOnline) {
                    PacketEvents.getAPI().playerManager.getUser(player)?.sendPacket(
                        WrapperPlayServerDestroyEntities(*destroyEntityIds)
                    )
                }
            }
        }
    }

    /**
     * Constructs the multi-line text for the nametag in ADVANCED mode.
     */
    private fun buildText(view: PlayerViewData, isFocused: Boolean, playerUUID: UUID): String {
        val config = plugin.gameplayManager.config
        val snap = view.snapshot

        val settlementLine = if (snap.settlementName != null) "&6«${snap.settlementName}»\n" else ""

        val profession = when {
            snap.isRaider -> plugin.language.getString("villager-professions.raider") ?: "&cRaider"
            snap.customProfessionDisplay != null -> snap.customProfessionDisplay
            else -> (plugin.language.getString("villager-professions.${snap.professionName}") ?: "ERR").replace("_", " ").capitalizeWords()
        }

        val line1 = String.format(java.util.Locale.US, config.nametag.nameProfessionLevelTemplate, snap.name, profession, snap.level)
        var text = "$settlementLine$line1"

        if (isFocused) {
            val repManager = plugin.gameplayManager.reputationManager
            val settlementRep = snap.settlementRepMap[playerUUID] ?: 0
            val finalRepScore = view.personalRep + settlementRep
            val repStatus = repManager.getReputationStatusFromScore(finalRepScore)

            val repStr = String.format(Locale.US, config.nametag.reputationTemplate, finalRepScore, repStatus.getLocalizedName())
            val healthStr = String.format(Locale.US, config.nametag.healthTemplate, snap.health, snap.maxHealth)

            val line2 = "$repStr &7|&r $healthStr"

            text += "\n$line2"

            if (snap.hungerValue <= config.hunger.eatThreshold) {
                val statusKey = if (snap.hungerValue <= config.hunger.starvationThreshold) "starving" else "hungry"
                val status = plugin.language.getString("hunger-status.$statusKey")!!

                val hungerStr = String.format(
                    Locale.US,
                    config.nametag.hungerTemplate,
                    status,
                    snap.hungerValue.toDouble(),
                    config.hunger.max.toDouble()
                )
                text += "\n$hungerStr"
            }

            if (snap.partyLeaderUuid != null) {
                val leaderName = Bukkit.getOfflinePlayer(snap.partyLeaderUuid).name ?: "Unknown"
                val partyTemplate = plugin.language.getString("party.member") ?: "&b{playerName} Party Member"
                text += "\n${partyTemplate.replace("{playerName}", leaderName)}"
            }

            if (view.stateName != null && view.stateColor != null) {
                val stateDisplayName = try {
                    plugin.language.getString(view.stateName) ?: view.stateName
                } catch (e: Exception) { view.stateName }
                text += "\n${view.stateColor}$stateDisplayName"
            }
        } else {
            if (view.stateColor != null) {
                text = "${view.stateColor}✦&r\n$text"
            }
        }

        return text
    }

    /**
     * Determines the ARGB background color of the Text Display entity based on the NPC's attributes.
     */
    private fun determineBackgroundColor(snap: NpcSnapshot, playerUUID: UUID, configColor: List<Int>, isFocused: Boolean): Int {
        val settlementRep = snap.settlementRepMap[playerUUID] ?: 0

        val baseAlpha = configColor[0]
        val targetAlpha = if (isFocused) baseAlpha else max(20, baseAlpha - 130)

        val color = when {
            snap.isRaider -> Color.fromARGB(targetAlpha, 130, 20, 20)
            snap.customProfessionDisplay != null -> Color.fromARGB(targetAlpha, 180, 130, 25)
            snap.partyLeaderUuid == playerUUID -> Color.fromARGB(targetAlpha, 20, 130, 20)
            snap.partyLeaderUuid != null -> Color.fromARGB(targetAlpha, 20, 90, 140)
            settlementRep > 100 -> Color.fromARGB(targetAlpha, 30, 110, 30)
            settlementRep < -50 -> Color.fromARGB(targetAlpha, 120, 50, 20)
            else -> {
                when (snap.professionName) {
                    "armorer", "weaponsmith", "toolsmith", "guard" -> Color.fromARGB(targetAlpha, 80, 85, 95)
                    "cleric", "librarian", "mage" -> Color.fromARGB(targetAlpha, 100, 40, 140)
                    "farmer", "shepherd", "fisherman", "fletcher" -> Color.fromARGB(targetAlpha, 80, 120, 40)
                    "butcher", "leatherworker" -> Color.fromARGB(targetAlpha, 130, 60, 40)
                    "cartographer" -> Color.fromARGB(targetAlpha, 180, 140, 70)
                    "mason" -> Color.fromARGB(targetAlpha, 90, 100, 110)
                    "builder" -> Color.fromARGB(targetAlpha, 210, 105, 30) // Уникальный цвет фона для Builder
                    "nitwit", "none" -> Color.fromARGB(targetAlpha, 100, 130, 80)
                    else -> Color.fromARGB(targetAlpha, configColor[1], configColor[2], configColor[3])
                }
            }
        }
        return toARGBInt(color.alpha, color.red, color.green, color.blue)
    }

    /**
     * Sends the spawn and mounting packets to the player.
     */
    private fun sendSpawnPackets(player: Player, baseEntityId: Int, displayEntityId: Int) {
        val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return

        val spawnPos = com.github.retrooper.packetevents.util.Vector3d(
            player.location.x,
            player.location.y,
            player.location.z
        )

        // Spawn Text Display entity
        val spawnPacket = WrapperPlayServerSpawnEntity(
            displayEntityId,
            Optional.of(UUID.randomUUID()),
            EntityTypes.TEXT_DISPLAY,
            spawnPos,
            0f, 0f, 0f, 0, Optional.empty()
        )
        user.sendPacket(spawnPacket)

        // Mount the display on the NPC as a passenger
        val passengerPacket = WrapperPlayServerSetPassengers(
            baseEntityId,
            intArrayOf(displayEntityId)
        )
        user.sendPacket(passengerPacket)
    }

    /**
     * Updates the visuals (text, color, scaling, etc.) of the Text Display entity.
     */
    private fun sendMetadataPacket(
        player: Player, displayEntityId: Int, text: String, bgColor: Int, opacity: Byte,
        scale: PEVector3f, translation: PEVector3f, interpDuration: Int, interpDelay: Int
    ) {
        val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return
        val safeText = text.replace('§', '&')

        val component = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build()
            .deserialize(safeText)

        val metadata = listOf(
            EntityData(DisplayMeta.TEXT, EntityDataTypes.ADV_COMPONENT, component),
            EntityData(DisplayMeta.BG_COLOR, EntityDataTypes.INT, bgColor),
            EntityData(DisplayMeta.OPACITY, EntityDataTypes.BYTE, opacity),
            EntityData(DisplayMeta.SCALE, EntityDataTypes.VECTOR3F, scale),
            EntityData(DisplayMeta.TRANSLATION, EntityDataTypes.VECTOR3F, translation),
            EntityData(DisplayMeta.INTERPOLATION_DURATION, EntityDataTypes.INT, interpDuration),
            EntityData(DisplayMeta.INTERPOLATION_DELAY, EntityDataTypes.INT, interpDelay),
            EntityData(DisplayMeta.BILLBOARD, EntityDataTypes.BYTE, 3.toByte()), // 3 = Center Billboard (always faces player)
            EntityData(DisplayMeta.FLAGS, EntityDataTypes.BYTE, 1.toByte()) // 1 = Shadow (Makes it look natively vanilla!)
        )

        user.sendPacket(WrapperPlayServerEntityMetadata(displayEntityId, metadata))
    }

    /**
     * Destroys all active nametags for all players (used on disable or config reload).
     */
    private fun cleanupAll() {
        activeDisplays.forEach { (uuid, entities) ->
            val player = Bukkit.getPlayer(uuid)
            val ids = entities.values.map { it.displayEntityId }.toIntArray()
            if (player != null && player.isOnline && ids.isNotEmpty()) {
                PacketEvents.getAPI().playerManager.getUser(player)?.sendPacket(
                    WrapperPlayServerDestroyEntities(*ids)
                )
            }
        }
        activeDisplays.clear()
        initializedHumanoids.clear()
    }

    /**
     * Helper method to convert ARGB values into an Int format required by the Minecraft protocol.
     */
    private fun toARGBInt(a: Int, r: Int, g: Int, b: Int): Int {
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}
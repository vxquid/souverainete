package vx.sv.gameplay.humanoid

import com.cryptomorin.xseries.XAttribute
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.Listener
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import vx.sv.Souverainete.Companion.plugin
import vx.sv.persistent.LivingEntityExtend.hunger
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt
import com.github.retrooper.packetevents.util.Vector3f as PEVector3f

class NametagDisplayManager : Listener {

    private val activeDisplays = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, DisplayData>>()
    private val entityIdGenerator = AtomicInteger(2_000_000)

    data class DisplayData(
        val displayEntityId: Int,
        var lastFocusState: Boolean? = null,
        var lastCeilingState: Boolean? = null,
        var lastBgColor: Int? = null,
        var lastText: String = "",
        val randomYOffset: Float
    )

    data class NpcSnapshot(
        val entityId: Int,
        val uuid: UUID,
        val isCeilingLow: Boolean,
        val name: String,
        val professionName: String,
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
     * Minecraft 1.20.2 - 1.21.x TextDisplay Metadata Indices.
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
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        val config = plugin.gameplayManager.config
        if (config.nametag.enabled) {
            startSnapshotTick()
        }
    }

    companion object {
        val RAIDER_KEY = NamespacedKey(plugin, "is_raider")
    }

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

                val npcCache = mutableMapOf<Int, NpcSnapshot>()
                val dispatchMap = mutableMapOf<Player, List<PlayerViewData>>()

                val onlinePlayers = Bukkit.getOnlinePlayers().filter {
                    cfg.worlds.allowedWorlds.contains(it.world.name)
                }

                for (player in onlinePlayers) {
                    val nearbyVillagers = player.location.getNearbyEntitiesByType(Villager::class.java, viewDistance)
                    val playerEyeLoc = player.eyeLocation
                    val playerDir = playerEyeLoc.direction

                    val viewList = mutableListOf<PlayerViewData>()

                    for (npc in nearbyVillagers) {
                        val toNpcVector = npc.eyeLocation.toVector().subtract(playerEyeLoc.toVector())
                        val distSq = toNpcVector.lengthSquared()
                        if (distSq !in 0.1..viewDistanceSq) continue

                        val dist = sqrt(distSq)
                        val dot = playerDir.dot(toNpcVector.normalize())
                        val focusScore = if (dot > 0.5) (dot * 10.0) - dist else -Double.MAX_VALUE

                        val snapshot = npcCache.getOrPut(npc.entityId) {
                            val isCeilingLow = npc.location.clone().add(0.0, 2.2, 0.0).block.type.isSolid
                            val isRaider = npc.persistentDataContainer.has(RAIDER_KEY, PersistentDataType.BYTE)
                            val profName = npc.profession.key.key.lowercase()
                            val settlementData = npc.settlement?.data

                            NpcSnapshot(
                                entityId = npc.entityId,
                                uuid = npc.uniqueId,
                                isCeilingLow = isCeilingLow,
                                name = npc.customName ?: "Unknown",
                                professionName = profName,
                                level = npc.villagerLevel,
                                isRaider = isRaider,
                                settlementName = settlementData?.settlementName,
                                health = npc.health,
                                maxHealth = npc.getAttribute(XAttribute.MAX_HEALTH.get()!!)?.value ?: 20.0,
                                hungerValue = npc.hunger.toInt(),
                                partyLeaderUuid = plugin.gameplayManager.partyManager.getLeaderUUID(npc),
                                randomYOffset = (npc.uniqueId.hashCode() % 16) / 100f,
                                settlementRepMap = settlementData?.reputation?.toMap() ?: emptyMap()
                            )
                        }

                        val personalRep = plugin.gameplayManager.reputationManager.getReputationMap(npc)[player.uniqueId] ?: 0
                        val npcState = plugin.gameplayManager.reputationTracker.getNPCState(npc, player)

                        viewList.add(
                            PlayerViewData(
                                snapshot = snapshot,
                                personalRep = personalRep,
                                stateName = npcState?.translationKey, // <-- ИСПРАВЛЕНИЕ: берем ключ перевода, а не имя Enum
                                stateColor = npcState?.color,
                                focusScore = focusScore,
                                distanceSq = distSq,
                                isSingleClose = (nearbyVillagers.size == 1 && distSq < 9.0)
                            )
                        )
                    }
                    dispatchMap[player] = viewList
                }

                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    processAsyncDisplayUpdates(dispatchMap)
                })
            }
        }.runTaskTimer(plugin, 0L, updateIntervalTicks)
    }

    private fun processAsyncDisplayUpdates(dispatchMap: Map<Player, List<PlayerViewData>>) {
        val config = plugin.gameplayManager.config
        val baseConfigColor = config.nametag.backgroundColor

        for ((player, viewList) in dispatchMap) {
            if (!player.isOnline) continue

            val playerDisplays = activeDisplays.getOrPut(player.uniqueId) { ConcurrentHashMap() }
            val currentVisibleIds = mutableSetOf<Int>()

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

                // Прокидываем UUID игрока для корректного получения репутации
                val text = buildText(view, isFocused, player.uniqueId)

                val targetBgColor = determineBackgroundColor(snapshot, player.uniqueId, baseConfigColor, isFocused)

                val baseOffsetY = config.nametag.displayOffsetY.toFloat()
                val targetOffsetY = (if (snapshot.isCeilingLow) baseOffsetY - 0.45f else baseOffsetY) + snapshot.randomYOffset

                val baseScaleCfg = config.nametag.displayScale
                val targetScaleMult = if (isFocused) 1.0f else 0.65f
                val scale = PEVector3f(
                    baseScaleCfg[0] * targetScaleMult,
                    baseScaleCfg[1] * targetScaleMult,
                    baseScaleCfg[2] * targetScaleMult
                )
                val translation = PEVector3f(0f, targetOffsetY, 0f)

                val data = playerDisplays[snapshot.entityId]

                if (data == null) {
                    val newDisplayId = entityIdGenerator.incrementAndGet()
                    val newData = DisplayData(newDisplayId, randomYOffset = snapshot.randomYOffset)
                    playerDisplays[snapshot.entityId] = newData

                    sendSpawnPackets(player, snapshot.entityId, newDisplayId)
                    sendMetadataPacket(player, newDisplayId, text, targetBgColor, if (isFocused) 255.toByte() else 90.toByte(), scale, translation, 0, 0)

                    newData.lastFocusState = isFocused
                    newData.lastCeilingState = snapshot.isCeilingLow
                    newData.lastBgColor = targetBgColor
                    newData.lastText = text
                } else {
                    val focusGained = isFocused && data.lastFocusState != true
                    val visualChanged = data.lastFocusState != isFocused || data.lastCeilingState != snapshot.isCeilingLow || data.lastBgColor != targetBgColor
                    val textChanged = data.lastText != text

                    if (focusGained) {
                        sendMetadataPacket(player, data.displayEntityId, text, targetBgColor, 255.toByte(), scale, translation, 0, 0)

                        plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable {
                            if (player.isOnline && playerDisplays.contains(snapshot.entityId)) {
                                sendMetadataPacket(player, data.displayEntityId, text, targetBgColor, 255.toByte(), scale, translation, 10, 0)
                            }
                        }, 1L)
                    } else if (visualChanged || textChanged) {
                        val opacity = if (isFocused) 255.toByte() else 90.toByte()
                        sendMetadataPacket(player, data.displayEntityId, text, targetBgColor, opacity, scale, translation, 10, 0)
                    }

                    data.lastFocusState = isFocused
                    data.lastCeilingState = snapshot.isCeilingLow
                    data.lastBgColor = targetBgColor
                    data.lastText = text
                }
            }

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

    private fun buildText(view: PlayerViewData, isFocused: Boolean, playerUUID: UUID): String {
        val config = plugin.gameplayManager.config
        val snap = view.snapshot

        val settlementLine = if (snap.settlementName != null) "&6«${snap.settlementName}»\n" else ""
        val profession = if (snap.isRaider) {
            plugin.language.getString("villager-professions.raider") ?: "&cRaider"
        } else {
            (plugin.language.getString("villager-professions.${snap.professionName}") ?: "ERR").replace("_", " ").capitalizeWords()
        }

        val line1 = String.format(java.util.Locale.US, config.nametag.nameProfessionLevelTemplate, snap.name, profession, snap.level)
        var text = "$settlementLine$line1"

        if (isFocused) {
            val repManager = plugin.gameplayManager.reputationManager
            // ИСПРАВЛЕНИЕ: Теперь проверяем репутацию по UUID игрока, а не по UUID моба
            val settlementRep = snap.settlementRepMap[playerUUID] ?: 0
            val finalRepScore = view.personalRep + settlementRep
            val repStatus = repManager.getReputationStatusFromScore(finalRepScore)

            // ИСПРАВЛЕНИЕ: Форматируем строго в Locale.US, чтобы избежать запятых в дробях (20,0 -> 20.0)
            val repStr = String.format(Locale.US, config.nametag.reputationTemplate, finalRepScore, repStatus.getLocalizedName())
            val healthStr = String.format(Locale.US, config.nametag.healthTemplate, snap.health, snap.maxHealth)
            val line2 = "$repStr &7|&r $healthStr"

            text += "\n$line2"

            if (snap.hungerValue <= config.hunger.eatThreshold) {
                val statusKey = if (snap.hungerValue <= config.hunger.starvationThreshold) "starving" else "hungry"
                val status = plugin.language.getString("hunger-status.$statusKey")!!
                text += "\n${String.format(Locale.US, config.nametag.hungerTemplate, status, snap.hungerValue, config.hunger.max)}"
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

    private fun determineBackgroundColor(snap: NpcSnapshot, playerUUID: UUID, configColor: List<Int>, isFocused: Boolean): Int {
        val settlementRep = snap.settlementRepMap[playerUUID] ?: 0
        val totalRep = settlementRep

        val baseAlpha = configColor[0]
        val targetAlpha = if (isFocused) baseAlpha else max(20, baseAlpha - 130)

        val color = when {
            snap.isRaider -> Color.fromARGB(targetAlpha, 130, 20, 20)
            snap.partyLeaderUuid == playerUUID -> Color.fromARGB(targetAlpha, 20, 130, 20)
            snap.partyLeaderUuid != null -> Color.fromARGB(targetAlpha, 20, 90, 140)
            totalRep > 100 -> Color.fromARGB(targetAlpha, 30, 110, 30)
            totalRep < -50 -> Color.fromARGB(targetAlpha, 120, 50, 20)
            else -> {
                when (snap.professionName) {
                    "armorer", "weaponsmith", "toolsmith", "guard" -> Color.fromARGB(targetAlpha, 60, 65, 75)
                    "cleric", "librarian", "mage" -> Color.fromARGB(targetAlpha, 90, 30, 120)
                    "farmer", "shepherd", "fisherman", "fletcher" -> Color.fromARGB(targetAlpha, 70, 100, 30)
                    "butcher", "leatherworker" -> Color.fromARGB(targetAlpha, 100, 45, 30)
                    else -> Color.fromARGB(targetAlpha, configColor[1], configColor[2], configColor[3])
                }
            }
        }
        return toARGBInt(color.alpha, color.red, color.green, color.blue)
    }

    private fun sendSpawnPackets(player: Player, baseEntityId: Int, displayEntityId: Int) {
        val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return

        val spawnPos = com.github.retrooper.packetevents.util.Vector3d(
            player.location.x,
            player.location.y,
            player.location.z
        )

        val spawnPacket = WrapperPlayServerSpawnEntity(
            displayEntityId,
            Optional.of(UUID.randomUUID()),
            EntityTypes.TEXT_DISPLAY,
            spawnPos,
            0f, 0f, 0f, 0, Optional.empty()
        )
        user.sendPacket(spawnPacket)

        val passengerPacket = WrapperPlayServerSetPassengers(
            baseEntityId,
            intArrayOf(displayEntityId)
        )
        user.sendPacket(passengerPacket)
    }

    private fun sendMetadataPacket(
        player: Player, displayEntityId: Int, text: String, bgColor: Int, opacity: Byte,
        scale: PEVector3f, translation: PEVector3f, interpDuration: Int, interpDelay: Int
    ) {
        val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return

        // ИСПРАВЛЕНИЕ: Чтобы Kyori не сыпал варнингами из-за LegacyFormattingCodes
        // безопасно меняем все параграфы на амперсанды и скармливаем парсеру.
        val safeText = text.replace('§', '&')
        val component = LegacyComponentSerializer.legacyAmpersand().deserialize(safeText)

        val metadata = listOf(
            EntityData(DisplayMeta.TEXT, EntityDataTypes.ADV_COMPONENT, component),
            EntityData(DisplayMeta.BG_COLOR, EntityDataTypes.INT, bgColor),
            EntityData(DisplayMeta.OPACITY, EntityDataTypes.BYTE, opacity),
            EntityData(DisplayMeta.SCALE, EntityDataTypes.VECTOR3F, scale),
            EntityData(DisplayMeta.TRANSLATION, EntityDataTypes.VECTOR3F, translation),
            EntityData(DisplayMeta.INTERPOLATION_DURATION, EntityDataTypes.INT, interpDuration),
            EntityData(DisplayMeta.INTERPOLATION_DELAY, EntityDataTypes.INT, interpDelay),
            EntityData(DisplayMeta.BILLBOARD, EntityDataTypes.BYTE, 3.toByte())
        )

        user.sendPacket(WrapperPlayServerEntityMetadata(displayEntityId, metadata))
    }

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
    }

    private fun toARGBInt(a: Int, r: Int, g: Int, b: Int): Int {
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}
package vx.sv.gameplay.settlement

import com.google.gson.reflect.TypeToken
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.*
import org.bukkit.block.Bell
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.meta.CompassMeta
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.ai.base.DummyClient
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.humanoid.race.RaceManager.Race
import vx.sv.gameplay.quest.QuestManager.Companion.replaceMap
import vx.sv.gameplay.reputation.ReputationManager.Reputation
import vx.sv.gameplay.settlement.gui.SettlementMenus
import vx.sv.nms.v1_21_R7.entity.ai.construct.SettlementPlanner
import vx.sv.nms.v1_21_R7.entity.ai.construct.VanillaBuildingType
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class SettlementManager : Listener {

    private val config = plugin.gameplayManager.config.settlement
    private val repConfig = plugin.gameplayManager.config.reputation

    // Хранилище активных боссбаров для зданий по UUID игроков
    private val buildingBossBars = ConcurrentHashMap<UUID, BossBar>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun handleWorldLoad(world: World) {
        settlements[world] = mutableListOf()
        this.loadSettlements(world)

        // Восстанавливаем 3D разметку всех спланированных зданий из базы данных
        SettlementPlanner.loadBuildingsFromWorld(world)

        val worldSettlements = settlements[world] ?: emptyList()
        plugin.gameplayManager.raidManager.restoreRaidsFromData(worldSettlements)

        this.startEnteringTick(world)
        this.startSettlementDetectionTask(world)

        world.entities.filterIsInstance<Villager>().forEach { villager ->
            villager.settlement?.villagers?.add(villager)
        }
    }

    private fun startEnteringTick(world: World) {
        if (!config.trackEntry) return
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val enterMsg = plugin.language.getString("settlement.entering") ?: ""
            val leaveMsg = plugin.language.getString("settlement.leaving") ?: ""
            world.players.forEach { player ->
                handlePlayerMovement(player, world, enterMsg, leaveMsg)
            }
        }, 0L, config.movementTickInterval)
    }

    private fun handlePlayerMovement(player: Player, world: World, enterMsg: String, leaveMsg: String) {
        val playerLocationVector = player.location.toVector()
        val currentWorldSettlements = settlements[world] ?: return

        val activeSettlement = currentWorldSettlements.find { it.territory.contains(playerLocationVector) }
        val lastSettlementName = player.currentSettlement

        if (activeSettlement != null) {
            val name = activeSettlement.data.settlementName

            if (lastSettlementName == null && name != config.defaultName) {
                player.sendTitle("${config.titleColor}$name", enterMsg, config.titleFadeIn, config.titleStay, config.titleFadeOut)
                player.currentSettlement = name
            }

            sendReputationActionBar(player, activeSettlement)

            // === ЛОГИКА БОССБАРА ЗДАНИЙ ===
            // ИСПРАВЛЕНО: Теперь поиск зданий выполняется по UUID (activeSettlement.data.id)
            val buildings = SettlementPlanner.buildings[activeSettlement.data.id] ?: emptyList()
            // Проверяем, находится ли игрок внутри BoundingBox какого-либо здания
            val currentBuilding = buildings.find { it.box.contains(playerLocationVector) }

            if (currentBuilding != null) {
                val bar = buildingBossBars.getOrPut(player.uniqueId) {
                    val newBar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS)
                    player.showBossBar(newBar)
                    newBar
                }

                // Извлекаем красивое название типа здания (если не найдено — форматируем raw строку)
                val buildingEnum = VanillaBuildingType.byTypeName(currentBuilding.type)
                val displayName = buildingEnum?.displayName ?: currentBuilding.type.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }

                bar.name(Component.text("🏛 $displayName", NamedTextColor.AQUA))
            } else {
                buildingBossBars.remove(player.uniqueId)?.let { player.hideBossBar(it) }
            }

        } else {
            // Если игрок вышел из поселения
            buildingBossBars.remove(player.uniqueId)?.let { player.hideBossBar(it) }

            if (lastSettlementName != null) {
                val leavingName = currentWorldSettlements.find { it.data.settlementName == lastSettlementName }?.data?.settlementName
                    ?: lastSettlementName
                player.sendTitle("${config.titleColor}$leavingName", leaveMsg, config.titleFadeIn, config.titleStay, config.titleFadeOut)
                player.currentSettlement = null
            }
        }
    }

    private fun sendReputationActionBar(player: Player, settlement: Settlement) {
        val score = settlement.data.reputation[player.uniqueId] ?: 0
        val status = getReputationStatus(score)
        val statusName = status.getLocalizedName()

        val colorCode = when (status) {
            Reputation.EXILED, Reputation.HOSTILE -> "§c"
            Reputation.UNFRIENDLY -> "§6"
            Reputation.NEUTRAL -> "§f"
            Reputation.FRIENDLY, Reputation.HONORED -> "§a"
            Reputation.REVERED, Reputation.EXALTED -> "§b"
        }

        val message = "§7${settlement.data.settlementName}: $colorCode$statusName ($score)"
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(message))
    }

    private fun getReputationStatus(score: Int): Reputation {
        return when {
            score >= repConfig.exaltedRequired -> Reputation.EXALTED
            score >= repConfig.reveredRequired -> Reputation.REVERED
            score >= repConfig.honoredRequired -> Reputation.HONORED
            score >= repConfig.friendlyRequired -> Reputation.FRIENDLY
            score >= repConfig.neutralRequired -> Reputation.NEUTRAL
            score >= repConfig.unfriendlyRequired -> Reputation.UNFRIENDLY
            score >= repConfig.hostileRequired -> Reputation.HOSTILE
            else -> Reputation.EXILED
        }
    }

    private fun startSettlementDetectionTask(world: World) {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {

            settlements[world]?.forEach { settlement ->
                if (settlement.villagers.isNotEmpty()) {
                    val hasActiveLeader = settlement.villagers.any {
                        it.uniqueId == settlement.data.leaderId && it.isValid
                    }
                    if (!hasActiveLeader) {
                        settlement.electLeader()
                        saveSettlements(world)
                    }

                    // === АВТОМАТИЧЕСКИЙ РОСТ ПОСЕЛЕНИЯ ВО ВРЕМЕНИ ===
                    // Каждые несколько минут планировщик пытается запустить следующую постройку по приоритету.
                    // По мере завершения прошлых домов и расширения территории новые здания будут автоматически
                    // находить свободные места и ставиться в очередь строительства для жителей.
                    val planner = SettlementPlanner(settlement)
                    val success = planner.planNextPriorityBuilding()
                    if (success) {
                        plugin.logger.info("[Souverainete] Поселение ${settlement.data.settlementName} расширилось и запланировало новое здание!")
                        saveSettlements(world)
                    }
                }
            }

            val homelessVillagers = world.entities
                .filterIsInstance<Villager>()
                .filter { it.settlement == null }

            if (homelessVillagers.isEmpty()) return@Runnable

            plugin.server.scheduler.runTask(plugin, Runnable {
                processVillagerGroups(world, homelessVillagers)
            })
        }, 0L, config.detectionInterval)
    }

    private fun processVillagerGroups(world: World, villagers: List<Villager>) {
        for (villager in villagers.shuffled()) {
            if (villager.settlement != null) continue

            val potentialCitizens = getPotentialCitizens(villager)

            if (potentialCitizens.size >= config.villagersRequired) {
                val settlementCenter = findNearbyBell(world, villager.location, config.detectionDistance) ?: continue
                val dominantRace = villagers.groupingBy { it.race }.eachCount().maxByOrNull { it.value }?.key ?: Race.VILLAGER_RACE
                CompletableFuture.runAsync {
                    val newData = Settlement.SettlementData(
                        UUID.randomUUID(),
                        world.uid,
                        config.defaultName,
                        settlementCenter,
                        System.currentTimeMillis(),
                        dominantRace.name
                    )
                    this.generateSettlementName(Settlement(newData, potentialCitizens.toMutableSet()))
                }
                break
            }
        }
    }

    private fun getPotentialCitizens(origin: Villager): MutableList<Villager> {
        val nearby = origin.getNearbyEntities(config.detectionDistance, config.detectionDistance, config.detectionDistance)
            .filterIsInstance<Villager>()
            .filter { it.settlement == null }
            .toMutableList()
        nearby.add(origin)

        val world = origin.world
        settlements[world]?.forEach { settlement ->
            val attractDistance = config.detectionDistance * 1.5
            nearby.forEach { villager ->
                if (settlement.data.center.distance(villager.location) <= attractDistance) {
                    villager.settlement = settlement
                }
            }
        }
        nearby.removeIf { it.settlement != null }
        return nearby
    }

    private fun findNearbyBell(world: World, center: Location, radius: Double): Location? {
        val chunkRadius = (radius / 16).toInt()
        val centerChunk = center.chunk
        for (x in -chunkRadius..chunkRadius) {
            for (z in -chunkRadius..chunkRadius) {
                val cx = centerChunk.x + x
                val cz = centerChunk.z + z
                if (!world.isChunkLoaded(cx, cz)) continue
                val chunk = world.getChunkAt(cx, cz)
                for (tileEntity in chunk.tileEntities) {
                    if (tileEntity is Bell) {
                        if (tileEntity.location.distance(center) <= radius) return tileEntity.location
                    }
                }
            }
        }
        return null
    }

    fun generateSettlementName(settlement: Settlement) {
        val client = plugin.providerManager.client
        val raceEnum = getDominantRaceEnum(settlement)

        if (client is DummyClient) {
            pickRaceName(settlement)
            return
        }

        CompletableFuture.runAsync {
            try {
                val generator = SettlementNameGenerator(settlement, raceEnum)
                val response = client.sendPromptWithSchema(generator.prompt, SettlementData::class)
                    ?: throw IllegalStateException("Empty response from AI")

                val newName = response.settlementName.random()
                plugin.server.scheduler.runTask(plugin, Runnable {
                    applySettlementCreation(settlement, newName)
                })
            } catch (e: Exception) {
                plugin.logger.warning("AI Settlement generation failed: ${e.message}. Using racial fallback.")
                plugin.server.scheduler.runTask(plugin, Runnable {
                    pickRaceName(settlement)
                })
            }
        }
    }

    private fun pickRaceName(settlement: Settlement) {
        val dominantRace = getDominantRaceEnum(settlement)
        val possibleNames = dominantRace.settlementNames

        val usedNames = settlements[settlement.world]?.map { it.data.settlementName } ?: emptyList()
        val availableNames = possibleNames.filter { !usedNames.contains(it) }

        val finalName = availableNames.randomOrNull()
            ?: "${possibleNames.randomOrNull() ?: config.defaultName} ${usedNames.size + 1}"

        applySettlementCreation(settlement, finalName)
    }

    private fun getDominantRaceEnum(settlement: Settlement): Race {
        return settlement.villagers
            .groupingBy { it.race }
            .eachCount()
            .maxByOrNull { it.value }?.key
            ?: Race.VILLAGER_RACE
    }

    private fun applySettlementCreation(settlement: Settlement, name: String) {
        settlement.data.settlementName = name
        settlement.villagers.forEach { it.settlement = settlement }

        settlement.electLeader()

        val world = settlement.world
        val worldSettlements = settlements.computeIfAbsent(world) { mutableListOf() }

        worldSettlements.add(settlement)
        saveSettlements(world)

        if (config.broadcastCreation) {
            val message = plugin.language.getString("settlement.created")?.replace("{settlementName}", name)
            if (!message.isNullOrEmpty()) world.players.forEach { it.sendMessage(message) }
        }
        plugin.logger.info("New settlement founded: $name (Dominant Race: ${getDominantRaceEnum(settlement).name})")
    }

    private fun loadSettlements(world: World) {
        val rawJson = world.persistentDataContainer.get(settlementsWorldKey, PersistentDataType.STRING) ?: return
        val typeToken = object : TypeToken<List<Settlement.SettlementData>>() {}.type
        try {
            val loadedData: List<Settlement.SettlementData>? = gson.fromJson(rawJson, typeToken)
            loadedData?.forEach { data ->
                settlements.getOrPut(world) { mutableListOf() }.add(Settlement(data))
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load settlements: ${e.message}")
        }
    }

    @EventHandler
    private fun onChunkLoad(event: ChunkLoadEvent) {
        event.chunk.entities.filterIsInstance<Villager>().forEach { villager ->
            villager.settlement?.villagers?.add(villager)
        }
    }

    @EventHandler
    fun onBellInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type != Material.BELL) return

        val player = event.player
        val world = block.world
        val blockVector = block.location.toVector()

        val worldSettlements = settlements[world] ?: return
        val settlement = worldSettlements.find { it.territory.contains(blockVector) } ?: return

        val itemInHand = event.item
        if (itemInHand != null && itemInHand.type == Material.COMPASS) {
            event.isCancelled = true

            val meta = itemInHand.itemMeta as CompassMeta
            meta.lodestone = settlement.data.center
            meta.isLodestoneTracked = false
            meta.setDisplayName("§6Compass: ${settlement.data.settlementName}")
            meta.lore = listOf("§7Points to the heart of ${settlement.data.settlementName}.")
            itemInHand.itemMeta = meta

            player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.0f)

            val msg = plugin.language.getString("settlement.compass-attuned")?.replace("{settlementName}", settlement.data.settlementName)
                ?: "§aCompass attuned to ${settlement.data.settlementName}!"
            player.sendFormattedMessage(msg)
            return
        }

        event.isCancelled = true
        SettlementMenus.openMainMenu(player, settlement)
    }

    data class SettlementData(val settlementName: List<String>)

    class SettlementNameGenerator(settlement: Settlement, race: Race) {
        private val basePrompt = """
            Answer only in JSON. Generate 5 creative settlement names.
            Schema: {"settlementName":["Name1", "Name2", ...]}
            
            Context:
            - Dominant Race: {dominantRace} (CRITICAL: The names must strictly reflect the language, culture, and lore of this race).
            - Biome: {currentBiome}
            - Setting: {setting}
            - Naming Style: {namingStyle}
            
            Avoid existing names: {existingNames}.
        """.trimIndent()

        private val existingNames = settlements[settlement.world]
            ?.joinToString(", ") { it.data.settlementName } ?: "NONE"

        private val biomeName = settlement.world.getBiome(settlement.data.center).key.key.replace("_", " ").lowercase()

        val prompt = basePrompt.replaceMap(mapOf(
            "dominantRace" to race.name,
            "currentBiome" to biomeName,
            "setting" to plugin.providerManager.config.setting,
            "namingStyle" to plugin.providerManager.config.namingStyle,
            "existingNames" to existingNames.ifEmpty { "NONE" }
        ))
    }

    companion object {
        val settlements: MutableMap<World, MutableList<Settlement>> = mutableMapOf()
        val settlementsWorldKey = NamespacedKey(plugin, "SettlementList")
        val currentSettlementKey = NamespacedKey(plugin, "CurrentSettlement")

        fun getDominantRace(settlement: Settlement): String {
            return settlement.data.dominantRace
        }

        fun saveSettlements(world: World) {
            val data = settlements[world]?.map { it.data } ?: return
            world.persistentDataContainer.set(settlementsWorldKey, PersistentDataType.STRING, gson.toJson(data))

            // Нативное сохранение 3D разметки зданий при сохранении мира
            SettlementPlanner.saveBuildingsToWorld(world)
        }

        fun getById(id: UUID): Settlement? {
            return settlements.values.flatten().find { it.data.id == id }
        }

        fun getByName(name: String): Settlement? {
            return settlements.values.flatten().find { it.data.settlementName == name }
        }

        fun getRelation(settlementA: Settlement, settlementB: Settlement): Settlement.RelationLevel {
            return settlementA.data.relations[settlementB.data.id] ?: Settlement.RelationLevel.NEUTRAL
        }

        fun setRelation(settlementA: Settlement, settlementB: Settlement, level: Settlement.RelationLevel) {
            if (settlementA.data.id == settlementB.data.id) return

            if (level == Settlement.RelationLevel.NEUTRAL) {
                settlementA.data.relations.remove(settlementB.data.id)
                settlementB.data.relations.remove(settlementA.data.id)
            } else {
                settlementA.data.relations[settlementB.data.id] = level
                settlementB.data.relations[settlementA.data.id] = level
            }

            saveSettlements(settlementA.world)
            if (settlementA.world != settlementB.world) {
                saveSettlements(settlementB.world)
            }
        }

        /**
         * Records a diplomatic event between two settlements.
         * Keeps only the last 5 events to save memory and AI token limits.
         */
        fun recordDiplomaticEvent(settlementA: Settlement, settlementB: Settlement, event: String) {
            val maxRecords = 5

            // Initialize history if it's missing (backward compatibility with old saves)
            if (settlementA.data.diplomaticHistory == null) settlementA.data.diplomaticHistory = mutableMapOf()
            if (settlementB.data.diplomaticHistory == null) settlementB.data.diplomaticHistory = mutableMapOf()

            // Record history for settlement A
            val historyA = settlementA.data.diplomaticHistory!!.getOrPut(settlementB.data.id) { mutableListOf() }
            historyA.add(event)
            if (historyA.size > maxRecords) historyA.removeAt(0)

            // Record history for settlement B
            val historyB = settlementB.data.diplomaticHistory!!.getOrPut(settlementA.data.id) { mutableListOf() }
            historyB.add(event)
            if (historyB.size > maxRecords) historyB.removeAt(0)

            saveSettlements(settlementA.world)
            if (settlementA.world != settlementB.world) {
                saveSettlements(settlementB.world)
            }
        }

        var Player.currentSettlement: String?
            get() = this.persistentDataContainer.get(currentSettlementKey, PersistentDataType.STRING)
            set(value) {
                if (value != null) this.persistentDataContainer.set(currentSettlementKey, PersistentDataType.STRING, value)
                else this.persistentDataContainer.remove(currentSettlementKey)
            }
    }
}
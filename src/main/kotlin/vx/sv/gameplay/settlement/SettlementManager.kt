package vx.sv.gameplay.settlement

import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Bell
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.ai.base.DummyClient
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.humanoid.race.RaceManager.Race
import vx.sv.gameplay.quest.QuestManager.Companion.replaceMap
import vx.sv.gameplay.reputation.ReputationManager.Reputation
import vx.sv.gameplay.settlement.gui.SettlementMenus
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.*
import java.util.concurrent.CompletableFuture

class SettlementManager : Listener {

    private val config = plugin.gameplayManager.config.settlement
    private val repConfig = plugin.gameplayManager.config.reputation

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun handleWorldLoad(world: World) {
        settlements[world] = mutableListOf()
        this.loadSettlements(world)
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
        val playerLocation = player.location.toVector()
        val currentWorldSettlements = settlements[world] ?: return

        // Находим поселение, в территории которого находится игрок
        val activeSettlement = currentWorldSettlements.find { it.territory.contains(playerLocation) }
        val lastSettlementName = player.currentSettlement

        if (activeSettlement != null) {
            val name = activeSettlement.data.settlementName

            // 1. Логика Titile (Вход в регион) - срабатывает один раз при входе
            if (lastSettlementName == null && name != config.defaultName) {
                player.sendTitle("${config.titleColor}$name", enterMsg, config.titleFadeIn, config.titleStay, config.titleFadeOut)
                player.currentSettlement = name
            }

            // 2. Логика Action Bar (Статус репутации) - обновляется каждый тик проверки (раз в 2 сек)
            sendReputationActionBar(player, activeSettlement)
        }

        // Логика выхода из региона
        if (activeSettlement == null && lastSettlementName != null) {
            val leavingName = currentWorldSettlements.find { it.data.settlementName == lastSettlementName }?.data?.settlementName
                ?: lastSettlementName
            player.sendTitle("${config.titleColor}$leavingName", leaveMsg, config.titleFadeIn, config.titleStay, config.titleFadeOut)
            player.currentSettlement = null
        }
    }

    private fun sendReputationActionBar(player: Player, settlement: Settlement) {
        val score = settlement.data.reputation[player.uniqueId] ?: 0
        val status = getReputationStatus(score)
        val statusName = status.getLocalizedName()

        // Подбираем цвет в зависимости от отношений
        val colorCode = when (status) {
            Reputation.EXILED, Reputation.HOSTILE -> "§c"      // Красный
            Reputation.UNFRIENDLY -> "§6"                      // Золотой/Оранжевый
            Reputation.NEUTRAL -> "§f"                         // Белый
            Reputation.FRIENDLY, Reputation.HONORED -> "§a"    // Зеленый
            Reputation.REVERED, Reputation.EXALTED -> "§b"     // Голубой
        }

        // Формируем строку: "Название: Статус (Очки)"
        // Например: "Outpost: Hostile (-600)"
        val message = "§7${settlement.data.settlementName}: $colorCode$statusName ($score)"

        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(message))
    }

    // Дублируем логику порогов, так как ReputationManager требует Entity, а у нас тут сырой Int
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

                CompletableFuture.runAsync {
                    val newData = Settlement.SettlementData(
                        UUID.randomUUID(),
                        world.uid,
                        config.defaultName,
                        settlementCenter,
                        System.currentTimeMillis()
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

        if (client is DummyClient) {
            pickRaceName(settlement)
            return
        }

        CompletableFuture.runAsync {
            try {
                val generator = SettlementNameGenerator(settlement)
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
        val dominantRace = getDominantRace(settlement)
        val possibleNames = dominantRace.settlementNames

        val usedNames = settlements[settlement.world]?.map { it.data.settlementName } ?: emptyList()
        val availableNames = possibleNames.filter { !usedNames.contains(it) }

        val finalName = availableNames.randomOrNull()
            ?: "${possibleNames.randomOrNull() ?: config.defaultName} ${usedNames.size + 1}"

        applySettlementCreation(settlement, finalName)
    }

    private fun getDominantRace(settlement: Settlement): Race {
        return settlement.villagers
            .groupingBy { it.race }
            .eachCount()
            .maxByOrNull { it.value }?.key
            ?: Race.VILLAGER_RACE
    }

    private fun applySettlementCreation(settlement: Settlement, name: String) {
        settlement.data.settlementName = name
        settlement.villagers.forEach { it.settlement = settlement }

        val world = settlement.world
        val worldSettlements = settlements.computeIfAbsent(world) { mutableListOf() }

        worldSettlements.add(settlement)
        saveSettlements(world)

        if (config.broadcastCreation) {
            val message = plugin.language.getString("settlement.created")?.replace("{settlementName}", name)
            if (!message.isNullOrEmpty()) world.players.forEach { it.sendMessage(message) }
        }
        plugin.logger.info("New settlement founded: $name (Dominant Race: ${getDominantRace(settlement).name})")
    }

    private fun saveSettlements(world: World) {
        val data = settlements[world]?.map { it.data } ?: return
        world.persistentDataContainer.set(settlementsWorldKey, PersistentDataType.STRING, gson.toJson(data))
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
        val settlementName = player.currentSettlement ?: return
        val settlement = getByName(settlementName) ?: return

        val center = settlement.data.center
        if (center.blockX != block.x || center.blockY != block.y || center.blockZ != block.z) {
            return
        }

        event.isCancelled = true
        SettlementMenus.openMainMenu(player, settlement)
    }

    data class SettlementData(val settlementName: List<String>)

    class SettlementNameGenerator(settlement: Settlement) {
        private val basePrompt = """
            Answer only in JSON. Generate 5 creative settlement names.
            Schema: {"settlementName": ["Name1", "Name2", ...]}
            Biome: {currentBiome}, Setting: {setting}, Style: {namingStyle}.
            Avoid: {existingNames}.
        """.trimIndent()

        private val existingNames = settlements[settlement.world]
            ?.joinToString(", ") { it.data.settlementName } ?: "NONE"

        private val biomeName = settlement.world.getBiome(settlement.data.center).key.key.replace("_", " ").lowercase()

        val prompt = basePrompt.replaceMap(mapOf(
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

        /**
         * Find settlement by its unique UUID. Primary method.
         */
        fun getById(id: UUID): Settlement? {
            return settlements.values.flatten().find { it.data.id == id }
        }

        /**
         * Find settlement by its name. Used as a fallback for legacy data
         * and possibly for command-based searches.
         */
        fun getByName(name: String): Settlement? {
            return settlements.values.flatten().find { it.data.settlementName == name }
        }

        /**
         * Action Bar info: Current settlement name the player is standing in.
         */
        var Player.currentSettlement: String?
            get() = this.persistentDataContainer.get(currentSettlementKey, PersistentDataType.STRING)
            set(value) {
                if (value != null) this.persistentDataContainer.set(currentSettlementKey, PersistentDataType.STRING, value)
                else this.persistentDataContainer.remove(currentSettlementKey)
            }
    }

}
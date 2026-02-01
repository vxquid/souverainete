package vx.sv.gameplay.settlement

import com.google.gson.reflect.TypeToken
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
import vx.sv.gameplay.settlement.gui.SettlementMenus
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.concurrent.CompletableFuture

class SettlementManager : Listener {

    private val config = plugin.gameplayManager.config.settlement

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
        val activeSettlement = currentWorldSettlements.find { it.territory.contains(playerLocation) }
        val lastSettlementName = player.currentSettlement

        if (activeSettlement != null) {
            val name = activeSettlement.data.settlementName
            if (lastSettlementName == null && name != config.defaultName) {
                player.sendTitle("${config.titleColor}$name", enterMsg, config.titleFadeIn, config.titleStay, config.titleFadeOut)
                player.currentSettlement = name
            }
        }

        if (activeSettlement == null && lastSettlementName != null) {
            val leavingName = currentWorldSettlements.find { it.data.settlementName == lastSettlementName }?.data?.settlementName
                ?: lastSettlementName
            player.sendTitle("${config.titleColor}$leavingName", leaveMsg, config.titleFadeIn, config.titleStay, config.titleFadeOut)
            player.currentSettlement = null
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

        // LOGIC FOR DUMMY CLIENT (OR FALLBACK)
        if (client is DummyClient) {
            pickRaceName(settlement)
            return
        }

        // LOGIC FOR AI CLIENT
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

        // Filter out names already used in this world
        val usedNames = settlements[settlement.world]?.map { it.data.settlementName } ?: emptyList()
        val availableNames = possibleNames.filter { !usedNames.contains(it) }

        // Pick one, or append a number if we ran out of unique names
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

        // 1. Быстрая проверка через PDC. Если игрок не "внутри" поселения — досвидули.
        val settlementName = player.currentSettlement ?: return

        // 2. Достаем объект поселения по имени
        // (Твой getByName сейчас перебирает список, но это всё равно быстрее,
        // чем считать дистанции до каждого города).
        val settlement = SettlementManager.getByName(settlementName) ?: return

        // 3. ПРОВЕРКА ВАЛИДНОСТИ КОЛОКОЛА
        // Мы должны убедиться, что кликнутый колокол — это РЕАЛЬНО центр этого поселения.
        // Иначе игроки будут ставить свои колокола и открывать меню города где попало.
        val center = settlement.data.center

        // Сравниваем координаты блока.
        if (center.blockX != block.x || center.blockY != block.y || center.blockZ != block.z) {
            // Можно добавить сообщение: "Этот колокол не является административным центром."
            return
        }

        // 4. Всё совпало — открываем
        event.isCancelled = true // Не даем колоколу звенеть (опционально)
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

        fun getByName(name: String): Settlement? {
            return settlements.values.flatten().find { it.data.settlementName == name }
        }

        var Player.currentSettlement: String?
            get() = this.persistentDataContainer.get(currentSettlementKey, PersistentDataType.STRING)
            set(value) {
                if (value != null) this.persistentDataContainer.set(currentSettlementKey, PersistentDataType.STRING, value)
                else this.persistentDataContainer.remove(currentSettlementKey)
            }
    }
}
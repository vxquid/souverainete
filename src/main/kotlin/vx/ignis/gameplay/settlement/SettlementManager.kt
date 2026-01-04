package vx.ignis.gameplay.settlement

import com.google.gson.reflect.TypeToken
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Bell
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.quest.QuestManager.Companion.replaceMap
import vx.ignis.persistent.LivingEntityExtend.settlement
import java.util.concurrent.CompletableFuture

class SettlementManager : Listener {

    private val config = plugin.gameplayManager.config.settlement

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /**
     * Инициализирует логику поселений для конкретного мира.
     * Загружает данные, запускает таски и привязывает жителей.
     */
    fun handleWorldLoad(world: World) {
        settlements[world] = mutableListOf()
        this.loadSettlements(world)
        this.startEnteringTick(world)
        this.startSettlementDetectionTask(world)

        // Связываем уже существующих жителей с их поселениями (на случай перезагрузки)
        world.entities.filterIsInstance<Villager>().forEach { villager ->
            villager.settlement?.villagers?.add(villager)
        }
    }

    /**
     * Запускает периодическую проверку положения игроков относительно поселений.
     * Отправляет Title при входе и выходе из зоны поселения.
     */
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

        // Поиск поселения, в котором находится игрок
        val activeSettlement = currentWorldSettlements.find { it.territory.contains(playerLocation) }
        val lastSettlementName = player.currentSettlement

        // Логика входа
        if (activeSettlement != null) {
            val name = activeSettlement.data.settlementName
            if (lastSettlementName == null && name != config.defaultName) {
                player.sendTitle("${config.titleColor}$name", enterMsg, config.titleFadeIn, config.titleStay, config.titleFadeOut)
                player.currentSettlement = name
            }
        }

        // Логика выхода
        if (activeSettlement == null && lastSettlementName != null) {
            // Проверяем, существует ли все еще это поселение (на случай удаления)
            val leavingName = currentWorldSettlements.find { it.data.settlementName == lastSettlementName }?.data?.settlementName
                ?: lastSettlementName

            player.sendTitle("${config.titleColor}$leavingName", leaveMsg, config.titleFadeIn, config.titleStay, config.titleFadeOut)
            player.currentSettlement = null
        }
    }

    /**
     * Запускает фоновую задачу по поиску новых поселений.
     * Ищет группы бездомных жителей, затем ищет колокол и, при успехе, генерирует поселение.
     */
    private fun startSettlementDetectionTask(world: World) {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            // Фильтруем жителей: только Villager и только без поселения
            val homelessVillagers = world.entities
                .filterIsInstance<Villager>()
                .filter { it.settlement == null }

            if (homelessVillagers.isEmpty()) return@Runnable

            // Обработку конкретных жителей переносим в следующий тик, чтобы не нагружать основной поток
            plugin.server.scheduler.runTask(plugin, Runnable {
                processVillagerGroups(world, homelessVillagers)
            })

        }, 0L, config.detectionInterval)
    }

    private fun processVillagerGroups(world: World, villagers: List<Villager>) {
        for (villager in villagers.shuffled()) {
            if (villager.settlement != null) continue // Мог получить прописку в предыдущей итерации

            val potentialCitizens = getPotentialCitizens(villager)

            // Проверка: достаточно ли жителей для основания
            if (potentialCitizens.size >= config.villagersRequired) {
                // Пытаемся найти центр (колокол)
                val settlementCenter = findNearbyBell(world, villager.location, config.detectionDistance) ?: continue

                // Асинхронно генерируем имя и создаем поселение
                CompletableFuture.runAsync {
                    val newData = Settlement.SettlementData(
                        world.uid,
                        config.defaultName,
                        settlementCenter,
                        System.currentTimeMillis()
                    )
                    this.generateSettlementName(Settlement(newData, potentialCitizens.toMutableSet()))
                }

                // Прерываем цикл после успешного нахождения группы, чтобы не создавать несколько поселений в одном месте за раз
                break
            }
        }
    }

    /**
     * Собирает жителей вокруг цели, которые еще не имеют поселения.
     * Привязывает их к ближайшим существующим поселениям, если таковые есть.
     * Возвращает список жителей, оставшихся бездомными (кандидаты на новое поселение).
     */
    private fun getPotentialCitizens(origin: Villager): MutableList<Villager> {
        val nearby = origin.getNearbyEntities(config.detectionDistance, config.detectionDistance, config.detectionDistance)
            .filterIsInstance<Villager>()
            .filter { it.settlement == null }
            .toMutableList()

        nearby.add(origin)

        // Проверяем, не находятся ли они уже на территории другого поселения
        val world = origin.world
        settlements[world]?.forEach { settlement ->
            // Используем расширенный радиус для "примагничивания" к существующим
            val attractDistance = config.detectionDistance * 1.5
            nearby.forEach { villager ->
                if (settlement.data.center.distance(villager.location) <= attractDistance) {
                    villager.settlement = settlement
                }
            }
        }

        // Оставляем только тех, кто все еще ничей
        nearby.removeIf { it.settlement != null }
        return nearby
    }

    /**
     * Ищет блок колокола (Bell) в загруженных чанках вокруг локации.
     */
    private fun findNearbyBell(world: World, center: Location, radius: Double): Location? {
        val chunkRadius = (radius / 16).toInt()
        val centerChunk = center.chunk

        for (x in -chunkRadius..chunkRadius) {
            for (z in -chunkRadius..chunkRadius) {
                val cx = centerChunk.x + x
                val cz = centerChunk.z + z

                // Работаем только с загруженными чанками во избежание лагов
                if (!world.isChunkLoaded(cx, cz)) continue

                val chunk = world.getChunkAt(cx, cz)
                for (tileEntity in chunk.tileEntities) {
                    if (tileEntity is Bell) {
                        if (tileEntity.location.distance(center) <= radius) {
                            return tileEntity.location
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Генерирует название поселения через AI и сохраняет данные.
     */
    fun generateSettlementName(settlement: Settlement) {
        try {
            val generator = SettlementNameGenerator(settlement)
            val response = plugin.providerManager.client.sendPromptWithSchema(generator.prompt, SettlementData::class)
                ?: throw IllegalStateException("Empty response from AI provider")

            val newName = response.settlementName.random()

            // Возвращаемся в основной поток для применения изменений в Bukkit API
            plugin.server.scheduler.runTask(plugin, Runnable {
                applySettlementCreation(settlement, newName)
            })

        } catch (e: Exception) {
            plugin.logger.warning("Failed to generate settlement name: ${e.message}")
            // Можно добавить фоллбэк на дефолтное имя, если генерация упала
        }
    }

    private fun applySettlementCreation(settlement: Settlement, name: String) {
        settlement.data.settlementName = name
        settlement.villagers.forEach { it.settlement = settlement }

        val world = settlement.world
        val worldSettlements = settlements.computeIfAbsent(world) { mutableListOf() }

        worldSettlements.add(settlement)
        saveSettlements(world)

        if (config.broadcastCreation) {
            val message = plugin.language.getString("settlement.created")
                ?.replace("{settlementName}", name)

            if (!message.isNullOrEmpty()) {
                world.players.forEach { it.sendMessage(message) }
            }
        }
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
            plugin.logger.severe("Failed to load settlements for world ${world.name}: ${e.message}")
        }
    }

    @EventHandler
    private fun onChunkLoad(event: ChunkLoadEvent) {
        // При загрузке чанка проверяем жителей, не принадлежат ли они уже загруженным поселениям
        event.chunk.entities.filterIsInstance<Villager>().forEach { villager ->
            // Если у жителя уже есть данные о поселении, обновляем список в объекте поселения
            villager.settlement?.villagers?.add(villager)
        }
    }

    // --- Вспомогательные классы и объекты ---

    data class SettlementData(val settlementName: List<String>)

    class SettlementNameGenerator(settlement: Settlement) {
        // Вынесено в константу или конфиг для удобства
        private val basePrompt = """
            Answer only in JSON format, without unnecessary text. 
            Generate settlement names using the following JSON scheme: 
            `settlementName` — string array, five short but creative settlement names.
            Current biome: {currentBiome}, setting: {setting}, naming style: {namingStyle}.
            {settlementInfo}
            Avoid these names: {existingNames}.
        """.trimIndent()

        private val existingNames = settlements[settlement.world]
            ?.joinToString(", ") { it.data.settlementName }
            ?: "NONE"

        private val biomeName = settlement.world.getBiome(settlement.data.center).key.key
            .replace("_", " ")
            .lowercase()

        private val placeholders = mapOf(
            "currentBiome" to biomeName,
            "setting" to plugin.providerManager.config.setting,
            "namingStyle" to plugin.providerManager.config.namingStyle,
            "existingNames" to existingNames.ifEmpty { "NONE" },
            "settlementInfo" to "Generate creative names based on details."
        )

        val prompt = basePrompt.replaceMap(placeholders)
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
                if (value != null) {
                    this.persistentDataContainer.set(currentSettlementKey, PersistentDataType.STRING, value)
                } else {
                    this.persistentDataContainer.remove(currentSettlementKey)
                }
            }
    }

}
package vx.ignis.gameplay.settlement

import com.google.gson.reflect.TypeToken
import org.bukkit.NamespacedKey
import org.bukkit.World
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

class SettlementManager: Listener {

    private val defaultSettlementName    = "Default Settlement Name"
    private val settlementTitleNameColor = "§6"
    private val detectionDistance        = 128.0
    private val villagersRequired        = 5

    fun handleWorldLoad(world: World) {

        settlements[world] = mutableListOf()
        this.loadSettlements(world)
        this.startEnteringTick(world)
        this.startSettlementDetectionTask(world)

        // На всякий случай. Лол.
        world.entities.filterIsInstance<Villager>().forEach { villager ->
            villager.settlement?.villagers?.add(villager)
        }
    }

    private fun startEnteringTick(world: World) {

        // Check settlement tracking display.
        if (!plugin.gameplayManager.config.trackPlayerSettlementEntry) return

        plugin.server.scheduler.runTaskTimer(plugin, { _ ->

            val enterMessage = plugin.language.getString("settlement-entering.entering") ?: ""
            val leaveMessage = plugin.language.getString("settlement-entering.leaving") ?: ""

            world.players.forEach { player ->
                val settlement = settlements[world]!!.find { it.territory.contains(player.location.toVector()) }

                if (settlement != null && player.currentSettlement == null && settlement.data.settlementName != "Default Settlement Name") {
                    val name = settlement.data.settlementName
                    player.sendTitle("$settlementTitleNameColor$name", enterMessage, 20, 40, 20)
                    player.currentSettlement = name
                }

                if (settlement == null && player.currentSettlement != null) {
                    settlements[world]!!.find { it.data.settlementName == player.currentSettlement }
                        ?.let {
                            player.sendTitle("$settlementTitleNameColor${it.data.settlementName}", leaveMessage, 20, 40, 20)
                            player.currentSettlement = null
                        }
                }
            }

        }, 0, 40)
    }

    // Таск, в котором происходит поиск жителей, которые подохдят для создания сетлментов.
    private fun startSettlementDetectionTask(world: World) {

        plugin.server.scheduler.runTaskTimer(plugin, { _ ->

            // Проходимся по всем жителям в мире, которые нигде не "прописаны".
            world.entities.filterIsInstance<Villager>().filter { it.settlement == null }.let { villagers ->

                plugin.server.scheduler.runTask(plugin) { _ ->
                    for (villager in villagers.shuffled()) {

                        // Создаём поселение только с теми жителями, которые нигде не "прописаны"
                        val villagersAround = villager.getNearbyEntities(detectionDistance, detectionDistance, detectionDistance)
                            .filterIsInstance<Villager>()
                            .filter { it.settlement == null }
                            .toMutableList()

                        // Добавляем итерируемого жителя в список
                        villagersAround.add(villager)

                        // Если найденные бездомные находятся на территории поселения (или недалеко от него), то автоматически прописываем их
                        settlements[world]?.forEach { settlement ->
                            villagersAround.forEach {
                                if (settlement.data.center.distance(it.location) <= detectionDistance + detectionDistance / 2) {
                                    it.settlement = settlement
                                }
                            }
                        }

                        // На всякий случай чистим тех, кто мог получить прописку
                        villagersAround.removeIf { it.settlement != null }

                        // Создание поселения моментально, но название будет сгенерировано чуть позже. Возможно, что работа с локациями не может происходить вне тика сервера.
                        if (villagersAround.size >= villagersRequired) {
                            plugin.server.scheduler.runTaskAsynchronously(plugin,
                                { _ ->
                                    this.generateSettlementName(Settlement(Settlement.SettlementData(world.uid, defaultSettlementName, villager.location, System.currentTimeMillis()), villagers.toMutableSet()))
                                }
                            )

                            break
                        }

                    }
                }

            }

        }, 0, 200)
    }

    data class SettlementData(val settlementName: List<String>)

    class SettlementNameGenerator(private val settlement: Settlement) {

        private val settlementInfo = "Generate creative names for the settlement based on the provided details."

        private val settlementPrompt = "Answer only in JSON format, without unnecessary text, make sure it will be JSON parseable. Generate settlement names using the following JSON scheme: " +
                "`settlementName` — string array, five short but creative settlement names, must differ from each other. " +
                "The writing style must be strictly tailored in the following order: global setting, current biome, naming style. Start with a neutral description — it'll be easier for you to navigate that way. Don't shorten the names — we don't want scraps of phrases, right? Select the most important words (like biome features) with bold Markdown if applicable. Select interesting parts with italic Markdown if applicable. All content must be written in a narrative style to enhance immersion and believability. " +
                "The following is the information about the settlement: current biome is {currentBiome}, setting is {setting}, naming style is {namingStyle}. {settlementInfo} " +
                "Avoid these existing settlement names: {existingNames}."

        private val existingSettlementNames = settlements[settlement.world]?.joinToString(", ") { it.data.settlementName } ?: ""

        private val placeholders = mutableMapOf<String, String>().also {
            it["currentBiome"] = settlement.world.getBiome(settlement.data.center).toString().replace("_", " ").lowercase().split(":").getOrNull(1) ?: settlement.world.getBiome(settlement.data.center).toString().replace("_", " ").lowercase()
            it["setting"] = plugin.providerManager.config.setting
            it["namingStyle"] = plugin.providerManager.config.namingStyle
            it["existingNames"] = existingSettlementNames.ifEmpty { "NONE" }
            it["settlementInfo"] = settlementInfo
        }

        val prompt = settlementPrompt.replaceMap(placeholders)
    }

    fun generateSettlementName(settlement: Settlement) {
        class SettlementNameGenerationException : Exception("Error during settlement name generation!")
        val generator = SettlementNameGenerator(settlement)

        plugin.providerManager.client.sendPromptWithSchema(generator.prompt, SettlementData::class)?.let { data ->
            settlement.data.settlementName = data.settlementName.random()
            settlement.villagers.forEach { villager -> villager.settlement = settlement }
            val worldSettlements = settlements[settlement.world] ?: throw NullPointerException("Missing settlement list in world ${settlement.world.name}!")
            worldSettlements.add(settlement)
            settlement.world.persistentDataContainer.set(settlementsWorldKey, PersistentDataType.STRING, gson.toJson(worldSettlements.map { it.data }))
        } ?: throw SettlementNameGenerationException()
    }

    private fun loadSettlements(world: World) {
        world.persistentDataContainer.get(settlementsWorldKey, PersistentDataType.STRING)?.let { serializedSettlements ->
            gson.fromJson(serializedSettlements, object : TypeToken<List<Settlement.SettlementData>>() {})?.let { list ->
                list.forEach { settlementData ->
                    settlements[world]?.add(Settlement(settlementData))
                }
            }
        }
    }

    @EventHandler
    private fun onChunkLoad(event: ChunkLoadEvent) {
        event.chunk.entities.filterIsInstance<Villager>().forEach { villager ->
            villager.settlement?.villagers?.add(villager)
        }
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    companion object {

        val settlements: MutableMap<World, MutableList<Settlement>> = mutableMapOf()

        val settlementsWorldKey  = NamespacedKey(plugin, "SettlementList")
        val currentSettlementKey = NamespacedKey(plugin, "CurrentSettlement")

        fun getByName(name: String): Settlement? {
            settlements.values.forEach { settlementList ->
                settlementList.forEach { settlement ->
                    if (settlement.data.settlementName == name) {
                        return settlement
                    }
                }
            }
            return null
        }

        var Player.currentSettlement: String?
            get() = this.persistentDataContainer.get(currentSettlementKey, PersistentDataType.STRING)
            set(value) {
                if (value != null) {
                    this.persistentDataContainer.set(currentSettlementKey, PersistentDataType.STRING, value)
                } else this.persistentDataContainer.remove(currentSettlementKey)
            }
    }

}
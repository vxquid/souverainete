package vx.ignis.gameplay.quest

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.sendFormattedMessage
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.talk
import vx.ignis.gameplay.event.MerchantTradeEvent
import vx.ignis.gameplay.event.PlayerAcceptQuestEvent
import vx.ignis.gameplay.event.QuestInvalidationEvent
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import vx.ignis.gameplay.personality.PersonalityManager.Companion.gender
import vx.ignis.gameplay.personality.PersonalityManager.Companion.getCharacterData
import vx.ignis.gameplay.personality.PersonalityManager.Companion.getPersonality
import vx.ignis.gameplay.quest.ProgressTracker.Companion.experienceEarnedByQuests
import vx.ignis.gameplay.quest.ProgressTracker.Companion.questTracker
import vx.ignis.gameplay.quest.ProgressTracker.Companion.questsCompleted
import vx.ignis.gameplay.quest.ProgressTracker.Companion.questsFailed
import vx.ignis.gameplay.quest.QuestManager.Quest.QuestItem
import vx.ignis.gameplay.quest.pragma.QuestItemStrategy
import vx.ignis.gameplay.quest.pragma.strategy.MusicDiscQuestItemStrategy
import vx.ignis.gameplay.quest.pragma.strategy.ProfessionItemGatheringQuestItemStrategy
import vx.ignis.gameplay.reputation.ReputationManager.Companion.reputationOf
import vx.ignis.gameplay.reputation.ReputationManager.Reputation
import vx.ignis.gameplay.trade.ScoreCalculator.calculateScore
import vx.ignis.persistent.LivingEntityExtend.questDataKey
import vx.ignis.persistent.LivingEntityExtend.quests
import vx.ignis.persistent.VillagerExtend.professionLevelName
import java.util.*

class QuestManager : Listener {

    val progressTracker = ProgressTracker()

    // TODO; Некоторые из этих значений должны быть в конфиге.
    private val questLifetimeDuration      = 96000
    private val questIntervalTicks         = 400L
    private val reputationPriceMultiplier  = 0.05
    private val experienceMultiplierPlayer = 0.05
    private val experienceMultiplierNPC    = 0.0025

    // ID квестов помогает отслеживать актуальные квесты и те, которые уже не имеют смысла.
    val questCountKey = NamespacedKey(plugin, "TotalQuestCount")
    val totalQuestAmount: Long
        get() = Bukkit.getWorlds()[0]!!.persistentDataContainer.get(questCountKey, PersistentDataType.LONG) ?: 0

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, { _ -> tick() }, 0, questIntervalTicks)
    }

    fun tick() {

        val world = plugin.gameplayManager.allowedWorlds.filter { it.entities.filterIsInstance<Villager>().isNotEmpty() }.randomOrNull() ?: run {
            return
        }

        val villager  = this.selectRandomVillager(world) ?: run {
            return
        }

        if (villager.getCharacterData() == null) run {
            return
        }

        if (villager.profession == Villager.Profession.NONE) run {
            return
        }

        if (villager.quests().size > 1 + villager.villagerLevel) run {
            return
        }

        // Random quest type will be chosen.
        val questType = QuestType.entries.random()

        plugin.server.scheduler.runTaskAsynchronously(plugin, { _ ->
            try {
                plugin.gameplayManager.questManager.generateQuest(questType, villager as LivingEntity).let { quest ->
                    plugin.gameplayManager.actualQuests.add(quest.id)
                    villager.addQuest(quest)
                    // plugin.logger.info("Generated a brand-new quest ${quest.name} for entity ${villager.customName} in world ${villager.world.name} at ${villager.location}.")
                }
            } catch (exception: Exception) {
                val debug = true
                if (debug) {
                    exception.printStackTrace()
                }
            }
        })

    }

    // Выбираем случайного жителя, предварительно отчистив старые квесты.
    private fun selectRandomVillager(world: World) : Villager? {
        return world.entities.filterIsInstance<Villager>().onEach { villager: Villager ->
            villager.quests().forEach { quest ->
                if ((System.currentTimeMillis() - quest.timeCreated) / 1000 * 20 > questLifetimeDuration) {
                    this.invalidateQuest(quest, QuestInvalidationEvent.Reason.TIME_EXPIRATION)
                    villager.removeQuest(quest)
                }
            }
        }.filter { it.quests().size < it.villagerLevel + 1 }.randomOrNull()
    }

    /**
     * Avoid using this in the main server tick, or it will cause massive lags!
     * @return If generation is successful, returns a brand-new quest.
     * */
    @Suppress("KotlinUnreachableCode")
    fun generateQuest(type: QuestType, questGiver: LivingEntity): Quest {
        class QuestGenerationException : Exception("Error during quest generation!")
        val generator = QuestGenerationController(type, questGiver)

        plugin.providerManager.client.sendPromptWithSchema(generator.prompt, GeneratedCharacterDataContainer::class)?.let { data ->
            val quest = Quest(
                type,
                type.questFamily,
                QuestItem(gson.toJson(generator.questItem.item, ItemStack::class.java)),
                questGiver.uniqueId,
                data,
                data.questNames.random(),
                this.incrementQuestID(),
                System.currentTimeMillis(),
                generator.score,
                false,
                0.0
            )
            return quest
        } ?: throw QuestGenerationException()
    }

    fun cancelQuest(player: Player, quest: Quest) {
        progressTracker.stopTracking(player, quest)
        player.removeQuest(quest)
    }

    private fun incrementQuestID(): Long {
        var questCount = totalQuestAmount
        questCount += 1
        Bukkit.getWorlds()[0]!!.persistentDataContainer.set(questCountKey, PersistentDataType.LONG, questCount)
        return questCount
    }

    /* Общий метод инвалидации квеста. Указанный квест будет удалён из списка актуальных квестов, и у всех игроков онлайн. */
    fun invalidateQuest(quest: Quest, reason: QuestInvalidationEvent.Reason) {
        plugin.gameplayManager.actualQuests.remove(quest.id)
        Bukkit.getOnlinePlayers().forEach { onlinePlayer ->
            onlinePlayer.quests().find { it.id == quest.id }?.let {
                plugin.server.pluginManager.callEvent(QuestInvalidationEvent(onlinePlayer, it, reason))
            }
        }
    }

    fun finishQuest(player: Player, questGiver: LivingEntity, quest: Quest, onFinish: () -> Unit = {}) {

        val playerReputation   = (quest.score * reputationPriceMultiplier).toInt()
        val playerExperience   = (quest.score * experienceMultiplierPlayer).toInt()
        val villagerExperience = (quest.score * experienceMultiplierNPC).toInt()

        if (questGiver is Villager) questGiver.villagerExperience += villagerExperience
        player.giveExp(playerExperience)
        plugin.gameplayManager.reputationManager.addReputation(questGiver, player, playerReputation)

        val finishMessage = plugin.language.getString("quest.finished")!!.replace("{quest}", quest.name)
        player.sendFormattedMessage(finishMessage)

        progressTracker.stopTracking(player, quest)
        questGiver.removeQuest(quest)
        player.removeQuest(quest)

        /* Обновляем статистику. */
        player.questsCompleted += 1
        player.experienceEarnedByQuests += playerExperience

        onFinish.invoke()
        this.invalidateQuest(quest, QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE)
        questGiver.talk(player, this.determineFinishingDialogue(player, questGiver, quest))
    }

    /* Определяем текст после завершения квеста на основании репутации игрока. */
    private fun determineFinishingDialogue(player: Player, entity: LivingEntity, quest: Quest) : String {
        return when (entity.reputationOf(player)) {
            Reputation.EXALTED    -> quest.data.reputationBasedQuestFinishingDialogues[7]
            Reputation.REVERED    -> quest.data.reputationBasedQuestFinishingDialogues[6]
            Reputation.HONORED    -> quest.data.reputationBasedQuestFinishingDialogues[5]
            Reputation.FRIENDLY   -> quest.data.reputationBasedQuestFinishingDialogues[4]
            Reputation.NEUTRAL    -> quest.data.reputationBasedQuestFinishingDialogues[3]
            Reputation.UNFRIENDLY -> quest.data.reputationBasedQuestFinishingDialogues[2]
            Reputation.HOSTILE    -> quest.data.reputationBasedQuestFinishingDialogues[1]
            Reputation.EXILED     -> quest.data.reputationBasedQuestFinishingDialogues[0]
        }.replace("%playerName%", player.name)
    }

    @EventHandler
    fun onMerchantTrade(event: MerchantTradeEvent) {
        event.player.quests().find { it.questItem.getItemStack().isSimilar(event.recipe.ingredients.first()) }?.let { quest ->
            event.player.closeInventory()
            when (quest.type) {
                QuestType.PROFESSION_ITEM_GATHERING, QuestType.MUSIC_DISC -> {
                    this.finishQuest(event.player, event.merchant, quest)
                }
            }
        }
    }

    @EventHandler
    private fun onQuestInvalidation(event: QuestInvalidationEvent) {

        val player = event.player

        /* Обязательно удаляем активный квест-трэкер, если он есть. */
        progressTracker.stopTracking(player, event.quest)

        val message = when (event.reason) {
            QuestInvalidationEvent.Reason.NPC_DEATH -> plugin.language.getString("quest.failed.npcDeath")
            QuestInvalidationEvent.Reason.TIME_EXPIRATION -> plugin.language.getString("quest.failed.timeExpiration")
            QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE -> plugin.language.getString("quest.failed.finishedBySomeoneElse")
            QuestInvalidationEvent.Reason.NOT_ACTUAL -> plugin.language.getString("quest.failed.notActual")
        }

        player.sendFormattedMessage(message!!.replace("{quest}", event.quest.name))
        player.removeQuest(event.quest)
        player.questsFailed += 1
    }

    @EventHandler
    fun onPlayerAcceptQuest(event: PlayerAcceptQuestEvent) {

        val player = event.player
        val npc    = event.questGiver
        val quest  = event.quest

        // Если у игрока есть квест с таким же ID, значит он уже взят!
        if (player.quests().any { it.id == quest.id }) {
            player.sendFormattedMessage(plugin.language.getString("quest.already-accepted")!!)
            return
        }

        val playerQuestLimit = 3 // TODO; Move me to the cfg!
        if (player.quests().size >= playerQuestLimit) {
            val questLimitMessage = plugin.language.getString("quest.limit")!!.replace("{playerQuestLimit}", playerQuestLimit.toString())
            player.sendFormattedMessage(questLimitMessage)
            return
        }

        // Отправляем информацию о новом квесте игроку.
        player.sendFormattedMessage(plugin.language.getString("quest.accepted")!!.replace("{quest}", quest.name))
        player.sendFormattedMessage(plugin.language.getString("info-messages.quest-chat-info.quest-giver")!!.replace("{npcName}", npc.customName!!))
        player.sendFormattedMessage(plugin.language.getString("info-messages.quest-chat-info.task-description")!!.replace("{desc}", quest.data.extraShortTaskDescription))

        // Только один квест может быть активен.
        if (questTracker[player] == null) {
            questTracker[player] = quest to progressTracker.startTracking(player, quest)
        }

        player.addQuest(quest)
    }

    class QuestGenerationController(questType: QuestType, private val questGiver: LivingEntity) {

        private val questInfo = questType.questFamily.questDescription.replace("{taskDescription}", questType.taskDescription)

        private val questPrompt = "Answer only in JSON format, without unnecessary text, make sure it will be JSON parseable. Generate a quest for NPC using the following JSON scheme: " +
        "`questNames` — string array, five short but creative quest names, must differ from each other" +
        "`extraShortTaskDescription` — extremely short description of the task (goal, quest giver name, amount), " +
        "`shortRequiredQuestItemDescription` — a short (literally one sentence) description of the item in the context of the quest (from the third party), " +
        "string array of `reputationBasedQuestDescriptions` and string array of `reputationBasedQuestFinishingDialogues` which will shift from the most negative reputation to the most positive (existing reputation states: exiled, hostile, unfriendly, neutral, friendly, honored, revered, exalted, don't mention the exact status, just play around it, eight values must be in each array). " +
        "The writing style must be strictly tailored in the following order: global setting, race (race description), character definition, current biome, profession (profession level), gender. Start with a neutral description — it'll be easier for you to navigate that way. Don't shorten the descriptions because it's an array — we don't want scraps of phrases, right? Then, sort the content of the array from the worst to the best reputation. Select the most important words (like names or goals) with bold Markdown. Select interesting parts with italic Markdown. All content must be written in the first person to enhance player immersion and believability. In places where the npc want to address the player, use the %playerName% placeholder. " +
        "The following is the information about the NPC: name is {npcName}, current biome is {currentBiome}, NPC personality definition is [{npcPersonality}], race is {npcRace} and race description: [{raceDescription}], profession is {npcProfession}, npc profession mastery level is {npcProfessionLevel}, npc gender is {npcGender}. {questInfo}"

        val questItem = questType.strategy.get(questGiver)
        val currency  = plugin.gameplayManager.itemDictionary.getItem(questGiver.race.normalCurrency.name)
        val amount    = questItem.item.amount
        val score     = if (questItem.score * amount < currency.score) currency.score else questItem.score * amount

        private val placeholders  = mutableMapOf<String, String>().also {
            it["npcPersonality"]  = "${questGiver.getPersonality()}"
            it["npcName"]         = questGiver.customName.toString()
            it["npcGender"]       = questGiver.gender.toString()
            it["npcRace"]         = questGiver.race.name
            it["raceDescription"] = questGiver.race.description
            it["currentBiome"]    = questGiver.location.block.biome.key.toString()
            // Only villagers can have a profession and settlement (they live in a fucking villages!). I'm planning to add quests to wandering trades and witches as well.
            (questGiver as? Villager)?.let { villager ->
                it["npcProfession"]      = villager.profession.toString()
                it["npcProfessionLevel"] = villager.professionLevelName
            }

            it["questInfo"]       = questInfo
            it["rewardItem"]      = currency.item.type.name.lowercase().replace("_", " ")
            it["questItem"]       = questItem.key.lowercase().replace("_", " ")
            it["questItemAmount"] = amount.toString()
        }

        val prompt = questPrompt.replaceMap(placeholders)

    }

    data class Quest(
        val type: QuestType,
        val family: QuestFamily,
        val questItem: QuestItem,
        val giver: UUID,
        val data: GeneratedCharacterDataContainer,
        val name: String,
        val id: Long,
        val timeCreated: Long,
        val score: Long,
        var tracking: Boolean,
        var progress: Double
    ) {

        data class QuestItem(val serialized: String) {
            fun getItemStack(): ItemStack = gson.fromJson(serialized, ItemStack::class.java)
        }

        fun calculateReward(currency: String): ItemStack {

            val currency = plugin.gameplayManager.itemDictionary.getItem(currency)
            var amount = (this.questItem.getItemStack().calculateScore() / currency.score)

            if (amount < 64)
                return currency.item.apply { this.amount = if (amount <= 0) 1 else amount.toInt() }

            val items = mutableListOf<ItemStack>()

            while (amount > 0) {
                amount.coerceIn(1, 64).toInt().let { i ->
                    items.add(currency.item.apply { this.amount = i }); amount -= i;
                }
            }

            return this.bundle(items)
        }

        private fun bundle(items: List<ItemStack>): ItemStack {
            return ItemStack(Material.BUNDLE, 1).apply {
                itemMeta = (itemMeta as BundleMeta).apply {
                    items.forEach(::addItem)
                }
            }
        }

    }

    data class GeneratedCharacterDataContainer(val questNames: List<String>,
                                               val extraShortTaskDescription: String,
                                               val shortRequiredQuestItemDescription: String,
                                               val reputationBasedQuestDescriptions: List<String>,
                                               val reputationBasedQuestFinishingDialogues: List<String>)

    enum class QuestFamily(val questDescription: String) {
        GATHERING("To complete the quest, the player will need to obtain an item `{questItem}` in the amount of {questItemAmount} and bring it to the NPC. The NPC promises a reward ({rewardItem}) for the assistance, without specifying what exactly it will be. When generating the quest, be sure to thoughtfully consider this information. In addition to the previous requirements, follow these guidelines during the generation: {taskDescription}.")
    }

    enum class QuestType(val questFamily: QuestFamily, val strategy: QuestItemStrategy, val taskDescription: String) {
        PROFESSION_ITEM_GATHERING(QuestFamily.GATHERING, ProfessionItemGatheringQuestItemStrategy(), "NPC requests an item for their development — this quest is related to the NPC's profession leveling. Based on the quest item and the NPC profession, NPC should explain the task to the player by sharing the reason they need the quest item."),
        MUSIC_DISC(QuestFamily.GATHERING, MusicDiscQuestItemStrategy(), "NPC wants a music disc and asks the player to find him one. The reason must be related to either personality or profession.")
    }

    companion object {

        fun String.replaceMap(replacements: Map<String, String>): String {
            var result = this
            for ((key, value) in replacements) {
                result = result.replace("{${key}}", value)
            }
            return result
        }

        fun LivingEntity.addQuest(quest: Quest) {
            persistentDataContainer.set(questDataKey, PersistentDataType.STRING, gson.toJson(this.quests().apply { add(quest) }))
        }

        fun LivingEntity.removeQuest(quest: Quest) {
            persistentDataContainer.set(questDataKey, PersistentDataType.STRING, gson.toJson(this.quests().apply { removeIf { it.id == quest.id } }))
        }

    }

}
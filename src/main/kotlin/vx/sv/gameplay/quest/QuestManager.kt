package vx.sv.gameplay.quest

import com.cryptomorin.xseries.XMaterial
import net.kyori.adventure.key.Key
import org.bukkit.*
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.entity.Villager.Profession
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.gameplay.dialogue.DialogueManager.Companion.talk
import vx.sv.gameplay.event.MerchantTradeEvent
import vx.sv.gameplay.event.PlayerAcceptQuestEvent
import vx.sv.gameplay.event.QuestInvalidationEvent
import vx.sv.gameplay.humanoid.HungerManager.eat
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.personality.PersonalityManager.Companion.gender
import vx.sv.gameplay.personality.PersonalityManager.Companion.getPersonality
import vx.sv.gameplay.quest.ProgressTracker.Companion.experienceEarnedByQuests
import vx.sv.gameplay.quest.ProgressTracker.Companion.questTracker
import vx.sv.gameplay.quest.ProgressTracker.Companion.questsCompleted
import vx.sv.gameplay.quest.ProgressTracker.Companion.questsFailed
import vx.sv.gameplay.quest.QuestManager.Quest.QuestItem
import vx.sv.gameplay.quest.pragma.QuestItemStrategy
import vx.sv.gameplay.quest.pragma.strategy.*
import vx.sv.gameplay.quest.pragma.strategy.TreasureHuntQuestItemStrategy.Companion.treasureItems
import vx.sv.gameplay.reputation.ReputationManager.Companion.opinionOn
import vx.sv.gameplay.reputation.ReputationManager.Reputation
import vx.sv.gameplay.trade.ScoreCalculator.getBasicScore
import vx.sv.nms.VersionBridge.Companion.asHumanoid
import vx.sv.persistent.LivingEntityExtend.hasEdibleItem
import vx.sv.persistent.LivingEntityExtend.hunger
import vx.sv.persistent.LivingEntityExtend.questDataKey
import vx.sv.persistent.LivingEntityExtend.quests
import vx.sv.persistent.LivingEntityExtend.takeItemFromQuillInventory
import vx.sv.persistent.VillagerExtend.professionLevelName
import java.util.*

class QuestManager : Listener {

    val progressTracker = ProgressTracker()

    // ID квестов помогает отслеживать актуальные квесты и те, которые уже не имеют смысла.
    val questCountKey = NamespacedKey(plugin, "TotalQuestCount")
    val totalQuestAmount: Long
        get() = Bukkit.getWorlds()[0]!!.persistentDataContainer.get(questCountKey, PersistentDataType.LONG) ?: 0

    private var gatheringDescription: String
    private val taskDescriptions = mutableMapOf<QuestType, String>()
    private val allowedQuestTypes = mutableSetOf<QuestType>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, { _ -> tick() }, 0, plugin.gameplayManager.config.quest.intervalTicks)

        // Загружаем описания и allowed из prompts.yml
        gatheringDescription = plugin.prompts.getString("quest-family.gathering.quest-description")
            ?: "To complete the quest, the player will need to obtain an item `{questItem}` in the amount of {questItemAmount} and bring it to the NPC. The NPC promises a reward ({rewardItem}) for the assistance, without specifying what exactly it will be. When generating the quest, be sure to thoughtfully consider this information. In addition to the previous requirements, follow these guidelines during the generation: {taskDescription}."

        QuestType.entries.forEach { type ->
            val key = type.name.lowercase().replace("_", "-") + "-quest"
            if (plugin.prompts.getBoolean("$key.allowed", true)) {
                allowedQuestTypes.add(type)
            }
            taskDescriptions[type] = plugin.prompts.getString("$key.quest-requirements")
                ?: "Default task description for ${type.name}."
        }
    }

    fun tick() {

        val world = plugin.gameplayManager.allowedWorlds.filter { it.entities.filterIsInstance<Villager>().isNotEmpty() }.randomOrNull() ?: run {
            return
        }

        val villager = this.selectRandomVillager(world) ?: run {
            return
        }

        if (villager.profession == Profession.NONE) run {
            return
        }

        if (villager.quests().size > plugin.gameplayManager.config.quest.npcQuestBase + villager.villagerLevel) run {
            return
        }

        // Basic small quests allowed for every profession.
        val allowedQuests = mutableListOf(QuestType.PROFESSION_ITEM_GATHERING, QuestType.BOOZE, QuestType.MUSIC_DISC)
            .filter { it in allowedQuestTypes }.toMutableList()

        // Random quest type will be chosen. If villager is hungry, enforce food quest.
        val questType = if (villager.hunger <= plugin.gameplayManager.config.hunger.questThreshold && !villager.hasEdibleItem() && QuestType.FOOD_SEARCH in allowedQuestTypes) {
            QuestType.FOOD_SEARCH
        } else {
            allowedQuests.apply {
                when (villager.profession) {
                    Profession.ARMORER -> if (QuestType.SMITHING_TEMPLATE_ORDER in allowedQuestTypes) this.add(QuestType.SMITHING_TEMPLATE_ORDER)
                    Profession.LIBRARIAN -> {
                        if (QuestType.ENCHANTED_BOOK_ORDER in allowedQuestTypes) this.add(QuestType.ENCHANTED_BOOK_ORDER)
                        if (QuestType.TREASURE_HUNT in allowedQuestTypes) this.add(QuestType.TREASURE_HUNT)
                    }
                    Profession.CARTOGRAPHER -> if (QuestType.TREASURE_HUNT in allowedQuestTypes) this.add(QuestType.TREASURE_HUNT)
                }
            }.randomOrNull() ?: run { return }
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, { _ ->
            try {
                plugin.gameplayManager.questManager.generateQuest(questType, villager as LivingEntity)?.let { quest ->
                    plugin.gameplayManager.actualQuests.add(quest.id)
                    quest.let { villager.addQuest(it) }
                }
            } catch (exception: Exception) {
                val debug = false
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
                if ((System.currentTimeMillis() - quest.timeCreated) / 1000 * 20 > plugin.gameplayManager.config.quest.lifetimeDuration) {
                    this.invalidateQuest(quest, QuestInvalidationEvent.Reason.TIME_EXPIRATION)
                    villager.removeQuest(quest)
                }
            }
        }.filter { it.quests().size < it.villagerLevel + plugin.gameplayManager.config.quest.npcQuestBase }.randomOrNull()
    }

    /**
     * Avoid using this in the main server tick, or it will cause massive lags!
     * @return If generation is successful, returns a brand-new quest.
     * */
    fun generateQuest(type: QuestType, questGiver: LivingEntity): Quest? {
        val generator = QuestGenerationController(type, questGiver)
        return plugin.providerManager.client.sendPromptWithSchema(generator.prompt, GeneratedCharacterDataContainer::class)?.let { data ->
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
        }
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

        val playerReputation   = (quest.score * plugin.gameplayManager.config.quest.reputationMultiplier).toInt()
        val playerExperience   = (quest.score * plugin.gameplayManager.config.quest.playerExperienceMultiplier).toInt()
        val villagerExperience = (quest.score * plugin.gameplayManager.config.quest.npcExperienceMultiplier).toInt()

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
        return when (entity.opinionOn(player)) {
            Reputation.EXALTED    -> quest.data.reputationBasedQuestFinishingDialogues[0]
            Reputation.REVERED    -> quest.data.reputationBasedQuestFinishingDialogues[1]
            Reputation.HONORED    -> quest.data.reputationBasedQuestFinishingDialogues[2]
            Reputation.FRIENDLY   -> quest.data.reputationBasedQuestFinishingDialogues[3]
            Reputation.NEUTRAL    -> quest.data.reputationBasedQuestFinishingDialogues[4]
            Reputation.UNFRIENDLY -> quest.data.reputationBasedQuestFinishingDialogues[5]
            Reputation.HOSTILE    -> quest.data.reputationBasedQuestFinishingDialogues[6]
            Reputation.EXILED     -> quest.data.reputationBasedQuestFinishingDialogues[7]
        }.replace("%playerName%", player.name)
    }

    @EventHandler
    fun onMerchantTrade(event: MerchantTradeEvent) {
        event.player.quests().find { it.questItem.getItemStack().isSimilar(event.recipe.ingredients.first()) }?.let { quest ->
            event.player.closeInventory()
            when (quest.type) {

                // Default quests without special finishers.
                QuestType.PROFESSION_ITEM_GATHERING, QuestType.SMITHING_TEMPLATE_ORDER, QuestType.ENCHANTED_BOOK_ORDER, QuestType.TREASURE_HUNT -> {
                    this.finishQuest(event.player, event.merchant, quest)
                }

                // Music disc quest finisher.
                QuestType.MUSIC_DISC -> {

                    fun getSoundKeyFromMaterial(material: Material): String? {
                        val name = material.name
                        if (!name.startsWith("MUSIC_DISC_")) return null  // Не disc — игнор
                        val suffix = name.removePrefix("MUSIC_DISC_").lowercase()  // "CAT" → "cat"; "CREATOR_MUSIC" → "creator_music"
                        return "music_disc.$suffix"  // Готовый key для Adventure Sound
                    }

                    val recordKey = getSoundKeyFromMaterial(quest.questItem.getItemStack().type)!!

                    fun playRecordFollowingNpc(
                        npc: Entity,
                        recordKey: String,
                        source: net.kyori.adventure.sound.Sound.Source = net.kyori.adventure.sound.Sound.Source.RECORD,
                        volume: Float = 1.5f,
                        pitch: Float = 1.0f,
                        radius: Double = 32.0
                    ) {
                        val sound = net.kyori.adventure.sound.Sound.sound(Key.key(recordKey), source, volume, pitch)

                        val nearbyPlayers: List<Player> = npc.world.getNearbyEntities(npc.location, radius, radius, radius)
                            .filterIsInstance<Player>()

                        if (nearbyPlayers.isEmpty()) return

                        nearbyPlayers.forEach { player ->
                            player.playSound(sound, npc)
                        }
                    }

                    playRecordFollowingNpc(event.merchant, recordKey)
                    this.finishQuest(event.player, event.merchant, quest)

                }

                // Food quest. Logically, NPC must eat after taking the food.
                QuestType.FOOD_SEARCH -> {
                    event.merchant.eat()
                    this.finishQuest(event.player, event.merchant, quest)
                }

                // Booze quest. My favorite. Drink, and only after that, finish.
                QuestType.BOOZE -> {
                    event.merchant.asHumanoid()?.let { humanoid ->
                        val potion = quest.questItem.getItemStack()
                        val effect = (potion.itemMeta as PotionMeta).basePotionType?.potionEffects?.firstOrNull()
                        humanoid.consume(event.merchant.world, potion, Sound.ENTITY_GENERIC_DRINK, 7, event.merchant.location, 7) {
                            effect?.let { event.merchant.addPotionEffect(it) }
                            event.merchant.takeItemFromQuillInventory(potion, 1)
                            this.finishQuest(event.player, event.merchant, quest)
                        }
                    } ?: this.finishQuest(event.player, event.merchant, quest) // Fallback, if humanoids is disabled. For some reason.
                }

            }
        }
    }

    @EventHandler
    private fun onVillagerDeath(event: EntityDeathEvent) {
        (event.entity as? Villager)?.quests()?.forEach { quest ->
            this@QuestManager.invalidateQuest(quest, QuestInvalidationEvent.Reason.NPC_DEATH)
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

        if (player.quests().size >= plugin.gameplayManager.config.quest.playerQuestLimit) {
            val questLimitMessage = plugin.language.getString("quest.limit")!!.replace("{playerQuestLimit}", plugin.gameplayManager.config.quest.playerQuestLimit.toString())
            player.sendFormattedMessage(questLimitMessage)
            return
        }

        // Отправляем информацию о новом квесте игроку.
        player.sendFormattedMessage(plugin.language.getString("quest.accepted")!!.replace("{quest}", quest.name))
        player.sendFormattedMessage(plugin.language.getString("info-messages.quest-chat-info.quest-giver")!!.replace("{npcName}", npc.customName!!).replace("%playerName%", player.name))
        player.sendFormattedMessage(plugin.language.getString("info-messages.quest-chat-info.task-description")!!.replace("{desc}", quest.data.extraShortTaskDescription).replace("%playerName%", player.name))

        // Только один квест может быть активен.
        if (questTracker[player] == null) {
            questTracker[player] = quest to progressTracker.startTracking(player, quest)
        }

        player.addQuest(quest)
    }

    class QuestGenerationController(questType: QuestType, private val questGiver: LivingEntity) {

        private val questInfo = plugin.gameplayManager.questManager.gatheringDescription.replace("{taskDescription}", plugin.gameplayManager.questManager.taskDescriptions[questType]!!)

        private val questPrompt = """
            You are an expert game narrative designer and voice actor. Your task is to generate a quest for an NPC.
            Respond ONLY with valid, parseable JSON. Do not include markdown code blocks (like ```json), explanations, or any other text.
        
            ### JSON SCHEMA TO FOLLOW:
            {
              "questNames":[
                // Array of 5 short, creative, and distinct quest names (Do NOT use personalized dialogue here)
              ],
              "extraShortTaskDescription": "Extremely short description of the task (Goal, Quest Giver Name, Amount).",
              "shortRequiredQuestItemDescription": "Literally one sentence describing the item in the context of the quest (written in third-person perspective).",
              "reputationBasedQuestDescriptions":[
                // Array of exactly 8 strings (Written in FIRST-PERSON perspective).
                // Order strictly from worst to best reputation: 1. Exiled, 2. Hostile, 3. Unfriendly, 4. Neutral, 5. Friendly, 6. Honored, 7. Revered, 8. Exalted.
                // Do NOT mention the reputation name directly, reflect the attitude in the tone. Do not shorten phrases.
              ],
              "reputationBasedQuestFinishingDialogues":[
                // Array of exactly 8 strings (Written in FIRST-PERSON perspective).
                // Same 8 reputation stages and rules as above.
              ]
            }
        
            ### WRITING STYLE & RULES:
            1. Deep Roleplay: Fully embody the NPC. Tone, vocabulary, and worldview MUST be heavily influenced by the following priority: Global Setting > Personality > Race > Biome > Profession > Gender.
            2. Personal Connection: In the dialogue arrays (QuestDescriptions and FinishingDialogues), the NPC must communicate with the player in a highly personal, expressive, and engaging manner tailored to the current reputation level.
            3. Clear Requests & Motives: In the dialogue, the NPC must explicitly state WHAT they need the player to do and explain WHY they need it done (their underlying motive/reason). Only omit the "why" if the NPC's specific personality (e.g., highly secretive, arrogant, mindless) strictly forbids explaining themselves.
            4. Player Placeholder: Whenever the NPC addresses the player directly, use exactly "%playerName%".
        
            ### NPC & QUEST CONTEXT:
            - Global Setting: {globalSetting}
            - NPC Name: {npcName}
            - Personality: {npcPersonality}
            - Race: {npcRace}
            - Race Description: {raceDescription}
            - Profession: {npcProfession} (Mastery Level: {npcProfessionLevel})
            - Current Biome: {currentBiome}
            - Gender: {npcGender}
            - Quest Info: {questInfo}
        """.trimIndent()

        val questItem = questType.strategy.get(questGiver)
        val currency  = questGiver.race.normalCurrency.get() ?: throw NullPointerException("Can't get race normal currency: ${questGiver.race.name}.")
        val amount    = questItem.item.amount
        val score     = (if (questItem.score < currency.getBasicScore()) currency.getBasicScore() * 10 else questItem.score).toLong()

        private val placeholders  = mutableMapOf<String, String>().also { it ->
            it["npcPersonality"]  = "${questGiver.getPersonality()}"
            it["npcName"]         = questGiver.customName.toString()
            it["npcGender"]       = questGiver.gender.toString()
            it["npcRace"]         = questGiver.race.name
            it["raceDescription"] = questGiver.race.description
            it["currentBiome"]    = questGiver.location.block.biome.key.key

            // Only villagers can have a profession and settlement (they live in a fucking villages!). I'm planning to add quests to wandering trades and witches as well.
            (questGiver as? Villager)?.let { villager ->
                it["npcProfession"]      = villager.profession.key.key
                it["npcProfessionLevel"] = villager.professionLevelName
            }

            when (questType) {
                QuestType.BOOZE -> it["potionType"] = (questItem.item.itemMeta as PotionMeta).basePotionType!!.key.key.lowercase().replace("_", " ")
                QuestType.ENCHANTED_BOOK_ORDER -> it["enchantmentType"] = (questItem.item.itemMeta as EnchantmentStorageMeta).storedEnchants.toList().first().first.key.key.replace("_", " ")
                QuestType.TREASURE_HUNT -> it["treasureDescription"] = treasureItems.find { it.first == questItem.item.type }?.third ?: "No extra info."
                else -> { /* :) */ }
            }

            it["rewardItem"]      = currency.name.lowercase().replace("_", " ")
            it["questItem"]       = questItem.key.lowercase().replace("_", " ")
            it["questItemAmount"] = amount.toString()
            it["questInfo"]       = questInfo.replaceMap(it) // Must be last!
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

            val material     = XMaterial.valueOf(currency).get() ?: throw NullPointerException("Can't find material with name $currency.")
            val currencyItem = ItemStack(material)
            var amount       = score / material.getBasicScore()

            if (amount < 64)
                return currencyItem.apply { this.amount = if (amount <= 0) 1 else amount.toInt() }

            val items = mutableListOf<ItemStack>()

            while (amount > 0) {
                amount.coerceIn(1, 64).toInt().let { i ->
                    items.add(currencyItem.apply { this.amount = i }); amount -= i;
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

    enum class QuestFamily {
        GATHERING
    }

    enum class QuestType(val questFamily: QuestFamily, val strategy: QuestItemStrategy) {
        PROFESSION_ITEM_GATHERING(QuestFamily.GATHERING, ProfessionItemGatheringQuestItemStrategy()),
        MUSIC_DISC(QuestFamily.GATHERING, MusicDiscQuestItemStrategy()),
        FOOD_SEARCH(QuestFamily.GATHERING, FoodSearchQuestItemStrategy()),
        BOOZE(QuestFamily.GATHERING, BoozeQuestItemStrategy()),
        SMITHING_TEMPLATE_ORDER(QuestFamily.GATHERING, SmithingTemplateQuestItemStrategy()),
        ENCHANTED_BOOK_ORDER(QuestFamily.GATHERING, EnchantedBookQuestItemStrategy()),
        TREASURE_HUNT(QuestFamily.GATHERING, TreasureHuntQuestItemStrategy())
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
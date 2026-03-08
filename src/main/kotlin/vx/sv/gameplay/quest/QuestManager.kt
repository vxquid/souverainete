package vx.sv.gameplay.quest

import com.cryptomorin.xseries.XMaterial
import net.kyori.adventure.key.Key
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.entity.Villager.Profession
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
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
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.isSettlementLeader
import vx.sv.gameplay.trade.ScoreCalculator.getBasicScore
import vx.sv.nms.VersionBridge.Companion.asHumanoid
import vx.sv.persistent.LivingEntityExtend.hasEdibleItem
import vx.sv.persistent.LivingEntityExtend.hunger
import vx.sv.persistent.LivingEntityExtend.questDataKey
import vx.sv.persistent.LivingEntityExtend.quests
import vx.sv.persistent.LivingEntityExtend.settlement
import vx.sv.persistent.LivingEntityExtend.takeItemFromQuillInventory
import vx.sv.persistent.VillagerExtend.professionLevelName
import java.util.*

/**
 * Manages the entire quest lifecycle in the game, including generation via AI,
 * assignment to NPCs, player acceptance, progress tracking, and completion/failure events.
 */
class QuestManager : Listener {

    val progressTracker = ProgressTracker()

    // Key used to store and retrieve the total number of quests ever generated on the server
    val questCountKey = NamespacedKey(plugin, "TotalQuestCount")

    // Retrieves the global quest counter from the primary world's persistent data container
    val totalQuestAmount: Long
        get() = Bukkit.getWorlds()[0]!!.persistentDataContainer.get(questCountKey, PersistentDataType.LONG) ?: 0

    // Prompts for the AI to generate quest lore
    private var gatheringDescription: String
    private var deliveryDescription: String
    private val taskDescriptions = mutableMapOf<QuestType, String>()
    private val allowedQuestTypes = mutableSetOf<QuestType>()

    init {
        // Register this class to listen for Bukkit events
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Start a recurring task to generate new quests periodically
        plugin.server.scheduler.runTaskTimer(plugin, { _ -> tick() }, 0, plugin.gameplayManager.config.quest.intervalTicks)

        // Load AI prompt templates from the configuration, falling back to defaults if not found
        gatheringDescription = plugin.prompts.getString("quest-family.gathering.quest-description")
            ?: "To complete the quest, the player will need to obtain an item `{questItem}` in the amount of {questItemAmount} and bring it to the NPC. The NPC promises a reward ({rewardItem}) for the assistance, without specifying what exactly it will be. When generating the quest, be sure to thoughtfully consider this information. In addition to the previous requirements, follow these guidelines during the generation: {taskDescription}."

        deliveryDescription = plugin.prompts.getString("quest-family.delivery.quest-description")
            ?: "The player must deliver a highly important political `{questItem}` to {targetLeader}, the leader of {targetSettlement}. The quest has a strict time limit. If the letter is lost or time runs out, relations between the settlements will deteriorate. The NPC promises a generous reward ({rewardItem}) upon successful delivery. {taskDescription}."

        // Load enabled quest types and their specific task descriptions
        QuestType.entries.forEach { type ->
            val key = type.name.lowercase().replace("_", "-") + "-quest"
            if (plugin.prompts.getBoolean("$key.allowed", true)) {
                allowedQuestTypes.add(type)
            }
            taskDescriptions[type] = plugin.prompts.getString("$key.quest-requirements")
                ?: "Default task description for ${type.name}."
        }
    }

    /**
     * Called periodically to handle background quest logic.
     * Selects a random eligible villager and attempts to generate a new quest for them.
     */
    fun tick() {
        val world = plugin.gameplayManager.allowedWorlds.filter { it.entities.filterIsInstance<Villager>().isNotEmpty() }.randomOrNull() ?: return
        val villager = this.selectRandomVillager(world) ?: return

        // Skip villagers with no profession or those who have reached their max quest limit
        if (villager.profession == Profession.NONE) return
        if (villager.quests().size > plugin.gameplayManager.config.quest.npcQuestBase + villager.villagerLevel) return

        val allowedQuests = mutableListOf(QuestType.PROFESSION_ITEM_GATHERING, QuestType.BOOZE, QuestType.MUSIC_DISC)
            .filter { it in allowedQuestTypes }.toMutableList()

        var targetLeaderId: UUID? = null
        var targetLeaderName: String? = null
        var targetSettlementId: UUID? = null
        var giverSettlementId: UUID? = null
        val villagerSettlement = villager.settlement

        // If the villager is a settlement leader, they can give delivery quests to other settlements
        if (villager.isSettlementLeader() && villagerSettlement != null && QuestType.MESSAGE_DELIVERY in allowedQuestTypes) {
            val otherSettlements = SettlementManager.settlements[world]?.filter {
                it.data.id != villagerSettlement.data.id && it.data.leaderId != null &&
                        it.villagers.any { v -> v.uniqueId == it.data.leaderId && v.isValid }
            }
            if (!otherSettlements.isNullOrEmpty()) {
                allowedQuests.add(QuestType.MESSAGE_DELIVERY)
            }
        }

        // Determine the type of quest to generate based on the villager's needs (e.g., hunger) or profession
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
                    else -> {}
                }
            }.randomOrNull() ?: return
        }

        // Setup parameters for delivery quests
        if (questType == QuestType.MESSAGE_DELIVERY) {
            val target = SettlementManager.settlements[world]?.filter {
                it.data.id != villagerSettlement!!.data.id && it.data.leaderId != null
            }?.randomOrNull()
            if (target != null) {
                targetLeaderId = target.data.leaderId
                targetLeaderName = target.data.leaderName
                targetSettlementId = target.data.id
                giverSettlementId = villagerSettlement?.data?.id
            } else return
        }

        // Run the actual AI generation asynchronously to avoid freezing the main server thread
        plugin.server.scheduler.runTaskAsynchronously(plugin, { _ ->
            try {
                plugin.gameplayManager.questManager.generateQuest(
                    questType, villager, targetLeaderId, targetLeaderName, targetSettlementId, giverSettlementId
                )?.let { quest ->
                    plugin.gameplayManager.actualQuests.add(quest.id)
                    villager.addQuest(quest)
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
        })
    }

    /**
     * Selects a random eligible villager to receive a new quest.
     * Also cleans up (invalidates) any expired quests from villagers during the iteration.
     */
    private fun selectRandomVillager(world: World): Villager? {
        return world.entities.filterIsInstance<Villager>().onEach { villager ->
            villager.quests().forEach { quest ->
                // Check if the quest has lived past its lifetime duration
                if ((System.currentTimeMillis() - quest.timeCreated) / 1000 * 20 > plugin.gameplayManager.config.quest.lifetimeDuration) {
                    this.invalidateQuest(quest, QuestInvalidationEvent.Reason.TIME_EXPIRATION)
                    villager.removeQuest(quest)
                }
            }
        }.filter { it.quests().size < it.villagerLevel + plugin.gameplayManager.config.quest.npcQuestBase }.randomOrNull()
    }

    /**
     * Generates a brand new quest by interacting with the AI prompt schema.
     * Creates custom items for delivery quests based on the AI response.
     */
    fun generateQuest(
        type: QuestType,
        questGiver: LivingEntity,
        targetLeaderId: UUID? = null,
        targetLeaderName: String? = null,
        targetSettlementId: UUID? = null,
        giverSettlementId: UUID? = null
    ): Quest? {
        val generator = QuestGenerationController(type, questGiver, targetLeaderName, targetSettlementId)

        // Send the prompt to the AI and parse it into our data container
        return plugin.providerManager.client.sendPromptWithSchema(generator.prompt, GeneratedCharacterDataContainer::class)?.let { data ->

            val finalItem = generator.questItem.item.clone()

            // If it's a delivery quest, dynamically create the lore and name of the item from the AI's response
            if (type == QuestType.MESSAGE_DELIVERY) {
                val meta = finalItem.itemMeta
                if (meta != null) {
                    val itemName = data.questItemName ?: "Important Package"
                    val itemLore = data.questItemDescription ?: "A sealed political item."

                    if (meta is BookMeta) {
                        meta.title = itemName
                        meta.author = questGiver.customName ?: "Leader"
                        meta.pages = listOf(itemLore)
                    } else {
                        meta.setDisplayName("§d$itemName")
                        meta.lore = listOf("§7$itemLore", "", "§8Highly important political document.")
                    }
                    finalItem.itemMeta = meta
                }
            }

            // Return the fully constructed Quest object
            Quest(
                type,
                type.questFamily,
                QuestItem(gson.toJson(finalItem, ItemStack::class.java)),
                questGiver.uniqueId,
                data,
                data.questNames.random(),
                this.incrementQuestID(),
                System.currentTimeMillis(),
                generator.score,
                false,
                0.0,
                targetSettlementId,
                targetLeaderId,
                giverSettlementId,
                0L,
                0L
            )
        }
    }

    /**
     * Cancels an active quest for a specific player.
     */
    fun cancelQuest(player: Player, quest: Quest) {
        progressTracker.stopTracking(player, quest)
        player.removeQuest(quest)
    }

    /**
     * Increments the global server quest counter and saves it to persistent data.
     */
    private fun incrementQuestID(): Long {
        var questCount = totalQuestAmount
        questCount += 1
        Bukkit.getWorlds()[0]!!.persistentDataContainer.set(questCountKey, PersistentDataType.LONG, questCount)
        return questCount
    }

    /**
     * Removes a quest globally and triggers failure/invalidation logic for any players who had it active.
     */
    fun invalidateQuest(quest: Quest, reason: QuestInvalidationEvent.Reason) {
        plugin.gameplayManager.actualQuests.remove(quest.id)
        Bukkit.getOnlinePlayers().forEach { onlinePlayer ->
            onlinePlayer.quests().find { it.id == quest.id }?.let {
                plugin.server.pluginManager.callEvent(QuestInvalidationEvent(onlinePlayer, it, reason))
            }
        }
    }

    /**
     * Handles successful quest completion, distributing rewards, experience, and triggering completion dialogues.
     */
    fun finishQuest(player: Player, questGiver: LivingEntity, quest: Quest, onFinish: () -> Unit = {}) {
        val playerReputation = (quest.score * plugin.gameplayManager.config.quest.reputationMultiplier).toInt()
        val playerExperience = (quest.score * plugin.gameplayManager.config.quest.playerExperienceMultiplier).toInt()
        val villagerExperience = (quest.score * plugin.gameplayManager.config.quest.npcExperienceMultiplier).toInt()

        if (questGiver is Villager) questGiver.villagerExperience += villagerExperience
        player.giveExp(playerExperience)
        plugin.gameplayManager.reputationManager.addReputation(questGiver, player, playerReputation)

        val finishMessage = plugin.language.getString("quest.finished")!!.replace("{quest}", quest.name)
        player.sendFormattedMessage(finishMessage)

        progressTracker.stopTracking(player, quest)
        questGiver.removeQuest(quest)
        player.removeQuest(quest)

        player.questsCompleted += 1
        player.experienceEarnedByQuests += playerExperience

        onFinish.invoke()

        // Invalidate the quest for everyone else so it can't be completed multiple times
        this.invalidateQuest(quest, QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE)

        // Trigger the AI-generated finishing dialogue
        questGiver.talk(player, this.determineFinishingDialogue(player, quest))
    }

    private fun determineFinishingDialogue(player: Player, quest: Quest): String {
        return quest.data.questFinisherDialogue.replace("%playerName%", player.name)
    }

    /**
     * Intercepts merchant trades to process quest turn-ins.
     * If the item being traded matches an active quest item, it triggers the quest completion logic.
     */
    @EventHandler
    fun onMerchantTrade(event: MerchantTradeEvent) {
        event.player.quests().find { it.questItem.getItemStack().isSimilar(event.recipe.ingredients.first()) }?.let { quest ->
            event.player.closeInventory()

            // Handle different types of turn-in logic based on the quest type
            when (quest.type) {
                QuestType.PROFESSION_ITEM_GATHERING, QuestType.SMITHING_TEMPLATE_ORDER, QuestType.ENCHANTED_BOOK_ORDER, QuestType.TREASURE_HUNT -> {
                    this.finishQuest(event.player, event.merchant, quest)
                }
                QuestType.MUSIC_DISC -> {
                    // Custom logic to play the music disc given to the NPC
                    fun getSoundKeyFromMaterial(material: Material): String? {
                        val name = material.name
                        if (!name.startsWith("MUSIC_DISC_")) return null
                        val suffix = name.removePrefix("MUSIC_DISC_").lowercase()
                        return "music_disc.$suffix"
                    }
                    val recordKey = getSoundKeyFromMaterial(quest.questItem.getItemStack().type)!!
                    fun playRecordFollowingNpc(npc: Entity, recordKey: String, source: net.kyori.adventure.sound.Sound.Source = net.kyori.adventure.sound.Sound.Source.RECORD, volume: Float = 1.5f, pitch: Float = 1.0f, radius: Double = 32.0) {
                        val sound = net.kyori.adventure.sound.Sound.sound(Key.key(recordKey), source, volume, pitch)
                        val nearbyPlayers = npc.world.getNearbyEntities(npc.location, radius, radius, radius).filterIsInstance<Player>()
                        if (nearbyPlayers.isEmpty()) return
                        nearbyPlayers.forEach { it.playSound(sound, npc) }
                    }
                    playRecordFollowingNpc(event.merchant, recordKey)
                    this.finishQuest(event.player, event.merchant, quest)
                }
                QuestType.FOOD_SEARCH -> {
                    // Replenish the merchant's hunger
                    event.merchant.eat()
                    this.finishQuest(event.player, event.merchant, quest)
                }
                QuestType.BOOZE -> {
                    // Make the humanoid drink the potion they requested
                    event.merchant.asHumanoid()?.let { humanoid ->
                        val potion = quest.questItem.getItemStack()
                        val effect = (potion.itemMeta as PotionMeta).basePotionType?.potionEffects?.firstOrNull()
                        humanoid.consume(event.merchant.world, potion, Sound.ENTITY_GENERIC_DRINK, 7, event.merchant.location, 7) {
                            effect?.let { event.merchant.addPotionEffect(it) }
                            event.merchant.takeItemFromQuillInventory(potion, 1)
                            this.finishQuest(event.player, event.merchant, quest)
                        }
                    } ?: this.finishQuest(event.player, event.merchant, quest)
                }
                else -> {}
            }
        }
    }

    /**
     * If a villager dies, invalidate all quests associated with them.
     */
    @EventHandler
    private fun onVillagerDeath(event: EntityDeathEvent) {
        (event.entity as? Villager)?.quests()?.forEach { quest ->
            this@QuestManager.invalidateQuest(quest, QuestInvalidationEvent.Reason.NPC_DEATH)
        }
    }

    /**
     * Detects if a quest-critical item (like a delivery letter) takes damage and gets destroyed (e.g., burned in lava).
     */
    @EventHandler
    fun onItemDamage(event: EntityDamageEvent) {
        val entity = event.entity as? Item ?: return
        val questId = entity.itemStack.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "quest_id"), PersistentDataType.LONG) ?: return

        if (event.finalDamage >= entity.health || event.cause == EntityDamageEvent.DamageCause.LAVA || event.cause == EntityDamageEvent.DamageCause.FIRE || event.cause == EntityDamageEvent.DamageCause.VOID) {
            handleItemDestruction(questId)
        }
    }

    /**
     * Detects if a quest-critical item despawns from the world.
     */
    @EventHandler
    fun onItemDespawn(event: ItemDespawnEvent) {
        val questId = event.entity.itemStack.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "quest_id"), PersistentDataType.LONG) ?: return
        handleItemDestruction(questId)
    }

    /**
     * Marks a quest as failed because its required item was destroyed.
     */
    private fun handleItemDestruction(questId: Long) {
        val player = Bukkit.getOnlinePlayers().find { p -> p.quests().any { it.id == questId } } ?: return
        val quest = player.quests().find { it.id == questId } ?: return
        quest.progress = -1.0 // Set progress to -1 to flag it as destroyed
        plugin.server.scheduler.runTask(plugin, Runnable {
            invalidateQuest(quest, QuestInvalidationEvent.Reason.NOT_ACTUAL)
        })
    }

    /**
     * Handles the logic for when a quest becomes invalid or is failed by the player.
     * Decreases relations between settlements if a delivery quest fails.
     */
    @EventHandler
    private fun onQuestInvalidation(event: QuestInvalidationEvent) {
        val player = event.player
        progressTracker.stopTracking(player, event.quest)

        var message = when (event.reason) {
            QuestInvalidationEvent.Reason.NPC_DEATH -> plugin.language.getString("quest.failed.npcDeath")
            QuestInvalidationEvent.Reason.TIME_EXPIRATION -> plugin.language.getString("quest.failed.timeExpiration")
            QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE -> plugin.language.getString("quest.failed.finishedBySomeoneElse")
            QuestInvalidationEvent.Reason.NOT_ACTUAL -> plugin.language.getString("quest.failed.notActual")
        }

        // Apply severe penalties if a political delivery quest is failed or the item is destroyed
        if (event.quest.type == QuestType.MESSAGE_DELIVERY) {
            if (event.quest.progress == -1.0) {
                message = plugin.language.getString("quest.failed.destroyed") ?: "Your quest item was destroyed!"
            }
            if (event.reason == QuestInvalidationEvent.Reason.TIME_EXPIRATION || event.quest.progress == -1.0) {
                val giverSettlement = event.quest.giverSettlementId?.let { SettlementManager.getById(it) }
                val targetSettlement = event.quest.targetSettlementId?.let { SettlementManager.getById(it) }
                if (giverSettlement != null && targetSettlement != null) {
                    SettlementManager.setRelation(giverSettlement, targetSettlement, Settlement.RelationLevel.TENSE)
                }
            }
        }

        player.sendFormattedMessage(message!!.replace("{quest}", event.quest.name))
        player.removeQuest(event.quest)
        player.questsFailed += 1
    }

    /**
     * Handles the moment a player clicks to accept a quest.
     * Validates limits, assigns physical items if needed, and starts tracking.
     */
    @EventHandler
    fun onPlayerAcceptQuest(event: PlayerAcceptQuestEvent) {
        val player = event.player
        val npc = event.questGiver
        val quest = event.quest

        if (player.quests().any { it.id == quest.id }) {
            player.sendFormattedMessage(plugin.language.getString("quest.already-accepted")!!)
            return
        }

        if (player.quests().size >= plugin.gameplayManager.config.quest.playerQuestLimit) {
            val questLimitMessage = plugin.language.getString("quest.limit")!!.replace("{playerQuestLimit}", plugin.gameplayManager.config.quest.playerQuestLimit.toString())
            player.sendFormattedMessage(questLimitMessage)
            return
        }

        // Give the player the physical document to deliver and set the quest deadline
        if (quest.type == QuestType.MESSAGE_DELIVERY) {
            val item = quest.questItem.getItemStack().clone()
            val meta = item.itemMeta
            meta.persistentDataContainer.set(NamespacedKey(plugin, "quest_id"), PersistentDataType.LONG, quest.id)
            item.itemMeta = meta
            player.inventory.addItem(item)

            npc.removeQuest(quest)
            plugin.gameplayManager.actualQuests.remove(quest.id)

            quest.timeLimit = 2 * 60 * 60 * 1000L // 2 hours
            quest.deadline = System.currentTimeMillis() + quest.timeLimit
        }

        player.sendFormattedMessage(plugin.language.getString("quest.accepted")!!.replace("{quest}", quest.name))
        player.sendFormattedMessage(plugin.language.getString("info-messages.quest-chat-info.quest-giver")!!.replace("{npcName}", npc.customName!!).replace("%playerName%", player.name))
        player.sendFormattedMessage(plugin.language.getString("info-messages.quest-chat-info.task-description")!!.replace("{desc}", quest.data.extraShortTaskDescription).replace("%playerName%", player.name))

        // Begin tracking the player's progress on this quest
        if (questTracker[player] == null) {
            questTracker[player] = quest to progressTracker.startTracking(player, quest)
        }

        player.addQuest(quest)
    }

    /**
     * Helper class to map game context and dynamically build the prompt sent to the AI for generating quests.
     */
    class QuestGenerationController(questType: QuestType, private val questGiver: LivingEntity, targetLeaderName: String? = null, targetSettlementId: UUID? = null) {

        private val isDelivery = questType.questFamily == QuestFamily.DELIVERY
        private val baseInfo = if (isDelivery) plugin.gameplayManager.questManager.deliveryDescription else plugin.gameplayManager.questManager.gatheringDescription
        private val questInfo = baseInfo.replace("{taskDescription}", plugin.gameplayManager.questManager.taskDescriptions[questType]!!)

        private val schemaAdditions = if (isDelivery) {
            ",\n  \"questItemName\": \"Creative name for the political item to deliver (e.g. 'Sealed Treaty', 'Cursed Relic', 'Royal Decree').\",\n  \"questItemDescription\": \"Short lore description of the item to be delivered.\""
        } else ""

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
              "questDescription": "A highly immersive, deeply personal first-person dialogue where the NPC explains what they need and why.",
              "questFinisherDialogue": "A highly immersive, deeply personal first-person dialogue where the NPC thanks the player for completing the quest."{schemaAdditions}
            }
        
            ### WRITING STYLE & RULES:
            1. Deep Roleplay: Fully embody the NPC. Tone, vocabulary, and worldview MUST be heavily influenced by the following priority: Global Setting > Personality & Race > Biome > Profession > Gender.
            2. Personal Connection: The NPC must communicate with the player in a highly personal, expressive, and engaging manner in the dialogues.
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
            - Target Settlement (If Delivery): {targetSettlement}
            - Target Leader (If Delivery): {targetLeader}
        """.trimIndent()

        val questItem = questType.strategy.get(questGiver)
        val currency  = questGiver.race.normalCurrency.get() ?: throw NullPointerException("Can't get race normal currency: ${questGiver.race.name}.")
        val amount    = questItem.item.amount
        val score     = (if (questItem.score < currency.getBasicScore()) currency.getBasicScore() * 10 else questItem.score).toLong()

        // Populate placeholders for the prompt to contextualize the AI's generation
        private val placeholders  = mutableMapOf<String, String>().also { it ->
            it["npcPersonality"]  = "${questGiver.getPersonality()}"
            it["npcName"]         = questGiver.customName.toString()
            it["npcGender"]       = questGiver.gender.toString()
            it["npcRace"]         = questGiver.race.name
            it["raceDescription"] = questGiver.race.description
            it["currentBiome"]    = questGiver.location.block.biome.key.key

            val targetSettlement = targetSettlementId?.let { id -> SettlementManager.getById(id) }
            it["targetSettlement"] = targetSettlement?.data?.settlementName ?: "Unknown Town"
            it["targetLeader"] = targetLeaderName ?: "Another Leader"

            (questGiver as? Villager)?.let { villager ->
                it["npcProfession"]      = villager.profession.key.key
                it["npcProfessionLevel"] = villager.professionLevelName
            }

            when (questType) {
                QuestType.BOOZE -> it["potionType"] = (questItem.item.itemMeta as PotionMeta).basePotionType!!.key.key.lowercase().replace("_", " ")
                QuestType.ENCHANTED_BOOK_ORDER -> it["enchantmentType"] = (questItem.item.itemMeta as EnchantmentStorageMeta).storedEnchants.toList().first().first.key.key.replace("_", " ")
                QuestType.TREASURE_HUNT -> it["treasureDescription"] = treasureItems.find { it.first == questItem.item.type }?.third ?: "No extra info."
                else -> { }
            }

            it["rewardItem"]      = currency.name.lowercase().replace("_", " ")
            it["questItem"]       = questItem.key.lowercase().replace("_", " ")
            it["questItemAmount"] = amount.toString()
            it["schemaAdditions"] = schemaAdditions
            it["questInfo"]       = questInfo.replaceMap(it)
        }

        // The finalized prompt ready to be sent to the AI
        val prompt = questPrompt.replaceMap(placeholders)
    }

    /**
     * Data class representing an active or generated quest, holding all contextual information, progress, and AI data.
     */
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
        var progress: Double,
        var targetSettlementId: UUID? = null,
        var targetLeaderId: UUID? = null,
        var giverSettlementId: UUID? = null,
        var deadline: Long = 0L,
        var timeLimit: Long = 0L
    ) {

        /** Wrapper for storing and retrieving the required quest item via JSON serialization. */
        data class QuestItem(val serialized: String) {
            fun getItemStack(): ItemStack = gson.fromJson(serialized, ItemStack::class.java)
        }

        /**
         * Calculates and packages the physical currency reward for finishing the quest.
         * If the amount exceeds a single stack (64), it groups them inside a Bundle.
         */
        fun calculateReward(currency: String): ItemStack {
            val material     = XMaterial.valueOf(currency).get() ?: throw NullPointerException("Can't find material with name $currency.")
            val currencyItem = ItemStack(material)
            var amount       = score / material.getBasicScore()

            // Return a single stack if the amount is less than a full stack limit
            if (amount < 64)
                return currencyItem.apply { this.amount = if (amount <= 0) 1 else amount.toInt() }

            // If the reward is massive, bundle it together
            val items = mutableListOf<ItemStack>()
            while (amount > 0) {
                amount.coerceIn(1, 64).toInt().let { i ->
                    items.add(currencyItem.apply { this.amount = i }); amount -= i;
                }
            }
            return this.bundle(items)
        }

        /**
         * Wraps multiple item stacks into a single Minecraft Bundle item.
         */
        private fun bundle(items: List<ItemStack>): ItemStack {
            return ItemStack(Material.BUNDLE, 1).apply {
                itemMeta = (itemMeta as BundleMeta).apply {
                    items.forEach(::addItem)
                }
            }
        }
    }

    /**
     * Container matching the exact schema returned by the AI provider to map JSON into usable object properties.
     */
    data class GeneratedCharacterDataContainer(val questNames: List<String>,
                                               val extraShortTaskDescription: String,
                                               val shortRequiredQuestItemDescription: String,
                                               val questDescription: String,
                                               val questFinisherDialogue: String,
                                               val questItemName: String? = null,
                                               val questItemDescription: String? = null)

    enum class QuestFamily {
        GATHERING,
        DELIVERY
    }

    enum class QuestType(val questFamily: QuestFamily, val strategy: QuestItemStrategy) {
        PROFESSION_ITEM_GATHERING(QuestFamily.GATHERING, ProfessionItemGatheringQuestItemStrategy()),
        MUSIC_DISC(QuestFamily.GATHERING, MusicDiscQuestItemStrategy()),
        FOOD_SEARCH(QuestFamily.GATHERING, FoodSearchQuestItemStrategy()),
        BOOZE(QuestFamily.GATHERING, BoozeQuestItemStrategy()),
        SMITHING_TEMPLATE_ORDER(QuestFamily.GATHERING, SmithingTemplateQuestItemStrategy()),
        ENCHANTED_BOOK_ORDER(QuestFamily.GATHERING, EnchantedBookQuestItemStrategy()),
        TREASURE_HUNT(QuestFamily.GATHERING, TreasureHuntQuestItemStrategy()),
        MESSAGE_DELIVERY(QuestFamily.DELIVERY, MessageDeliveryQuestItemStrategy())
    }

    companion object {
        /** Utility function to replace a map of keys with their corresponding values in a String. */
        fun String.replaceMap(replacements: Map<String, String>): String {
            var result = this
            for ((key, value) in replacements) {
                result = result.replace("{${key}}", value)
            }
            return result
        }

        /** Adds a quest to a living entity's persistent data storage. */
        fun LivingEntity.addQuest(quest: Quest) {
            persistentDataContainer.set(questDataKey, PersistentDataType.STRING, gson.toJson(this.quests().apply { add(quest) }))
        }

        /** Removes a specific quest from a living entity's persistent data storage. */
        fun LivingEntity.removeQuest(quest: Quest) {
            persistentDataContainer.set(questDataKey, PersistentDataType.STRING, gson.toJson(this.quests().apply { removeIf { it.id == quest.id } }))
        }
    }
}
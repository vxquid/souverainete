package vx.sv.gameplay.quest

import com.cryptomorin.xseries.XMaterial
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.entity.Villager.Profession
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.gameplay.achievement.AchievementManager
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
import vx.sv.gameplay.trade.ScoreCalculator.getBasicScore
import vx.sv.nms.VersionBridge.Companion.asHumanoid
import vx.sv.persistent.LivingEntityExtend.hasEdibleItem
import vx.sv.persistent.LivingEntityExtend.hunger
import vx.sv.persistent.LivingEntityExtend.professionLevelName
import vx.sv.persistent.LivingEntityExtend.questDataKey
import vx.sv.persistent.LivingEntityExtend.quests
import vx.sv.persistent.LivingEntityExtend.settlement
import vx.sv.util.VivaldiHook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.*

class QuestManager : Listener {

    val progressTracker = ProgressTracker()
    val questCountKey = NamespacedKey(plugin, "TotalQuestCount")

    val totalQuestAmount: Long
        get() = Bukkit.getWorlds()[0]!!.persistentDataContainer.get(questCountKey, PersistentDataType.LONG) ?: 0

    private var gatheringDescription: String
    private var deliveryDescription: String
    private val taskDescriptions = mutableMapOf<QuestType, String>()
    private val allowedQuestTypes = mutableSetOf<QuestType>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 0, plugin.gameplayManager.config.quest.intervalTicks)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { politicalTick() }, 100L, plugin.gameplayManager.config.quest.intervalTicks)

        gatheringDescription = plugin.prompts.getString("quest-family.gathering.quest-description")
            ?: "To complete the quest, the player will need to obtain an item `{questItem}` in the amount of {questItemAmount} and bring it to the NPC. The NPC promises a reward ({rewardItem}) for the assistance, without specifying what exactly it will be. When generating the quest, be sure to thoughtfully consider this information. In addition to the previous requirements, follow these guidelines during the generation: {taskDescription}."

        deliveryDescription = plugin.prompts.getString("quest-family.delivery.quest-description")
            ?: "The player must deliver a highly important sealed political package to {targetLeader}, the leader of {targetSettlement}. The NPC promises a reward ({rewardItem}) upon delivery. {taskDescription}."

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
        val world = plugin.gameplayManager.allowedWorlds.filter { it.entities.filterIsInstance<Villager>().isNotEmpty() }.randomOrNull() ?: return
        val villager = this.selectRandomVillager(world) ?: return

        if (Bukkit.getOnlinePlayers().count() == 0) return
        if (villager.profession == Profession.NONE) return
        if (villager.quests().size > plugin.gameplayManager.config.quest.npcQuestBase + villager.villagerLevel) return

        val allowedQuests = mutableListOf(QuestType.PROFESSION_ITEM_GATHERING, QuestType.BOOZE, QuestType.MUSIC_DISC)
            .filter { it in allowedQuestTypes }.toMutableList()

        val hasEdible = villager.hasEdibleItem() || (villager.settlement?.villageInventory?.any { it.type.isEdible } == true)

        val questType = if (villager.hunger <= plugin.gameplayManager.config.hunger.questThreshold && !hasEdible && QuestType.FOOD_SEARCH in allowedQuestTypes) {
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

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                plugin.gameplayManager.questManager.generateQuest(
                    questType, villager, null, null, null, null
                )?.let { quest ->
                    plugin.gameplayManager.actualQuests.add(quest.id)
                    villager.addQuest(quest)
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
        })
    }

    fun politicalTick() {
        if (QuestType.MESSAGE_DELIVERY !in allowedQuestTypes) return

        val world = plugin.gameplayManager.allowedWorlds.filter {
            !SettlementManager.settlements[it].isNullOrEmpty()
        }.randomOrNull() ?: return

        val worldSettlements = SettlementManager.settlements[world] ?: return

        val validSettlements = worldSettlements.filter { settlement ->
            settlement.data.leaderId != null &&
                    settlement.villagers.any { v -> v.uniqueId == settlement.data.leaderId && v.isValid }
        }

        if (validSettlements.size < 2) return

        val giverSettlement = validSettlements.random()
        val targetSettlement = validSettlements.filter { it.data.id != giverSettlement.data.id }.randomOrNull() ?: return
        val giverLeader = giverSettlement.villagers.find { it.uniqueId == giverSettlement.data.leaderId && it.isValid } ?: return

        if (giverLeader.quests().size > plugin.gameplayManager.config.quest.npcQuestBase + giverLeader.villagerLevel) return

        val targetLeaderId = targetSettlement.data.leaderId ?: return

        val targetLeaderName = targetSettlement.data.leaderName
            ?: targetSettlement.villagers.find { it.uniqueId == targetLeaderId }?.customName
            ?: "Unknown Leader"

        val targetSettlementId = targetSettlement.data.id
        val giverSettlementId = giverSettlement.data.id

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                plugin.gameplayManager.questManager.generateQuest(
                    QuestType.MESSAGE_DELIVERY,
                    giverLeader,
                    targetLeaderId,
                    targetLeaderName,
                    targetSettlementId,
                    giverSettlementId
                )?.let { quest ->
                    plugin.gameplayManager.actualQuests.add(quest.id)
                    giverLeader.addQuest(quest)
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
        })
    }

    private fun selectRandomVillager(world: World): Villager? {
        return world.entities.filterIsInstance<Villager>().onEach { villager ->
            villager.quests().forEach { quest ->
                if ((System.currentTimeMillis() - quest.timeCreated) / 1000 * 20 > plugin.gameplayManager.config.quest.lifetimeDuration) {
                    this.invalidateQuest(quest, QuestInvalidationEvent.Reason.TIME_EXPIRATION)
                    villager.removeQuest(quest)
                }
            }
        }.filter { it.quests().size < it.villagerLevel + plugin.gameplayManager.config.quest.npcQuestBase }.randomOrNull()
    }

    fun generateQuest(
        type: QuestType,
        questGiver: LivingEntity,
        targetLeaderId: UUID? = null,
        targetLeaderName: String? = null,
        targetSettlementId: UUID? = null,
        giverSettlementId: UUID? = null
    ): Quest? {
        val generator = QuestGenerationController(type, questGiver, targetLeaderName, targetSettlementId, giverSettlementId)

        return plugin.providerManager.client.sendPromptWithSchema(generator.prompt, GeneratedCharacterDataContainer::class)?.let { data ->

            val finalItem = generator.questItem.item.clone()

            if (type == QuestType.MESSAGE_DELIVERY) {
                val meta = finalItem.itemMeta as? SkullMeta
                if (meta != null) {
                    val defaultItemName = plugin.language.getString("quest.delivery.default-item-name") ?: "Sealed Package"
                    val defaultItemLore = plugin.language.getString("quest.delivery.default-item-lore") ?: "A highly important sealed parcel."

                    val itemName = data.questItemName ?: defaultItemName
                    val itemLore = data.questItemDescription ?: defaultItemLore

                    meta.setDisplayName("§d$itemName")

                    val loreFormat = plugin.language.getStringList("quest.delivery.package-lore")
                    if (loreFormat.isNotEmpty()) {
                        meta.lore = loreFormat.map { it.replace("{itemLore}", itemLore) }
                    } else {
                        meta.lore = listOf(
                            "§7$itemLore",
                            "",
                            "§8Highly important political parcel.",
                            "§e[Right-Click] §7to break the seal and open.",
                            "§4WARNING: Opening this will fail the quest!"
                        )
                    }

                    try {
                        val profile = Bukkit.createPlayerProfile(UUID.randomUUID())
                        val textures = profile.textures
                        textures.skin = URL("http://textures.minecraft.net/texture/131c54ff62535750bf3a0cd15bdf848de8d83da1664ab91acfd6dec617552439")
                        profile.setTextures(textures)
                        meta.ownerProfile = profile
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to apply package texture: ${e.message}")
                    }

                    val defaultSender = plugin.language.getString("quest.delivery.default-sender") ?: "Leader"
                    val bookTitleFormat = plugin.language.getString("quest.delivery.book-title") ?: "§6Letter from {sender}"
                    val senderName = questGiver.customName ?: defaultSender

                    val letterBook = ItemStack(Material.WRITTEN_BOOK)
                    val bookMeta = letterBook.itemMeta as BookMeta
                    bookMeta.title = bookTitleFormat.replace("{sender}", senderName)
                    bookMeta.author = senderName

                    val defaultLetterContent = plugin.language.getString("quest.delivery.default-letter-content") ?: "We must stand together."
                    val content = data.letterContent ?: defaultLetterContent
                    bookMeta.pages = content.chunked(250)
                    letterBook.itemMeta = bookMeta

                    val loot = mutableListOf<ItemStack>(letterBook)
                    if (Math.random() > 0.5) loot.add(ItemStack(Material.DIAMOND, (1..3).random()))
                    if (Math.random() > 0.3) loot.add(ItemStack(Material.GOLD_INGOT, (3..10).random()))
                    if (Math.random() > 0.7) loot.add(ItemStack(Material.EMERALD, (1..5).random()))

                    meta.persistentDataContainer.set(NamespacedKey(plugin, "package_contents"), PersistentDataType.STRING, serializeItems(loot))
                    finalItem.itemMeta = meta
                }
            }

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

    @EventHandler
    fun onPackageInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        if (item.type != Material.PLAYER_HEAD) return

        val meta = item.itemMeta ?: return
        val packageKey = NamespacedKey(plugin, "package_contents")

        if (meta.persistentDataContainer.has(packageKey, PersistentDataType.STRING)) {
            event.isCancelled = true

            val questId = meta.persistentDataContainer.get(NamespacedKey(plugin, "quest_id"), PersistentDataType.LONG)
            val contentsRaw = meta.persistentDataContainer.get(packageKey, PersistentDataType.STRING)

            if (contentsRaw != null) {
                val items = deserializeItems(contentsRaw)
                items.forEach { event.player.world.dropItemNaturally(event.player.location, it) }
            }

            event.player.playSound(event.player.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
            val msg = plugin.language.getString("quest.seal-broken") ?: "§cYou broke the seal and stole the contents!"
            event.player.sendFormattedMessage(msg)

            item.amount -= 1

            if (questId != null) {
                val player = event.player
                val quest = player.quests().find { it.id == questId }
                if (quest != null) {
                    quest.progress = -2.0

                    // ВЫДАЕМ АЧИВКУ КУРЬЕРА-ПРЕДАТЕЛЯ
                    AchievementManager.grant(player, "courier_betrayal")

                    val giverSettlementId = quest.giverSettlementId
                    if (giverSettlementId != null) {
                        val giverSettlement = SettlementManager.getById(giverSettlementId)
                        if (giverSettlement != null && giverSettlement.territory.contains(player.location.toVector())) {
                            player.sendFormattedMessage("§4[!] §cYou were caught breaking the seal within ${giverSettlement.data.settlementName}'s territory! They are outraged!")
                            plugin.gameplayManager.reputationManager.addReputation(giverSettlement, player, -1000)
                        }
                    }

                    plugin.server.scheduler.runTask(plugin, Runnable {
                        invalidateQuest(quest, QuestInvalidationEvent.Reason.NOT_ACTUAL)
                    })
                }
            }
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

    fun invalidateQuest(quest: Quest, reason: QuestInvalidationEvent.Reason) {
        plugin.gameplayManager.actualQuests.remove(quest.id)
        Bukkit.getOnlinePlayers().forEach { onlinePlayer ->
            onlinePlayer.quests().find { it.id == quest.id }?.let {
                plugin.server.pluginManager.callEvent(QuestInvalidationEvent(onlinePlayer, it, reason))
            }
        }
    }

    fun finishQuest(player: Player, questGiver: LivingEntity, quest: Quest, onFinish: () -> Unit = {}) {
        val playerReputation = (quest.score * plugin.gameplayManager.config.quest.reputationMultiplier).toInt()
        val playerExperience = (quest.score * plugin.gameplayManager.config.quest.playerExperienceMultiplier).toInt()
        val villagerExperience = (quest.score * plugin.gameplayManager.config.quest.npcExperienceMultiplier).toInt()

        if (questGiver is Villager) questGiver.villagerExperience += villagerExperience
        player.giveExp(playerExperience)

        val settlement = questGiver.settlement
        if (settlement != null) {
            plugin.gameplayManager.reputationManager.addReputation(settlement, player, playerReputation)
        } else {
            plugin.gameplayManager.reputationManager.addReputation(questGiver, player, playerReputation)
        }

        val finishMessage = plugin.language.getString("quest.finished")!!.replace("{quest}", quest.name)
        player.sendFormattedMessage(finishMessage)

        progressTracker.stopTracking(player, quest)
        questGiver.removeQuest(quest)
        player.removeQuest(quest)

        if (quest.type == QuestType.MESSAGE_DELIVERY && quest.giverSettlementId != null && quest.targetSettlementId != null) {
            val giverSettlement = SettlementManager.getById(quest.giverSettlementId!!)
            val targetSettlement = SettlementManager.getById(quest.targetSettlementId!!)
            if (giverSettlement != null && targetSettlement != null) {
                val currentRelation = SettlementManager.getRelation(giverSettlement, targetSettlement)
                val levels = Settlement.RelationLevel.entries.toTypedArray()
                val newLevel = levels[minOf(levels.size - 1, currentRelation.ordinal + 1)]

                SettlementManager.setRelation(giverSettlement, targetSettlement, newLevel)

                val defaultItemName = plugin.language.getString("quest.delivery.default-item-name") ?: "Sealed Package"
                val itemName = quest.data.questItemName ?: defaultItemName
                val record = "SUCCESS: Player '${player.name}' successfully delivered '$itemName'. Relations improved to ${newLevel.name}."
                SettlementManager.recordDiplomaticEvent(giverSettlement, targetSettlement, record)

                val impactMsg = plugin.language.getString("quest.diplomatic-success")
                    ?.replace("{settlementA}", giverSettlement.data.settlementName)
                    ?.replace("{settlementB}", targetSettlement.data.settlementName)
                    ?: "§6[Diplomacy] §eYour actions have positively influenced the relations between §b${giverSettlement.data.settlementName} §eand §b${targetSettlement.data.settlementName}§e!"
                player.sendFormattedMessage(impactMsg)

                // ВЫДАЕМ АЧИВКУ МЕДИАТОРА (ЗА УСПЕШНУЮ ДОСТАВКУ)
                AchievementManager.grant(player, "peacemaker")
            }
        }

        player.questsCompleted += 1
        player.experienceEarnedByQuests += playerExperience

        // ВЫДАЕМ АЧИВКИ ЗА КВЕСТЫ (НОВИЧОК И МАСТЕР)
        AchievementManager.grant(player, "quest_novice")
        if (player.questsCompleted >= 10) {
            AchievementManager.grant(player, "quest_master")
        }

        onFinish.invoke()
        this.invalidateQuest(quest, QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE)
        questGiver.talk(player, this.determineFinishingDialogue(player, quest))
    }

    private fun determineFinishingDialogue(player: Player, quest: Quest): String {
        return quest.data.questFinisherDialogue.replace("%playerName%", player.name)
    }

    @EventHandler
    fun onMerchantTrade(event: MerchantTradeEvent) {
        val villager = event.merchant as? Villager ?: return

        event.player.quests().find { it.questItem.getItemStack().isSimilar(event.recipe.ingredients.first()) }?.let { quest ->
            event.player.closeInventory()
            when (quest.type) {
                QuestType.PROFESSION_ITEM_GATHERING, QuestType.SMITHING_TEMPLATE_ORDER, QuestType.ENCHANTED_BOOK_ORDER, QuestType.TREASURE_HUNT, QuestType.MESSAGE_DELIVERY -> {
                    val settlement = villager.settlement
                    if (settlement != null) {
                        val personalInv = villager.inventory
                        val virtualInv = settlement.villageInventory

                        val itemsToTransfer = personalInv.contents.filterNotNull().filter { item ->
                            val type = item.type
                            !type.name.contains("SWORD") && !type.name.contains("AXE") &&
                                    !type.name.contains("PICKAXE") && !type.name.contains("HELMET") &&
                                    !type.name.contains("CHESTPLATE") && !type.name.contains("LEGGINGS") &&
                                    !type.name.contains("BOOTS")
                        }

                        for (item in itemsToTransfer) {
                            personalInv.removeItem(item)
                            val maxStack = item.type.maxStackSize
                            var remaining = item.amount
                            for (stored in virtualInv) {
                                if (stored.isSimilar(item)) {
                                    val space = maxStack - stored.amount
                                    if (space > 0) {
                                        val toAdd = minOf(space, remaining)
                                        stored.amount += toAdd
                                        remaining -= toAdd
                                        if (remaining <= 0) break
                                    }
                                }
                            }
                            while (remaining > 0) {
                                val copy = item.clone()
                                val toAdd = minOf(maxStack, remaining)
                                copy.amount = toAdd
                                virtualInv.add(copy)
                                remaining -= toAdd
                            }
                        }
                        SettlementManager.saveSettlements(villager.world)
                    }
                    this.finishQuest(event.player, villager, quest)
                }
                QuestType.MUSIC_DISC -> {
                    fun getSoundKeyFromMaterial(material: Material): String? {
                        val name = material.name
                        if (!name.startsWith("MUSIC_DISC_")) return null
                        val suffix = name.removePrefix("MUSIC_DISC_").lowercase()
                        return "music_disc.$suffix"
                    }
                    val recordKey = getSoundKeyFromMaterial(quest.questItem.getItemStack().type)!!
                    fun playRecordFollowingNpc(npc: Entity, recordKey: String, source: net.kyori.adventure.sound.Sound.Source = net.kyori.adventure.sound.Sound.Source.RECORD, volume: Float = 1.5f, pitch: Float = 1.0f, radius: Double = 32.0) {
                        val sound = net.kyori.adventure.sound.Sound.sound(net.kyori.adventure.key.Key.key(recordKey), source, volume, pitch)
                        val nearbyPlayers = npc.world.getNearbyEntities(npc.location, radius, radius, radius).filterIsInstance<Player>()
                        if (nearbyPlayers.isEmpty()) return
                        nearbyPlayers.forEach { it.playSound(sound, npc) }
                    }
                    playRecordFollowingNpc(villager, recordKey)

                    val settlement = villager.settlement
                    if (settlement != null) {
                        val personalInv = villager.inventory
                        val virtualInv = settlement.villageInventory
                        val disc = quest.questItem.getItemStack()
                        val found = personalInv.filterNotNull().find { it.isSimilar(disc) }
                        if (found != null) {
                            personalInv.removeItem(found)
                            virtualInv.add(found)
                            SettlementManager.saveSettlements(villager.world)
                        }
                    }
                    this.finishQuest(event.player, villager, quest)
                }
                QuestType.FOOD_SEARCH -> {
                    val settlement = villager.settlement
                    val edibleInVillage = settlement?.villageInventory?.find { it.type.isEdible }

                    if (edibleInVillage != null) {
                        villager.eat()
                        edibleInVillage.amount -= 1
                        if (edibleInVillage.amount <= 0) {
                            settlement.villageInventory.remove(edibleInVillage)
                        }
                        SettlementManager.saveSettlements(villager.world)
                    } else {
                        villager.eat()
                    }
                    this.finishQuest(event.player, villager, quest)
                }
                QuestType.BOOZE -> {
                    villager.asHumanoid()?.let { humanoid ->
                        val potion = quest.questItem.getItemStack()
                        val effect = (potion.itemMeta as PotionMeta).basePotionType?.potionEffects?.firstOrNull()
                        humanoid.consume(villager.world, potion, Sound.ENTITY_GENERIC_DRINK, 7, villager.location, 7) {
                            effect?.let { villager.addPotionEffect(it) }

                            val inv = villager.inventory
                            val found = inv.filterNotNull().find { it.isSimilar(potion) }
                            if (found != null) {
                                if (found.amount <= 1) {
                                    inv.removeItem(found)
                                } else {
                                    found.amount -= 1
                                }
                            } else {
                                val settlement = villager.settlement
                                val virtualInv = settlement?.villageInventory
                                val virtualFound = virtualInv?.find { it.isSimilar(potion) }
                                if (virtualFound != null) {
                                    virtualFound.amount -= 1
                                    if (virtualFound.amount <= 0) {
                                        virtualInv.remove(virtualFound)
                                    }
                                    SettlementManager.saveSettlements(villager.world)
                                }
                            }
                            this.finishQuest(event.player, villager, quest)
                        }
                    } ?: this.finishQuest(event.player, villager, quest)
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
    fun onItemDamage(event: EntityDamageEvent) {
        val entity = event.entity as? Item ?: return
        val questId = entity.itemStack.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "quest_id"), PersistentDataType.LONG) ?: return

        if (event.finalDamage >= entity.health || event.cause == EntityDamageEvent.DamageCause.LAVA || event.cause == EntityDamageEvent.DamageCause.FIRE || event.cause == EntityDamageEvent.DamageCause.VOID) {
            handleItemDestruction(questId)
        }
    }

    @EventHandler
    fun onItemDespawn(event: ItemDespawnEvent) {
        val questId = event.entity.itemStack.itemMeta?.persistentDataContainer?.get(NamespacedKey(plugin, "quest_id"), PersistentDataType.LONG) ?: return
        handleItemDestruction(questId)
    }

    private fun handleItemDestruction(questId: Long) {
        val player = Bukkit.getOnlinePlayers().find { p -> p.quests().any { it.id == questId } } ?: return
        val quest = player.quests().find { it.id == questId } ?: return
        quest.progress = -1.0
        plugin.server.scheduler.runTask(plugin, Runnable {
            invalidateQuest(quest, QuestInvalidationEvent.Reason.NOT_ACTUAL)
        })
    }

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

        if (event.quest.type == QuestType.MESSAGE_DELIVERY) {
            var reasonStr = "Unknown reason"

            if (event.quest.progress == -1.0) {
                message = plugin.language.getString("quest.failed.destroyed") ?: "Quest §6{quest} §7was failed; your quest item was destroyed!"
                reasonStr = "Item was destroyed"
            } else if (event.quest.progress == -2.0) {
                message = plugin.language.getString("quest.failed.seal-broken") ?: "Quest §6{quest} §7was failed; you broke the seal and stole the contents!"
                reasonStr = "Courier betrayed us, broke the seal and stole the contents"
            } else if (event.reason == QuestInvalidationEvent.Reason.TIME_EXPIRATION) {
                reasonStr = "Time expired"
            } else if (event.reason == QuestInvalidationEvent.Reason.NPC_DEATH) {
                reasonStr = "A leader died"
            }

            val giverSettlement = event.quest.giverSettlementId?.let { SettlementManager.getById(it) }
            val targetSettlement = event.quest.targetSettlementId?.let { SettlementManager.getById(it) }

            if (giverSettlement != null && targetSettlement != null) {
                val defaultItemName = plugin.language.getString("quest.delivery.default-item-name") ?: "Sealed Package"
                val itemName = event.quest.data.questItemName ?: defaultItemName

                val currentRelation = SettlementManager.getRelation(giverSettlement, targetSettlement)
                val levels = Settlement.RelationLevel.entries.toTypedArray()

                val newLevel = levels[maxOf(0, currentRelation.ordinal - 1)]

                SettlementManager.setRelation(giverSettlement, targetSettlement, newLevel)

                val record = "FAILURE: Player '${player.name}' failed to deliver '$itemName' ($reasonStr). Relations worsened to ${newLevel.name}."
                SettlementManager.recordDiplomaticEvent(giverSettlement, targetSettlement, record)

                val impactMsg = plugin.language.getString("quest.diplomatic-fail")
                    ?.replace("{settlementA}", giverSettlement.data.settlementName)
                    ?.replace("{settlementB}", targetSettlement.data.settlementName)
                    ?: "§4[Diplomacy] §cYour actions have worsened the relations between §b${giverSettlement.data.settlementName} §cand §b${targetSettlement.data.settlementName}§c."
                player.sendFormattedMessage(impactMsg)
            }
        }

        player.sendFormattedMessage(message!!.replace("{quest}", event.quest.name))
        player.removeQuest(event.quest)
        player.questsFailed += 1
    }

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

        if (quest.type == QuestType.MESSAGE_DELIVERY) {
            val item = quest.questItem.getItemStack().clone()
            val meta = item.itemMeta
            meta.persistentDataContainer.set(NamespacedKey(plugin, "quest_id"), PersistentDataType.LONG, quest.id)
            item.itemMeta = meta
            player.inventory.addItem(item)

            npc.removeQuest(quest)
            plugin.gameplayManager.actualQuests.remove(quest.id)

            quest.timeLimit = 2 * 60 * 60 * 1000L
            quest.deadline = System.currentTimeMillis() + quest.timeLimit
        }

        player.sendFormattedMessage(plugin.language.getString("quest.accepted")!!.replace("{quest}", quest.name))
        player.sendFormattedMessage(plugin.language.getString("info-messages.quest-chat-info.quest-giver")!!.replace("{npcName}", npc.customName!!).replace("%playerName%", player.name))
        player.sendFormattedMessage(plugin.language.getString("info-messages.quest-chat-info.task-description")!!.replace("{desc}", quest.data.extraShortTaskDescription).replace("%playerName%", player.name))

        if (questTracker[player] == null) {
            questTracker[player] = quest to progressTracker.startTracking(player, quest)
        }

        player.addQuest(quest)
    }

    class QuestGenerationController(
        questType: QuestType,
        private val questGiver: LivingEntity,
        targetLeaderName: String? = null,
        targetSettlementId: UUID? = null,
        giverSettlementId: UUID? = null
    ) {

        private val isDelivery = questType.questFamily == QuestFamily.DELIVERY
        private val baseInfo = if (isDelivery) plugin.gameplayManager.questManager.deliveryDescription else plugin.gameplayManager.questManager.gatheringDescription
        private val questInfo = baseInfo.replace("{taskDescription}", plugin.gameplayManager.questManager.taskDescriptions[questType]!!)

        private val currentSeason = VivaldiHook.getCurrentSeasonName()

        private val diplomaticContext = if (isDelivery) {
            """
            
            ### DIPLOMATIC CONTEXT (HIGHEST PRIORITY):
            - Sender Settlement (Quest Giver's Home): {giverSettlement}
            - Sender Leader (Quest Giver): {npcName}
            - Receiver Settlement (Target Destination): {targetSettlement}
            - Receiver Leader (Target Leader): {targetLeader}
            - Current Diplomatic Relation: {relationLevel}
            - Diplomatic History: {diplomaticHistory}
            
            CRITICAL INSTRUCTION 1: This is a diplomatic interaction between {giverSettlement} and {targetSettlement}. You MUST explicitly address the receiving leader by their actual name: "{targetLeader}". Do NOT invent a random name for the receiver. The dialogue and letter content MUST be heavily driven by their 'Diplomatic History' and 'Current Diplomatic Relation'. This history takes absolute precedence over all other factors.
            CRITICAL INSTRUCTION 2: The 'questDescription' is spoken by {npcName} (Sender). BUT the 'questFinisherDialogue' MUST be written from the perspective of {targetLeader} (Receiver) reacting to the letter!
            """.trimIndent()
        } else ""

        private val roleplayPriority = if (isDelivery) {
            if (currentSeason != null) "Diplomatic History & Relations > Seasonal Setting > Global Setting"
            else "Diplomatic History & Relations > Global Setting"
        } else {
            if (currentSeason != null) "Seasonal Setting > Global Setting"
            else "Global Setting"
        }

        private val schemaAdditions = if (isDelivery) {
            ",\n  \"questItemName\": \"Creative name for the sealed package (e.g. 'Sealed Diplomatic Pouch').\",\n  \"questItemDescription\": \"Short lore description of the package.\",\n  \"letterContent\": \"The actual text of the secret letter inside the package (written in 1st person from {npcName} to {targetLeader}. Be expressive!)\""
        } else ""

        private val seasonContextString = if (currentSeason != null) "\n    - Current Season: $currentSeason" else ""

        private val questPrompt = """
            You are an expert game narrative designer and voice actor. Your task is to generate a quest for an NPC.
            Respond ONLY with valid, parseable JSON. Do not include markdown code blocks (like ```json), explanations, or any other text.
        
            ### JSON SCHEMA TO FOLLOW:
            {
              "questNames":[
                // Array of 5 short, creative, and distinct quest names
              ],
              "extraShortTaskDescription": "Extremely short description of the task (Goal, Quest Giver Name, Amount).",
              "shortRequiredQuestItemDescription": "Literally one sentence describing the item in the context of the quest (written in third-person perspective).",
              "questDescription": "A highly immersive, deeply personal first-person dialogue where the NPC explains what they need and why.",
              "shortQuestDescription": "A concise, 1-2 sentence alternative version of 'questDescription', written in 1st person. Tell the exact task and reason without fluff.",
              "questFinisherDialogue": "A highly immersive, deeply personal first-person dialogue where the TARGET LEADER ({targetLeader}) reads the letter and thanks the player."{schemaAdditions}
            }
        
            ### WRITING STYLE & RULES:
            1. Deep Roleplay: Fully embody the NPC. Tone, vocabulary, and worldview MUST be heavily influenced by the following priority: {roleplayPriority} > Personality & Race > Biome > Profession > Gender.
            2. Personal Connection: The NPC must communicate with the player in a highly personal, expressive, and engaging manner in the dialogues.
            3. Clear Requests & Motives: In the dialogue, the NPC must explicitly state WHAT they need the player to do and explain WHY they need it done.
            4. Player Placeholder: Whenever the NPC addresses the player directly, use exactly "%playerName%".{diplomaticContext}
        
            ### NPC & QUEST CONTEXT:
            - Global Setting: {globalSetting}{seasonContextString}
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
            it["globalSetting"]       = plugin.prompts.getString("global-setting") ?: "A medieval fantasy world."
            it["seasonContextString"] = seasonContextString
            it["npcPersonality"]      = "${questGiver.getPersonality()}"
            it["npcName"]             = questGiver.customName.toString()
            it["npcGender"]           = questGiver.gender.toString().lowercase()
            it["npcRace"]             = questGiver.race.name
            it["raceDescription"]     = questGiver.race.description
            it["currentBiome"]        = questGiver.location.block.biome.key.key

            val giverSettlementObj = giverSettlementId?.let { id -> SettlementManager.getById(id) } ?: questGiver.settlement
            val targetSettlementObj = targetSettlementId?.let { id -> SettlementManager.getById(id) }

            val giverNameStr = giverSettlementObj?.data?.settlementName ?: "Unknown Town"
            val targetNameStr = targetSettlementObj?.data?.settlementName ?: "Unknown Town"

            var relationLevel = "NEUTRAL"
            var historyStr = "This is the beginning of diplomatic relations between $giverNameStr and $targetNameStr."

            if (giverSettlementObj != null && targetSettlementObj != null) {
                relationLevel = SettlementManager.getRelation(giverSettlementObj, targetSettlementObj).name

                val rawHistory = giverSettlementObj.data.diplomaticHistory?.get(targetSettlementId!!)
                if (!rawHistory.isNullOrEmpty()) {
                    historyStr = rawHistory.joinToString("\n- ", prefix = "- ")
                }
            }

            it["giverSettlement"] = giverNameStr
            it["targetSettlement"] = targetNameStr
            it["targetLeader"] = targetLeaderName ?: "Another Leader"
            it["relationLevel"] = relationLevel
            it["diplomaticHistory"] = historyStr
            it["roleplayPriority"] = roleplayPriority
            it["diplomaticContext"] = diplomaticContext

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
        var progress: Double,
        var targetSettlementId: UUID? = null,
        var targetLeaderId: UUID? = null,
        var giverSettlementId: UUID? = null,
        var deadline: Long = 0L,
        var timeLimit: Long = 0L
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
                                               val questDescription: String,
                                               val shortQuestDescription: String? = null,
                                               val questFinisherDialogue: String,
                                               val questItemName: String? = null,
                                               val questItemDescription: String? = null,
                                               val letterContent: String? = null)

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

        fun serializeItems(items: List<ItemStack>): String {
            try {
                val io = ByteArrayOutputStream()
                val os = BukkitObjectOutputStream(io)
                os.writeInt(items.size)
                for (item in items) os.writeObject(item)
                os.flush()
                return Base64.getEncoder().encodeToString(io.toByteArray())
            } catch (e: Exception) {
                e.printStackTrace()
                return ""
            }
        }

        fun deserializeItems(data: String): List<ItemStack> {
            val items = mutableListOf<ItemStack>()
            try {
                val bytes = Base64.getDecoder().decode(data)
                val io = ByteArrayInputStream(bytes)
                val `is` = BukkitObjectInputStream(io)
                val size = `is`.readInt()
                for (i in 0 until size) items.add(`is`.readObject() as ItemStack)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return items
        }
    }
}
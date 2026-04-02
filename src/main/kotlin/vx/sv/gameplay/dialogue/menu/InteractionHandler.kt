package vx.sv.gameplay.dialogue.menu

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.premium
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.config.lib.GameplayConfiguration.DialogueConfig.DialogueFormat
import vx.sv.event.VillagerKillTargetEvent
import vx.sv.event.VillagerStartFightEvent
import vx.sv.gameplay.dialogue.DialogueManager
import vx.sv.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundAlpha
import vx.sv.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundBlue
import vx.sv.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundGreen
import vx.sv.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundRed
import vx.sv.gameplay.dialogue.DialogueManager.Companion.dialogueFormat
import vx.sv.gameplay.dialogue.DialogueManager.Companion.dialogues
import vx.sv.gameplay.dialogue.DialogueManager.Companion.shout
import vx.sv.gameplay.dialogue.DialogueManager.Companion.talk
import vx.sv.gameplay.dialogue.DialogueSession
import vx.sv.gameplay.dialogue.DialogueSession.Companion.getActiveDialogueSession
import vx.sv.gameplay.event.PlayerAcceptQuestEvent
import vx.sv.gameplay.event.QuestInvalidationEvent
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.party.PartyManager.CombatTactic
import vx.sv.gameplay.party.PartyManager.PartyState
import vx.sv.gameplay.quest.QuestManager
import vx.sv.gameplay.quest.QuestManager.Companion.removeQuest
import vx.sv.gameplay.quest.QuestManager.Quest
import vx.sv.gameplay.reputation.ReputationManager.Companion.opinionOn
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.getLedSettlementId
import vx.sv.gameplay.trade.TradeManager.Companion.openTradeMenu
import vx.sv.persistent.LivingEntityExtend.quests
import vx.sv.persistent.MenuControlMode
import vx.sv.persistent.PlayerPreferencesManager.preferences
import vx.sv.persistent.QuestDialogueLength

class InteractionHandler : Listener {

    private val partyManager by lazy {
        plugin.gameplayManager.partyManager
    }

    private val lastInteraction = mutableMapOf<Player, Long>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            openedMenuList.toList().forEach(Menu::relocate)
        }, 0L, 1L)
    }

    companion object {
        val openedMenuList: MutableList<Menu> = mutableListOf()

        val defaultButtonColor = Color.fromARGB(
            dialogueBackgroundAlpha,
            dialogueBackgroundRed,
            dialogueBackgroundGreen,
            dialogueBackgroundBlue
        )
    }

    private fun tryHandleMenuClick(player: Player): Boolean {
        val menu = openedMenuList.find { it.viewer == player } ?: return false

        val time = System.currentTimeMillis()
        val last = lastInteraction.computeIfAbsent(player) { System.currentTimeMillis() }
        if (time - last <= 200) return true
        lastInteraction[player] = time

        menu.invokeSelected()
        menu.destroy()
        plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(menu.villager)?.talkingPlayer = null
        return true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private fun onPlayerInteract(event: PlayerInteractEvent) {
        val action = event.action
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK ||
            action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {

            if (tryHandleMenuClick(event.player)) {
                event.isCancelled = true
            }
        }

        event.clickedBlock?.let { block ->
            (block.blockData as? Bed)?.let { bed ->
                if (bed.isOccupied) event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private fun handleVillagerInteraction(event: PlayerInteractEntityEvent) {
        if (tryHandleMenuClick(event.player)) {
            event.isCancelled = true
            return
        }

        val villager = event.rightClicked as? Villager ?: return

        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return
        if (event.isCancelled || !villager.isAware) return

        val outdatedQuests = villager.quests().filter { quest ->
            @Suppress("SENSELESS_COMPARISON")
            quest.data.questDescription == null || quest.data.questFinisherDialogue == null
        }
        if (outdatedQuests.isNotEmpty()) {
            outdatedQuests.forEach { oldQuest ->
                plugin.gameplayManager.questManager.invalidateQuest(oldQuest, QuestInvalidationEvent.Reason.NOT_ACTUAL)
                villager.removeQuest(oldQuest)
            }
        }

        val player: Player = event.player
        val time = System.currentTimeMillis()
        val last = lastInteraction.computeIfAbsent(player) { System.currentTimeMillis() }

        if (time - last <= 200) return else lastInteraction[player] = time
        event.isCancelled = true

        if (dialogues.containsKey(player to villager)) return

        if (villager.pose == Pose.SLEEPING) {
            val message = villager.race.phrases.sleepInterruption.randomOrNull()
            message?.let { villager.talk(player, it, followDuringDialogue = false) }
            return
        }

        if (plugin.geyserProvider?.checkGeyserPlayer(player) == true) {
            plugin.geyserProvider?.openInteractionMenu(player, villager)
            return
        }

        val dialogueSession = player.getActiveDialogueSession()
        if (dialogueSession != null) {
            if (dialogueSession.entity == villager) this.showDialogueMenu(player, villager)
            return
        }

        player.inventory.heldItemSlot = 4
        this.showDefaultMenu(player, villager)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private fun onPlayerDamageEntity(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return

        if (tryHandleMenuClick(player)) {
            event.isCancelled = true
            return
        }

        val entity = event.entity as? Villager ?: return

        if (dialogues.contains(player to entity)) {
            dialogues[player to entity]?.skip()
            event.isCancelled = true
            return
        }

        if (event.finalDamage >= entity.health) {
            if (entity.equipment?.getItem(EquipmentSlot.OFF_HAND)?.type == Material.TOTEM_OF_UNDYING) {
                val message = entity.race.phrases.totemResurrection.randomOrNull()
                message?.let {
                    entity.talk(player, it, displaySize = 0.55F, followDuringDialogue = false, interruptPreviousDialogue = true)
                }
            }
            return
        }

        val message = entity.race.phrases.damage.randomOrNull()
        message?.let {
            entity.talk(player, it, displaySize = 0.55F, followDuringDialogue = false, interruptPreviousDialogue = true)
        }
    }

    @EventHandler
    private fun onVillagerStartFight(event: VillagerStartFightEvent) {
        val villager = event.villager
        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return

        val phrases = villager.race.phrases.startFight
        if (phrases.isNotEmpty()) {
            villager.shout(phrases.random())
        }
    }

    @EventHandler
    private fun onVillagerKillTarget(event: VillagerKillTargetEvent) {
        val villager = event.villager
        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return

        val phrases = when (event.killType) {
            VillagerKillTargetEvent.KillType.RANGED -> villager.race.phrases.rangedKill
            else -> villager.race.phrases.meleeKill
        }
        if (phrases.isNotEmpty()) {
            villager.shout(phrases.random())
        }
    }

    @EventHandler
    private fun whenVillagerDies(event: EntityDeathEvent) {
        (event.entity as? Villager)?.let { villager ->
            openedMenuList.filter { it.villager == villager }.forEach(Menu::destroy)
            dialogues.values.filter { it.entity == villager }.forEach(DialogueManager.DialogueWindow::destroy)
        }
    }

    @EventHandler
    private fun onPlayerJoin(event: PlayerJoinEvent) {
        lastInteraction[event.player] = System.currentTimeMillis()
    }

    @EventHandler
    private fun handlePlayerQuit(event: PlayerQuitEvent) {
        openedMenuList.removeIf { it.viewer == event.player }
    }

    @EventHandler
    private fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val prefs = player.preferences

        if (prefs.menuControl != MenuControlMode.SCROLL) return

        val menu = openedMenuList.find { it.viewer == player } ?: return

        event.isCancelled = true

        val diff = event.newSlot - event.previousSlot
        val direction = if (diff > 0) {
            if (diff == 8) -1 else 1
        } else {
            if (diff == -8) 1 else -1
        }

        if (direction > 0) {
            menu.selectNext()
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.3f, 1.2f)
        } else {
            menu.selectPrevious()
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.3f, 1.2f)
        }
    }

    private fun showDialogueMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)

        builder.button(plugin.language.getString("interaction-menu.quests-button") ?: "Quests") {
            handleQuestButtonClick(player, villager)
        }
        builder.button(plugin.language.getString("interaction-menu.trade-button") ?: "Trade") {
            handleTradeButtonClick(player, villager)
        }
        builder.button(plugin.language.getString("interaction-menu.gift-button") ?: "Gift") {
            if (player.getActiveDialogueSession()?.giftAwaiting == false) {
                player.getActiveDialogueSession()?.giftAwaiting = true
            }
        }
        builder.button(plugin.language.getString("interaction-menu.interrupt-button") ?: "Stop") {
            player.getActiveDialogueSession()?.cancelled = true
        }
        builder.button(plugin.language.getString("interaction-menu.close-button") ?: "Close") { menu ->
            menu.destroy()
        }
        builder.build()
    }

    private fun showDefaultMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)

        val activeDelivery = player.quests().find {
            it.type == QuestManager.QuestType.MESSAGE_DELIVERY &&
                    (it.targetLeaderId == villager.uniqueId || (it.targetSettlementId != null && it.targetSettlementId == villager.getLedSettlementId()))
        }

        if (activeDelivery != null) {
            builder.button("§dDeliver Message", isRainbow = true) { menu ->
                val questIdKey = NamespacedKey(plugin, "quest_id")
                val itemInInv = player.inventory.contents.find {
                    it != null && it.itemMeta?.persistentDataContainer?.get(questIdKey, PersistentDataType.LONG) == activeDelivery.id
                }

                if (itemInInv != null) {
                    itemInInv.amount -= 1

                    plugin.gameplayManager.questManager.finishQuest(player, villager, activeDelivery) {
                        val giverSet = activeDelivery.giverSettlementId?.let { SettlementManager.getById(it) }
                        val targetSet = activeDelivery.targetSettlementId?.let { SettlementManager.getById(it) }

                        if (giverSet != null) {
                            val originalRepBoost = (activeDelivery.score * plugin.gameplayManager.config.quest.reputationMultiplier * 2).toInt()
                            giverSet.data.reputation[player.uniqueId] = (giverSet.data.reputation[player.uniqueId] ?: 0) + originalRepBoost
                        }

                        if (giverSet != null && targetSet != null) {
                            SettlementManager.setRelation(giverSet, targetSet, Settlement.RelationLevel.WARM)
                            SettlementManager.recordDiplomaticEvent(giverSet, targetSet, "RELATION CHANGED: Improved to WARM due to successful diplomatic delivery.")
                        }
                    }
                    menu.destroy()
                } else {
                    player.sendFormattedMessage("§cYou don't have the message with you!")
                }
            }
        }

        val questLabel = (plugin.language.getString("interaction-menu.quests-button") ?: "Quests") + " §8[${villager.quests().count()}]"
        builder.button(questLabel) {
            handleQuestButtonClick(player, villager)
        }

        builder.button(plugin.language.getString("interaction-menu.trade-button") ?: "Trade") {
            handleTradeButtonClick(player, villager)
        }

        if (partyManager.isMember(player, villager)) {
            val manageText = plugin.language.getString("interaction-menu.party-control-button") ?: "§bManage Companion"
            builder.button(manageText, isRainbow = true) {
                this.showPartyMenu(player, villager)
            }
        } else if (!partyManager.hasParty(villager) && villager.opinionOn(player).ordinal <= 3) {
            val inviteText = plugin.language.getString("interaction-menu.party-invite-button") ?: "Follow Me"
            builder.button(inviteText) { menu ->
                if (partyManager.addMember(player, villager)) {
                    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                    menu.destroy()
                } else {
                    player.sendFormattedMessage(plugin.language.getString("party.full") ?: "Your party is full!")
                    menu.destroy()
                }
            }
        }

        builder.button(plugin.language.getString("interaction-menu.talk-button") ?: "Talk") {
            if (premium) {
                if (player.getActiveDialogueSession() == null) DialogueSession(player, villager)
            } else {
                player.sendFormattedMessage(plugin.language.getString("info-messages.premium-only") ?: "This feature is only available in the premium version.")
            }
        }

        builder.button(plugin.language.getString("interaction-menu.close-button") ?: "Close") { menu ->
            menu.destroy()
        }

        builder.build()
    }

    private fun showPartyMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)

        val currentState = partyManager.getPartyState(villager)
        val movementText = if (currentState == PartyState.FOLLOW)
            plugin.language.getString("party.order.follow") ?: "§eOrder: §aFollow"
        else
            plugin.language.getString("party.order.stay") ?: "§eOrder: §cStay Here"

        builder.button(movementText) { menu ->
            val newState = partyManager.togglePartyState(villager)
            val response = if (newState == PartyState.STAY)
                plugin.language.getString("party.order.response-stay") ?: "I'll hold this position."
            else
                plugin.language.getString("party.order.response-follow") ?: "Right behind you."

            villager.talk(player, response, followDuringDialogue = false, displaySize = 0.4f)
            menu.destroy()
            this.showPartyMenu(player, villager)
        }

        val currentTactic = partyManager.getCombatTactic(villager)
        val tacticColor = when (currentTactic) {
            CombatTactic.AUTO -> "§a"
            CombatTactic.MELEE -> "§c"
            CombatTactic.RANGED -> "§b"
        }
        val tacticText = (plugin.language.getString("party.tactic.prefix") ?: "§eTactic: ") + "$tacticColor${currentTactic.name}"

        builder.button(tacticText) { menu ->
            val newTactic = partyManager.cycleCombatTactic(villager)
            val response = when (newTactic) {
                CombatTactic.AUTO -> plugin.language.getString("party.tactic.response-auto") ?: "I'll fight as I see fit."
                CombatTactic.MELEE -> plugin.language.getString("party.tactic.response-melee") ?: "Swords up! Close quarters it is."
                CombatTactic.RANGED -> plugin.language.getString("party.tactic.response-ranged") ?: "I'll keep my distance and shoot."
            }
            villager.talk(player, response, followDuringDialogue = false, displaySize = 0.4f)
            menu.destroy()
            this.showPartyMenu(player, villager)
        }

        val dismissText = plugin.language.getString("interaction-menu.party-kick-button") ?: "§4Dismiss"
        builder.button(dismissText) { menu ->
            partyManager.removeMember(player, villager)
            val response = plugin.language.getString("party.dismiss-response") ?: "Farewell, traveler."
            villager.talk(player, response, followDuringDialogue = false)
            menu.destroy()
        }

        builder.button(plugin.language.getString("interaction-menu.return-button") ?: "Return") { menu ->
            menu.destroy()
            this.showDefaultMenu(player, villager)
        }
        builder.build()
    }

    private fun handleQuestButtonClick(player: Player, villager: Villager) {
        if (villager.profession == Villager.Profession.NONE && villager.quests().isEmpty()) {
            val message = villager.race.phrases.jobless.randomOrNull()
            message?.let { villager.talk(player, it, followDuringDialogue = false) }
            return
        }
        if (villager.profession != Villager.Profession.NONE && villager.quests().isEmpty()) {
            val message = villager.race.phrases.noQuest.randomOrNull()
            message?.let { villager.talk(player, it, followDuringDialogue = false) }
            return
        }
        this.showQuestListMenu(player, villager)
    }

    private fun handleTradeButtonClick(player: Player, villager: Villager) {
        if (villager.profession == Villager.Profession.NONE) {
            val message = villager.race.phrases.jobless.randomOrNull()
            message?.let { villager.talk(player, it, followDuringDialogue = false) }
            return
        }
        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            if (!villager.openTradeMenu(player)) {
                val message = villager.race.phrases.noItemsToTrade.randomOrNull()
                message?.let { villager.talk(player, it, followDuringDialogue = false) }
            }
        }, 1L)
    }

    private fun showQuestSuggestionMenu(player: Player, villager: Villager, quest: Quest) {
        val builder = Builder(villager, player)
        builder.button(plugin.language.getString("interaction-menu.accept-button") ?: "Accept") {
            plugin.server.pluginManager.callEvent(PlayerAcceptQuestEvent(player, villager, quest))
        }
        builder.button(plugin.language.getString("interaction-menu.decline-button") ?: "Decline") { menu ->
            menu.destroy()
        }
        builder.button(plugin.language.getString("interaction-menu.close-button") ?: "Close") { menu ->
            menu.destroy()
        }
        builder.build()
    }

    private fun showQuestListMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)
        val quests = villager.quests().toMutableList()

        quests.forEach { quest ->
            val useRainbow = false
            builder.button(quest.name, isRainbow = useRainbow) {

                // === QUEST DIALOGUE LENGTH LOGIC ===
                val questLengthPref = player.preferences.questDialogueLength
                val rawDescription = if (questLengthPref == QuestDialogueLength.SHORT && !quest.data.shortQuestDescription.isNullOrEmpty()) {
                    quest.data.shortQuestDescription
                } else {
                    quest.data.questDescription
                }
                val description = rawDescription.replace("%playerName%", player.name)

                var skipMenu: Menu? = null

                villager.talk(player, description) {
                    skipMenu?.destroy()
                    this.showQuestSuggestionMenu(player, villager, quest)
                }

                val format = player.dialogueFormat
                if (format == DialogueFormat.IMMERSIVE || format == DialogueFormat.BOTH) {
                    val activeDialogue = dialogues[player to villager]
                    val dialogueScale = activeDialogue?.size ?: 0.35f

                    val dynamicOffset = -(dialogueScale * 0.8) - 0.12

                    val skipBuilder = Builder(villager, player, dynamicOffset)
                    skipBuilder.button(plugin.language.getString("interaction-menu.skip-button") ?: "§eSkip >>") { menu ->
                        menu.destroy()
                        dialogues[player to villager]?.skip()
                    }
                    skipMenu = skipBuilder.build()
                }
            }
        }

        builder.button(plugin.language.getString("interaction-menu.return-button") ?: "Back") { menu ->
            menu.destroy()
            this.showDefaultMenu(player, villager)
        }
        builder.build()
    }

    class Builder(villager: Villager, viewer: Player, yOffset: Double = 0.0) {
        private val menu: Menu = Menu(villager, viewer, yOffset)

        fun button(name: String, buttonColor: Color = defaultButtonColor, isRainbow: Boolean = false, action: (Menu) -> Unit): Builder {
            menu.addLine(name, buttonColor, isRainbow) {
                action(menu)
            }
            return this
        }

        fun build(): Menu {
            return menu
        }
    }
}
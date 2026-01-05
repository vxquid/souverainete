package vx.ignis.gameplay.dialogue.menu

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.data.type.Bed
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.premium
import vx.ignis.Ignis.Companion.sendFormattedMessage
import vx.ignis.event.VillagerKillTargetEvent
import vx.ignis.event.VillagerStartFightEvent
import vx.ignis.gameplay.dialogue.DialogueManager
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundAlpha
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundBlue
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundGreen
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundRed
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogues
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.shout
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.talk
import vx.ignis.gameplay.dialogue.DialogueSession
import vx.ignis.gameplay.dialogue.DialogueSession.Companion.getActiveDialogueSession
import vx.ignis.gameplay.event.PlayerAcceptQuestEvent
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import vx.ignis.gameplay.party.PartyManager.CombatTactic
import vx.ignis.gameplay.party.PartyManager.PartyState
import vx.ignis.gameplay.quest.QuestManager.Quest
import vx.ignis.gameplay.reputation.ReputationManager.Companion.reputationOf
import vx.ignis.gameplay.reputation.ReputationManager.Reputation
import vx.ignis.gameplay.trade.TradeManager.Companion.openTradeMenu
import vx.ignis.persistent.LivingEntityExtend.quests

class InteractionManager : Listener {

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

    // ============================================================================================
    // COMBAT LISTENERS
    // ============================================================================================

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

    // ============================================================================================
    // INTERACTION LOGIC
    // ============================================================================================

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

    @EventHandler(priority = EventPriority.HIGHEST)
    private fun handleVillagerInteraction(event: PlayerInteractEntityEvent) {
        val villager = event.rightClicked as? Villager ?: return

        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return
        if (event.isCancelled || !villager.isAware) return

        val player: Player = event.player
        val time = System.currentTimeMillis()
        val last = lastInteraction.computeIfAbsent(player) { System.currentTimeMillis() }

        if (time - last <= 200) return else lastInteraction[player] = time

        event.isCancelled = true

        openedMenuList.find { it.viewer == player }?.let { menu ->
            menu.invokeSelected()
            menu.destroy()
            (villager as CraftVillager).handle.tradingPlayer = null
            return
        }

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

    @EventHandler
    private fun handlePlayerQuit(event: PlayerQuitEvent) {
        openedMenuList.removeIf { it.viewer == event.player }
    }

    // ============================================================================================
    // MENUS
    // ============================================================================================

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

        val questLabel = (plugin.language.getString("interaction-menu.quests-button") ?: "Quests") +
                " §8[${villager.quests().count()}]"
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
        } else if (!partyManager.hasParty(villager) && villager.reputationOf(player).ordinal <= 3) {
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

    // ============================================================================================
    // HELPER METHODS
    // ============================================================================================

    private fun handleQuestButtonClick(player: Player, villager: Villager) {
        if (villager.profession == Villager.Profession.NONE) {
            val message = villager.race.phrases.jobless.randomOrNull()

            message?.let { villager.talk(player, it, followDuringDialogue = true) }
            return
        }
        if (villager.quests().isEmpty()) {
            val message = villager.race.phrases.noQuest.randomOrNull()

            message?.let { villager.talk(player, it, followDuringDialogue = true) }
            return
        }
        this.showQuestListMenu(player, villager)
    }

    private fun handleTradeButtonClick(player: Player, villager: Villager) {
        if (villager.profession == Villager.Profession.NONE) {
            val message = villager.race.phrases.jobless.randomOrNull()

            message?.let { villager.talk(player, it, followDuringDialogue = true) }
            return
        }
        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            if (!villager.openTradeMenu(player)) {
                val message = villager.race.phrases.noItemsToTrade.randomOrNull()

                message?.let { villager.talk(player, it, followDuringDialogue = true) }
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
                val description = (villager.let { npc ->
                    return@let when (npc.reputationOf(player)) {
                        Reputation.EXALTED -> quest.data.reputationBasedQuestDescriptions.getOrNull(0)
                        Reputation.REVERED -> quest.data.reputationBasedQuestDescriptions.getOrNull(1)
                        Reputation.HONORED -> quest.data.reputationBasedQuestDescriptions.getOrNull(2)
                        Reputation.FRIENDLY -> quest.data.reputationBasedQuestDescriptions.getOrNull(3)
                        Reputation.NEUTRAL -> quest.data.reputationBasedQuestDescriptions.getOrNull(4)
                        Reputation.UNFRIENDLY -> quest.data.reputationBasedQuestDescriptions.getOrNull(5)
                        Reputation.HOSTILE -> quest.data.reputationBasedQuestDescriptions.getOrNull(6)
                        Reputation.EXILED -> quest.data.reputationBasedQuestDescriptions.getOrNull(7)
                    }
                } ?: (plugin.language.getString("quest.description-missing") ?: "Quest description missing."))
                    .replace("%playerName%", player.name)

                villager.talk(player, description) {
                    this.showQuestSuggestionMenu(player, villager, quest)
                }
            }
        }

        builder.button(plugin.language.getString("interaction-menu.return-button") ?: "Back") { menu ->
            menu.destroy()
            this.showDefaultMenu(player, villager)
        }

        builder.build()
    }

    // ============================================================================================
    // MENU CONTROLS & EVENTS
    // ============================================================================================

    @EventHandler
    private fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val menu = openedMenuList.find { it.viewer == player } ?: return

        event.isCancelled = true

        if (System.currentTimeMillis() - menu.lastScrollTime > 250) {
            menu.lastScrollTime = System.currentTimeMillis()
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1F, 2F)
            if (event.previousSlot < event.newSlot) {
                menu.index += 1
            } else menu.index -= 1
        }
    }

    @EventHandler
    private fun onPlayerInteract(event: PlayerInteractEvent) {
        event.clickedBlock?.let { block ->
            (block.blockData as? Bed)?.let { bed ->
                if (bed.isOccupied) event.isCancelled = true
            }
        }
    }

    @EventHandler
    private fun onPlayerDamageEntity(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val entity = event.entity as? Villager ?: return

        if (dialogues.contains(player to entity)) {
            dialogues[player to entity]?.destroy()
            event.isCancelled = true
            return
        }

        if (event.finalDamage >= entity.health) {
            if (entity.equipment?.getItem(EquipmentSlot.OFF_HAND)?.type == Material.TOTEM_OF_UNDYING) {
                val message = entity.race.phrases.totemResurrection.randomOrNull()

                message?.let {
                    entity.talk(
                        player, it,
                        displaySize = 0.55F,
                        followDuringDialogue = false,
                        interruptPreviousDialogue = true
                    )
                }
            }
            return
        }

        val message = entity.race.phrases.damage.randomOrNull()

        message?.let {
            entity.talk(
                player, it,
                displaySize = 0.55F,
                followDuringDialogue = false,
                interruptPreviousDialogue = true
            )
        }
    }

    class Builder(villager: Villager, viewer: Player) {
        private val menu: Menu = Menu(villager, viewer)

        fun button(
            name: String,
            buttonColor: Color = defaultButtonColor,
            isRainbow: Boolean = false,
            action: (Menu) -> Unit
        ): Builder {
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
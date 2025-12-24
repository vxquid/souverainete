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
import vx.ignis.gameplay.party.PartyManager.CombatTactic
import vx.ignis.gameplay.party.PartyManager.PartyState
import vx.ignis.gameplay.personality.PersonalityManager.Companion.getCharacterData
import vx.ignis.gameplay.personality.PersonalityManager.GenericCharacterData
import vx.ignis.gameplay.quest.QuestManager.Quest
import vx.ignis.gameplay.reputation.ReputationManager.Companion.reputationOf
import vx.ignis.gameplay.reputation.ReputationManager.Reputation
import vx.ignis.gameplay.trade.TradeManager.Companion.openTradeMenu
import vx.ignis.persistent.LivingEntityExtend.quests

/**
 * Manages all physical interactions between players and Humanoid Villagers.
 *
 * Responsibilities:
 * - Handling right-click interactions to open menus or start dialogues.
 * - Managing the Text Display Menu system (opening, closing, relocating).
 * - reacting to combat events (start fight, kill target) with shouting phrases.
 * - Handling party management interactions.
 */
class InteractionManager : Listener {

    /**
     * Lazy reference to generic character data.
     * Used as a fallback if a specific NPC lacks a personality or generated phrases.
     */
    private val genericReactionMessages: GenericCharacterData? by lazy {
        plugin.gameplayManager.personalityManager.genericCharacterData
    }

    /**
     * Lazy reference to the PartyManager for handling companion logic.
     */
    private val partyManager by lazy {
        plugin.gameplayManager.partyManager
    }

    // Map to handle click debouncing (preventing double executions).
    private val lastInteraction = mutableMapOf<Player, Long>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Ticker to relocate menu text displays relative to the player's view
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            openedMenuList.toList().forEach(Menu::relocate)
        }, 0L, 1L)
    }

    companion object {
        // Registry of currently opened text-display menus
        val openedMenuList: MutableList<Menu> = mutableListOf()

        // Standard background color for menu buttons
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

    /**
     * Triggered when a custom villager enters combat.
     * Makes the NPC shout a battle cry based on their personality.
     */
    @EventHandler
    private fun onVillagerStartFight(event: VillagerStartFightEvent) {
        val villager = event.villager
        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return

        // Prioritize specific character data, fall back to generic data, or use an empty list.
        val phrases = villager.getCharacterData()?.startFightPhrases
            ?: genericReactionMessages?.startFightPhrases
            ?: emptyList()

        if (phrases.isNotEmpty()) {
            villager.shout(phrases.random())
        }
    }

    /**
     * Triggered when a custom villager kills a target.
     * Makes the NPC shout a victory phrase based on the kill type (Melee/Ranged).
     */
    @EventHandler
    private fun onVillagerKillTarget(event: VillagerKillTargetEvent) {
        val villager = event.villager
        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return

        val phrases = when (event.killType) {
            VillagerKillTargetEvent.KillType.RANGED ->
                villager.getCharacterData()?.rangedKillPhrases
                    ?: genericReactionMessages?.rangedKillPhrases
            else ->
                villager.getCharacterData()?.meleeKillPhrases
                    ?: genericReactionMessages?.meleeKillPhrases
        } ?: emptyList()

        if (phrases.isNotEmpty()) {
            villager.shout(phrases.random())
        }
    }

    // ============================================================================================
    // INTERACTION LOGIC
    // ============================================================================================

    /**
     * Cleans up menus and dialogues when a villager dies.
     */
    @EventHandler
    private fun whenVillagerDies(event: EntityDeathEvent) {
        (event.entity as? Villager)?.let { villager ->
            // Close active menus
            openedMenuList.filter { it.villager == villager }.forEach(Menu::destroy)
            // Close active dialogues
            dialogues.values.filter { it.entity == villager }.forEach(DialogueManager.DialogueWindow::destroy)

            // Note: Party removal is handled within PartyManager itself via its own Death listener.
        }
    }

    @EventHandler
    private fun onPlayerJoin(event: PlayerJoinEvent) {
        lastInteraction[event.player] = System.currentTimeMillis()
    }

    /**
     * The main entry point for interacting with a Villager.
     * Handles debouncing, sleeping checks, active dialogues, and opening the main menu.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private fun handleVillagerInteraction(event: PlayerInteractEntityEvent) {
        val villager = event.rightClicked as? Villager ?: return

        // 1. World & State Checks
        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return
        if (event.isCancelled || !villager.isAware) return

        // 2. Debounce Check (200ms cooldown)
        val player: Player = event.player
        val time = System.currentTimeMillis()
        val last = lastInteraction.computeIfAbsent(player) { System.currentTimeMillis() }

        if (time - last <= 200) return else lastInteraction[player] = time

        event.isCancelled = true

        // 3. Handle Active Menu Interaction (Clicking again selects the option)
        openedMenuList.find { it.viewer == player }?.let { menu ->
            menu.invokeSelected()
            menu.destroy()
            (villager as CraftVillager).handle.tradingPlayer = null
            return
        }

        // 4. Block interaction if a dialogue is currently running
        if (dialogues.containsKey(player to villager)) return

        // 5. Handle Sleeping State
        if (villager.pose == Pose.SLEEPING) {
            val message = villager.getCharacterData()?.sleepInterruptionPhrases?.randomOrNull()
                ?: genericReactionMessages?.sleepInterruptionPhrases?.randomOrNull()

            message?.let { villager.talk(player, it, followDuringDialogue = false) }
            return
        }

        // 6. Geyser Bedrock Player Support
        if (plugin.geyserProvider?.checkGeyserPlayer(player) == true) {
            plugin.geyserProvider?.openInteractionMenu(player, villager)
            return
        }

        // 7. Resume or Open Dialogue Session
        val dialogueSession = player.getActiveDialogueSession()
        if (dialogueSession != null) {
            if (dialogueSession.entity == villager) this.showDialogueMenu(player, villager)
            return
        }

        // 8. Open Default Interaction Menu
        player.inventory.heldItemSlot = 4 // Reset slot to middle for easier scrolling
        this.showDefaultMenu(player, villager)
    }

    @EventHandler
    private fun handlePlayerQuit(event: PlayerQuitEvent) {
        openedMenuList.removeIf { it.viewer == event.player }
    }

    // ============================================================================================
    // MENUS
    // ============================================================================================

    /**
     * Shows a simplified menu when a dialogue session is already active.
     */
    private fun showDialogueMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)

        // [Quest]
        builder.button(plugin.language.getString("interaction-menu.quests-button") ?: "Quests") {
            handleQuestButtonClick(player, villager)
        }

        // [Trade]
        builder.button(plugin.language.getString("interaction-menu.trade-button") ?: "Trade") {
            handleTradeButtonClick(player, villager)
        }

        // [Gift]
        builder.button(plugin.language.getString("interaction-menu.gift-button") ?: "Gift") {
            if (player.getActiveDialogueSession()?.giftAwaiting == false) {
                player.getActiveDialogueSession()?.giftAwaiting = true
            }
        }

        // [Stop]
        builder.button(plugin.language.getString("interaction-menu.interrupt-button") ?: "Stop") {
            player.getActiveDialogueSession()?.cancelled = true
        }

        // [Close]
        builder.button(plugin.language.getString("interaction-menu.close-button") ?: "Close") { menu ->
            menu.destroy()
        }

        builder.build()
    }

    /**
     * Shows the main interaction menu.
     */
    private fun showDefaultMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)

        // [Quest]
        val questLabel = (plugin.language.getString("interaction-menu.quests-button") ?: "Quests") +
                " §8[${villager.quests().count()}]"
        builder.button(questLabel) {
            handleQuestButtonClick(player, villager)
        }

        // [Trade]
        builder.button(plugin.language.getString("interaction-menu.trade-button") ?: "Trade") {
            handleTradeButtonClick(player, villager)
        }

        // --- PARTY LOGIC ---
        if (partyManager.isMember(player, villager)) {
            // If already in party -> Show Party Management Submenu
            val manageText = plugin.language.getString("interaction-menu.party-control-button") ?: "§bManage Companion"
            builder.button(manageText, isRainbow = true) {
                this.showPartyMenu(player, villager)
            }
        } else if (!partyManager.hasParty(villager)) {
            // If free -> Show Invite Button
            val inviteText = plugin.language.getString("interaction-menu.party-invite-button") ?: "Follow Me"
            builder.button(inviteText) { menu ->
                if (partyManager.addMember(player, villager)) {
                    player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                    // Optional: Villager says something positive here
                    menu.destroy()
                } else {
                    player.sendFormattedMessage("Your party is full!")
                    menu.destroy()
                }
            }
        }
        // -------------------

        // [Talk] (Premium Only)
        builder.button(plugin.language.getString("interaction-menu.talk-button") ?: "Talk") {
            if (premium) {
                if (player.getActiveDialogueSession() == null) DialogueSession(player, villager)
            } else {
                player.sendFormattedMessage("This feature is only available in the premium version.")
            }
        }

        // [Close]
        builder.button(plugin.language.getString("interaction-menu.close-button") ?: "Close") { menu ->
            menu.destroy()
        }

        builder.build()
    }

    /**
     * Shows the submenu for managing a companion.
     */
    private fun showPartyMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)

        // 1. Movement Order (Follow / Stay)
        val currentState = partyManager.getPartyState(villager)
        val movementText = if (currentState == PartyState.FOLLOW)
            "§eOrder: §aFollow"
        else
            "§eOrder: §cStay Here"

        builder.button(movementText) { menu ->
            val newState = partyManager.togglePartyState(villager)

            val response = if (newState == PartyState.STAY) "I'll hold this position." else "Right behind you."
            villager.talk(player, response, followDuringDialogue = false, displaySize = 0.4f)

            menu.destroy()
            this.showPartyMenu(player, villager) // Re-open to update button text
        }

        // 2. Combat Tactic (Auto / Melee / Ranged)
        val currentTactic = partyManager.getCombatTactic(villager)
        val tacticColor = when (currentTactic) {
            CombatTactic.AUTO -> "§a"   // Green
            CombatTactic.MELEE -> "§c"  // Red
            CombatTactic.RANGED -> "§b" // Aqua
        }
        val tacticText = "§eTactic: $tacticColor${currentTactic.name}"

        builder.button(tacticText) { menu ->
            val newTactic = partyManager.cycleCombatTactic(villager)

            val response = when (newTactic) {
                CombatTactic.AUTO -> "I'll fight as I see fit."
                CombatTactic.MELEE -> "Swords up! Close quarters it is."
                CombatTactic.RANGED -> "I'll keep my distance and shoot."
            }
            villager.talk(player, response, followDuringDialogue = false, displaySize = 0.4f)

            menu.destroy()
            this.showPartyMenu(player, villager) // Re-open
        }

        // 3. Set Home (Future Feature Placeholder)
        // builder.button("§eSet Home") { ... }

        // 4. Dismiss (Kick)
        val dismissText = plugin.language.getString("interaction-menu.party-kick-button") ?: "§4Dismiss"
        builder.button(dismissText) { menu ->
            partyManager.removeMember(player, villager)
            villager.talk(player, "Farewell, traveler.", followDuringDialogue = false)
            menu.destroy()
        }

        // 5. Back
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
            val message = villager.getCharacterData()?.joblessPhrases?.randomOrNull()
                ?: genericReactionMessages?.joblessPhrases?.randomOrNull()
            message?.let { villager.talk(player, it, followDuringDialogue = true) }
            return
        }
        if (villager.quests().isEmpty()) {
            val message = villager.getCharacterData()?.noQuestPhrases?.randomOrNull()
                ?: genericReactionMessages?.noQuestPhrases?.randomOrNull()
            message?.let { villager.talk(player, it, followDuringDialogue = true) }
            return
        }
        this.showQuestListMenu(player, villager)
    }

    private fun handleTradeButtonClick(player: Player, villager: Villager) {
        if (villager.profession == Villager.Profession.NONE) {
            val message = villager.getCharacterData()?.joblessPhrases?.randomOrNull()
                ?: genericReactionMessages?.joblessPhrases?.randomOrNull()
            message?.let { villager.talk(player, it, followDuringDialogue = true) }
            return
        }
        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            if (!villager.openTradeMenu(player)) {
                val message = villager.getCharacterData()?.noItemsToTradePhrases?.randomOrNull()
                    ?: genericReactionMessages?.noItemsToTradePhrases?.randomOrNull()
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
                        Reputation.EXALTED -> quest.data.reputationBasedQuestDescriptions.getOrNull(7)
                        Reputation.REVERED -> quest.data.reputationBasedQuestDescriptions.getOrNull(6)
                        Reputation.HONORED -> quest.data.reputationBasedQuestDescriptions.getOrNull(5)
                        Reputation.FRIENDLY -> quest.data.reputationBasedQuestDescriptions.getOrNull(4)
                        Reputation.NEUTRAL -> quest.data.reputationBasedQuestDescriptions.getOrNull(3)
                        Reputation.UNFRIENDLY -> quest.data.reputationBasedQuestDescriptions.getOrNull(2)
                        Reputation.HOSTILE -> quest.data.reputationBasedQuestDescriptions.getOrNull(1)
                        Reputation.EXILED -> quest.data.reputationBasedQuestDescriptions.getOrNull(0)
                    }
                } ?: "Quest description missing.").replace("%playerName%", player.name)

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

        // Double scroll fix & Sound
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

        // 1. Dialogue interruption check
        if (dialogues.contains(player to entity)) {
            dialogues[player to entity]?.destroy()
            event.isCancelled = true
            return
        }

        // 2. Totem Resurrection logic
        if (event.finalDamage >= entity.health) {
            if (entity.equipment?.getItem(EquipmentSlot.OFF_HAND)?.type == Material.TOTEM_OF_UNDYING) {
                val message = entity.getCharacterData()?.totemOfUndyingResurrectionPhrases?.randomOrNull()
                    ?: genericReactionMessages?.totemOfUndyingResurrectionPhrases?.randomOrNull()

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

        // 3. Standard Damage Reaction
        val message = entity.getCharacterData()?.damagePhrases?.randomOrNull()
            ?: genericReactionMessages?.damagePhrases?.randomOrNull()

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
package vx.sv.util

import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.geysermc.cumulus.form.Form
import org.geysermc.cumulus.form.ModalForm
import org.geysermc.cumulus.form.SimpleForm
import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.geyser.api.GeyserApi
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.premium
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.gameplay.dialogue.DialogueSession
import vx.sv.gameplay.dialogue.DialogueSession.Companion.getActiveDialogueSession
import vx.sv.gameplay.event.PlayerAcceptQuestEvent
import vx.sv.gameplay.party.PartyManager.CombatTactic
import vx.sv.gameplay.party.PartyManager.PartyState
import vx.sv.gameplay.reputation.ReputationManager.Companion.reputationOf
import vx.sv.gameplay.reputation.ReputationManager.Reputation
import vx.sv.gameplay.trade.TradeManager.Companion.openTradeMenu
import vx.sv.persistent.LivingEntityExtend.quests

class GeyserSupportProvider {

    private val dialogueBoxTextBaseColor        = "&f"
    private val dialogueBoxTextImportantColor   = "&5"
    private val dialogueBoxTextInterestingColor = "&5"

    private val partyManager by lazy { plugin.gameplayManager.partyManager }

    init {
        plugin.logger.info("Geyser usage detected. Support for Bedrock Edition players will be provided.")
    }

    fun checkGeyserPlayer(player: Player): Boolean = try {
        GeyserApi.api().connectionByUuid(player.uniqueId) != null
    } catch (_: Exception) {
        false
    }

    private fun openForm(player: Player, form: Form) {
        if (plugin.server.pluginManager.isPluginEnabled("floodgate")) {
            FloodgateApi.getInstance().sendForm(player.uniqueId, form)
        } else {
            GeyserApi.api().sendForm(player.uniqueId, form)
        }
    }

    fun openInteractionMenu(player: Player, villager: Villager) {
        // Load localized strings
        val questBtnText = plugin.language.getString("interaction-menu.quests-button") ?: "Quests"
        val tradeBtnText = plugin.language.getString("interaction-menu.trade-button") ?: "Trade"
        val giftBtnText = plugin.language.getString("interaction-menu.gift-button") ?: "Gift"
        val interruptBtnText = plugin.language.getString("interaction-menu.interrupt-button") ?: "Interrupt Conversation"
        val closeBtnText = plugin.language.getString("interaction-menu.close-button") ?: "Close"
        val talkBtnText = plugin.language.getString("interaction-menu.talk-button") ?: "Chat"
        val acceptBtnText = plugin.language.getString("interaction-menu.accept-button") ?: "Accept"
        val declineBtnText = plugin.language.getString("interaction-menu.decline-button") ?: "Decline" // Unused in main menu but good for consistancy

        val partyManageText = plugin.language.getString("interaction-menu.party-control-button") ?: "§bManage Companion"
        val partyInviteText = plugin.language.getString("interaction-menu.party-invite-button") ?: "Follow Me"

        // Quest List Form
        val questListForm = SimpleForm.builder().title(questBtnText)
        villager.quests().forEach { quest -> questListForm.button(quest.name) }
        questListForm.button(closeBtnText)

        questListForm.validResultHandler { response ->
            val buttonName = response.clickedButton().text()
            if (buttonName == closeBtnText) return@validResultHandler

            val quest = villager.quests().find { it.name == buttonName } ?: return@validResultHandler

            val questDescriptionRaw = villager.let { npc ->
                when (npc.reputationOf(player)) {
                    Reputation.EXALTED -> quest.data.reputationBasedQuestDescriptions.getOrNull(0)
                    Reputation.REVERED -> quest.data.reputationBasedQuestDescriptions.getOrNull(1)
                    Reputation.HONORED -> quest.data.reputationBasedQuestDescriptions.getOrNull(2)
                    Reputation.FRIENDLY -> quest.data.reputationBasedQuestDescriptions.getOrNull(3)
                    Reputation.NEUTRAL -> quest.data.reputationBasedQuestDescriptions.getOrNull(4)
                    Reputation.UNFRIENDLY -> quest.data.reputationBasedQuestDescriptions.getOrNull(5)
                    Reputation.HOSTILE -> quest.data.reputationBasedQuestDescriptions.getOrNull(6)
                    Reputation.EXILED -> quest.data.reputationBasedQuestDescriptions.getOrNull(7)
                }
            } ?: (plugin.language.getString("quest.description-missing") ?: "Quest description missing.")

            val questDescription = questDescriptionRaw.replace("%playerName%", player.name)

            // Markdown parsing
            val formattedQuestDescription = dialogueBoxTextBaseColor + questDescription.replace(Regex("\\*\\*(.*?)\\*\\*")) { matchResult ->
                "${dialogueBoxTextImportantColor}${matchResult.groupValues[1]}${dialogueBoxTextBaseColor}"
            }.replace(Regex("\\*(.*?)\\*")) { matchResult ->
                "${dialogueBoxTextInterestingColor}${matchResult.groupValues[1]}${dialogueBoxTextBaseColor}"
            }.replace("\\\"", "\"")

            // Quest Detail Modal
            val questDescriptionForm = ModalForm.builder()
                .title(buttonName)
                .content(formattedQuestDescription)
                .button1(acceptBtnText)
                .button2(closeBtnText)
                .validResultHandler { responseData ->
                    if (responseData.clickedButtonText() == acceptBtnText) {
                        plugin.server.scheduler.runTask(plugin) { _ ->
                            plugin.server.pluginManager.callEvent(PlayerAcceptQuestEvent(player, villager, quest))
                        }
                    }
                }
            this.openForm(player, questDescriptionForm.build())
        }

        // Active Dialogue Menu
        val dialogueSessionForm = SimpleForm.builder()
            .title(villager.customName ?: "Unknown")
            .button(questBtnText)
            .button(tradeBtnText)
            .button(giftBtnText)
            .button(interruptBtnText)
            .button(closeBtnText)
            .validResultHandler { responseData ->
                val clickedText = responseData.clickedButton().text()
                when (clickedText) {
                    questBtnText -> this.openForm(player, questListForm.build())
                    tradeBtnText -> plugin.server.scheduler.runTask(plugin) { _ -> villager.openTradeMenu(player) }
                    giftBtnText -> if (player.getActiveDialogueSession()?.giftAwaiting == false) player.getActiveDialogueSession()?.giftAwaiting = true
                    interruptBtnText -> player.getActiveDialogueSession()?.cancelled = true
                }
            }

        // Main Interaction Menu
        val mainFormBuilder = SimpleForm.builder()
            .title(villager.customName ?: "Unknown")
            .button(questBtnText)
            .button(tradeBtnText)

        // Party Logic Buttons
        val isPartyMember = partyManager.isMember(player, villager)
        val canInvite = !partyManager.hasParty(villager) && villager.reputationOf(player).ordinal <= 3

        if (isPartyMember) {
            mainFormBuilder.button(partyManageText)
        } else if (canInvite) {
            mainFormBuilder.button(partyInviteText)
        }

        mainFormBuilder.button(talkBtnText)
        mainFormBuilder.button(closeBtnText)

        mainFormBuilder.validResultHandler { responseData ->
            val clickedText = responseData.clickedButton().text()

            when (clickedText) {
                questBtnText -> this.openForm(player, questListForm.build())
                tradeBtnText -> plugin.server.scheduler.runTask(plugin) { _ -> villager.openTradeMenu(player) }
                partyManageText -> this.openPartyControlMenu(player, villager)
                partyInviteText -> {
                    if (partyManager.addMember(player, villager)) {
                        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f)
                        this.openInteractionMenu(player, villager) // Refresh
                    } else {
                        player.sendFormattedMessage(plugin.language.getString("party.full") ?: "Your party is full!")
                    }
                }
                talkBtnText -> {
                    if (premium) {
                        if (player.getActiveDialogueSession() == null) DialogueSession(player, villager)
                    } else {
                        player.sendFormattedMessage(plugin.language.getString("info-messages.premium-only") ?: "This feature is only available in the premium version.")
                    }
                }
            }
        }

        this.openForm(player, if (player.getActiveDialogueSession() != null) dialogueSessionForm.build() else mainFormBuilder.build())
    }

    private fun openPartyControlMenu(player: Player, villager: Villager) {
        // Load Party Strings
        val closeBtnText = plugin.language.getString("interaction-menu.close-button") ?: "Close"
        val dismissBtnText = plugin.language.getString("interaction-menu.party-kick-button") ?: "§4Dismiss"
        val returnBtnText = plugin.language.getString("interaction-menu.return-button") ?: "Return"

        // Order Button Text
        val currentState = partyManager.getPartyState(villager)
        val orderBtnText = if (currentState == PartyState.FOLLOW)
            plugin.language.getString("party.order.follow") ?: "§eOrder: §aFollow"
        else
            plugin.language.getString("party.order.stay") ?: "§eOrder: §cStay Here"

        // Tactic Button Text
        val currentTactic = partyManager.getCombatTactic(villager)
        val tacticColor = when (currentTactic) {
            CombatTactic.AUTO -> "§a"
            CombatTactic.MELEE -> "§c"
            CombatTactic.RANGED -> "§b"
        }
        val tacticPrefix = plugin.language.getString("party.tactic.prefix") ?: "§eTactic: "
        val tacticBtnText = "$tacticPrefix$tacticColor${currentTactic.name}"

        // Build Form
        val form = SimpleForm.builder()
            .title(plugin.language.getString("interaction-menu.party-control-button") ?: "Companion")
            .button(orderBtnText)
            .button(tacticBtnText)
            .button(dismissBtnText)
            .button(returnBtnText)
            .validResultHandler { response ->
                when (response.clickedButton().text()) {
                    orderBtnText -> {
                        partyManager.togglePartyState(villager)
                        this.openPartyControlMenu(player, villager) // Re-open to update text
                    }
                    tacticBtnText -> {
                        partyManager.cycleCombatTactic(villager)
                        this.openPartyControlMenu(player, villager) // Re-open to update text
                    }
                    dismissBtnText -> {
                        partyManager.removeMember(player, villager)
                        val msg = plugin.language.getString("party.dismiss-response") ?: "Farewell, traveler."
                        player.sendFormattedMessage(msg) // Send to chat as feedback
                        this.openInteractionMenu(player, villager)
                    }
                    returnBtnText -> {
                        this.openInteractionMenu(player, villager)
                    }
                }
            }
        this.openForm(player, form.build())
    }

}
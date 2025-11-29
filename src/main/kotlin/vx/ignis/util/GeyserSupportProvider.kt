package vx.ignis.util

import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.geysermc.cumulus.form.Form
import org.geysermc.cumulus.form.ModalForm
import org.geysermc.cumulus.form.SimpleForm
import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.geyser.api.GeyserApi
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.premium
import vx.ignis.gameplay.dialogue.DialogueSession
import vx.ignis.gameplay.dialogue.DialogueSession.Companion.getActiveDialogueSession
import vx.ignis.gameplay.event.PlayerAcceptQuestEvent
import vx.ignis.gameplay.reputation.ReputationManager.Companion.reputationOf
import vx.ignis.gameplay.reputation.ReputationManager.Reputation
import vx.ignis.gameplay.trade.TradeManager.Companion.openTradeMenu
import vx.ignis.persistent.LivingEntityExtend.quests

class GeyserSupportProvider {

    val dialogueBoxTextBaseColor        = "&f"
    val dialogueBoxTextImportantColor   = "&5"
    val dialogueBoxTextInterestingColor = "&5"

    init {
        plugin.logger.info("Geyser usage detected. Support for Bedrock Edition players will be provided.")
    }

    fun checkGeyserPlayer(player: Player) : Boolean = try {
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

    // QI automatically detects if the player is playing through Geyser, and if true, selects a menu from Form. Suddenly, Bedrock Edition has one cool feature — the ability to create your own GUI.
    fun openInteractionMenu(player: Player, villager: Villager) {

        val questListForm = SimpleForm.builder()
            .title(plugin.language.getString("interaction-menu.quests-button")!!)

        villager.quests().forEach { quest ->
            questListForm.button(quest.name)
        }

        questListForm.button(plugin.language.getString("interaction-menu.close-button")!!)
        questListForm.validResultHandler { response ->

            val buttonName = response.clickedButton().text()
            if (buttonName == plugin.language.getString("interaction-menu.close-button")!!) return@validResultHandler

            val quest = villager.quests().find { it.name == response.clickedButton().text() } ?: return@validResultHandler

            // Looking for a quest description.
            val questDescription = quest.let {
                when (villager.reputationOf(player)) {
                    Reputation.EXALTED -> quest.data.reputationBasedQuestDescriptions[7]
                    Reputation.REVERED -> quest.data.reputationBasedQuestDescriptions[6]
                    Reputation.HONORED -> quest.data.reputationBasedQuestDescriptions[5]
                    Reputation.FRIENDLY -> quest.data.reputationBasedQuestDescriptions[4]
                    Reputation.NEUTRAL -> quest.data.reputationBasedQuestDescriptions[3]
                    Reputation.UNFRIENDLY -> quest.data.reputationBasedQuestDescriptions[2]
                    Reputation.HOSTILE -> quest.data.reputationBasedQuestDescriptions[1]
                    Reputation.EXILED -> quest.data.reputationBasedQuestDescriptions[0]
                }.replace("%playerName%", player.name)
            }

            // Markdown parsing.
            val formattedQuestDescription = dialogueBoxTextBaseColor + questDescription.replace(Regex("\\*\\*(.*?)\\*\\*")) { matchResult ->
                "${dialogueBoxTextImportantColor}${matchResult.groupValues[1]}${dialogueBoxTextBaseColor}"
            }.replace(Regex("\\*(.*?)\\*")) { matchResult ->
                "${dialogueBoxTextInterestingColor}${matchResult.groupValues[1]}${dialogueBoxTextBaseColor}"
            }.replace("\\\"", "\"")

            // Menu with quest description.
            val questDescriptionForm = ModalForm.builder()
                .title(response.clickedButton().text())
                .content(formattedQuestDescription)
                .button1(plugin.language.getString("interaction-menu.accept-button")!!)
                .button2(plugin.language.getString("interaction-menu.close-button")!!)
                .validResultHandler { responseData ->
                    if (responseData.clickedButtonText() == plugin.language.getString("interaction-menu.accept-button")!!) {
                        plugin.server.scheduler.runTask(plugin) { _ ->
                            plugin.server.pluginManager.callEvent(PlayerAcceptQuestEvent(player, villager, quest))
                        }
                    }
                }

            this.openForm(player, questDescriptionForm.build())
        }

        val dialogueSessionForm = SimpleForm.builder()
            .title(villager.customName ?: "Unknown")
            .button(plugin.language.getString("interaction-menu.quests-button")!!)
            .button(plugin.language.getString("interaction-menu.trade-button")!!)
            .button(plugin.language.getString("interaction-menu.gift-button")!!)
            .button(plugin.language.getString("interaction-menu.interrupt-button")!!)
            .button(plugin.language.getString("interaction-menu.close-button")!!)
            .validResultHandler { responseData ->
                if (responseData.clickedButton().text() == plugin.language.getString("interaction-menu.quests-button")!!) {
                    this.openForm(player, questListForm.build())
                }
                if (responseData.clickedButton().text() == plugin.language.getString("interaction-menu.trade-button")!!) {
                    plugin.server.scheduler.runTask(plugin) { _ ->
                        villager.openTradeMenu(player)
                    }
                }
                if (responseData.clickedButton().text() == plugin.language.getString("interaction-menu.gift-button")!!) {
                    if (player.getActiveDialogueSession()?.giftAwaiting == false) player.getActiveDialogueSession()?.giftAwaiting = true
                }
                if (responseData.clickedButton().text() == plugin.language.getString("interaction-menu.interrupt-button")!!) {
                    player.getActiveDialogueSession()?.cancelled = true
                }
            }

        val mainForm = SimpleForm.builder()
            .title(villager.customName ?: "Unknown")
            .button(plugin.language.getString("interaction-menu.quests-button")!!)
            .button(plugin.language.getString("interaction-menu.trade-button")!!)
            .button(plugin.language.getString("interaction-menu.talk-button")!!)
            .button(plugin.language.getString("interaction-menu.close-button")!!)
            .validResultHandler { responseData ->
                if (responseData.clickedButton().text() == plugin.language.getString("interaction-menu.quests-button")!!) {
                    this.openForm(player, questListForm.build())
                }
                if (responseData.clickedButton().text() == plugin.language.getString("interaction-menu.trade-button")!!) {
                    plugin.server.scheduler.runTask(plugin) { _ ->
                        villager.openTradeMenu(player)
                    }
                }
                if (responseData.clickedButton().text() == plugin.language.getString("interaction-menu.talk-button")!!) {
                    if (premium) {
                        if (player.getActiveDialogueSession() == null) DialogueSession(player, villager)
                    }
                }
            }

        this.openForm(player, if(player.getActiveDialogueSession() != null) dialogueSessionForm.build() else mainForm.build())
    }

}
package vx.sv.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandCompletion
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Subcommand
import org.bukkit.entity.Player
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.gameplay.quest.ProgressTracker.Companion.experienceEarnedByQuests
import vx.sv.gameplay.quest.ProgressTracker.Companion.getTrackedQuest
import vx.sv.gameplay.quest.ProgressTracker.Companion.questsCompleted
import vx.sv.gameplay.quest.ProgressTracker.Companion.questsFailed
import vx.sv.persistent.LivingEntityExtend.quests

@CommandAlias("quest|q")
class QuestCommand : BaseCommand() {

    init {
        plugin.commandManager.commandCompletions.registerCompletion("acceptedQuests") { context ->
            context.player.quests().map { it.name }
        }
    }

    @Subcommand("remove")
    @CommandPermission("sv.quest.remove")
    @CommandCompletion("@acceptedQuests")
    fun onQuestRemove(player: Player, questName: String) {
        val questData = player.quests().find { it.name == questName } ?: run {
            val questNotFoundMessage = plugin.language.getString("info-messages.quest-command.not-found")!!.replace("{quest}", questName)
            player.sendFormattedMessage(questNotFoundMessage)
            return
        }
        plugin.gameplayManager.questManager.cancelQuest(player, questData)
        val questRemovedMessage = plugin.language.getString("info-messages.quest-command.removed")!!.replace("{quest}", questName)
        player.sendFormattedMessage(questRemovedMessage)
    }

    @Subcommand("track")
    @CommandPermission("sv.quest.track")
    @CommandCompletion("@acceptedQuests")
    fun onQuestTrack(player: Player, questName: String) {
        val questData = player.quests().find { it.name == questName } ?: run {
            val questNotFoundMessage = plugin.language.getString("info-messages.quest-command.not-found")!!.replace("{quest}", questName)
            player.sendFormattedMessage(questNotFoundMessage)
            return
        }
        player.getTrackedQuest()?.let {
            plugin.gameplayManager.questManager.progressTracker.stopTracking(player, it.first)
        }
        plugin.gameplayManager.questManager.progressTracker.startTracking(player, questData)
        val questTrackingMessage = plugin.language.getString("info-messages.quest-command.tracking")!!.replace("{quest}", questName)
        player.sendFormattedMessage(questTrackingMessage)
    }

    @Subcommand("list")
    @CommandPermission("sv.quest.list")
    fun onQuestList(player: Player) {
        val quests = player.quests()
        val questAmountMessage = plugin.language.getString("info-messages.quest-command.amount")!!.replace("{questAmount}", quests.size.toString())
        player.sendFormattedMessage(questAmountMessage)
        quests.forEach { quest ->
            player.sendMessage(" §7- §6${quest.name}")
        }
    }

    @Subcommand("stats")
    @CommandPermission("sv.quest.stats")
    fun onStats(player: Player) {
        val questStatsMessage = plugin.language.getString("info-messages.quest-command.statistics")!!
        val tgqMessage = plugin.language.getString("info-messages.quest-command.totally-generated")!!.replace("{questCount}", plugin.gameplayManager.questManager.totalQuestAmount.toString())
        val completedAmountMessage = plugin.language.getString("info-messages.quest-command.completed")!!.replace("{questsCompleted}", player.questsCompleted.toString())
        val failedAmountMessage = plugin.language.getString("info-messages.quest-command.failed")!!.replace("{questsFailed}", player.questsFailed.toString())
        val xpEarnedMessage = plugin.language.getString("info-messages.quest-command.xp-earned")!!.replace("{xpEarned}", player.experienceEarnedByQuests.toString())
        player.sendFormattedMessage(questStatsMessage)
        player.sendFormattedMessage(tgqMessage)
        player.sendFormattedMessage(completedAmountMessage)
        player.sendFormattedMessage(failedAmountMessage)
        player.sendFormattedMessage(xpEarnedMessage)
    }

}
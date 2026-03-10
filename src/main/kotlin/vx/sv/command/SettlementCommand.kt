package vx.sv.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandCompletion
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Subcommand
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.debug.LeaderHighlightManager
import vx.sv.gameplay.quest.QuestManager
import vx.sv.gameplay.quest.QuestManager.Companion.addQuest
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.isSettlementLeader
import vx.sv.persistent.LivingEntityExtend.settlement

@CommandAlias("settlement|s")
class SettlementCommand : BaseCommand() {

    init {
        // Registering a custom completion that returns a list of all settlement names
        plugin.commandManager.commandCompletions.registerCompletion("settlements") {
            SettlementManager.settlements.values.flatten().map { it.data.settlementName }
        }
    }

    @Subcommand("teleport|tp")
    @CommandPermission("sv.settlement.teleport")
    @CommandCompletion("@settlements")
    fun onTeleport(player: Player, settlementName: String) {
        val settlement = SettlementManager.getByName(settlementName) ?: run {
            val notFoundMsg = plugin.language.getString(
                "info-messages.settlement-command.not-found",
                "§cSettlement {settlement} not found."
            )!!.replace("{settlement}", settlementName)
            player.sendFormattedMessage(notFoundMsg)
            return
        }

        // Teleport player to the center of the settlement
        player.teleport(settlement.data.center)

        val teleportMsg = plugin.language.getString(
            "info-messages.settlement-command.teleported",
            "§aTeleported to {settlement}."
        )!!.replace("{settlement}", settlement.data.settlementName)
        player.sendFormattedMessage(teleportMsg)
    }

    @Subcommand("reputation|rep")
    @CommandPermission("sv.settlement.reputation")
    @CommandCompletion("@settlements @players")
    fun onReputation(player: Player, settlementName: String, targetPlayerName: String, amount: Int) {
        val settlement = SettlementManager.getByName(settlementName) ?: run {
            val notFoundMsg = plugin.language.getString(
                "info-messages.settlement-command.not-found",
                "§cSettlement {settlement} not found."
            )!!.replace("{settlement}", settlementName)
            player.sendFormattedMessage(notFoundMsg)
            return
        }

        // Fetch offline player to allow changing reputation even if they are offline
        @Suppress("DEPRECATION")
        val targetPlayer = Bukkit.getOfflinePlayer(targetPlayerName)

        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline) {
            val playerNotFoundMsg = plugin.language.getString(
                "info-messages.settlement-command.player-not-found",
                "§cPlayer {player} not found."
            )!!.replace("{player}", targetPlayerName)
            player.sendFormattedMessage(playerNotFoundMsg)
            return
        }

        // Apply new reputation score
        settlement.data.reputation[targetPlayer.uniqueId] = amount
        SettlementManager.saveSettlements(settlement.world)

        val successMsg = plugin.language.getString(
            "info-messages.settlement-command.reputation-set",
            "§aReputation of {player} in {settlement} has been set to {amount}."
        )!!.replace("{player}", targetPlayer.name ?: targetPlayerName)
            .replace("{settlement}", settlement.data.settlementName)
            .replace("{amount}", amount.toString())

        player.sendFormattedMessage(successMsg)
    }

    @Subcommand("relation|rel")
    @CommandPermission("sv.settlement.relation")
    @CommandCompletion("@settlements @settlements")
    fun onRelation(player: Player, settlementNameA: String, settlementNameB: String, level: Settlement.RelationLevel) {
        val settlementA = SettlementManager.getByName(settlementNameA)
        val settlementB = SettlementManager.getByName(settlementNameB)

        if (settlementA == null) {
            val notFoundMsg = plugin.language.getString(
                "info-messages.settlement-command.not-found",
                "§cSettlement {settlement} not found."
            )!!.replace("{settlement}", settlementNameA)
            player.sendFormattedMessage(notFoundMsg)
            return
        }

        if (settlementB == null) {
            val notFoundMsg = plugin.language.getString(
                "info-messages.settlement-command.not-found",
                "§cSettlement {settlement} not found."
            )!!.replace("{settlement}", settlementNameB)
            player.sendFormattedMessage(notFoundMsg)
            return
        }

        if (settlementA.data.id == settlementB.data.id) {
            val sameSettlementMsg = plugin.language.getString(
                "info-messages.settlement-command.same-settlement",
                "§cYou cannot establish a relation with the same settlement."
            )!!
            player.sendFormattedMessage(sameSettlementMsg)
            return
        }

        // Establish the new relationship between both settlements
        SettlementManager.setRelation(settlementA, settlementB, level)

        val relationSetMsg = plugin.language.getString(
            "info-messages.settlement-command.relation-set",
            "§aRelation between {settlementA} and {settlementB} is now {level}."
        )!!.replace("{settlementA}", settlementA.data.settlementName)
            .replace("{settlementB}", settlementB.data.settlementName)
            .replace("{level}", level.name)

        player.sendFormattedMessage(relationSetMsg)
    }

    @Subcommand("raid")
    @CommandAlias("raid")
    @CommandPermission("sv.settlement.raid")
    @CommandCompletion("@settlements @settlements")
    fun onRaid(player: Player, attackerName: String, defenderName: String) {
        val attacker = SettlementManager.getByName(attackerName)
        val defender = SettlementManager.getByName(defenderName)

        if (attacker == null) {
            val notFoundMsg = plugin.language.getString(
                "info-messages.settlement-command.not-found",
                "§cSettlement {settlement} not found."
            )!!.replace("{settlement}", attackerName)
            player.sendFormattedMessage(notFoundMsg)
            return
        }

        if (defender == null) {
            val notFoundMsg = plugin.language.getString(
                "info-messages.settlement-command.not-found",
                "§cSettlement {settlement} not found."
            )!!.replace("{settlement}", defenderName)
            player.sendFormattedMessage(notFoundMsg)
            return
        }

        if (attacker.data.id == defender.data.id) {
            val sameSettlementMsg = plugin.language.getString(
                "info-messages.settlement-command.same-settlement-raid",
                "§cA settlement cannot raid itself!"
            )!!
            player.sendFormattedMessage(sameSettlementMsg)
            return
        }

        if (defender.data.activeRaid != null) {
            val alreadyRaidedMsg = plugin.language.getString(
                "info-messages.settlement-command.already-raided",
                "§cSettlement {settlement} is already under attack!"
            )!!.replace("{settlement}", defender.data.settlementName)
            player.sendFormattedMessage(alreadyRaidedMsg)
            return
        }

        // Temporarily override relationship to WAR just to make the raid logically sound for testing
        SettlementManager.setRelation(attacker, defender, Settlement.RelationLevel.WAR)

        // Trigger the raid manually.
        plugin.gameplayManager.raidManager.startRaid(attacker, defender)

        val broadcastRaidMessage = plugin.language.getString("raid.chat.started-broadcast")
            ?.replace("{attacker}", attacker.data.settlementName)
            ?.replace("{defender}", defender.data.settlementName)
            ?: "§c⚔ A raid has begun: §6${attacker.data.settlementName} §chas attacked §6${defender.data.settlementName}§c!"

        player.world.players.forEach { p ->
            p.sendMessage(broadcastRaidMessage)
        }
    }

    @Subcommand("forcequest")
    @CommandPermission("sv.settlement.forcequest")
    fun onForceQuest(player: Player) {
        // Fetch the entity the player is looking at within 10 blocks
        val targetEntity = player.getTargetEntity(10)

        if (targetEntity !is Villager) {
            player.sendFormattedMessage("§cYou must be looking directly at a Villager.")
            return
        }

        if (!targetEntity.isSettlementLeader()) {
            player.sendFormattedMessage("§cThe villager you are looking at is not a settlement leader.")
            return
        }

        val giverSettlement = targetEntity.settlement ?: run {
            player.sendFormattedMessage("§cThis leader does not belong to a valid settlement.")
            return
        }

        // Find the closest other settlement with a valid leader
        val worldSettlements = SettlementManager.settlements[player.world] ?: emptyList()
        val targetSettlement = worldSettlements
            .filter { it.data.id != giverSettlement.data.id && it.data.leaderId != null }
            .minByOrNull { it.data.center.distance(giverSettlement.data.center) }

        if (targetSettlement == null) {
            player.sendFormattedMessage("§cNo other settlements with a leader found to send the message to.")
            return
        }

        val leaderName = targetEntity.customName ?: "Leader"
        player.sendFormattedMessage("§eGenerating a political quest for $leaderName... Please wait, AI is processing.")

        // Generate the quest asynchronously
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val quest = plugin.gameplayManager.questManager.generateQuest(
                    QuestManager.QuestType.MESSAGE_DELIVERY,
                    targetEntity,
                    targetSettlement.data.leaderId,
                    targetSettlement.data.leaderName,
                    targetSettlement.data.id,
                    giverSettlement.data.id
                )

                if (quest != null) {
                    // Return to the main thread to apply Bukkit changes
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        plugin.gameplayManager.actualQuests.add(quest.id)
                        targetEntity.addQuest(quest)
                        player.sendFormattedMessage("§aSuccessfully generated a political quest targeting ${targetSettlement.data.settlementName}!")
                    })
                } else {
                    player.sendFormattedMessage("§cFailed to generate the quest (AI returned null).")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                player.sendFormattedMessage("§cAn error occurred while generating the quest. Check console for details.")
            }
        })
    }

    @Subcommand("highlight|hl")
    @CommandPermission("sv.settlement.highlight")
    fun onHighlight(player: Player) {
        val isEnabled = LeaderHighlightManager.toggleHighlight(player)
        if (isEnabled) {
            player.sendFormattedMessage("§a[Debug] Leader highlight mode has been §eENABLED§a. Leaders will now glow for you.")
        } else {
            player.sendFormattedMessage("§a[Debug] Leader highlight mode has been §cDISABLED§a.")
        }
    }
}
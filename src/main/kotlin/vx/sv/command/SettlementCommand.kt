package vx.sv.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandCompletion
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Subcommand
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager

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

        player.world.players.forEach { player ->
            player.sendMessage(broadcastRaidMessage)
        }
    }

}
package vx.sv.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.InvalidCommandArgument
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandCompletion
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Subcommand
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.debug.LeaderHighlightManager
import vx.sv.gameplay.achievement.AchievementManager
import vx.sv.gameplay.quest.QuestManager
import vx.sv.gameplay.quest.QuestManager.Companion.addQuest
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.isSettlementLeader
import vx.sv.nms.entity.ai.construct.SettlementPlanner
import vx.sv.nms.entity.ai.construct.VanillaBuildingType
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.*

@CommandAlias("settlement|s")
class SettlementCommand : BaseCommand() {

    init {
        plugin.commandManager.commandContexts.registerContext(Settlement::class.java) { c ->
            val argList = mutableListOf<String>()
            var resolvedSettlement: Settlement? = null

            while (c.getFirstArg() != null) {
                argList.add(c.popFirstArg())
                val testName = argList.joinToString(" ")
                val match = SettlementManager.getByName(testName)
                if (match != null) {
                    resolvedSettlement = match
                    break
                }
            }

            if (resolvedSettlement == null) {
                val attemptedName = argList.joinToString(" ")
                throw InvalidCommandArgument(
                    plugin.language.getString(
                        "info-messages.settlement-command.not-found",
                        "§cSettlement {settlement} not found."
                    )!!.replace("{settlement}", attemptedName),
                    false
                )
            }
            resolvedSettlement
        }

        plugin.commandManager.commandCompletions.registerCompletion("settlements") {
            SettlementManager.settlements.values.flatten().map { it.data.settlementName }
        }
    }

    @Subcommand("teleport|tp")
    @CommandPermission("sv.settlement.teleport")
    @CommandCompletion("@settlements")
    fun onTeleport(player: Player, settlement: Settlement) {
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
    fun onReputation(player: Player, settlement: Settlement, targetPlayerName: String, amount: Int) {
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
    fun onRelation(player: Player, settlementA: Settlement, settlementB: Settlement, level: Settlement.RelationLevel) {
        if (settlementA.data.id == settlementB.data.id) {
            val sameSettlementMsg = plugin.language.getString(
                "info-messages.settlement-command.same-settlement",
                "§cYou cannot establish a relation with the same settlement."
            )!!
            player.sendFormattedMessage(sameSettlementMsg)
            return
        }

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
    fun onRaid(player: Player, attacker: Settlement, defender: Settlement) {
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

        SettlementManager.setRelation(attacker, defender, Settlement.RelationLevel.WAR)
        plugin.gameplayManager.raidManager.startRaid(attacker, defender)

        val broadcastRaidMessage = plugin.language.getString("raid.chat.started-broadcast")
            ?.replace("{attacker}", attacker.data.settlementName)
            ?.replace("{defender}", defender.data.settlementName)
            ?: "§c⚔ A raid has begun: §6${attacker.data.settlementName} §chas attacked §6${defender.data.settlementName}§c!"

        player.world.players.forEach { p ->
            p.sendMessage(broadcastRaidMessage)
        }
    }

    @Subcommand("create")
    @CommandPermission("sv.settlement.create")
    @CommandCompletion("@nothing")
    fun onCreateSettlement(player: Player, name: String, raceName: String) {
        val world = player.world
        if (!plugin.gameplayConfig.worlds.allowedWorlds.contains(world.name)) {
            player.sendFormattedMessage("§cSettlements cannot be created in this world!")
            return
        }

        val race = vx.sv.gameplay.humanoid.race.RaceManager.racesRegistry[raceName.lowercase()]
        if (race == null) {
            val availableRaces = vx.sv.gameplay.humanoid.race.RaceManager.racesRegistry.keys.joinToString(", ")
            player.sendFormattedMessage("§cRace '$raceName' not found! Available races: §e$availableRaces")
            return
        }

        val centerLoc = player.location.clone()
        val groundY = SettlementPlanner.getHighestGroundYAt(world, centerLoc.blockX, centerLoc.blockZ)
        if (groundY != -999) {
            centerLoc.y = groundY.toDouble() + 1.0
        }

        val newTerritoryRadius = 126.0
        val newTerritoryBox = BoundingBox.of(centerLoc, newTerritoryRadius, 128.0, newTerritoryRadius)

        val worldSettlements = SettlementManager.settlements[world] ?: emptyList()
        for (existing in worldSettlements) {
            if (existing.territory.overlaps(newTerritoryBox) || existing.data.center.distanceSquared(centerLoc) < 80000.0) {
                val errorMsg = plugin.language.getString("info-messages.settlement-command.overlap")
                    ?.replace("{settlement}", existing.data.settlementName)
                    ?: "§cCannot create settlement here! The territory overlaps with §6{settlement}§c."
                player.sendFormattedMessage(errorMsg)
                return
            }
        }

        centerLoc.block.type = Material.CAMPFIRE

        val citizens = mutableSetOf<Villager>()
        for (i in 0 until 5) {
            val spawnLoc = centerLoc.clone().add((i - 2).toDouble(), 1.0, 0.0)
            val v = world.spawn(spawnLoc, Villager::class.java) { villager ->
                villager.profession = Villager.Profession.NONE
                villager.villagerLevel = 1
            }
            citizens.add(v)
        }

        val newData = Settlement.SettlementData(
            UUID.randomUUID(),
            world.uid,
            name,
            centerLoc,
            System.currentTimeMillis(),
            race.name
        )

        val settlement = Settlement(newData, citizens)
        val manager = SettlementManager()
        manager.generateSettlementName(settlement)
        settlement.data.settlementName = name

        val planner = SettlementPlanner(settlement)
        planner.planMeetingPointAtCenter()
        planner.planBuilding(VanillaBuildingType.TOWN_HALL)
        planner.planBuilding(VanillaBuildingType.FARM)

        val worldSettlementsList = SettlementManager.settlements.computeIfAbsent(world) { java.util.concurrent.CopyOnWriteArrayList() }
        worldSettlementsList.add(settlement)
        SettlementManager.saveSettlements(world)

        // ВЫДАЕМ АЧИВКУ ФОУНДЕР
        AchievementManager.grant(player, "founder")

        val successMsg = plugin.language.getString("info-messages.settlement-command.created-success")
            ?.replace("{settlement}", name)
            ?: "§aSuccessfully founded the settlement §6{settlement}§a!"
        player.sendFormattedMessage(successMsg)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
    }

    @Subcommand("forcequest")
    @CommandPermission("sv.settlement.forcequest")
    fun onForceQuest(player: Player) {
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
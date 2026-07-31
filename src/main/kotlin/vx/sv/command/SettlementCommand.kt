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
import vx.sv.gameplay.quest.QuestManager
import vx.sv.gameplay.quest.QuestManager.Companion.addQuest
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.isSettlementLeader
import vx.sv.gameplay.settlement.rent.RentManager
import vx.sv.nms.entity.ai.construct.SettlementPlanner
import vx.sv.nms.entity.ai.construct.VanillaBuildingType
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.UUID

@CommandAlias("settlement|s")
class SettlementCommand : BaseCommand() {

    init {
        // Регистрация кастомного контекстного сопоставителя для Settlement.
        // Он склеивает аргументы до нахождения точного совпадения названия поселения (поддерживает пробелы).
        plugin.commandManager.commandContexts.registerContext(Settlement::class.java) { c ->
            val argList = mutableListOf<String>()
            var resolvedSettlement: Settlement? = null

            // Исправлено: используем getFirstArg() != null для безопасной peek-проверки наличия аргументов
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

        // Registering a custom completion that returns a list of all settlement names
        plugin.commandManager.commandCompletions.registerCompletion("settlements") {
            SettlementManager.settlements.values.flatten().map { it.data.settlementName }
        }
    }

    @Subcommand("teleport|tp")
    @CommandPermission("sv.settlement.teleport")
    @CommandCompletion("@settlements")
    fun onTeleport(player: Player, settlement: Settlement) {
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
    fun onReputation(player: Player, settlement: Settlement, targetPlayerName: String, amount: Int) {
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
    fun onRelation(player: Player, settlementA: Settlement, settlementB: Settlement, level: Settlement.RelationLevel) {
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

    @Subcommand("create")
    @CommandPermission("sv.settlement.create")
    @CommandCompletion("@nothing")
    fun onCreateSettlement(player: Player, name: String, raceName: String) {
        val world = player.world
        if (!plugin.gameplayConfig.worlds.allowedWorlds.contains(world.name)) {
            player.sendFormattedMessage("§cSettlements cannot be created in this world!")
            return
        }

        // Проверяем существование расы
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

        // Проверка пересечения границ (территория поселения имеет радиус ~126 блоков)
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

        // Проверяем, не состоит ли игрок уже где-то или нет ли рядом жителей, но здесь создаем принудительно
        centerLoc.block.type = Material.CAMPFIRE

        val citizens = mutableSetOf<org.bukkit.entity.Villager>()
        for (i in 0 until 5) { // Стартовый набор из 5 жителей
            val spawnLoc = centerLoc.clone().add((i - 2).toDouble(), 1.0, 0.0)
            val v = world.spawn(spawnLoc, org.bukkit.entity.Villager::class.java) { villager ->
                villager.profession = org.bukkit.entity.Villager.Profession.NONE
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
        manager.generateSettlementName(settlement) // Либо сразу применяем имя
        settlement.data.settlementName = name

        val planner = SettlementPlanner(settlement)
        planner.planMeetingPointAtCenter()
        planner.planBuilding(VanillaBuildingType.TOWN_HALL)
        planner.planBuilding(VanillaBuildingType.FARM)

        val worldSettlementsList = SettlementManager.settlements.computeIfAbsent(world) { java.util.concurrent.CopyOnWriteArrayList() }
        worldSettlementsList.add(settlement)
        SettlementManager.saveSettlements(world)

        val successMsg = plugin.language.getString("info-messages.settlement-command.created-success")
            ?.replace("{settlement}", name)
            ?: "§aSuccessfully founded the settlement §6{settlement}§a!"
        player.sendFormattedMessage(successMsg)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
    }

    @Subcommand("highlight|hl")
    @CommandPermission("sv.settlement.highlight")
    fun onHighlight(player: Player) {
        val isEnabled = LeaderHighlightManager.toggleHighlight(player)
        if (isEnabled) {
            player.sendFormattedMessage("§aLeader highlight mode has been §eENABLED§a. Leaders will now glow for you.")
        } else {
            player.sendFormattedMessage("§aLeader highlight mode has been §cDISABLED§a.")
        }
    }

    @Subcommand("rent addmember")
    @CommandCompletion("@players")
    fun onRentAddMember(player: Player, targetName: String) {
        val rentedPlots = RentManager.getRentedPlotsByPlayer(player)
        if (rentedPlots.isEmpty()) {
            player.sendFormattedMessage(plugin.language.getString("rent.no-plots-owned") ?: "§cYou do not own any rented plots!")
            return
        }

        @Suppress("DEPRECATION")
        val target = Bukkit.getOfflinePlayer(targetName)
        if (!target.hasPlayedBefore() && !target.isOnline) {
            player.sendFormattedMessage(plugin.language.getString("command-error-message.player-not-found")?.replace("{playerName}", targetName) ?: "§cPlayer not found!")
            return
        }

        for (pair in rentedPlots) {
            pair.second.members.add(target.uniqueId)
        }
        RentManager.saveAll()

        val msg = plugin.language.getString("rent.member-added")
            ?.replace("{player}", target.name ?: targetName)
            ?: "§aPlayer {player} has been added to your plot members!"
        player.sendFormattedMessage(msg)
    }

    @Subcommand("rent removemember")
    @CommandCompletion("@players")
    fun onRentRemoveMember(player: Player, targetName: String) {
        val rentedPlots = RentManager.getRentedPlotsByPlayer(player)
        if (rentedPlots.isEmpty()) {
            player.sendFormattedMessage(plugin.language.getString("rent.no-plots-owned") ?: "§cYou do not own any rented plots!")
            return
        }

        @Suppress("DEPRECATION")
        val target = Bukkit.getOfflinePlayer(targetName)

        for (pair in rentedPlots) {
            pair.second.members.remove(target.uniqueId)
        }
        RentManager.saveAll()

        val msg = plugin.language.getString("rent.member-removed")
            ?.replace("{player}", target.name ?: targetName)
            ?: "§aPlayer {player} has been removed from your plot members!"
        player.sendFormattedMessage(msg)
    }

    @Subcommand("rent info")
    fun onRentInfo(player: Player) {
        val rentedPlots = RentManager.getRentedPlotsByPlayer(player)
        if (rentedPlots.isEmpty()) {
            player.sendFormattedMessage(plugin.language.getString("rent.no-plots-owned") ?: "§cYou do not own any rented plots!")
            return
        }

        for (pair in rentedPlots) {
            val record = pair.first
            val data = pair.second
            val settlement = SettlementManager.getById(data.settlementId)
            val settlementName = settlement?.data?.settlementName ?: "Unknown"

            val remainingTicks = data.rentExpiryGameTime - (settlement?.world?.gameTime ?: 0L)
            val remainingDays = (remainingTicks / 24000L).coerceAtLeast(0L)

            val memberNames = data.members.mapNotNull { Bukkit.getOfflinePlayer(it).name }.joinToString(", ")
                .ifEmpty { "None" }

            val msg = """
                §6=== Plot Info (${record.type}) ===
                §7Settlement: §f$settlementName
                §7Expires in: §e$remainingDays in-game days
                §7Members: §b$memberNames
            """.trimIndent()

            player.sendMessage(msg)
        }
    }

    @Subcommand("rent pay")
    fun onRentPay(player: Player) {
        val rentedPlots = RentManager.getRentedPlotsByPlayer(player)
        if (rentedPlots.isEmpty()) {
            player.sendFormattedMessage(plugin.language.getString("rent.no-plots-owned") ?: "§cYou do not own any rented plots!")
            return
        }

        var paidCount = 0
        for (pair in rentedPlots) {
            val data = pair.second
            val settlement = SettlementManager.getById(data.settlementId) ?: continue

            if (RentManager.renewPlotRent(player, settlement, data)) {
                paidCount++
            }
        }

        if (paidCount > 0) {
            val msg = plugin.language.getString("rent.success-renewed")
                ?: "§aYou have successfully extended your land lease!"
            player.sendFormattedMessage(msg)
        } else {
            player.sendFormattedMessage("§cYou do not have enough race currency to pay rent!")
        }
    }

}
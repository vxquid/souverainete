package vx.sv.gameplay.settlement.politics

import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import kotlin.random.Random

class PoliticsManager : Listener {

    private val tickInterval = 6000L // 5 minutes in ticks
    private val searchRadius = 2000.0
    private val notificationRadius = 150.0

    // Tracks contacts made during this server session
    private val establishedContacts = mutableSetOf<String>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startPoliticalTicker()
    }

    private fun startPoliticalTicker() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            simulatePolitics()
        }, tickInterval, tickInterval)
    }

    private fun getContactPairKey(id1: Any, id2: Any): String {
        val s1 = id1.toString()
        val s2 = id2.toString()
        return if (s1 < s2) "${s1}_${s2}" else "${s2}_${s1}"
    }

    private fun notifyLocalPlayers(settlement: Settlement, message: String) {
        val center = settlement.data.center
        val world = center.world ?: return

        world.players.forEach { player ->
            if (player.location.distance(center) <= notificationRadius) {
                player.sendMessage(message)
            }
        }
    }

    private fun simulatePolitics() {
        val worlds = plugin.gameplayManager.allowedWorlds
        if (worlds.isEmpty()) return

        val world = worlds.random()
        val worldSettlements = SettlementManager.settlements[world] ?: return
        if (worldSettlements.size < 2) return

        val initiator = worldSettlements.random()
        val neighbors = worldSettlements.filter {
            it.data.id != initiator.data.id &&
                    it.data.center.distance(initiator.data.center) <= searchRadius
        }

        if (neighbors.isEmpty()) return
        val target = neighbors.random()

        // --- 1. First Contact Logic (Dice roll mechanic) ---
        val contactKey = getContactPairKey(initiator.data.id, target.data.id)
        val knownRelation = initiator.data.relations.containsKey(target.data.id)

        // If they aren't in each other's maps and haven't met this session, it's first contact!
        if (!knownRelation && !establishedContacts.contains(contactKey)) {
            establishedContacts.add(contactKey)
            handleFirstContact(initiator, target)
            return // Skip standard shift for this tick
        }

        // --- 2. Standard Daily Politics ---
        val roll = Random.nextInt(100)
        when {
            roll < 30 -> return // 30% chance: Nothing happens
            roll < 85 -> shiftRelation(initiator, target) // 55% chance: Diplomatic shift
            else -> triggerCriticalEvent(initiator, target) // 15% chance: Critical event
        }
    }

    /**
     * Handles the dice roll for the First Contact between two settlements.
     */
    private fun handleFirstContact(initiator: Settlement, target: Settlement) {
        val sameRace = initiator.data.dominantRace == target.data.dominantRace
        var roll = Random.nextInt(100) + 1 // Dice 1 to 100

        // If settlements have the same dominant race, they are highly favored to become allies
        if (sameRace) {
            roll += 25
        }

        val (newRelation, messageKey, defaultMessage) = when {
            roll >= 90 -> Triple(Settlement.RelationLevel.ALLIANCE, "politics.first-contact.critical-success", "§a📜 Historic Event! §6{settlementA} §aand §6{settlementB} §ahave discovered each other and instantly formed an §bALLIANCE§a!")
            roll >= 70 -> Triple(Settlement.RelationLevel.WARM, "politics.first-contact.success", "§a📜 First Contact! §6{settlementA} §aand §6{settlementB} §ahave met peacefully. Their relations are §eWARM§a.")
            roll >= 40 -> Triple(Settlement.RelationLevel.NEUTRAL, "politics.first-contact.neutral", "§e📜 First Contact! Scouts from §6{settlementA} §eand §6{settlementB} §eestablished cautious communication. Relations are §fNEUTRAL§e.")
            roll >= 20 -> Triple(Settlement.RelationLevel.TENSE, "politics.first-contact.failure", "§c⚠ Border Clash! The first meeting between §6{settlementA} §cand §6{settlementB} §cended in blood. Relations are highly §6TENSE§c!")
            else -> Triple(Settlement.RelationLevel.WAR, "politics.first-contact.critical-failure", "§4☠ Declaration of War! §cDiplomacy failed instantly. §6{settlementA} §cand §6{settlementB} §care now at §4WAR§c!")
        }

        SettlementManager.setRelation(initiator, target, newRelation)

        val message = plugin.language.getString(messageKey)
            ?.replace("{settlementA}", initiator.data.settlementName)
            ?.replace("{settlementB}", target.data.settlementName)
            ?: defaultMessage
                .replace("{settlementA}", initiator.data.settlementName)
                .replace("{settlementB}", target.data.settlementName)

        notifyLocalPlayers(initiator, message)
        notifyLocalPlayers(target, message)

        plugin.logger.info("[Politics] First Contact between ${initiator.data.settlementName} and ${target.data.settlementName}. Roll: $roll (Same Race Bonus: $sameRace). Result: $newRelation")
    }

    /**
     * Shifts the relationship up or down strictly by one level.
     */
    private fun shiftRelation(settlementA: Settlement, settlementB: Settlement) {
        val currentRelation = SettlementManager.getRelation(settlementA, settlementB)
        val values = Settlement.RelationLevel.entries.toTypedArray()
        val currentIndex = currentRelation.ordinal

        val moveUp = Random.nextBoolean()
        val newIndex = if (moveUp) {
            (currentIndex + 1).coerceAtMost(values.size - 1)
        } else {
            (currentIndex - 1).coerceAtLeast(0)
        }

        val newRelation = values[newIndex]

        if (newRelation != currentRelation) {
            SettlementManager.setRelation(settlementA, settlementB, newRelation)
            plugin.logger.info("[Politics] Relation shift: ${settlementA.data.settlementName} and ${settlementB.data.settlementName} are now $newRelation.")

            val shiftMsg = plugin.language.getString("politics.relation-shift")
                ?.replace("{settlementA}", settlementA.data.settlementName)
                ?.replace("{settlementB}", settlementB.data.settlementName)
                ?.replace("{relation}", newRelation.name)
                ?: "§e📜 Diplomatic update: Relations between §6${settlementA.data.settlementName} §eand §6${settlementB.data.settlementName} §eare now §b$newRelation§e."

            notifyLocalPlayers(settlementA, shiftMsg)
            notifyLocalPlayers(settlementB, shiftMsg)
        }
    }

    /**
     * Contextual critical events. Allies won't betray each other instantly.
     */
    private fun triggerCriticalEvent(initiator: Settlement, target: Settlement) {
        val currentRelation = SettlementManager.getRelation(initiator, target)

        when (currentRelation) {
            Settlement.RelationLevel.WAR -> {
                // If they are already at war, launch a raid
                if (target.data.activeRaid == null) {
                    plugin.gameplayManager.raidManager.startRaid(initiator, target)

                    val world = target.data.center.world
                    val broadcastMessage = plugin.language.getString("raid.chat.started-broadcast")
                        ?.replace("{attacker}", initiator.data.settlementName)
                        ?.replace("{defender}", target.data.settlementName)
                        ?: "§c⚔ A raid has begun: §6${initiator.data.settlementName} §chas attacked §6${target.data.settlementName}§c!"

                    world?.players?.forEach { player ->
                        player.sendMessage(broadcastMessage)
                    }
                }
            }
            Settlement.RelationLevel.TENSE -> {
                SettlementManager.setRelation(initiator, target, Settlement.RelationLevel.WAR)
                plugin.logger.info("[Politics] Critical Event: ${initiator.data.settlementName} declared WAR on ${target.data.settlementName}!")

                val warMsg = plugin.language.getString("politics.war-declared")
                    ?.replace("{attacker}", initiator.data.settlementName)
                    ?.replace("{defender}", target.data.settlementName)
                    ?: "§c⚔ WAR! §6${initiator.data.settlementName} §chas formally declared war on §6${target.data.settlementName}§c!"

                notifyLocalPlayers(initiator, warMsg)
                notifyLocalPlayers(target, warMsg)
            }
            Settlement.RelationLevel.ALLIANCE -> {
                plugin.logger.info("[Politics] Event: ${initiator.data.settlementName} sent gifts to their ally, ${target.data.settlementName}.")

                val giftMsg = plugin.language.getString("politics.alliance-gifts")
                    ?.replace("{sender}", initiator.data.settlementName)
                    ?.replace("{receiver}", target.data.settlementName)
                    ?: "§a🎁 §6${initiator.data.settlementName} §ahas sent a caravan of diplomatic gifts to their ally, §6${target.data.settlementName}§a."

                notifyLocalPlayers(initiator, giftMsg)
                notifyLocalPlayers(target, giftMsg)
            }
            else -> {
                SettlementManager.setRelation(initiator, target, Settlement.RelationLevel.TENSE)
                plugin.logger.info("[Politics] Critical Event: Diplomatic incident! ${initiator.data.settlementName} and ${target.data.settlementName} are now TENSE.")

                val incidentMsg = plugin.language.getString("politics.diplomatic-incident")
                    ?.replace("{settlementA}", initiator.data.settlementName)
                    ?.replace("{settlementB}", target.data.settlementName)
                    ?: "§c⚠ Diplomatic Incident! Relations between §6${initiator.data.settlementName} §cand §6${target.data.settlementName} §care severely damaged and are now tense."

                notifyLocalPlayers(initiator, incidentMsg)
                notifyLocalPlayers(target, incidentMsg)
            }
        }
    }

    // ========================================================================
    // Events to notify players about active raids when they spawn in a world.
    // ========================================================================
    private fun notifyAboutActiveRaids(player: Player, world: World) {
        val worldSettlements = SettlementManager.settlements[world] ?: return
        val raidedSettlements = worldSettlements.filter { it.data.activeRaid != null }

        if (raidedSettlements.isNotEmpty()) {
            val header = plugin.language.getString("raid.world-summary.header")
                ?: "§e⚔ Military conflicts are currently taking place in this world:"

            val message = buildString {
                append(header).append("\n")
                raidedSettlements.forEach { settlement ->
                    val item = plugin.language.getString("raid.world-summary.active-raid")
                        ?.replace("{settlement}", settlement.data.settlementName)
                        ?: "§c⚔ The settlement §6${settlement.data.settlementName} §cis under attack!"
                    append(item).append("\n")
                }
            }
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (player.isOnline && player.world == world) {
                    player.sendMessage(message)
                }
            }, 1L)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        notifyAboutActiveRaids(event.player, event.player.world)
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        notifyAboutActiveRaids(event.player, event.player.world)
    }

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val world = event.respawnLocation.world ?: return
        notifyAboutActiveRaids(event.player, world)
    }
}
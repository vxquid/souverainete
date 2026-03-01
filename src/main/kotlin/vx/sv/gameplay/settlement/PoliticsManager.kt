package vx.sv.gameplay.settlement

import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import vx.sv.Souverainete.Companion.plugin
import kotlin.random.Random

class PoliticsManager : Listener {

    private val tickInterval = 6000L // 5 minutes in ticks
    private val searchRadius = 1000.0

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startPoliticalTicker()
    }

    private fun startPoliticalTicker() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            simulatePolitics()
        }, tickInterval, tickInterval)
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

        val roll = Random.nextInt(100)
        when {
            roll < 30 -> return // 30% chance: Nothing happens
            roll < 85 -> shiftRelation(initiator, target) // 55% chance: Diplomatic shift
            else -> triggerCriticalEvent(initiator, target) // 15% chance: Critical event
        }
    }

    /**
     * Shifts the relationship up or down strictly by one level.
     * Prevents jumping straight from ALLIANCE to WAR.
     */
    private fun shiftRelation(settlementA: Settlement, settlementB: Settlement) {
        val currentRelation = SettlementManager.getRelation(settlementA, settlementB)
        val values = Settlement.RelationLevel.values()
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

                    // Broadcast to all players in the world about the raid starting
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
                // Tense situations erupt into war
                SettlementManager.setRelation(initiator, target, Settlement.RelationLevel.WAR)
                plugin.logger.info("[Politics] Critical Event: ${initiator.data.settlementName} declared WAR on ${target.data.settlementName}!")
            }
            Settlement.RelationLevel.ALLIANCE -> {
                // Allies strengthen their bond (Placeholder for resource sharing / rep boost)
                plugin.logger.info("[Politics] Event: ${initiator.data.settlementName} sent gifts to their ally, ${target.data.settlementName}.")
            }
            else -> {
                // Neutral/Warm suffer a sudden diplomatic incident resulting in tension
                SettlementManager.setRelation(initiator, target, Settlement.RelationLevel.TENSE)
                plugin.logger.info("[Politics] Critical Event: Diplomatic incident! ${initiator.data.settlementName} and ${target.data.settlementName} are now TENSE.")
            }
        }
    }

    // ========================================================================
    // Events to notify players about active raids when they spawn in a world.
    // ========================================================================
    private fun notifyAboutActiveRaids(player: Player, world: World) {
        val worldSettlements = SettlementManager.settlements[world] ?: return

        // Find all settlements in this world that are currently being raided
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
            // A slight 1-tick delay ensures the player is fully loaded in the world before receiving the message
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
        // Get the world of the respawn location
        val world = event.respawnLocation.world ?: return
        notifyAboutActiveRaids(event.player, world)
    }
}
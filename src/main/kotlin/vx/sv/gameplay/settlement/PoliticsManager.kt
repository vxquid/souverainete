package vx.sv.gameplay.settlement

import org.bukkit.event.Listener
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
}
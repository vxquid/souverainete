package vx.sv.gameplay.settlement

import org.bukkit.event.Listener
import vx.sv.Souverainete.Companion.plugin

class RaidManager : Listener {

    private val activeRaids = mutableSetOf<SettlementRaid>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startRaidTicker()
    }

    /**
     * Ticks every second (20 ticks) to update raid logic and bossbars.
     */
    private fun startRaidTicker() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            // Need a snapshot to prevent ConcurrentModificationException if a raid finishes and removes itself
            val raidsSnapshot = activeRaids.toList()
            raidsSnapshot.forEach { it.tick() }
        }, 20L, 20L)
    }

    /**
     * Initiates a brand new raid.
     */
    fun startRaid(attacker: Settlement, defender: Settlement) {
        if (defender.data.activeRaid != null) return // Already being raided

        val raidData = Settlement.RaidData(
            attackerId = attacker.data.id,
            status = Settlement.RaidStatus.ONGOING,
            currentWave = 0,
            totalWaves = 3
        )
        defender.data.activeRaid = raidData
        SettlementManager.saveSettlements(defender.world)

        val raid = SettlementRaid(defender, raidData)
        activeRaids.add(raid)

        plugin.logger.info("[Raid] ${attacker.data.settlementName} initiated a raid on ${defender.data.settlementName}!")
    }

    /**
     * Removes a raid from the active tracker.
     */
    fun removeActiveRaid(raid: SettlementRaid) {
        activeRaids.remove(raid)
    }

    /**
     * Should be called when a world is loaded to restore active raids from serialized data.
     */
    fun restoreRaidsFromData(settlements: List<Settlement>) {
        for (settlement in settlements) {
            val raidData = settlement.data.activeRaid
            if (raidData != null && raidData.status == Settlement.RaidStatus.ONGOING) {
                val raid = SettlementRaid(settlement, raidData)
                activeRaids.add(raid)
                plugin.logger.info("[Raid] Restored active raid on ${settlement.data.settlementName}.")
            } else if (raidData != null) {
                // Clean up stale raids that were finished but the server shut down before the 10-sec cleanup delay
                settlement.data.activeRaid = null
            }
        }
    }

    /**
     * Cleanly hide boss bars on plugin disable to prevent ghost UI elements.
     */
    fun disable() {
        activeRaids.forEach { it.destroy() }
        activeRaids.clear()
    }
}
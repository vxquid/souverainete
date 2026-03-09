package vx.sv.gameplay.settlement

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.plugin
import java.util.*

class Settlement(val data: SettlementData, val villagers: MutableSet<Villager> = mutableSetOf()) {

    data class SettlementData(
        val id: UUID,
        val worldUUID: UUID,
        var settlementName: String,
        val center: Location,
        val creationTime: Long,
        var dominantRace: String,
        var leaderId: UUID? = null, // Stores the elected leader's UUID
        var leaderName: String? = null, // Stores the elected leader's string name for quick access
        val reputation: MutableMap<UUID, Int> = mutableMapOf(),
        val relations: MutableMap<UUID, RelationLevel> = mutableMapOf(),
        var activeRaid: RaidData? = null
    )

    data class RaidData(
        val attackerId: UUID,
        var status: RaidStatus = RaidStatus.ONGOING,
        var currentWave: Int = 0,
        val totalWaves: Int = 3,
        var totalRaidersInWave: Int = 0,
        val aliveRaiders: MutableSet<UUID> = mutableSetOf(),
        var activeTicks: Long = 0
    )

    val world     = plugin.server.getWorld(data.worldUUID)!!
    var territory = BoundingBox.of(data.center, 64.0, 64.0, 64.0)

    /**
     * Randomly elects a new leader from the current villagers.
     * Removes the old leader's PDC tag and assigns it to the new one.
     */
    fun electLeader() {
        if (villagers.isEmpty()) return

        // 1. Clear previous leader if they are still alive in the settlement
        data.leaderId?.let { oldId ->
            val oldLeader = villagers.find { it.uniqueId == oldId }
            oldLeader?.persistentDataContainer?.remove(LEADER_KEY)
        }

        // 2. Elect a new random leader
        val newLeader = villagers.random()
        data.leaderId = newLeader.uniqueId
        data.leaderName = newLeader.customName ?: "Leader" // Save string name

        // 3. Set PDC tag to identify the leader (storing settlement UUID)
        newLeader.persistentDataContainer.set(LEADER_KEY, PersistentDataType.STRING, data.id.toString())
    }

    fun size(): SettlementSize {
        return when {
            villagers.size in 1..10 -> SettlementSize.UNDERDEVELOPED
            villagers.size in 11..20 -> SettlementSize.EMERGING
            villagers.size in 21..30 -> SettlementSize.ESTABLISHED
            villagers.size in 31..50 -> SettlementSize.ADVANCED
            villagers.size > 50 -> SettlementSize.METROPOLIS
            else -> SettlementSize.UNDERDEVELOPED
        }
    }

    enum class SettlementSize {
        UNDERDEVELOPED,
        EMERGING,
        ESTABLISHED,
        ADVANCED,
        METROPOLIS
    }

    enum class RelationLevel {
        WAR,
        TENSE,
        NEUTRAL,
        WARM,
        ALLIANCE
    }

    enum class RaidStatus {
        ONGOING,
        VICTORY,
        LOSS,
        STOPPED
    }

    companion object {
        // NamespaceKey used for PersistentDataContainer checks
        val LEADER_KEY = NamespacedKey(plugin, "settlement_leader")
    }
}

// =========================================
// Extensions for quick leader checking
// =========================================
/**
 * Checks if this NPC (Villager) is currently a settlement leader.
 */
fun Villager.isSettlementLeader(): Boolean {
    return this.persistentDataContainer.has(Settlement.LEADER_KEY, PersistentDataType.STRING)
}

/**
 * Retrieves the UUID of the settlement this Villager leads.
 * Returns null if the NPC is not a leader or data is invalid.
 */
fun Villager.getLedSettlementId(): UUID? {
    val idString = this.persistentDataContainer.get(Settlement.LEADER_KEY, PersistentDataType.STRING) ?: return null
    return try {
        UUID.fromString(idString)
    } catch (e: IllegalArgumentException) {
        null
    }
}
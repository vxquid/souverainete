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
        var leaderId: UUID? = null,
        var leaderName: String? = null,
        val reputation: MutableMap<UUID, Int> = mutableMapOf(),
        val relations: MutableMap<UUID, RelationLevel> = mutableMapOf(),

        var diplomaticHistory: MutableMap<UUID, MutableList<String>>? = null,
        var activeRaid: RaidData? = null,

        var activeProjectId: UUID? = null
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

    val world = plugin.server.getWorld(data.worldUUID)!!

    // ИСПРАВЛЕНО: Увеличен радиус территории деревни со 64.0 до 120.0 блоков во все стороны для простора
    var territory = BoundingBox.of(data.center, 126.0, 128.0, 126.0)

    fun electLeader() {
        if (villagers.isEmpty()) return

        data.leaderId?.let { oldId ->
            val oldLeader = villagers.find { it.uniqueId == oldId }
            oldLeader?.persistentDataContainer?.remove(LEADER_KEY)
        }

        val newLeader = villagers.random()
        data.leaderId = newLeader.uniqueId
        data.leaderName = newLeader.customName ?: "Leader"

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

    enum class SettlementSize { UNDERDEVELOPED, EMERGING, ESTABLISHED, ADVANCED, METROPOLIS }
    enum class RelationLevel { WAR, TENSE, NEUTRAL, WARM, ALLIANCE }
    enum class RaidStatus { ONGOING, VICTORY, LOSS, STOPPED }

    companion object {
        val LEADER_KEY = NamespacedKey(plugin, "settlement_leader")
    }
}

fun Villager.isSettlementLeader(): Boolean {
    return this.persistentDataContainer.has(Settlement.LEADER_KEY, PersistentDataType.STRING)
}

fun Villager.getLedSettlementId(): UUID? {
    val idString = this.persistentDataContainer.get(Settlement.LEADER_KEY, PersistentDataType.STRING) ?: return null
    return try {
        UUID.fromString(idString)
    } catch (e: IllegalArgumentException) {
        null
    }
}
package vx.sv.gameplay.settlement

import org.bukkit.Location
import org.bukkit.entity.Villager
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.plugin
import java.util.*

class Settlement(val data: SettlementData, val villagers: MutableSet<Villager> = mutableSetOf()) {

    data class SettlementData(val id: UUID, val worldUUID: UUID, var settlementName: String, val center: Location, val creationTime: Long, val reputation: MutableMap<UUID, Int> = mutableMapOf())

    val world        = plugin.server.getWorld(data.worldUUID)!!
    var territory    = BoundingBox.of(data.center, 64.0, 64.0, 64.0)

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

}
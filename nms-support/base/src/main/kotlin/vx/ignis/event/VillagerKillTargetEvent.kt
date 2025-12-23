package vx.ignis.event

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class VillagerKillTargetEvent(
    val villager: Villager,
    val victim: LivingEntity,
    val killType: KillType
) : Event() {

    enum class KillType {
        MELEE,  // Ближний бой (Меч, рука, удар луком)
        RANGED, // Дальний бой (Стрела, фейерверк из арбалета)
        OTHER   // Магия, шипы и прочее
    }

    override fun getHandlers(): HandlerList {
        return HANDLERS
    }

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList {
            return HANDLERS
        }
    }
}
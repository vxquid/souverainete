package vx.ignis.gameplay.humanoid.event

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import vx.ignis.gameplay.humanoid.entity.HumanoidInfo

class HumanoidInitializationEvent(val player: Player, val entity: Villager, val humanoidInfo: HumanoidInfo, val metadata: List<EntityData<*>>) :
    Event() {

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
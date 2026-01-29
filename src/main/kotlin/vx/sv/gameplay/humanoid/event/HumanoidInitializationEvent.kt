package vx.sv.gameplay.humanoid.event

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import vx.sv.gameplay.humanoid.protocol.HumanoidDataWrapper

class HumanoidInitializationEvent(val player: Player, val entity: Villager, val humanoidInfo: HumanoidDataWrapper, val metadata: List<EntityData<*>>) :
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
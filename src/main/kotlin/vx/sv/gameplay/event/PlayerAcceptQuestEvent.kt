package vx.sv.gameplay.event

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import vx.sv.gameplay.quest.QuestManager.Quest

class PlayerAcceptQuestEvent(val player: Player, val questGiver: LivingEntity, val quest: Quest) : Event() {

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
package vx.sv.gameplay.event

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import vx.sv.gameplay.quest.QuestManager

class QuestInvalidationEvent(val player: Player, val quest: QuestManager.Quest, val reason: Reason) : Event() {

    override fun getHandlers(): HandlerList {
        return HANDLERS
    }

    enum class Reason {
        NPC_DEATH, TIME_EXPIRATION, FINISHED_BY_SOMEONE_ELSE, NOT_ACTUAL
    }

    companion object {

        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList {
            return HANDLERS
        }

    }

}
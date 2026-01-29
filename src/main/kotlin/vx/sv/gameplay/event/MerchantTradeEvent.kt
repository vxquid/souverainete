package vx.sv.gameplay.event

import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.MerchantRecipe

class MerchantTradeEvent(val merchant: LivingEntity, val player: Player, val recipe: MerchantRecipe) : Event() {

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
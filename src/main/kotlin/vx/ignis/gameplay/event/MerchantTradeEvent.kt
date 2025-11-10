package vx.ignis.gameplay.event

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.MerchantRecipe
import vx.ignis.gameplay.trade.TradeManager.QuestMerchant

class MerchantTradeEvent(val merchant: QuestMerchant, val player: Player, val recipe: MerchantRecipe) : Event() {

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
package vx.ignis.gameplay.trade

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.inventory.view.MerchantView
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.persistent.LivingEntityExtend.quests
import vx.ignis.gameplay.event.MerchantTradeEvent
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race

@Suppress("UnstableApiUsage")
class TradeHack : Listener {

    data class QuestMerchant(val questGiverEntity: LivingEntity, val customTradeView: MerchantView)

    @EventHandler
    private fun handleMerchantInventoryClick(event: InventoryClickEvent) {
        if (event.inventory.type == InventoryType.MERCHANT) {
            val player        = (event.whoClicked as Player)
            val questMerchant = (playerTradingInventories[player] ?: return)
            val slot          = if (event.rawSlot != 2) return else 2

            val rewardItem  = event.inventory.getItem(slot) ?: return
            val firstTrade  = event.inventory.getItem(0) ?: return

            if (rewardItem.type != Material.AIR) {
                val recipe = questMerchant.customTradeView.merchant.recipes.find { it.result.isSimilar(rewardItem) && it.ingredients[0].isSimilar(firstTrade) } ?: throw NullPointerException("Null recipe on successful trade.")

                // Отправляем на следующем тике, чтобы прошёл трейд.
                Bukkit.getServer().scheduler.runTask(plugin) { _ ->
                    Bukkit.getServer().pluginManager.callEvent(MerchantTradeEvent(questMerchant, player, recipe))
                }

            }
        }
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    companion object {
        val playerTradingInventories = mutableMapOf<Player, QuestMerchant>()


        fun Villager.openCustomTradeMenu(player: Player, open: Boolean = true) : Boolean {

            val playerQuests    = player.quests()
            val originalRecipes = recipes.toMutableList() // We are copying it. Not modifying.

            val questTrades = mutableListOf<MerchantRecipe>()
            if (quests().isNotEmpty()) {
                quests().forEach { quest ->

                    // Only accepted quests can be finished.
                    if (playerQuests.find { it.id == quest.id } != null) {
                        val recipe = MerchantRecipe(quest.calculateReward(this.race.normalCurrency.name), 1)
                        recipe.addIngredient(quest.questItem.getItemStack())
                        questTrades += recipe
                    }

                }
            }

            if (questTrades.isEmpty() && originalRecipes.isEmpty()) {
                this.shakeHead()
                return false
            }

            // The idea is simple. To keep original trading recipes (they can and they definitely will be customized),
            // we're just creating a new merchant and copying the tradeable stuff. This will be changed in the future, when the plugin will be on the server.
            if (open) {
                val merchantView = MenuType.MERCHANT.create(player)
                merchantView.merchant.recipes = questTrades + originalRecipes
                player.openInventory(merchantView)
                playerTradingInventories[player] = QuestMerchant(this, merchantView)
            }

            return true
        }

    }

}
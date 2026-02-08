package vx.sv.gameplay.trade

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.event.MerchantTradeEvent
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.reputation.ReputationManager.Companion.opinionOn
import vx.sv.gameplay.trade.ScoreCalculator.calculateScore
import vx.sv.gameplay.trade.ScoreCalculator.getBasicScore
import vx.sv.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.sv.persistent.LivingEntityExtend.quests
import vx.sv.persistent.LivingEntityExtend.settlement
import vx.sv.persistent.LivingEntityExtend.subInventory
import vx.sv.persistent.LivingEntityExtend.takeItemFromQuillInventory

class TradeManager : Listener {

    @EventHandler
    private fun handleMerchantInventoryClick(event: InventoryClickEvent) {
        if (event.inventory.type == InventoryType.MERCHANT) {
            val player   = (event.whoClicked as Player)
            val villager = playerTradingInventories[player] ?: return
            val slot     = if (event.rawSlot != 2) return else 2

            val rewardItem  = event.inventory.getItem(slot) ?: return
            val firstTrade  = event.inventory.getItem(0) ?: return

            if (rewardItem.type != Material.AIR) {
                // If vanilla trading is enabled, we must ensure we only trigger plugin logic
                // for Quest items, otherwise we might mess up inventory for standard vanilla trades.
                if (plugin.gameplayManager.config.general.vanillaTrading) {
                    val isQuest = villager.quests().any {
                        it.questItem.getItemStack().isSimilar(firstTrade)
                    }
                    if (!isQuest) return
                }

                val recipe = villager.recipes.find { it.result.isSimilar(rewardItem) && it.ingredients[0].isSimilar(firstTrade) } ?: throw NullPointerException("Null recipe on successful trade.")

                // Отправляем на следующем тике, чтобы прошёл трейд.
                Bukkit.getServer().scheduler.runTask(plugin) { _ ->
                    Bukkit.getServer().pluginManager.callEvent(MerchantTradeEvent(villager, player, recipe))
                }

            }
        }
    }

    @EventHandler
    private fun handleTrade(event: MerchantTradeEvent) {
        (event.merchant as? Villager)?.let { villager: Villager ->
            event.recipe.let { recipe ->
                villager.quests().find {it.questItem.getItemStack().isSimilar(recipe.ingredients[0])}
                villager.addItemToQuillInventory(recipe.ingredients[0])
                if (recipe.ingredients.size > 1) villager.addItemToQuillInventory(recipe.ingredients[1])
                villager.takeItemFromQuillInventory(recipe.result, recipe.result.amount)
            }
        }
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    companion object {

        private data class TradingSlot(var currency: Material, var amount: Int) {

            fun calculateAmount(price: Int) : Int {
                return price / currency.getBasicScore()
            }

            fun totalPrice() : Int {
                return currency.getBasicScore() * amount
            }

            fun toItemStack() : ItemStack = ItemStack(currency, amount)

        }

        private val Villager.producedItems: List<ItemStack>
            get() {
                val itemsToProduce = plugin.professions.getStringList("villager-item-producing.profession.${this.profession.key.key.uppercase()}.item-produce")
                return subInventory.filterNotNull().filter { itemStack -> itemsToProduce.contains(itemStack.type.toString()) }.toList()
            }

        val playerTradingInventories = mutableMapOf<Player, Villager>()
        fun Villager.openTradeMenu(player: Player, open: Boolean = true) : Boolean {

            // Each race has own currency.
            val race = race
            val currency = race.normalCurrency
            val specialCurrency = race.specialCurrency

            val vanillaTrading = plugin.gameplayManager.config.general.vanillaTrading

            // If vanilla trading is OFF, we clear recipes. If ON, we keep them but might filter duplicates later.
            if (!vanillaTrading) {
                recipes = mutableListOf<MerchantRecipe>()
            }

            val playerQuests = player.quests()

            // Update quests and make them first in the trade GUI.
            val questTrades = mutableListOf<MerchantRecipe>()
            val activeQuests = quests()

            if (activeQuests.isNotEmpty()) {
                activeQuests.forEach { quest ->

                    // Only accepted quests can be finished.
                    if (playerQuests.find { it.id == quest.id } != null) {
                        val recipe = MerchantRecipe(quest.calculateReward(currency.get().toString()), 1)
                        recipe.addIngredient(quest.questItem.getItemStack())
                        questTrades += recipe
                    }

                }
            }

            // --- VANILLA MODE LOGIC ---
            if (vanillaTrading) {
                // We keep vanilla trades, but we need to ensure we don't duplicate quest trades
                // if the menu is opened multiple times.

                // Filter current recipes to keep ONLY vanilla ones (remove previous quest entries)
                val currentVanillaRecipes = this.recipes.filter { recipe ->
                    // Assume it's a plugin quest if the ingredient matches any active quest item
                    activeQuests.none { q -> q.questItem.getItemStack().isSimilar(recipe.ingredients.firstOrNull()) }
                }

                this.recipes = (questTrades + currentVanillaRecipes).toMutableList()

                if (open) {
                    player.openMerchant(this, true)
                    playerTradingInventories[player] = this
                }

                // Return true to indicate menu opened/ready, skipping custom economy logic below
                return true
            }
            // --------------------------

            val tradeProfessionItemsOnly = plugin.professions.getBoolean("villager-item-producing.trade-profession-items-only")
            val itemsToTrade = if (tradeProfessionItemsOnly) producedItems else subInventory.filterNotNull()

            val settlement = settlement
            val multiplier = if (settlement != null ) this.opinionOn(player).priceMultiplier.toFloat() else 1F

            // Going through every produced item.
            itemsToTrade.forEach { item ->

                // Trade a currency? Really?
                if (item.type == race.normalCurrency.get())
                    return@forEach

                // We skip adding identical trades
                if (recipes.find { recipe -> recipe.result.isSimilar(item) } != null) {
                    return@forEach
                }

                // We're trading just one item.
                val total = item.amount
                item.amount = 1

                val trade = TradingSlot(Material.AIR, 0) to TradingSlot(Material.AIR, 0)
                val price = (item.calculateScore() * multiplier).toInt()

                // Use the special currency if needed.
                val useSpecialCurrency = price / (currency.get() ?: throw NullPointerException("Can't init currency!")).getBasicScore() > (currency.get() ?: throw NullPointerException("Can't init currency!")).maxStackSize * 2

                // First things first.
                trade.first.let { firstSlot ->

                    firstSlot.currency = if (useSpecialCurrency) specialCurrency.get() ?: throw NullPointerException("Can't init currency!") else currency.get() ?: throw NullPointerException("Can't init currency!")
                    val useSecondSlot = firstSlot.calculateAmount(price) > firstSlot.currency.maxStackSize

                    if (!useSecondSlot) {
                        firstSlot.amount = firstSlot.calculateAmount(price)
                    } else {
                        firstSlot.amount = firstSlot.currency.maxStackSize
                        trade.second.let { secondSlot ->
                            secondSlot.currency = if (useSpecialCurrency) specialCurrency.get() ?: throw NullPointerException("Can't init currency!") else currency.get() ?: throw NullPointerException("Can't init currency!")
                            secondSlot.amount   = secondSlot.calculateAmount(price - firstSlot.totalPrice())
                        }
                    }

                    if (price - firstSlot.totalPrice() > 0 && !useSecondSlot && useSpecialCurrency) {
                        trade.second.currency = currency.get() ?: throw NullPointerException("Can't init currency!")
                        trade.second.amount   = trade.second.calculateAmount(price - firstSlot.totalPrice())
                    }
                }

                // If villager tries to sell something really cheap, skip it.
                if (trade.first.amount == 0)
                    return@forEach

                val recipe = MerchantRecipe(item, total)
                recipe.addIngredient(trade.first.toItemStack())

                if (trade.second.amount > 0)
                    recipe.addIngredient(trade.second.toItemStack())

                this.recipes = recipes + recipe
            }

            // Sort by type, then by price.
            val sorted = recipes.toMutableList().sortedWith(compareBy({ it.result.type }, { it.result.calculateScore() }))

            if (recipes.isEmpty() && questTrades.isEmpty()) {
                this.shakeHead()
                return false
            }

            if (open) {
                // Quest trades always first.
                recipes = questTrades + sorted

                player.openMerchant(this, true)
                playerTradingInventories[player] = this
            }

            return true
        }

    }

}
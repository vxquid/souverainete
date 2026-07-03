package vx.sv.gameplay.profession

import org.bukkit.Bukkit
import org.bukkit.Keyed
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.*
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.event.VillagerProduceItemEvent
import vx.sv.persistent.LivingEntityExtend.settlement
import vx.sv.gameplay.settlement.SettlementManager
import kotlin.random.Random

/**
 * Manages villager professions, including item production, unique item generation, and related events.
 * This class handles scheduling, crafting logic, and integration with AI for unique item descriptions.
 */
class ProfessionManager : Listener {

    private val cachedProfessionsConfig = plugin.professions // Cache config for performance
    private val gameplayConfig = plugin.gameplayManager.config

    init {

        /* If the plugin is in vanilla trading mode, there's no need to change profession behavior (because vanilla ones are used). */
        if (!gameplayConfig.general.vanillaTrading) {

            plugin.server.pluginManager.registerEvents(this, plugin)
            plugin.server.scheduler.runTaskTimer(plugin, Runnable { produceProfessionItem() }, 0, gameplayConfig.profession.workIntervalTicks)

            // Scheduler for processing unique item queue to avoid overwhelming AI requests
            plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                uniqueItemProduceQueue.keys.randomOrNull()?.let { villager ->
                    uniqueItemProduceQueue[villager]?.let { uniqueItem ->
                        UniqueItemManager.generateUniqueItemDescription(villager, uniqueItem)
                        uniqueItemProduceQueue.remove(villager)
                    }
                }
            }, 0, gameplayConfig.profession.uniqueProcessingIntervalTicks)
        }

    }

    private val uniqueItemProduceQueue = mutableMapOf<Villager, ItemStack>()

    /**
     * Helper to safely consume items from standard Bukkit inventory.
     */
    private fun takeItem(inventory: Inventory, item: ItemStack, amount: Int) {
        val found = inventory.filterNotNull().find { it.isSimilar(item) } ?: return
        if (found.amount <= amount) {
            inventory.removeItem(found)
        } else {
            found.amount -= amount
        }
    }

    /**
     * Helper to check total count of an item in both the villager's personal inventory
     * and the settlement's shared village inventory.
     */
    private fun countItem(villager: Villager, material: Material): Int {
        var count = villager.inventory.filterNotNull().filter { it.type == material }.sumOf { it.amount }
        val villageInv = villager.settlement?.villageInventory
        if (villageInv != null) {
            count += villageInv.filterNotNull().filter { it.type == material }.sumOf { it.amount }
        }
        return count
    }

    /**
     * Helper to consume an item from combined inventories (personal inventory takes priority,
     * fallback to shared village inventory).
     */
    private fun consumeItem(villager: Villager, material: Material, amount: Int) {
        var remaining = amount

        // 1. Consume from personal inventory first
        val inv = villager.inventory
        val personalStacks = inv.filterNotNull().filter { it.type == material }
        for (stack in personalStacks) {
            if (remaining <= 0) break
            val toTake = minOf(stack.amount, remaining)
            if (stack.amount <= toTake) {
                inv.removeItem(stack)
            } else {
                stack.amount -= toTake
            }
            remaining -= toTake
        }

        // 2. Consume from settlement shared storage if still needed
        if (remaining > 0) {
            val villageInv = villager.settlement?.villageInventory
            if (villageInv != null) {
                val iterator = villageInv.iterator()
                while (iterator.hasNext()) {
                    val stack = iterator.next() ?: continue
                    if (stack.type == material) {
                        val toTake = minOf(stack.amount, remaining)
                        stack.amount -= toTake
                        remaining -= toTake
                        if (stack.amount <= 0) {
                            iterator.remove()
                        }
                        if (remaining <= 0) break
                    }
                }
            }
        }

        if (amount - remaining > 0 && villager.settlement != null) {
            SettlementManager.saveSettlements(villager.world)
        }
    }

    /**
     * Initiates item production for all eligible villagers across allowed worlds.
     * Handles cleric brewing separately and general crafting for other professions.
     */
    private fun produceProfessionItem() {
        val villagers = plugin.gameplayManager.allowedWorlds.flatMap { world ->
            world.entities.filterIsInstance<Villager>().filter { it.profession != Villager.Profession.NONE && it.pose != Pose.SLEEPING }
        }

        if (villagers.isEmpty()) return

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            villagers.forEach { villager ->
                processVillagerProduction(villager)
            }
        })
    }

    /**
     * Processes item production for a single villager based on their profession.
     */
    private fun processVillagerProduction(villager: Villager) {
        val professionKey = villager.profession.key.key.uppercase()
        if (!cachedProfessionsConfig.contains("villager-item-producing.profession.$professionKey")) return

        if (villager.profession == Villager.Profession.CLERIC) {
            handleClericBrewing(villager)
            return
        }

        handleGeneralCrafting(villager, professionKey)
    }

    /**
     * Handles potion brewing for clerics.
     */
    private fun handleClericBrewing(villager: Villager) {
        val maxPotions = gameplayConfig.profession.clericMaxPotionsBase * villager.villagerLevel + 1
        val inv = villager.inventory
        val settlement = villager.settlement
        val villageInv = settlement?.villageInventory

        // Count total potions across both inventories
        val personalPotions = inv.filterNotNull().count { it.type == Material.POTION }
        val villagePotions = villageInv?.filterNotNull()?.count { it.type == Material.POTION } ?: 0
        if (personalPotions + villagePotions >= maxPotions) return

        val ingredients = cachedProfessionsConfig.getStringList("villager-item-producing.profession.CLERIC.item-priority")
            .map { it.split("~")[0] }
            .mapNotNull { runCatching { Material.valueOf(it) }.getOrNull() }

        // Find an ingredient available in combined storage
        val foundIngredient = ingredients.firstOrNull { countItem(villager, it) > 0 } ?: return

        val amountToTake = gameplayConfig.profession.clericBrewingIngredientMin + Random.Default.nextInt(gameplayConfig.profession.clericBrewingIngredientMaxRandom)
        consumeItem(villager, foundIngredient, amountToTake)

        val potion = ItemStack(Material.POTION).apply {
            itemMeta = (itemMeta as PotionMeta).apply {
                basePotionType = PotionType.entries.random()
            }
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.server.pluginManager.callEvent(VillagerProduceItemEvent(villager, potion))
            villager.world.playSound(villager, Sound.ENTITY_VILLAGER_WORK_CLERIC, 1F, 1F)
        })
    }

    /**
     * Handles general item crafting for non-cleric professions.
     */
    private fun handleGeneralCrafting(villager: Villager, professionKey: String) {
        val itemsToProduce = cachedProfessionsConfig.getStringList("villager-item-producing.profession.$professionKey.item-produce").shuffled()
        if (itemsToProduce.isEmpty()) return

        for (itemString in itemsToProduce) {
            val material = resolveMaterial(itemString) ?: continue
            val recipes = Bukkit.getRecipesFor(ItemStack(material))
            if (recipes.isEmpty()) continue

            val vanillaRecipes = recipes.filter { it is Keyed && (it as Keyed).key.namespace == "minecraft" }
            if (vanillaRecipes.isEmpty()) {
                plugin.logger.warning("No craftable recipes found for $professionKey.")
                continue
            }

            val recipe = vanillaRecipes.random()
            val recipeIngredients = extractRecipeIngredients(recipe) ?: continue

            if (!canCraftRecipe(villager, recipeIngredients)) continue

            consumeIngredients(villager, recipeIngredients)
            val producedItem = recipe.result

            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.server.pluginManager.callEvent(VillagerProduceItemEvent(villager, producedItem))
            })
            return // Exit after successful craft
        }
    }

    /**
     * Resolves material from string, handling smart tags like '@'.
     */
    private fun resolveMaterial(itemString: String): Material? {
        return if (itemString.startsWith('@')) {
            Material.entries.filter { it.isItem && it.toString().contains(itemString.removePrefix("@")) }.randomOrNull()
        } else {
            try {
                Material.valueOf(itemString)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    /**
     * Extracts ingredients from a recipe.
     */
    private fun extractRecipeIngredients(recipe: Recipe): List<Material>? {
        val ingredients = mutableListOf<Material>()
        when (recipe) {
            is ShapedRecipe -> recipe.choiceMap.values.filterIsInstance<RecipeChoice.MaterialChoice>().forEach { ingredients.add(it.itemStack.type) }
            is ShapelessRecipe -> recipe.choiceList.filterIsInstance<RecipeChoice.MaterialChoice>().forEach { ingredients.add(it.itemStack.type) }
            is FurnaceRecipe -> try {
                ingredients.add((recipe.inputChoice as RecipeChoice.MaterialChoice).itemStack.type)
            } catch (e: ClassCastException) {
                return null
            }
            else -> return null
        }
        return ingredients
    }

    /**
     * Checks if the villager has enough ingredients to craft the recipe using combined storage.
     */
    private fun canCraftRecipe(villager: Villager, ingredients: List<Material>): Boolean {
        ingredients.groupBy { it }.forEach { (material, list) ->
            if (countItem(villager, material) < list.size) return false
        }
        return true
    }

    /**
     * Consumes ingredients from both inventories.
     */
    private fun consumeIngredients(villager: Villager, ingredients: List<Material>) {
        ingredients.groupBy { it }.forEach { (material, list) ->
            consumeItem(villager, material, list.size)
        }
    }

    @EventHandler
    private fun onVillagerProduceItem(event: VillagerProduceItemEvent) {
        val villager = event.villager
        val item = event.producedItem
        val professionLevel = villager.villagerLevel

        // Check for unique item generation
        if (cachedProfessionsConfig.getStringList("villager-item-producing.mastery-affected-items").contains(item.type.toString())) {
            val uniqueChance = professionLevel * cachedProfessionsConfig.getInt("villager-item-producing.unique-item-chance")
            if (Random.nextInt(100) <= uniqueChance) {
                val uniqueItem = UniqueItemManager.createUniqueItem(villager, item)
                uniqueItemProduceQueue[villager] = uniqueItem
                return
            }
        }

        // TODO: Apply random enchantment on books for librarians with low chance

        val settlement = villager.settlement
        if (settlement != null) {
            val virtualInv = settlement.villageInventory
            val maxStack = item.type.maxStackSize
            var remaining = item.amount

            for (stored in virtualInv) {
                if (stored.isSimilar(item)) {
                    val space = maxStack - stored.amount
                    if (space > 0) {
                        val toAdd = minOf(space, remaining)
                        stored.amount += toAdd
                        remaining -= toAdd
                        if (remaining <= 0) break
                    }
                }
            }
            while (remaining > 0) {
                val copy = item.clone()
                val toAdd = minOf(maxStack, remaining)
                copy.amount = toAdd
                virtualInv.add(copy)
                remaining -= toAdd
            }
            SettlementManager.saveSettlements(villager.world)
        } else {
            villager.inventory.addItem(item)
        }
    }

    companion object {
        val SWORDS = listOf("COPPER_SWORD", "IRON_SWORD", "DIAMOND_SWORD", "NETHERITE_SWORD")
        val PICKAXES = listOf("COPPER_PICKAXE", "IRON_PICKAXE", "DIAMOND_PICKAXE", "NETHERITE_PICKAXE")
        val AXES = listOf("COPPER_AXE", "IRON_AXE", "DIAMOND_AXE", "NETHERITE_AXE")
        val HELMETS = listOf("LEATHER_HELMET", "COPPER_HELMET", "IRON_HELMET", "DIAMOND_HELMET", "NETHERITE_HELMET")
        val CHESTPLATES = listOf("LEATHER_CHESTPLATE", "COPPER_CHESTPLATE", "IRON_CHESTPLATE", "DIAMOND_CHESTPLATE", "NETHERITE_CHESTPLATE")
        val LEGGINGS = listOf("LEATHER_LEGGINGS", "COPPER_LEGGINGS", "IRON_LEGGINGS", "DIAMOND_LEGGINGS", "NETHERITE_LEGGINGS")
        val BOOTS = listOf("LEATHER_BOOTS", "COPPER_BOOTS", "IRON_BOOTS", "DIAMOND_BOOTS", "NETHERITE_BOOTS")
        val DIAMOND_ARMOR = HELMETS + CHESTPLATES + LEGGINGS + BOOTS.filter { it.contains("DIAMOND") }
        val NETHERITE_ARMOR = HELMETS + CHESTPLATES + LEGGINGS + BOOTS.filter { it.contains("NETHERITE") }
    }

}
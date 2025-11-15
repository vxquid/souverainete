package vx.ignis.gameplay.profession

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.*
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.event.VillagerProduceItemEvent
import vx.ignis.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.ignis.persistent.LivingEntityExtend.subInventory
import vx.ignis.persistent.LivingEntityExtend.takeItemFromQuillInventory
import kotlin.random.Random

/**
 * Manages villager professions, including item production, unique item generation, and related events.
 * This class handles scheduling, crafting logic, and integration with AI for unique item descriptions.
 */
class ProfessionManager : Listener {

    private val cachedProfessionsConfig = plugin.professions // Cache config for performance
    private val gameplayConfig = plugin.gameplayManager.config

    init {
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

    private val uniqueItemProduceQueue = mutableMapOf<Villager, ItemStack>()

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
        if (villager.subInventory.filterNotNull().count { it.type == Material.POTION } >= maxPotions) return

        val ingredients = cachedProfessionsConfig.getStringList("villager-item-producing.profession.CLERIC.item-priority").map { it.split("~")[0] }
        villager.subInventory.filterNotNull().find { ingredients.contains(it.type.toString()) }?.let { brewingIngredient ->
            villager.takeItemFromQuillInventory(brewingIngredient, gameplayConfig.profession.clericBrewingIngredientMin + Random.Default.nextInt(gameplayConfig.profession.clericBrewingIngredientMaxRandom))

            val potion = ItemStack(Material.POTION).apply {
                itemMeta = (itemMeta as PotionMeta).apply {
                    basePotionType = PotionType.entries.random()
                }
            }

            plugin.logger.info("Brewing a potion. Villager: ${villager.customName}, potion is ${potion.itemMeta?.let { (it as PotionMeta).basePotionType }}.")
            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.server.pluginManager.callEvent(VillagerProduceItemEvent(villager, potion))
                villager.world.playSound(villager, Sound.ENTITY_VILLAGER_WORK_CLERIC, 1F, 1F)
            })
        }
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

            val recipe = recipes.random()
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
     * Checks if the villager has enough ingredients to craft the recipe.
     */
    private fun canCraftRecipe(villager: Villager, ingredients: List<Material>): Boolean {
        ingredients.groupBy { it }.forEach { (material, list) ->
            if (!villager.subInventory.contains(material, list.size)) return false
        }
        return true
    }

    /**
     * Consumes ingredients from the villager's inventory.
     */
    private fun consumeIngredients(villager: Villager, ingredients: List<Material>) {
        ingredients.forEach { material ->
            villager.subInventory.filterNotNull().find { it.type == material }?.let {
                villager.takeItemFromQuillInventory(it, 1)
            }
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

        villager.addItemToQuillInventory(item)
    }

    companion object {
        val SWORDS = listOf(Material.COPPER_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD)
        val PICKAXES = listOf(Material.COPPER_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE)
        val AXES = listOf(Material.COPPER_AXE, Material.IRON_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE)
        val HELMETS = listOf(Material.LEATHER_HELMET, Material.COPPER_HELMET, Material.IRON_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET)
        val CHESTPLATES = listOf(Material.LEATHER_CHESTPLATE, Material.COPPER_CHESTPLATE, Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE)
        val LEGGINGS = listOf(Material.LEATHER_LEGGINGS, Material.COPPER_LEGGINGS, Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS)
        val BOOTS = listOf(Material.LEATHER_BOOTS, Material.COPPER_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS)
        val DIAMOND_ARMOR = HELMETS + CHESTPLATES + LEGGINGS + BOOTS.filter { it.name.contains("DIAMOND") }
        val NETHERITE_ARMOR = HELMETS + CHESTPLATES + LEGGINGS + BOOTS.filter { it.name.contains("NETHERITE") }
    }

}
package vx.ignis.gameplay

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.inventory.*
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionType
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.event.VillagerProduceItemEvent
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import vx.ignis.gameplay.personality.PersonalityManager.Companion.gender
import vx.ignis.gameplay.personality.PersonalityManager.Companion.getPersonality
import vx.ignis.gameplay.quest.QuestManager.Companion.replaceMap
import vx.ignis.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.ignis.persistent.LivingEntityExtend.settlement
import vx.ignis.persistent.LivingEntityExtend.subInventory
import vx.ignis.persistent.LivingEntityExtend.takeItemFromQuillInventory
import vx.ignis.persistent.VillagerExtend.professionLevelName
import vx.ignis.util.HexColorLib.color
import java.util.*
import kotlin.random.Random

/**
 * Manages villager professions, including item production, unique item generation, and related events.
 * This class handles scheduling, crafting logic, and integration with AI for unique item descriptions.
 */
class ProfessionManager : Listener {

    private val workIntervalTicks = 240L // (Default was 2400L) Configurable work interval for production cycles
    private val uniqueItemProduceQueue = mutableMapOf<Villager, ItemStack>()
    private val cachedProfessionsConfig = plugin.professions // Cache config for performance

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { produceProfessionItem() }, 0, workIntervalTicks)

        // Scheduler for processing unique item queue to avoid overwhelming AI requests
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            uniqueItemProduceQueue.keys.randomOrNull()?.let { villager ->
                uniqueItemProduceQueue[villager]?.let { uniqueItem ->
                    generateUniqueItemDescription(villager, uniqueItem)
                    uniqueItemProduceQueue.remove(villager)
                }
            }
        }, 0, 400)
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
        val maxPotions = 1 * villager.villagerLevel + 1
        if (villager.subInventory.filterNotNull().count { it.type == Material.POTION } >= maxPotions) return

        val ingredients = cachedProfessionsConfig.getStringList("villager-item-producing.profession.CLERIC.item-priority").map { it.split("~")[0] }
        villager.subInventory.filterNotNull().find { ingredients.contains(it.type.toString()) }?.let { brewingIngredient ->
            villager.takeItemFromQuillInventory(brewingIngredient, 1 + Random.nextInt(5))

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
                val uniqueItem = createUniqueItem(villager, item)
                uniqueItemProduceQueue[villager] = uniqueItem
                return
            }
        }

        // TODO: Apply random enchantment on books for librarians with low chance

        villager.addItemToQuillInventory(item)
    }

    /**
     * Generates a unique item description using AI provider.
     */
    private fun generateUniqueItemDescription(villager: Villager, item: ItemStack) {
        val generator = UniqueItemDescriptionGenerator(villager, item)
        plugin.providerManager.client.sendPromptWithSchema(generator.prompt, UniqueItemDescription::class)?.let { data ->
            plugin.logger.info(data.toString())
            plugin.server.scheduler.runTask(plugin, Runnable {
                finalizeUniqueItem(villager, item, data)
            })
        } ?: plugin.logger.warning("Failed to generate unique item description for ${villager.customName}")
    }

    data class UniqueItemDescription(val itemDescription: String, val itemNames: List<String>)

    /**
     * Generator for unique item prompts.
     */
    private class UniqueItemDescriptionGenerator(villager: Villager, item: ItemStack) {
        private val uniqueItemInfo = "Generate a unique description for the item based on the provided details."

        private val uniqueItemPrompt = "Answer only in JSON format, without unnecessary text, make sure it will be JSON parseable. Generate a unique item description using the following JSON scheme: " +
                "`itemDescription` — a detailed and immersive description of the item (one to three sentences), " +
                "`itemNames` — string array, five short but creative item names, must differ from each other. " +
                "The writing style must be strictly tailored in the following order: global setting, race (race description), character definition, current biome, profession (profession level), gender. Start with a neutral description — it'll be easier for you to navigate that way. Don't shorten the descriptions — we don't want scraps of phrases, right? Select the most important words (like names or attributes) with bold Markdown. Select interesting parts with italic Markdown. All content must be written in a narrative style to enhance immersion and believability. " +
                "The following is the information about the NPC: name is {npcName}, current biome is {currentBiome}, NPC personality definition is [{npcPersonality}], race is {npcRace} and race description: [{raceDescription}], profession is {npcProfession}, npc profession mastery level is {npcProfessionLevel}, npc gender is {npcGender}. {uniqueItemInfo} " +
                "Item details: item type is {itemType}, extra item attributes are {extraItemAttributes}, item rarity is {itemRarity}, settlement name is {settlementName}, settlement level is {settlementLevel}, setting is {setting}, naming style is {namingStyle}."

        private val placeholders = mapOf(
            "npcPersonality" to villager.getPersonality().toString(),
            "npcName" to (villager.customName ?: "unknown"),
            "npcGender" to villager.gender.toString(),
            "npcRace" to villager.race.name,
            "raceDescription" to villager.race.description,
            "currentBiome" to villager.location.block.biome.key.toString(),
            "npcProfession" to villager.profession.toString(),
            "npcProfessionLevel" to villager.professionLevelName,
            "itemType" to item.type.toString(),
            "extraItemAttributes" to item.getUniqueItemAttributes(),
            "itemRarity" to (if (item.isUniqueItem()) item.getUniqueItemRarity().toString().lowercase() else UniqueItemRarity.COMMON.toString().lowercase()),
            "settlementName" to (villager.settlement?.data?.settlementName ?: "no settlement"),
            "settlementLevel" to villager.settlement?.size().toString(),
            "setting" to plugin.providerManager.config.setting,
            "namingStyle" to plugin.providerManager.config.namingStyle,
            "uniqueItemInfo" to uniqueItemInfo
        )

        val prompt = uniqueItemPrompt.replaceMap(placeholders)
    }

    /**
     * Finalizes the unique item by applying name, lore, and adding to inventory.
     */
    private fun finalizeUniqueItem(villager: Villager, item: ItemStack, data: UniqueItemDescription) {
        val meta = item.itemMeta ?: return
        val rarity = item.getUniqueItemRarity()

        meta.setItemName((rarity.color + data.itemNames.random()).color())

        val lore = buildLore(data.itemDescription)
        meta.lore = lore
        item.itemMeta = meta

        villager.addItemToQuillInventory(item)
    }

    /**
     * Builds lore lines from description, wrapping at 60 characters.
     */
    private fun buildLore(description: String): MutableList<String> {
        val words = description.split(" ")
        val lore = mutableListOf<String>()
        var line = "§7"

        words.forEach { word ->
            if (line.length + word.length + 1 > 60) {
                lore.add(line.trim())
                line = "§7"
            }
            line += "$word "
        }

        if (line != "§7") {
            lore.add(line.trim())
        }
        return lore
    }

    /**
     * Creates a unique item with random attributes and rarity.
     */
    private fun createUniqueItem(villager: Villager, itemStack: ItemStack): ItemStack {
        var rolls = 0
        do {
            rolls++
        } while (Random.nextInt(100) <= 50 / rolls + villager.villagerLevel)

        val rarity = when (rolls) {
            1 -> UniqueItemRarity.COMMON
            2 -> UniqueItemRarity.UNCOMMON
            3 -> UniqueItemRarity.RARE
            4 -> UniqueItemRarity.EPIC
            5 -> UniqueItemRarity.LEGENDARY
            6 -> UniqueItemRarity.MYTHICAL
            else -> UniqueItemRarity.DIVINE
        }

        val attributeNames = this.getAllowedAttributes(itemStack.type)
        val slot = getEquipmentSlot(itemStack.type)
        val meta = itemStack.itemMeta ?: return itemStack

        val attributes = Registry.ATTRIBUTE.filter { attributeNames.contains(it.key.key.uppercase()) }
        attributes.forEach { meta.removeAttributeModifier(it) }

        var attackSpeed = getBaseAttackSpeed(itemStack.type)
        var attackDamage = getBaseAttackDamage(itemStack.type)
        var armor = getBaseArmor(itemStack.type)
        var armorToughness = getBaseArmorToughness(itemStack.type)
        var blockBreakSpeed = 1.0
        var blockInteractionRange = 0.0
        var entityInteractionRange = 0.0
        var maxHealth = 0.0
        var scale = 0.0

        val addedAttributes = mutableListOf<String>()
        plugin.logger.info { "[DEBUG] Allowed attributes for ${itemStack.type}: $attributes" }
        repeat(rolls) {
            val attribute = attributes.random()
            val attrName = attribute.key.key.replace("generic.", "").replace("player.", "").replace("_", " ").lowercase()
            addedAttributes.add(attrName)

            when (attribute) {
                Attribute.ATTACK_SPEED -> attackSpeed += 0.3
                Attribute.ATTACK_DAMAGE -> attackDamage += 1.5
                Attribute.BLOCK_BREAK_SPEED -> blockBreakSpeed += 0.4
                Attribute.BLOCK_INTERACTION_RANGE -> blockInteractionRange += 0.5
                Attribute.MAX_HEALTH -> maxHealth += 2.0
                Attribute.ARMOR -> armor += 1.0
                Attribute.ARMOR_TOUGHNESS -> armorToughness += 1.0
                Attribute.SCALE -> {
                    scale += 0.1
                    blockInteractionRange += 0.5
                    entityInteractionRange += 0.5
                }
                else -> {}
            }
        }

        addAttributeModifier(meta, Attribute.ATTACK_SPEED, attackSpeed, slot, if (attackSpeed != getBaseAttackSpeed(itemStack.type)) AttributeModifier.Operation.ADD_NUMBER else null)
        addAttributeModifier(meta, Attribute.ATTACK_DAMAGE, attackDamage, slot)
        addAttributeModifier(meta, Attribute.BLOCK_BREAK_SPEED, blockBreakSpeed, slot, if (blockBreakSpeed > 1.0) AttributeModifier.Operation.ADD_NUMBER else null)
        addAttributeModifier(meta, Attribute.BLOCK_INTERACTION_RANGE, blockInteractionRange, slot, if (blockInteractionRange > 0.0) AttributeModifier.Operation.ADD_NUMBER else null)
        addAttributeModifier(meta, Attribute.ENTITY_INTERACTION_RANGE, entityInteractionRange, slot, if (entityInteractionRange > 0.0) AttributeModifier.Operation.ADD_NUMBER else null)
        addAttributeModifier(meta, Attribute.MAX_HEALTH, maxHealth, slot, if (maxHealth > 0.0) AttributeModifier.Operation.ADD_NUMBER else null)
        addAttributeModifier(meta, Attribute.ARMOR, armor, slot)
        addAttributeModifier(meta, Attribute.ARMOR_TOUGHNESS, armorToughness, slot, if (armorToughness > 0.0) AttributeModifier.Operation.ADD_NUMBER else null)
        addAttributeModifier(meta, Attribute.SCALE, scale, slot, if (scale > 0.0) AttributeModifier.Operation.ADD_NUMBER else null)

        meta.persistentDataContainer.set(attributeKey, PersistentDataType.STRING, addedAttributes.joinToString(", "))
        meta.persistentDataContainer.set(rarityKey, PersistentDataType.STRING, rarity.toString())

        if (meta is ArmorMeta) {
            randomTrimPattern(villager)?.let { pattern ->
                meta.trim = ArmorTrim(randomTrimMaterial(), pattern)
            }
        }

        itemStack.itemMeta = meta
        return itemStack
    }

    /**
     * Adds an attribute modifier if the operation is not null.
     */
    private fun addAttributeModifier(meta: ItemMeta, attribute: Attribute, amount: Double, slot: EquipmentSlotGroup, operation: AttributeModifier.Operation? = AttributeModifier.Operation.ADD_NUMBER) {
        if (operation != null && amount != 0.0) {
            meta.addAttributeModifier(
                attribute,
                AttributeModifier(
                    NamespacedKey(plugin, UUID.randomUUID().toString()),
                    amount,
                    operation,
                    slot
                )
            )
        }
    }

    private fun getAllowedAttributes(type: Material): List<String> {
        return when (type) {
            in SWORDS -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.swords")
            in PICKAXES -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.pickaxes")
            in AXES -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.axes")
            in HELMETS -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.helmets")
            in CHESTPLATES -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.chestplates")
            in LEGGINGS -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.leggings")
            in BOOTS -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.boots")
            else -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.fishing-rod")
        }
    }

    private fun getEquipmentSlot(type: Material): EquipmentSlotGroup {
        return when (type) {
            in HELMETS -> EquipmentSlotGroup.HEAD
            in CHESTPLATES -> EquipmentSlotGroup.CHEST
            in LEGGINGS -> EquipmentSlotGroup.LEGS
            in BOOTS -> EquipmentSlotGroup.FEET
            else -> EquipmentSlotGroup.MAINHAND
        }
    }

    private fun getBaseAttackSpeed(type: Material): Double {
        return -4.0 + when (type) {
            in SWORDS -> 1.6
            in PICKAXES -> 1.2
            Material.IRON_AXE -> 0.9
            in listOf(Material.DIAMOND_AXE, Material.NETHERITE_AXE) -> 1.0
            else -> 4.0
        }
    }

    private fun getBaseAttackDamage(type: Material): Double {
        return when (type) {
            Material.COPPER_SWORD -> 5.0
            Material.IRON_SWORD -> 6.0
            Material.DIAMOND_SWORD -> 7.0
            Material.NETHERITE_SWORD -> 8.0
            Material.COPPER_PICKAXE -> 3.0
            Material.IRON_PICKAXE -> 4.0
            Material.DIAMOND_PICKAXE -> 5.0
            Material.NETHERITE_PICKAXE -> 6.0
            Material.COPPER_AXE -> 9.0
            Material.IRON_AXE -> 9.0
            Material.DIAMOND_AXE -> 9.0
            Material.NETHERITE_AXE -> 10.0
            else -> 0.0
        }
    }

    private fun getBaseArmor(type: Material): Double {
        return when (type) {
            Material.LEATHER_HELMET -> 1.0
            Material.LEATHER_CHESTPLATE -> 3.0
            Material.LEATHER_LEGGINGS -> 2.0
            Material.LEATHER_BOOTS -> 1.0
            Material.COPPER_HELMET -> 2.0
            Material.COPPER_CHESTPLATE -> 4.0
            Material.COPPER_LEGGINGS -> 3.0
            Material.COPPER_BOOTS -> 1.0
            Material.IRON_HELMET -> 2.0
            Material.IRON_CHESTPLATE -> 6.0
            Material.IRON_LEGGINGS -> 5.0
            Material.IRON_BOOTS -> 2.0
            Material.DIAMOND_HELMET -> 3.0
            Material.DIAMOND_CHESTPLATE -> 8.0
            Material.DIAMOND_LEGGINGS -> 6.0
            Material.DIAMOND_BOOTS -> 3.0
            Material.NETHERITE_HELMET -> 3.0
            Material.NETHERITE_CHESTPLATE -> 8.0
            Material.NETHERITE_LEGGINGS -> 6.0
            Material.NETHERITE_BOOTS -> 3.0
            else -> 0.0
        }
    }

    private fun getBaseArmorToughness(type: Material): Double {
        return when (type) {
            in DIAMOND_ARMOR -> 2.0
            in NETHERITE_ARMOR -> 3.0
            else -> 0.0
        }
    }

    private fun randomTrimMaterial(): TrimMaterial {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).toList().random()
    }

    private val trimTemplates = mapOf(
        Material.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.RAISER,
        Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.COAST,
        Material.RIB_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.RIB,
        Material.VEX_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.VEX,
        Material.EYE_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.EYE,
        Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.BOLT,
        Material.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.DUNE,
        Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.FLOW,
        Material.HOST_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.HOST,
        Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.SENTRY,
        Material.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.SHAPER,
        Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.SILENCE,
        Material.WILD_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.WILD,
        Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.WARD,
        Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.TIDE,
        Material.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.WAYFINDER,
        Material.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE to TrimPattern.SNOUT
    )

    private fun randomTrimPattern(villager: Villager): TrimPattern? {
        return if (cachedProfessionsConfig.getBoolean("villager-item-producing.forced-armor-trims")) {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).toList().random()
        } else {
            villager.subInventory.filterNotNull()
                .filter { it.type.toString().contains("TRIM_SMITHING_TEMPLATE") }
                .randomOrNull()?.let { trimTemplates[it.type] }
        }
    }

    companion object {
        private val attributeKey = NamespacedKey(plugin, "Attribute")
        private val rarityKey = NamespacedKey(plugin, "Rarity")

        private val SWORDS = listOf(Material.COPPER_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD)
        private val PICKAXES = listOf(Material.COPPER_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE)
        private val AXES = listOf(Material.COPPER_AXE, Material.IRON_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE)
        private val HELMETS = listOf(Material.LEATHER_HELMET, Material.COPPER_HELMET, Material.IRON_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET)
        private val CHESTPLATES = listOf(Material.LEATHER_CHESTPLATE, Material.COPPER_CHESTPLATE, Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE)
        private val LEGGINGS = listOf(Material.LEATHER_LEGGINGS, Material.COPPER_LEGGINGS, Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS)
        private val BOOTS = listOf(Material.LEATHER_BOOTS, Material.COPPER_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS)
        private val DIAMOND_ARMOR = HELMETS + CHESTPLATES + LEGGINGS + BOOTS.filter { it.name.contains("DIAMOND") }
        private val NETHERITE_ARMOR = HELMETS + CHESTPLATES + LEGGINGS + BOOTS.filter { it.name.contains("NETHERITE") }

        fun ItemStack.isUniqueItem(): Boolean {
            return itemMeta?.persistentDataContainer?.has(rarityKey) == true
        }

        fun ItemStack.getUniqueItemRarity(): UniqueItemRarity {
            return itemMeta?.persistentDataContainer?.get(rarityKey, PersistentDataType.STRING)?.let {
                UniqueItemRarity.valueOf(it)
            } ?: UniqueItemRarity.NONE
        }

        fun ItemStack.getUniqueItemAttributes(): String {
            return itemMeta?.persistentDataContainer?.get(attributeKey, PersistentDataType.STRING) ?: ""
        }
    }

    // TODO: Move to config
    enum class UniqueItemRarity(val color: String, val extraPrice: Int) {
        NONE("#ffffff", 0),
        COMMON("#a9a9a9", plugin.professions.getInt("villager-item-producing.extra-rarity-price.COMMON")),
        UNCOMMON("#00ff00", plugin.professions.getInt("villager-item-producing.extra-rarity-price.UNCOMMON")),
        RARE("#fae100", plugin.professions.getInt("villager-item-producing.extra-rarity-price.RARE")),
        EPIC("#00ffff", plugin.professions.getInt("villager-item-producing.extra-rarity-price.EPIC")),
        LEGENDARY("#fa8100", plugin.professions.getInt("villager-item-producing.extra-rarity-price.LEGENDARY")),
        MYTHICAL("#ff0000", plugin.professions.getInt("villager-item-producing.extra-rarity-price.MYTHICAL")),
        DIVINE("#ff50b4", plugin.professions.getInt("villager-item-producing.extra-rarity-price.DIVINE"))
    }

}
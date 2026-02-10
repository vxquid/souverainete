package vx.sv.gameplay.profession

import com.cryptomorin.xseries.XAttribute
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Villager
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimPattern
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.personality.PersonalityManager.Companion.gender
import vx.sv.gameplay.personality.PersonalityManager.Companion.getPersonality
import vx.sv.gameplay.quest.QuestManager.Companion.replaceMap
import vx.sv.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.sv.persistent.LivingEntityExtend.settlement
import vx.sv.persistent.LivingEntityExtend.subInventory
import vx.sv.persistent.VillagerExtend.professionLevelName
import vx.sv.util.HexColorLib.color
import java.util.*
import kotlin.random.Random

/**
 * Manages the generation, processing, and attribute modification of unique items.
 * Refactored using Builder pattern and component separation.
 */
class UniqueItemManager {

    companion object {
        private val cachedProfessionsConfig = plugin.professions
        private val gameplayConfig = plugin.gameplayManager.config

        // Keys for PersistentDataContainer
        val attributeKey = NamespacedKey(plugin, "Attribute")
        val rarityKey = NamespacedKey(plugin, "Rarity")

        /**
         * Entry point to create a unique item with stats and put it in villager's inventory (or handle it).
         * Returns the created ItemStack.
         */
        fun createUniqueItem(villager: Villager, itemStack: ItemStack): ItemStack {
            return UniqueItemBuilder(itemStack)
                .calculateRarity(villager)
                .resetAndApplyBaseStats()
                .rollAndApplyBonusAttributes()
                .applyCosmetics(villager)
                .build()
        }

        /**
         * Triggers AI generation for item name and lore.
         */
        fun generateUniqueItemDescription(villager: Villager, item: ItemStack) {
            AiDescriptionService.generate(villager, item)
        }

        // --- Extension Functions for External Access ---

        fun ItemStack.isUniqueItem(): Boolean {
            return itemMeta?.persistentDataContainer?.has(rarityKey) == true
        }

        fun ItemStack.getUniqueItemRarity(): UniqueItemRarity {
            return itemMeta?.persistentDataContainer?.get(rarityKey, PersistentDataType.STRING)?.let {
                runCatching { UniqueItemRarity.valueOf(it) }.getOrNull()
            } ?: UniqueItemRarity.NONE
        }

        fun ItemStack.getUniqueItemAttributes(): String {
            return itemMeta?.persistentDataContainer?.get(attributeKey, PersistentDataType.STRING) ?: ""
        }
    }

    /**
     * Builder class responsible for constructing the Unique Item step-by-step.
     */
    class UniqueItemBuilder(originalItem: ItemStack) {
        private val itemStack: ItemStack = originalItem.clone()
        private val meta: ItemMeta = itemStack.itemMeta ?: throw IllegalStateException("Item must have Meta")
        private val typeName: String = itemStack.type.toString()

        private var rarity: UniqueItemRarity = UniqueItemRarity.COMMON
        private var rolls: Int = 0

        // Mutable stats tracking
        private var attackSpeed: Double = BaseStatsProvider.getBaseAttackSpeed(typeName)
        private var attackDamage: Double = BaseStatsProvider.getBaseAttackDamage(typeName)
        private var armor: Double = BaseStatsProvider.getBaseArmor(typeName)
        private var armorToughness: Double = BaseStatsProvider.getBaseArmorToughness(typeName)
        private var blockBreakSpeed: Double = 1.0
        private var blockInteractionRange: Double = 0.0
        private var entityInteractionRange: Double = 0.0
        private var maxHealth: Double = 0.0
        private var scale: Double = 0.0

        private val addedAttributeNames = mutableListOf<String>()

        /**
         * Step 1: Calculate Rarity based on Villager stats and RNG.
         */
        fun calculateRarity(villager: Villager): UniqueItemBuilder {
            val result = RarityCalculator.calculate(villager)
            this.rarity = result.first
            this.rolls = result.second

            // Tag rarity immediately
            meta.persistentDataContainer.set(rarityKey, PersistentDataType.STRING, rarity.toString())
            return this
        }

        /**
         * Step 2: Clear existing modifiers and prepare base stats.
         */
        fun resetAndApplyBaseStats(): UniqueItemBuilder {
            val allowedAttributes = AttributeConfigProvider.getAllowedAttributes(typeName)
            val attributesToCheck = XAttribute.getValues().filter { allowedAttributes.contains(it.name().uppercase()) }

            // Clear existing
            attributesToCheck.forEach { xAttr ->
                xAttr.get()?.let { meta.removeAttributeModifier(it) }
            }
            return this
        }

        /**
         * Step 3: Roll RNG for bonus attributes and accumulate stats.
         */
        fun rollAndApplyBonusAttributes(): UniqueItemBuilder {
            val allowedAttributes = AttributeConfigProvider.getAllowedAttributes(typeName)
            val availableXAttributes = XAttribute.getValues().filter { allowedAttributes.contains(it.name().uppercase()) }

            repeat(rolls) {
                val attribute = availableXAttributes.randomOrNull() ?: return@repeat

                // Store pretty name for Lore/AI
                val prettyName = attribute.get()!!.key.key
                    .replace("generic.", "")
                    .replace("player.", "")
                    .replace("_", " ")
                    .lowercase()
                addedAttributeNames.add(prettyName)

                // Accumulate bonuses
                when (attribute) {
                    XAttribute.ATTACK_SPEED -> attackSpeed += 0.3
                    XAttribute.ATTACK_DAMAGE -> attackDamage += 1.5
                    XAttribute.BLOCK_BREAK_SPEED -> blockBreakSpeed += 0.4
                    XAttribute.BLOCK_INTERACTION_RANGE -> blockInteractionRange += 0.5
                    XAttribute.MAX_HEALTH -> maxHealth += 2.0
                    XAttribute.ARMOR -> armor += 1.0
                    XAttribute.ARMOR_TOUGHNESS -> armorToughness += 1.0
                    XAttribute.SCALE -> {
                        scale += 0.1
                        blockInteractionRange += 0.5
                        entityInteractionRange += 0.5
                    }
                    else -> {}
                }
            }
            return this
        }

        /**
         * Step 4: Apply visual changes (Armor Trims).
         */
        fun applyCosmetics(villager: Villager): UniqueItemBuilder {
            if (meta is ArmorMeta) {
                TrimApplier.applyRandomTrim(meta, villager)
            }
            return this
        }

        /**
         * Finalize: Write attributes to ItemMeta and return ItemStack.
         */
        fun build(): ItemStack {
            val slot = EquipmentSlotProvider.getSlot(typeName)

            // Helper to add modifier if value changed/valid
            fun apply(xAttr: XAttribute, value: Double, baseValue: Double = 0.0, op: AttributeModifier.Operation = AttributeModifier.Operation.ADD_NUMBER) {
                val attr = xAttr.get() ?: return
                // Add if value is distinct from base OR (if ADD_NUMBER and > 0)
                // Logic strictly follows original: checks base mismatch or positive value depending on attribute
                val shouldAdd = if (xAttr == XAttribute.ATTACK_SPEED) value != baseValue else value > 0.0

                // Attack Damage and Armor are special cases in original code: they are always added if they exist in base stats logic
                val isBaseStat = (xAttr == XAttribute.ATTACK_DAMAGE || xAttr == XAttribute.ARMOR) && value > 0.0

                if (shouldAdd || isBaseStat) {
                    val finalOp = if(xAttr == XAttribute.ATTACK_SPEED && value != baseValue) AttributeModifier.Operation.ADD_NUMBER else op

                    meta.addAttributeModifier(
                        attr,
                        AttributeModifier(
                            NamespacedKey(plugin, UUID.randomUUID().toString()),
                            value,
                            finalOp,
                            slot
                        )
                    )
                }
            }

            // Apply all accumulated stats
            apply(XAttribute.ATTACK_SPEED, attackSpeed, BaseStatsProvider.getBaseAttackSpeed(typeName))
            apply(XAttribute.ATTACK_DAMAGE, attackDamage)
            apply(XAttribute.BLOCK_BREAK_SPEED, blockBreakSpeed, 1.0)
            apply(XAttribute.BLOCK_INTERACTION_RANGE, blockInteractionRange)
            apply(XAttribute.ENTITY_INTERACTION_RANGE, entityInteractionRange)
            apply(XAttribute.MAX_HEALTH, maxHealth)
            apply(XAttribute.ARMOR, armor)
            apply(XAttribute.ARMOR_TOUGHNESS, armorToughness)
            apply(XAttribute.SCALE, scale)

            // Save attribute names for AI prompt
            meta.persistentDataContainer.set(attributeKey, PersistentDataType.STRING, addedAttributeNames.joinToString(", "))

            itemStack.itemMeta = meta
            return itemStack
        }
    }

    // --- Internal Logic Modules ---

    private object RarityCalculator {
        fun calculate(villager: Villager): Pair<UniqueItemRarity, Int> {
            var rolls = 0
            val baseDivisor = gameplayConfig.uniqueItem.rollsBaseChanceDivisor

            do {
                rolls++
            } while (Random.nextInt(100) <= baseDivisor / rolls + villager.villagerLevel)

            val rarity = when (rolls) {
                1 -> UniqueItemRarity.COMMON
                2 -> UniqueItemRarity.UNCOMMON
                3 -> UniqueItemRarity.RARE
                4 -> UniqueItemRarity.EPIC
                5 -> UniqueItemRarity.LEGENDARY
                6 -> UniqueItemRarity.MYTHICAL
                else -> UniqueItemRarity.DIVINE
            }
            return rarity to rolls
        }
    }

    private object BaseStatsProvider {
        fun getBaseAttackSpeed(type: String): Double {
            return -4.0 + when (type) {
                in ProfessionManager.SWORDS -> 1.6
                in ProfessionManager.PICKAXES -> 1.2
                "IRON_AXE" -> 0.9
                in listOf("DIAMOND_AXE", "NETHERITE_AXE") -> 1.0
                else -> 4.0
            }
        }

        fun getBaseAttackDamage(type: String): Double {
            return when (type) {
                "COPPER_SWORD" -> 5.0
                "IRON_SWORD" -> 6.0
                "DIAMOND_SWORD" -> 7.0
                "NETHERITE_SWORD" -> 8.0
                "COPPER_PICKAXE" -> 3.0
                "IRON_PICKAXE" -> 4.0
                "DIAMOND_PICKAXE" -> 5.0
                "NETHERITE_PICKAXE" -> 6.0
                "COPPER_AXE", "IRON_AXE", "DIAMOND_AXE" -> 9.0
                "NETHERITE_AXE" -> 10.0
                else -> 0.0
            }
        }

        fun getBaseArmor(type: String): Double {
            return when (type) {
                "LEATHER_HELMET", "LEATHER_BOOTS", "COPPER_BOOTS" -> 1.0
                "LEATHER_CHESTPLATE", "COPPER_LEGGINGS", "NETHERITE_HELMET", "NETHERITE_BOOTS", "DIAMOND_HELMET", "DIAMOND_BOOTS" -> 3.0
                "LEATHER_LEGGINGS", "COPPER_HELMET", "IRON_HELMET", "IRON_BOOTS" -> 2.0
                "COPPER_CHESTPLATE" -> 4.0
                "IRON_CHESTPLATE", "DIAMOND_LEGGINGS", "NETHERITE_LEGGINGS" -> 6.0
                "IRON_LEGGINGS" -> 5.0
                "DIAMOND_CHESTPLATE", "NETHERITE_CHESTPLATE" -> 8.0
                else -> 0.0
            }
        }

        fun getBaseArmorToughness(type: String): Double {
            return when (type) {
                in ProfessionManager.DIAMOND_ARMOR -> 2.0
                in ProfessionManager.NETHERITE_ARMOR -> 3.0
                else -> 0.0
            }
        }
    }

    private object AttributeConfigProvider {
        fun getAllowedAttributes(type: String): List<String> {
            val path = "villager-item-producing.allowed-attributes"
            return when (type) {
                in ProfessionManager.SWORDS -> cachedProfessionsConfig.getStringList("$path.swords")
                in ProfessionManager.PICKAXES -> cachedProfessionsConfig.getStringList("$path.pickaxes")
                in ProfessionManager.AXES -> cachedProfessionsConfig.getStringList("$path.axes")
                in ProfessionManager.HELMETS -> cachedProfessionsConfig.getStringList("$path.helmets")
                in ProfessionManager.CHESTPLATES -> cachedProfessionsConfig.getStringList("$path.chestplates")
                in ProfessionManager.LEGGINGS -> cachedProfessionsConfig.getStringList("$path.leggings")
                in ProfessionManager.BOOTS -> cachedProfessionsConfig.getStringList("$path.boots")
                else -> cachedProfessionsConfig.getStringList("$path.fishing-rod")
            }
        }
    }

    private object EquipmentSlotProvider {
        fun getSlot(type: String): EquipmentSlotGroup {
            return when (type) {
                in ProfessionManager.HELMETS -> EquipmentSlotGroup.HEAD
                in ProfessionManager.CHESTPLATES -> EquipmentSlotGroup.CHEST
                in ProfessionManager.LEGGINGS -> EquipmentSlotGroup.LEGS
                in ProfessionManager.BOOTS -> EquipmentSlotGroup.FEET
                else -> EquipmentSlotGroup.MAINHAND
            }
        }
    }

    private object TrimApplier {
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

        fun applyRandomTrim(meta: ArmorMeta, villager: Villager) {
            val pattern = getPattern(villager) ?: return
            val material = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).toList().random()
            meta.trim = ArmorTrim(material, pattern)
        }

        private fun getPattern(villager: Villager): TrimPattern? {
            return if (cachedProfessionsConfig.getBoolean("villager-item-producing.forced-armor-trims")) {
                RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).toList().random()
            } else {
                villager.subInventory.filterNotNull()
                    .filter { it.type.toString().contains("TRIM_SMITHING_TEMPLATE") }
                    .randomOrNull()?.let { trimTemplates[it.type] }
            }
        }
    }

    // --- AI Description Logic ---
    private object AiDescriptionService {
        data class UniqueItemDescription(val itemDescription: String, val itemNames: List<String>)

        fun generate(villager: Villager, item: ItemStack) {
            val prompt = buildPrompt(villager, item)

            plugin.providerManager.client.sendPromptWithSchema(prompt, UniqueItemDescription::class)?.let { data ->
                plugin.server.scheduler.runTask(plugin, Runnable {
                    applyDescription(villager, item, data)
                })
            } ?: plugin.logger.warning("Failed to generate unique item description for ${villager.customName}")
        }

        private fun applyDescription(villager: Villager, item: ItemStack, data: UniqueItemDescription) {
            val meta = item.itemMeta ?: return
            val rarity = item.getUniqueItemRarity()

            // Set Name
            meta.setItemName((rarity.color + data.itemNames.random()).color())

            // Set Lore
            meta.lore = formatLore(data.itemDescription)
            item.itemMeta = meta

            // Give to villager
            villager.addItemToQuillInventory(item)
        }

        private fun formatLore(description: String): MutableList<String> {
            val maxLength = gameplayConfig.uniqueItem.loreLineMaxLength
            val words = description.split(" ")
            val lore = mutableListOf<String>()
            var line = "§7§o"

            words.forEach { word ->
                if (line.length + word.length + 1 > maxLength) {
                    lore.add(line.trim())
                    line = "§7§o"
                }
                line += "$word "
            }
            if (line != "§7§o") lore.add(line.trim())
            return lore
        }

        private fun buildPrompt(villager: Villager, item: ItemStack): String {
            val uniqueItemPrompt = """
                # ROLE
                You are a world-class RPG writer and myth-builder. Your task is to write a "believable" and "immersive" description for a unique item crafted by an NPC.

                # FORBIDDEN
                - DO NOT list stats or technical data (e.g., "minecraft:desert", "female", "level 2").
                - DO NOT use the NPC's race description as a copy-paste.
                - DO NOT write "This is a sword" or other obvious statements.
                - Avoid meta-commentary.

                # CONTEXT
                - Setting: {setting} ({namingStyle} style)
                - Creator: {npcName}, a {npcGender} {npcRace} {npcProfession} ({npcProfessionLevel})
                - Creator's Personality: {npcPersonality}
                - Location: {currentBiome} biome, settlement of {settlementName}
                - Item Type: {itemType} ({itemRarity} quality)
                - Enchanted Properties: {extraItemAttributes}

                # GUIDELINES FOR DESCRIPTION
                Write 1 to 2 atmospheric sentences. Focus on the "feel", "history", or "craftsmanship" of the item. 
                - Use the creator's race and personality to influence the tone (e.g., an Orc's work is brutal/heavy, an Elf's is elegant/ethereal).
                - Mention how the {currentBiome} environment influenced the materials used.
                - If the item has "{extraItemAttributes}", subtly hint at these powers in the lore without naming the stats.

                # GUIDELINES FOR NAMES
                Provide 6 creative names:
                - 1-2: Grounded and descriptive.
                - 3-4: Poetic or legendary.
                - 5: A single "cool" punchy word.
                - 6: A name deeply tied to the {npcRace} heritage or {settlementName} history.

                # JSON FORMAT
                Output ONLY valid JSON.
                {
                  "itemDescription": "Immersive lore text here...",
                  "itemNames": ["Name 1", "Name 2", "Name 3", "Name 4", "Name 5", "Name 6"]
                }
            """.trimIndent()

            val placeholders = mapOf(
                "npcPersonality" to villager.getPersonality().toString(),
                "npcName" to (villager.customName ?: "an unknown artisan"),
                "npcGender" to villager.gender.toString().lowercase(),
                "npcRace" to villager.race.name,
                "raceDescription" to villager.race.description,
                "currentBiome" to villager.location.block.biome.key.key.replace("_", " "),
                "npcProfession" to villager.profession.toString().lowercase(),
                "npcProfessionLevel" to villager.professionLevelName,
                "itemType" to item.type.toString().replace("_", " ").lowercase(),
                "extraItemAttributes" to (item.getUniqueItemAttributes().ifEmpty { "exceptional balance" }),
                "itemRarity" to (if (item.isUniqueItem()) item.getUniqueItemRarity().toString() else "COMMON"),
                "settlementName" to (villager.settlement?.data?.settlementName ?: "the wild lands"),
                "settlementLevel" to (villager.settlement?.size()?.toString() ?: "1"),
                "setting" to plugin.providerManager.config.setting,
                "namingStyle" to plugin.providerManager.config.namingStyle
            )
            return uniqueItemPrompt.replaceMap(placeholders)
        }
    }

    // --- Enums ---

    enum class UniqueItemRarity(val extraPrice: Int) {
        NONE(0),
        COMMON(plugin.professions.getInt("villager-item-producing.extra-rarity-price.COMMON")),
        UNCOMMON(plugin.professions.getInt("villager-item-producing.extra-rarity-price.UNCOMMON")),
        RARE(plugin.professions.getInt("villager-item-producing.extra-rarity-price.RARE")),
        EPIC(plugin.professions.getInt("villager-item-producing.extra-rarity-price.EPIC")),
        LEGENDARY(plugin.professions.getInt("villager-item-producing.extra-rarity-price.LEGENDARY")),
        MYTHICAL(plugin.professions.getInt("villager-item-producing.extra-rarity-price.MYTHICAL")),
        DIVINE(plugin.professions.getInt("villager-item-producing.extra-rarity-price.DIVINE"));

        val color: String
            get() = when (this) {
                COMMON -> plugin.gameplayManager.config.uniqueItem.rarityColor.common
                UNCOMMON -> plugin.gameplayManager.config.uniqueItem.rarityColor.uncommon
                RARE -> plugin.gameplayManager.config.uniqueItem.rarityColor.rare
                EPIC -> plugin.gameplayManager.config.uniqueItem.rarityColor.epic
                LEGENDARY -> plugin.gameplayManager.config.uniqueItem.rarityColor.legendary
                MYTHICAL -> plugin.gameplayManager.config.uniqueItem.rarityColor.mythical
                DIVINE -> plugin.gameplayManager.config.uniqueItem.rarityColor.divine
                NONE -> "#ffffff"
            }
    }
}
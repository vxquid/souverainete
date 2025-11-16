package vx.ignis.gameplay.profession

import com.cryptomorin.xseries.XAttribute
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Villager
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import vx.ignis.gameplay.personality.PersonalityManager.Companion.gender
import vx.ignis.gameplay.personality.PersonalityManager.Companion.getPersonality
import vx.ignis.gameplay.quest.QuestManager.Companion.replaceMap
import vx.ignis.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.ignis.persistent.LivingEntityExtend.settlement
import vx.ignis.persistent.LivingEntityExtend.subInventory
import vx.ignis.persistent.VillagerExtend.professionLevelName
import vx.ignis.util.HexColorLib.color
import java.util.*
import kotlin.random.Random

/**
 * Manages the generation and processing of unique items for villagers.
 */
class UniqueItemManager {

    companion object {
        private val cachedProfessionsConfig = plugin.professions
        private val gameplayConfig = plugin.gameplayManager.config
        private val attributeKey = NamespacedKey(plugin, "Attribute")
        private val rarityKey = NamespacedKey(plugin, "Rarity")

        /**
         * Generates a unique item description using AI provider.
         */
        fun generateUniqueItemDescription(villager: Villager, item: ItemStack) {
            val generator = UniqueItemDescriptionGenerator(villager, item)
            plugin.providerManager.client.sendPromptWithSchema(generator.prompt, UniqueItemDescription::class)?.let { data ->
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
                    "`itemNames` — string array, six short but creative item names, must differ from each other; six name must be cool single word. " +
                    "The writing style must be strictly tailored in the following order: global setting, race (race description), character definition, current biome, profession (profession level), gender. " +
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
         * Builds lore lines from description, wrapping at configured length.
         */
        private fun buildLore(description: String): MutableList<String> {
            val words = description.split(" ")
            val lore = mutableListOf<String>()
            var line = "§7"

            words.forEach { word ->
                if (line.length + word.length + 1 > gameplayConfig.uniqueItem.loreLineMaxLength) {
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
        fun createUniqueItem(villager: Villager, itemStack: ItemStack): ItemStack {
            var rolls = 0
            do {
                rolls++
            } while (Random.nextInt(100) <= gameplayConfig.uniqueItem.rollsBaseChanceDivisor / rolls + villager.villagerLevel)

            val rarity = when (rolls) {
                1 -> UniqueItemRarity.COMMON
                2 -> UniqueItemRarity.UNCOMMON
                3 -> UniqueItemRarity.RARE
                4 -> UniqueItemRarity.EPIC
                5 -> UniqueItemRarity.LEGENDARY
                6 -> UniqueItemRarity.MYTHICAL
                else -> UniqueItemRarity.DIVINE
            }

            val attributeNames = getAllowedAttributes(itemStack.type.toString())
            val slot = getEquipmentSlot(itemStack.type.toString())
            val meta = itemStack.itemMeta ?: return itemStack

            val attributes = XAttribute.getValues().filter { attributeNames.contains(it.name().uppercase()) }
            attributes.forEach { it.get()?.let { p0 -> meta.removeAttributeModifier(p0) } }

            var attackSpeed = getBaseAttackSpeed(itemStack.type.toString())
            var attackDamage = getBaseAttackDamage(itemStack.type.toString())
            var armor = getBaseArmor(itemStack.type.toString())
            var armorToughness = getBaseArmorToughness(itemStack.type.toString())
            var blockBreakSpeed = 1.0
            var blockInteractionRange = 0.0
            var entityInteractionRange = 0.0
            var maxHealth = 0.0
            var scale = 0.0

            val addedAttributes = mutableListOf<String>()
            repeat(rolls) {
                val attribute = attributes.random()
                val attrName = attribute.get()!!.key.key.replace("generic.", "").replace("player.", "").replace("_", " ").lowercase()
                addedAttributes.add(attrName)

                when (attribute) {
                    XAttribute.ATTACK_SPEED -> attackSpeed += gameplayConfig.uniqueItem.attackSpeedIncrement
                    XAttribute.ATTACK_DAMAGE -> attackDamage += gameplayConfig.uniqueItem.attackDamageIncrement
                    XAttribute.BLOCK_BREAK_SPEED -> blockBreakSpeed += gameplayConfig.uniqueItem.blockBreakSpeedIncrement
                    XAttribute.BLOCK_INTERACTION_RANGE -> blockInteractionRange += gameplayConfig.uniqueItem.blockInteractionRangeIncrement
                    XAttribute.MAX_HEALTH -> maxHealth += gameplayConfig.uniqueItem.maxHealthIncrement
                    XAttribute.ARMOR -> armor += gameplayConfig.uniqueItem.armorIncrement
                    XAttribute.ARMOR_TOUGHNESS -> armorToughness += gameplayConfig.uniqueItem.armorToughnessIncrement
                    XAttribute.SCALE -> {
                        scale += gameplayConfig.uniqueItem.scaleIncrement
                        blockInteractionRange += gameplayConfig.uniqueItem.blockInteractionRangeIncrement
                        entityInteractionRange += gameplayConfig.uniqueItem.entityInteractionRangeIncrement
                    }
                    else -> {}
                }
            }

            XAttribute.ATTACK_SPEED.get()?.let { addAttributeModifier(meta, it, attackSpeed, slot, if (attackSpeed != getBaseAttackSpeed(itemStack.type.toString())) AttributeModifier.Operation.ADD_NUMBER else null) }
            XAttribute.ATTACK_DAMAGE.get()?.let { addAttributeModifier(meta, it, attackDamage, slot) }
            XAttribute.BLOCK_BREAK_SPEED.get()?.let { addAttributeModifier(meta, it, blockBreakSpeed, slot, if (blockBreakSpeed > 1.0) AttributeModifier.Operation.ADD_NUMBER else null) }
            XAttribute.BLOCK_INTERACTION_RANGE.get()?.let { addAttributeModifier(meta, it, blockInteractionRange, slot, if (blockInteractionRange > 0.0) AttributeModifier.Operation.ADD_NUMBER else null) }
            XAttribute.ENTITY_INTERACTION_RANGE.get()?.let { addAttributeModifier(meta, it, entityInteractionRange, slot, if (entityInteractionRange > 0.0) AttributeModifier.Operation.ADD_NUMBER else null) }
            XAttribute.MAX_HEALTH.get()?.let { addAttributeModifier(meta, it, maxHealth, slot, if (maxHealth > 0.0) AttributeModifier.Operation.ADD_NUMBER else null) }
            XAttribute.ARMOR.get()?.let { addAttributeModifier(meta, it, armor, slot) }
            XAttribute.ARMOR_TOUGHNESS.get()?.let { addAttributeModifier(meta, it, armorToughness, slot, if (armorToughness > 0.0) AttributeModifier.Operation.ADD_NUMBER else null) }
            XAttribute.SCALE.get()?.let { addAttributeModifier(meta, it, scale, slot, if (scale > 0.0) AttributeModifier.Operation.ADD_NUMBER else null) }

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

        private fun getAllowedAttributes(type: String): List<String> {
            return when (type) {
                in ProfessionManager.SWORDS -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.swords")
                in ProfessionManager.PICKAXES -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.pickaxes")
                in ProfessionManager.AXES -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.axes")
                in ProfessionManager.HELMETS -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.helmets")
                in ProfessionManager.CHESTPLATES -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.chestplates")
                in ProfessionManager.LEGGINGS -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.leggings")
                in ProfessionManager.BOOTS -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.boots")
                else -> cachedProfessionsConfig.getStringList("villager-item-producing.allowed-attributes.fishing-rod")
            }
        }

        private fun getEquipmentSlot(type: String): EquipmentSlotGroup {
            return when (type) {
                in ProfessionManager.HELMETS -> EquipmentSlotGroup.HEAD
                in ProfessionManager.CHESTPLATES -> EquipmentSlotGroup.CHEST
                in ProfessionManager.LEGGINGS -> EquipmentSlotGroup.LEGS
                in ProfessionManager.BOOTS -> EquipmentSlotGroup.FEET
                else -> EquipmentSlotGroup.MAINHAND
            }
        }

        private fun getBaseAttackSpeed(type: String): Double {
            return -4.0 + when (type) {
                in ProfessionManager.SWORDS -> gameplayConfig.uniqueItem.baseAttackSpeedSwords
                in ProfessionManager.PICKAXES -> gameplayConfig.uniqueItem.baseAttackSpeedPickaxes
                "IRON_AXE" -> gameplayConfig.uniqueItem.baseAttackSpeedIronAxe
                in listOf("DIAMOND_AXE", "NETHERITE_AXE") -> gameplayConfig.uniqueItem.baseAttackSpeedDiamondNetheriteAxe
                else -> gameplayConfig.uniqueItem.baseAttackSpeedDefault
            }
        }

        private fun getBaseAttackDamage(type: String): Double {
            return when (type) {
                "COPPER_SWORD" -> gameplayConfig.uniqueItem.baseAttackDamageCopperSword
                "IRON_SWORD" -> gameplayConfig.uniqueItem.baseAttackDamageIronSword
                "DIAMOND_SWORD" -> gameplayConfig.uniqueItem.baseAttackDamageDiamondSword
                "NETHERITE_SWORD" -> gameplayConfig.uniqueItem.baseAttackDamageNetheriteSword
                "COPPER_PICKAXE" -> gameplayConfig.uniqueItem.baseAttackDamageCopperPickaxe
                "IRON_PICKAXE" -> gameplayConfig.uniqueItem.baseAttackDamageIronPickaxe
                "DIAMOND_PICKAXE" -> gameplayConfig.uniqueItem.baseAttackDamageDiamondPickaxe
                "NETHERITE_PICKAXE" -> gameplayConfig.uniqueItem.baseAttackDamageNetheritePickaxe
                "COPPER_AXE" -> gameplayConfig.uniqueItem.baseAttackDamageCopperIronDiamondAxe
                "IRON_AXE" -> gameplayConfig.uniqueItem.baseAttackDamageCopperIronDiamondAxe
                "DIAMOND_AXE" -> gameplayConfig.uniqueItem.baseAttackDamageCopperIronDiamondAxe
                "NETHERITE_AXE" -> gameplayConfig.uniqueItem.baseAttackDamageNetheriteAxe
                else -> 0.0
            }
        }

        private fun getBaseArmor(type: String): Double {
            return when (type) {
                "LEATHER_HELMET" -> gameplayConfig.uniqueItem.baseArmorLeatherHelmet
                "LEATHER_CHESTPLATE" -> gameplayConfig.uniqueItem.baseArmorLeatherChestplate
                "LEATHER_LEGGINGS" -> gameplayConfig.uniqueItem.baseArmorLeatherLeggings
                "LEATHER_BOOTS" -> gameplayConfig.uniqueItem.baseArmorLeatherBoots
                "COPPER_HELMET" -> gameplayConfig.uniqueItem.baseArmorCopperHelmet
                "COPPER_CHESTPLATE" -> gameplayConfig.uniqueItem.baseArmorCopperChestplate
                "COPPER_LEGGINGS" -> gameplayConfig.uniqueItem.baseArmorCopperLeggings
                "COPPER_BOOTS" -> gameplayConfig.uniqueItem.baseArmorCopperBoots
                "IRON_HELMET" -> gameplayConfig.uniqueItem.baseArmorIronHelmet
                "IRON_CHESTPLATE" -> gameplayConfig.uniqueItem.baseArmorIronChestplate
                "IRON_LEGGINGS" -> gameplayConfig.uniqueItem.baseArmorIronLeggings
                "IRON_BOOTS" -> gameplayConfig.uniqueItem.baseArmorIronBoots
                "DIAMOND_HELMET" -> gameplayConfig.uniqueItem.baseArmorDiamondNetheriteHelmet
                "DIAMOND_CHESTPLATE" -> gameplayConfig.uniqueItem.baseArmorDiamondNetheriteChestplate
                "DIAMOND_LEGGINGS" -> gameplayConfig.uniqueItem.baseArmorDiamondNetheriteLeggings
                "DIAMOND_BOOTS" -> gameplayConfig.uniqueItem.baseArmorDiamondNetheriteBoots
                "NETHERITE_HELMET" -> gameplayConfig.uniqueItem.baseArmorDiamondNetheriteHelmet
                "NETHERITE_CHESTPLATE" -> gameplayConfig.uniqueItem.baseArmorDiamondNetheriteChestplate
                "NETHERITE_LEGGINGS" -> gameplayConfig.uniqueItem.baseArmorDiamondNetheriteLeggings
                "NETHERITE_BOOTS" -> gameplayConfig.uniqueItem.baseArmorDiamondNetheriteBoots
                else -> 0.0
            }
        }

        private fun getBaseArmorToughness(type: String): Double {
            return when (type) {
                in ProfessionManager.DIAMOND_ARMOR -> gameplayConfig.uniqueItem.baseArmorToughnessDiamond
                in ProfessionManager.NETHERITE_ARMOR -> gameplayConfig.uniqueItem.baseArmorToughnessNetherite
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
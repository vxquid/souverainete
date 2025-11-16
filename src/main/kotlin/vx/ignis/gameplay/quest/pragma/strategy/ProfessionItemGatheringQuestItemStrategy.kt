package vx.ignis.gameplay.quest.pragma.strategy

import org.bukkit.Material
import org.bukkit.Registry
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.dictionary.CustomItem
import vx.ignis.gameplay.quest.pragma.QuestItemStrategy
import vx.ignis.gameplay.trade.ScoreCalculator.calculateScore
import java.util.logging.Level
import kotlin.random.Random

/**
 * Strategy for generating custom quest items based on a villager's profession.
 * Items are selected from profession-specific priorities, ensuring they fit within
 * the villager's wealth level and a minimum price threshold.
 */
class ProfessionItemGatheringQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): CustomItem {
        require(questGiver is Villager) {
            "Quest giver must be a Villager!"
        }

        require(questGiver.profession != Villager.Profession.NONE) {
            "Villager must have a profession!"
        }

        val profession = questGiver.profession
        val level = questGiver.villagerLevel

        val minWealth = InventoryWealth.getProfessionLimit(level).minWealth
        val maxWealth = InventoryWealth.getProfessionLimit(level).maxWealth
        val prioritizedItems = professionItems[profession]
            ?: throw IllegalStateException(
                "No items configured for profession $profession; check professions.yml!"
            )

        val selectedItem = selectRandomItemInRange(prioritizedItems, minWealth, maxWealth)
            ?: selectFallbackItem(prioritizedItems, minWealth, maxWealth, profession)

        return CustomItem(selectedItem.material.name, selectedItem.price.toLong(), ItemStack(selectedItem.material, selectedItem.amount))
    }

    private fun selectRandomItemInRange(
        prioritizedItems: Map<Material, AmountRange>,
        minPrice: Int,
        maxWealth: Int,
        maxAttempts: Int = prioritizedItems.size * 2
    ): ItemSelection? {
        repeat(maxAttempts) {
            val (material, range) = prioritizedItems.entries.random()
            val amount = range.randomAmount()
            val adjustedAmount = amount.adjustToEvenIfPlural()
            val itemStack = ItemStack(material, adjustedAmount)
            val price = itemStack.calculateScore()

            if (price in minPrice..maxWealth) {
                return ItemSelection(material, adjustedAmount, price)
            }
        }
        return null
    }

    private fun selectFallbackItem(
        prioritizedItems: Map<Material, AmountRange>,
        minPrice: Int,
        maxWealth: Int,
        profession: Villager.Profession
    ): ItemSelection {
        val fallbackEntry = prioritizedItems.entries
            .minByOrNull { (material, range) -> ItemStack(material, range.min).calculateScore() }
            ?: throw IllegalStateException("No fallback items available for profession $profession.")

        val (material, range) = fallbackEntry
        var amount = range.min
        var price = ItemStack(material, amount).calculateScore()

        // Scale up to meet minimum price
        while (price < minPrice && amount < range.max) {
            amount++
            price = ItemStack(material, amount).calculateScore()
        }

        // Scale down if exceeding max wealth
        while (price > maxWealth && amount > range.min) {
            amount--
            price = ItemStack(material, amount).calculateScore()
        }

        logger.warning(
            "Fallback used for profession $profession: $material x$amount (price: $price, may not fit ideal range)"
        )

        return ItemSelection(material, amount, price)
    }

    private data class ItemSelection(
        val material: Material,
        val amount: Int,
        val price: Int
    )

    private data class AmountRange(val min: Int, val max: Int) {
        init {
            require(min > 0) { "Minimum amount must be positive" }
            require(min <= max) { "Minimum amount must not exceed maximum" }
        }

        fun randomAmount(): Int = if (min == max) min else Random.nextInt(min, max + 1)
    }

    private fun Int.adjustToEvenIfPlural(): Int = if (this > 1 && this % 2 != 0) this + 1 else this

    /**
     * Represents wealth tiers for villager inventories, mapped to profession levels.
     */
    private enum class InventoryWealth(val minWealth: Int, val maxWealth: Int) {
        BEGGAR(0, 3999),
        POOR(4000, 7999),
        AVERAGE(8000, 11_999),
        WELL_OFF(12_000, 14_999),
        RICH(15_000, 29_999),
        ELITE(30_000, Int.MAX_VALUE);

        companion object {
            fun getWealthLevel(wealth: Int): InventoryWealth = entries
                .firstOrNull { wealth in it.minWealth..it.maxWealth }
                ?: BEGGAR

            fun getProfessionLimit(level: Int): InventoryWealth = when (level) {
                0 -> BEGGAR
                1 -> POOR
                2 -> AVERAGE
                3 -> WELL_OFF
                4 -> RICH
                5 -> ELITE
                else -> AVERAGE
            }
        }
    }

    private companion object {
        private val logger = plugin.logger

        /**
         * Loads profession-specific item priorities from the professions config.
         * Each profession maps to a collection of materials with configurable amount ranges.
         */
        val professionItems: Map<Villager.Profession, Map<Material, AmountRange>> = buildMap {
            Registry.VILLAGER_PROFESSION
                .filter { it != Villager.Profession.NONE }
                .forEach { profession ->
                    val key = profession.key.key.uppercase()
                    val configPath = "villager-item-producing.profession.$key.item-priority"
                    val configLines = plugin.professions.getStringList(configPath)

                    if (configLines.isEmpty()) {
                        logger.warning("No items configured for profession $key at path $configPath")
                        return@forEach
                    }

                    val items = mutableMapOf<Material, AmountRange>()
                    configLines.forEach { line ->
                        parseConfigLine(line)?.let { (material, range) ->
                            items[material] = range
                        }
                    }

                    if (items.isNotEmpty()) {
                        put(profession, items)
                    } else {
                        logger.warning("No valid items loaded for profession $key")
                    }
                }
        }.also {
            if (it.isEmpty()) {
                logger.severe("Failed to load any profession items; check config.yml")
            }
        }

        private fun parseConfigLine(line: String): Pair<Material, AmountRange>? {
            val parts = line.split("~")
            if (parts.size != 2) {
                logger.log(Level.FINE, "Skipping invalid config line: $line (expected format: material~min-max)")
                return null
            }

            val (materialName, amountRange) = parts
            val rangeParts = amountRange.split("-")
            val min = rangeParts.getOrNull(0)?.toIntOrNull() ?: 1
            val max = rangeParts.getOrNull(1)?.toIntOrNull() ?: min
            val range = AmountRange(min.coerceAtLeast(1), max.coerceAtLeast(min))

            return if (materialName.startsWith('@')) {
                val searchTerm = materialName.drop(1)
                Material.entries.firstOrNull {
                    it.name.contains(
                        searchTerm,
                        ignoreCase = true
                    )
                } // Take first match for simplicity; extend if multiple needed
                    ?.let { it to range }
            } else {
                Material.matchMaterial(materialName.uppercase())?.let { it to range }
                    ?: run {
                        // logger.warning("Invalid material '$materialName' in config")
                        null
                    }
            }
        }

    }

}
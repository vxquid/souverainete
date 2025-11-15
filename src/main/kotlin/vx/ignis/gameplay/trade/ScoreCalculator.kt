package vx.ignis.gameplay.trade

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.profession.ProfessionManager.Companion.getUniqueItemRarity
import kotlin.random.Random

/**
 * Utility object for calculating trade scores/prices for item collections and individual items.
 * Excludes edibles and enchanted books from collections to prevent inflated scores from consumables.
 */
object ScoreCalculator {

    /**
     * Calculates the total score for a collection of items, excluding edibles and enchanted books.
     * @return Sum of individual item scores.
     */
    fun Collection<ItemStack>.calculateScore(): Int {
        return this
            .filterNot { item -> item.type.isEdible || item.type == Material.ENCHANTED_BOOK }
            .sumOf { it.calculateScore() }
    }

    /**
     * Calculates the score for an entire stack of items.
     * Formula: (base material score * amount) + potion effect bonus (if applicable) + rarity bonus.
     * @return Total score for the stack.
     */
    fun ItemStack.calculateScore(): Int {
        val baseScore = this.type.getBasicScore()
        val amountMultiplier = this.amount
        val potionBonus = calculatePotionBonus()
        val rarityBonus = this.getUniqueItemRarity().extraPrice
        return (baseScore * amountMultiplier) + potionBonus + rarityBonus
    }

    /**
     * Calculates bonus for potion effects, if the item is a potion.
     * Looks up config price for base potion type; defaults to a random value in (2400, 2800) if not found or zero.
     * @return Potion bonus score, or 0 if not a potion.
     */
    private fun ItemStack.calculatePotionBonus(): Int {
        val meta = itemMeta as? PotionMeta ?: return 0
        val potionType = meta.basePotionType ?: return 0 // Skip if no base type (e.g., custom potions)

        val configPrice = plugin.prices.getInt("effect-type.${potionType.name}")
        return if (configPrice > 0) {
            configPrice
        } else {
            2400 + Random.nextInt(1, 6) * 200 // Random bonus in range 2400-2800
        }
    }

    /**
     * Retrieves the base score for a material from the config.
     * Uses lowercase material name as the config key for consistency.
     * @param defaultPrice Fallback score if not found in config (default: 50).
     * @return Configured score or default.
     */
    fun Material.getBasicScore(defaultPrice: Int = 50): Int {
        if (this == Material.AIR) return 0

        val configKey = this.name
        val pricingConfig = plugin.prices

        return if (pricingConfig.contains(configKey)) {
            pricingConfig.getInt(configKey)
        } else {
            defaultPrice.also {
                plugin.logger.warning("Price for material $this (key: $configKey) not found in config. Using default: $defaultPrice")
            }
        }
    }

}
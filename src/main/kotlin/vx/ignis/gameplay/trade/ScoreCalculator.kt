package vx.ignis.gameplay.trade

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import vx.ignis.Ignis.Companion.plugin
import kotlin.random.Random

object ScoreCalculator {

    fun Collection<ItemStack>.calculateScore(): Int {
        return this.filterNot{ it.type.isEdible || it.type == Material.ENCHANTED_BOOK }.sumOf { it.calculateScore() }
    }

    /* Подсчитывание цены для всего стака. */
    fun ItemStack.calculateScore(): Int {
        return (this.type.getBasicScore() * this.amount + ((this.itemMeta as? PotionMeta)?.let {
            val type = if (PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_20_6)) it.basePotionType else it.basePotionData!!.type
            val price = plugin.prices.getInt("effect-type.$type")
            if (price != 0) price else 2400 + Random.nextInt(1, 6) * 200
        } ?: 0))
    }

    fun Material.getBasicScore(defaultPrice: Int = 50): Int {

        val pricingConfig = plugin.prices

        return if (pricingConfig.contains(this.name))
            pricingConfig.getInt(this.name)
        else defaultPrice.also {
            if (this != Material.AIR) plugin.logger.warning("Price for material $this not found.")
        }
    }

}
package vx.ignis.gameplay.quest.pragma.strategy

import com.cryptomorin.xseries.XMaterial
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.dictionary.CustomItem
import vx.ignis.gameplay.quest.pragma.QuestItemStrategy
import vx.ignis.gameplay.trade.ScoreCalculator.calculateScore
import vx.ignis.persistent.LivingEntityExtend.subInventory
import kotlin.random.Random

class TreasureHuntQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): CustomItem {
        val (material, range) = treasureItems.filter { !questGiver.subInventory.contains(it.first) }.random()
        val amount = (range.first + Random.nextInt(range.second)).apply { if (this != 1 && this % 2 != 0) this.inc() }
        val item = ItemStack(material, amount)
        return CustomItem(item.type.name, item.calculateScore().toLong(), item)
    }

    companion object {
        val treasureItems: MutableList<Triple<Material, Pair<Int, Int>, String>> =
            mutableListOf<Triple<Material, Pair<Int, Int>, String>>().apply {
                val data = plugin.prompts.getStringList("treasure-hunt-quest.allowed-items")
                for (line in data) {
                    val (materialName, amount, description) = line.split("~")
                    val (min, max) = amount.split("-")
                    this.add(Triple(XMaterial.valueOf(materialName).get() ?: continue, min.toInt() to max.toInt(), description))
                }
            }
    }

}
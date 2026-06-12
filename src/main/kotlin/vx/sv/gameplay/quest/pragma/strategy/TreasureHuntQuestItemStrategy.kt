package vx.sv.gameplay.quest.pragma.strategy

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.quest.QuestItemStack
import vx.sv.gameplay.quest.pragma.QuestItemStrategy
import vx.sv.gameplay.trade.ScoreCalculator.calculateScore
import kotlin.random.Random

class TreasureHuntQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): QuestItemStack {
        val inv = (questGiver as? Villager)?.inventory ?: Bukkit.createInventory(null, 9)
        val (material, range) = treasureItems.filter { !inv.contains(it.first) }.random()
        val amount = (range.first + Random.nextInt(range.second)).apply { if (this != 1 && this % 2 != 0) this.inc() }
        val item = ItemStack(material, amount)
        return QuestItemStack(item.type.name, item.calculateScore().toLong(), item)
    }

    companion object {
        val treasureItems: MutableList<Triple<Material, Pair<Int, Int>, String>> =
            mutableListOf<Triple<Material, Pair<Int, Int>, String>>().apply {
                val data = plugin.prompts.getStringList("treasure-hunt-quest.allowed-items")
                for (line in data) {
                    val (materialName, amount, description) = line.split("~")
                    val (min, max) = amount.split("-")
                    val mat = Material.matchMaterial(materialName) ?: continue
                    this.add(Triple(mat, min.toInt() to max.toInt(), description))
                }
            }
    }

}
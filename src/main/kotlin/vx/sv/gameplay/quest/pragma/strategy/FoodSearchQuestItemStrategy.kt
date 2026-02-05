package vx.sv.gameplay.quest.pragma.strategy

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.quest.QuestItemStack
import vx.sv.gameplay.quest.pragma.QuestItemStrategy
import vx.sv.gameplay.trade.ScoreCalculator.calculateScore
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class FoodSearchQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): QuestItemStack {
        val data = allowedFood.random()
        val minAmount = min(data.second.first, data.second.second)
        val maxAmount = max(data.second.first, data.second.second)
        val amount = if (minAmount == maxAmount) minAmount else minAmount + Random.nextInt(maxAmount - minAmount + 1)
        val food = ItemStack(data.first, amount)
        val item = QuestItemStack(food.type.toString(), food.calculateScore().toLong(), food)
        return item
    }

    companion object {
        val allowedFood = plugin.prompts.getStringList("food-quest.allowed-types").map {
            val parts = it.split("~")
            val material = Material.valueOf(parts[0])
            val amountRange: Pair<Int, Int> = if (parts.size > 1) {
                val rangeParts = parts[1].split("-")
                when (rangeParts.size) {
                    1 -> {
                        val num = rangeParts[0].toInt()
                        Pair(num, num)
                    }
                    2 -> {
                        val min = rangeParts[0].toInt()
                        val max = rangeParts[1].toInt()
                        Pair(min, max)
                    }
                    else -> Pair(1, 1)
                }
            } else {
                Pair(1, 1)
            }
            material to amountRange
        }
    }

}
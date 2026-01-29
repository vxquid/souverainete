package vx.sv.gameplay.quest.pragma.strategy

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import vx.sv.gameplay.dictionary.CustomItem
import vx.sv.gameplay.quest.pragma.QuestItemStrategy
import vx.sv.gameplay.trade.ScoreCalculator.calculateScore

class MusicDiscQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): CustomItem {
        val disc = ItemStack(discs.random(), 1)
        val item = CustomItem(disc.type.toString(), disc.calculateScore().toLong(), disc)
        return item
    }

    companion object {
        val discs = Material.entries.filter { material: Material -> material.isRecord }
    }

}
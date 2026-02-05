package vx.sv.gameplay.quest.pragma.strategy

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import vx.sv.gameplay.quest.QuestItemStack
import vx.sv.gameplay.quest.pragma.QuestItemStrategy
import vx.sv.gameplay.trade.ScoreCalculator.calculateScore

class MusicDiscQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): QuestItemStack {
        val disc = ItemStack(discs.random(), 1)
        val item = QuestItemStack(disc.type.toString(), disc.calculateScore().toLong(), disc)
        return item
    }

    companion object {
        val discs = Material.entries.filter { material: Material -> material.isRecord }
    }

}
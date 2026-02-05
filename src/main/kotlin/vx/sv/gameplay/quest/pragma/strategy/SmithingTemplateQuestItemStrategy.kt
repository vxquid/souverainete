package vx.sv.gameplay.quest.pragma.strategy

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import vx.sv.gameplay.quest.QuestItemStack
import vx.sv.gameplay.quest.pragma.QuestItemStrategy

class SmithingTemplateQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): QuestItemStack {
        val template = ItemStack(templates.random(), 1)
        val item = QuestItemStack(template.type.toString(), 5500, template)
        return item
    }

    companion object {
        val templates = Material.entries.filter { material: Material -> material.name.contains("SMITHING_TEMPLATE") }
    }

}
package vx.sv.gameplay.quest.pragma.strategy

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import vx.sv.gameplay.dictionary.CustomItem
import vx.sv.gameplay.quest.pragma.QuestItemStrategy

class SmithingTemplateQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): CustomItem {
        val template = ItemStack(templates.random(), 1)
        val item = CustomItem(template.type.toString(), 5500, template)
        return item
    }

    companion object {
        val templates = Material.entries.filter { material: Material -> material.name.contains("SMITHING_TEMPLATE") }
    }

}
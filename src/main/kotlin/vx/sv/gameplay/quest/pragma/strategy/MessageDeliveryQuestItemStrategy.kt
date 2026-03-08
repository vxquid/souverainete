package vx.sv.gameplay.quest.pragma.strategy

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import vx.sv.gameplay.quest.QuestItemStack
import vx.sv.gameplay.quest.pragma.QuestItemStrategy

class MessageDeliveryQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): QuestItemStack {
        // Предмет для доставки может быть документом, книгой, картой или даже ценным артефактом
        val materials = listOf(
            Material.PAPER,
            Material.BOOK,
            Material.WRITTEN_BOOK,
            Material.MAP,
            Material.KNOWLEDGE_BOOK,
            Material.GOLD_INGOT
        )
        val item = ItemStack(materials.random())

        return QuestItemStack("delivery_item", 100, item)
    }

}
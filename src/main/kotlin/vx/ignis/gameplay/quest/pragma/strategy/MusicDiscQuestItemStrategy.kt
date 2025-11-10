package vx.ignis.gameplay.quest.pragma.strategy

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import vx.ignis.gameplay.dictionary.CustomItem
import vx.ignis.gameplay.quest.pragma.QuestItemStrategy

/**
 * Simple quest for music disc search.
 */
class MusicDiscQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): CustomItem {
        val disc = ItemStack(discs.random(), 1)
        val item = CustomItem(disc.type.toString(), 3000, disc)
        return item
    }

    companion object {
        val discs = Material.entries.filter { material: Material -> material.isRecord }
    }

}
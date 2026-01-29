package vx.sv.gameplay.quest.pragma.strategy

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.dictionary.CustomItem
import vx.sv.gameplay.quest.pragma.QuestItemStrategy

class BoozeQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): CustomItem {
        val potion = ItemStack(Material.POTION)
        potion.itemMeta = (Bukkit.getItemFactory().getItemMeta(Material.POTION) as PotionMeta).apply { this.basePotionType = allowedPotionTypes.random() }
        return CustomItem(potion.type.toString(), potionScore, potion)
    }

    companion object {
        private val potionScore = plugin.prompts.getLong("booze-quest.reward-points")
        private val allowedPotionTypes = plugin.prompts.getStringList("booze-quest.allowed-potion-types").map {
            PotionType.valueOf(it)
        }
    }

}
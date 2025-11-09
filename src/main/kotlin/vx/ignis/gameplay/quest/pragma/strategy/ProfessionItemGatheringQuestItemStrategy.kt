package vx.ignis.gameplay.quest.pragma.strategy

import org.bukkit.entity.LivingEntity
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.dictionary.CustomItem
import vx.ignis.gameplay.quest.pragma.QuestItemStrategy

class ProfessionItemGatheringQuestItemStrategy : QuestItemStrategy() {

    // Крайне простой квест. Квестовые предметы хранятся в пуле, который в будущем можно будет настроить.
    override fun get(questGiver: LivingEntity): CustomItem {
        val fullKey = plugin.prompts.getStringList("item-gathering.allowed-types").random()
        val parts = fullKey.split("~")
        val key = parts[0]
        val rangeStr = parts.getOrNull(1)
        val quantity = if (rangeStr != null) {
            val rangeParts = rangeStr.split("-")
            val min = rangeParts[0].toInt()
            val max = if (rangeParts.size > 1) rangeParts[1].toInt() else min
            (min..max).random()
        } else {
            1
        }
        val item = dictionary.getItem(key)
        item.item.amount = quantity
        return item
    }

}

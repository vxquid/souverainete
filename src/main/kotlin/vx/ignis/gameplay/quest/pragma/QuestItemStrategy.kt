package vx.ignis.gameplay.quest.pragma

import org.bukkit.entity.LivingEntity
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.dictionary.CustomItem

abstract class QuestItemStrategy {
    val dictionary = plugin.gameplayManager.itemDictionary
    abstract fun get(questGiver: LivingEntity): CustomItem
}
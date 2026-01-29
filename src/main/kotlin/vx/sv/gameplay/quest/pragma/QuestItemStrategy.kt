package vx.sv.gameplay.quest.pragma

import org.bukkit.entity.LivingEntity
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.dictionary.CustomItem

abstract class QuestItemStrategy {
    val dictionary = plugin.gameplayManager.itemDictionary
    abstract fun get(questGiver: LivingEntity): CustomItem
}
package vx.sv.gameplay.quest.pragma

import org.bukkit.entity.LivingEntity
import vx.sv.gameplay.quest.QuestItemStack

abstract class QuestItemStrategy {
    abstract fun get(questGiver: LivingEntity): QuestItemStack
}
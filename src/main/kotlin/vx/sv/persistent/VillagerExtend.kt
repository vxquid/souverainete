package vx.sv.persistent

import org.bukkit.entity.Villager

object VillagerExtend {

    val Villager.professionLevelName get() = when (villagerLevel) { 1 -> "NOVICE"; 2 -> "APPRENTICE"; 3 -> "JOURNEYMAN"; 4 -> "EXPERT"; else -> "MASTER" }



}
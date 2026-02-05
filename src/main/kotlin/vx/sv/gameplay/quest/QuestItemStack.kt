package vx.sv.gameplay.quest

import org.bukkit.inventory.ItemStack

data class QuestItemStack(
    var key: String,
    var score: Long = 0,
    var item: ItemStack
)
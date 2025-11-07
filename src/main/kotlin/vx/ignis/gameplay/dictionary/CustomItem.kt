package vx.ignis.gameplay.dictionary

import org.bukkit.inventory.ItemStack

data class CustomItem(
    var key: String,
    var score: Long = 0,
    var item: ItemStack
)

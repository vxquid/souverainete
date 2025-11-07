package vx.ignis.gameplay.humanoid.entity

import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

interface Humanoid {

    fun consume(world: World, item: ItemStack, sound: Sound, duration: Int, location: Location, period: Long = 5L, onDone: () -> Unit)
    fun equip(slot: EquipmentSlot, item: ItemStack)

    var talkingPlayer: Player?

}
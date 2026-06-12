package vx.sv.nms

import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

interface EntityProvider {

    val plugin: JavaPlugin

    fun asHumanoid(entity: LivingEntity): Humanoid?
    fun replaceEntityTypes()

    interface Humanoid {

        fun consume(world: World, item: ItemStack, sound: Sound, duration: Int, location: Location, period: Long = 5L, onDone: () -> Unit)
        fun equip(slot: EquipmentSlot, item: ItemStack)
        fun attack(target: LivingEntity)
        fun attack(target: LivingEntity, maxStrikes: Int)

        var talkingPlayer: Player?
    }
}
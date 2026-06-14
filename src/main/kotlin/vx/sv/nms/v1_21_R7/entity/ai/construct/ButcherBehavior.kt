package vx.sv.nms.v1_21_R7.entity.ai.construct

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Sheep
import org.bukkit.inventory.ItemStack
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager

class ButcherBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ),
    200
) {
    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        return bukkitVillager.profession == org.bukkit.entity.Villager.Profession.BUTCHER && villager.settlement != null
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val center = settlement.data.center
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location

        // Собираем овец в радиусе 25 блоков от центра ратуши
        val villageSheep = bukkitWorld.getEntitiesByClass(Sheep::class.java).filter { sheep ->
            sheep.location.distanceSquared(center) <= 625.0
        }

        // Если отар превысил 12 голов — производим забой лишних
        if (villageSheep.size > 12) {
            val targetSheep = villageSheep.find { it.isAdult }

            if (targetSheep != null) {
                val distSq = npcLoc.distanceSquared(targetSheep.location)
                if (distSq <= 4.0) {
                    // Забиваем овцу
                    targetSheep.damage(20.0, villager.bukkitEntity)
                    bukkitWorld.playSound(targetSheep.location, Sound.ENTITY_SHEEP_DEATH, 1.0f, 1.0f)
                    
                    // Анимация удара топором
                    villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.IRON_AXE)))
                    villager.swing(InteractionHand.MAIN_HAND)
                } else {
                    // Направляемся к цели
                    val targetPos = BlockPos(targetSheep.location.blockX, targetSheep.location.blockY, targetSheep.location.blockZ)
                    villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                    villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
                }
                return
            }
        }

        villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
    }
}
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
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import kotlin.math.atan2
import kotlin.math.sqrt

class MinerBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ),
    200
) {
    private var mineBlockTicks = 0

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        return bukkitVillager.profession == Villager.Profession.TOOLSMITH && villager.settlement != null
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        return bukkitVillager.profession == Villager.Profession.TOOLSMITH && villager.settlement != null
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val center = settlement.data.center
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location

        // Находим стол кузнеца (рабочую станцию шахты)
        var tablePos: BlockPos? = null
        val r = 35
        for (cx in -r..r) {
            for (cz in -r..r) {
                for (cy in -12..12) {
                    val px = center.blockX + cx
                    val py = center.blockY + cy
                    val pz = center.blockZ + cz
                    if (bukkitWorld.getBlockAt(px, py, pz).type == Material.SMITHING_TABLE) {
                        tablePos = BlockPos(px, py, pz)
                        break
                    }
                }
                if (tablePos != null) break
            }
        }

        if (tablePos == null) {
            val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 3))
            return
        }

        val stonePos = BlockPos(tablePos.x, tablePos.y, tablePos.z + 1)
        val stoneBlock = bukkitWorld.getBlockAt(stonePos.x, stonePos.y, stonePos.z)

        // Восстанавливаем бесконечную жилу, если она сломана
        if (stoneBlock.type == Material.AIR) {
            stoneBlock.type = Material.STONE
        }

        val distSq = npcLoc.distanceSquared(stoneBlock.location.add(0.5, 0.0, 0.5))

        if (distSq <= 6.0) {
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(stonePos))

            val dX = stonePos.x + 0.5 - villager.x
            val dY = stonePos.y + 0.5 - villager.eyeY
            val dZ = stonePos.z + 0.5 - villager.z
            val distance = sqrt(dX * dX + dZ * dZ)
            villager.yRot = (Math.toDegrees(atan2(dZ, dX)) - 90.0).toFloat()
            villager.yHeadRot = villager.yRot
            villager.yBodyRot = villager.yRot
            villager.xRot = (-Math.toDegrees(atan2(dY, distance))).toFloat()

            villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.IRON_PICKAXE)))

            if (mineBlockTicks % 4 == 0) {
                villager.swing(InteractionHand.MAIN_HAND)
                bukkitWorld.playSound(stoneBlock.location, Sound.BLOCK_STONE_HIT, 0.8f, 1.0f)
                bukkitWorld.spawnParticle(Particle.BLOCK, stoneBlock.location.add(0.5, 0.5, 0.5), 6, stoneBlock.blockData)
            }

            mineBlockTicks++

            if (mineBlockTicks >= 60) {
                mineBlockTicks = 0

                stoneBlock.type = Material.AIR
                bukkitWorld.playSound(stoneBlock.location, Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f)
                bukkitWorld.spawnParticle(Particle.BLOCK, stoneBlock.location.add(0.5, 0.5, 0.5), 15, Material.STONE.createBlockData())

                // ИСПРАВЛЕНО: Значительно повышены шансы и количество выпадающих ресурсов (до 1-4 штук)
                val random = world.random
                val roll = random.nextInt(100)

                val dropMaterial = when (roll) {
                    in 0..4 -> Material.EMERALD            // 5% Изумруды
                    in 5..14 -> Material.RAW_GOLD          // 10% Сырое золото (новое)
                    in 15..34 -> Material.RAW_IRON         // 20% Сырое железо
                    in 35..59 -> Material.COAL             // 25% Уголь
                    in 60..69 -> Material.LAPIS_LAZULI     // 10% Лазурит (новое)
                    else -> Material.COBBLESTONE           // 30% Булыжник
                }

                val amount = when (dropMaterial) {
                    Material.COBBLESTONE, Material.COAL -> 2 + random.nextInt(3) // 2-4 штуки
                    else -> 1 + random.nextInt(3) // 1-3 штуки
                }

                bukkitWorld.dropItemNaturally(stoneBlock.location.add(0.5, 0.5, 0.5), ItemStack(dropMaterial, amount))
            }
        } else {
            val targetPos = BlockPos(stonePos.x, stonePos.y, stonePos.z)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
            villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))

            villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.IRON_PICKAXE)))
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}
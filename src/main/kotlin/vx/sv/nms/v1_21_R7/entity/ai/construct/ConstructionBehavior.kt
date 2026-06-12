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
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import org.bukkit.entity.Villager as BukkitVillager

class ConstructionBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT // Останавливает стройку, если рядом враги
    ),
    1200 // Максимальное время непрерывного выполнения тика
) {

    private fun takeItem(inventory: Inventory, material: Material, amount: Int) {
        val found = inventory.filterNotNull().find { it.type == material } ?: return
        if (found.amount <= amount) {
            inventory.removeItem(found)
        } else {
            found.amount -= amount
        }
    }

    private fun getGroundPos(world: ServerLevel, pos: BlockPos): BlockPos {
        var temp = pos
        while (temp.y > world.minY && world.getBlockState(temp.below()).isAir) {
            temp = temp.below()
        }
        return temp
    }

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        // 1. Не строим и не ломаем ночью
        if (!world.world.isDayTime) return false

        // 2. Проверяем кулдаун после предыдущего действия
        if (world.gameTime < villager.nextBuildAvailableTime) return false

        val job = villager.activeBuildJob ?: return false

        // 3. Ищем или резервируем следующий блок
        val assigned = villager.assignedBlock ?: job.claimNextBlock(villager) ?: return false
        villager.assignedBlock = assigned

        val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory
        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)
        if (currentBlock.type == Material.AIR && !bukkitInv.contains(assigned.material)) {
            job.unclaimBlock(assigned)
            villager.assignedBlock = null
            return false
        }
        return true
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val job = villager.activeBuildJob ?: return false
        val assigned = villager.assignedBlock ?: return false

        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)
        val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory
        val hasResources = currentBlock.type != Material.AIR || bukkitInv.contains(assigned.material)

        return world.world.isDayTime && hasResources && !job.isFinished()
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val assigned = villager.assignedBlock ?: return
        villager.previousMainHandItem = villager.mainHandItem.copy()

        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)
        val tool = if (currentBlock.type != Material.AIR) {
            ItemStack(Material.STONE_PICKAXE)
        } else {
            val itemToHold = if (assigned.blockData.material.isItem) assigned.blockData.material else assigned.material
            ItemStack(itemToHold)
        }

        villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(tool))

        val groundPos = getGroundPos(world, assigned.pos)
        villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(groundPos), speedModifier, 2))
        villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(assigned.pos))
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val assigned = villager.assignedBlock ?: return
        val job = villager.activeBuildJob ?: return

        val bukkitWorld = world.world
        val blockPos = assigned.pos
        val block = bukkitWorld.getBlockAt(blockPos.x, blockPos.y, blockPos.z)

        // Блокировка взгляда на цель
        villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(blockPos))

        val dX = blockPos.x + 0.5 - villager.x
        val dY = blockPos.y + 0.5 - villager.eyeY
        val dZ = blockPos.z + 0.5 - villager.z
        val distance = kotlin.math.sqrt(dX * dX + dZ * dZ)
        val targetYaw = (Math.toDegrees(kotlin.math.atan2(dZ, dX)) - 90.0).toFloat()
        val targetPitch = (-Math.toDegrees(kotlin.math.atan2(dY, distance))).toFloat()

        villager.yRot = targetYaw
        villager.yHeadRot = targetYaw
        villager.yBodyRot = targetYaw
        villager.xRot = targetPitch
        villager.lookControl.setLookAt(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)

        val npcPos = villager.blockPosition()
        val distSqr = npcPos.distSqr(blockPos)

        // Дистанция строительства больше не ограничивает работу ИИ
        val isWithinReach = true

        if (isWithinReach) {
            // Нам больше не нужны проверки на застревание, так как работа продолжается в любом случае
            villager.stuckTicks = 0
            villager.lastPosition = null

            // Останавливаем движение только в том случае, если подошли достаточно близко
            if (distSqr <= 9.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            }

            if (block.type != Material.AIR) {
                // === РЕЖИМ РАЗРУШЕНИЯ ПРЕПЯТСТВИЙ (Digging) ===
                val expectedTool = CraftItemStack.asNMSCopy(ItemStack(Material.IRON_PICKAXE))
                if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, expectedTool)) {
                    villager.setItemInHand(InteractionHand.MAIN_HAND, expectedTool)
                }

                if (villager.digTicks % 3 == 0) {
                    villager.swing(InteractionHand.MAIN_HAND)
                    bukkitWorld.playSound(block.location, block.blockData.soundGroup.hitSound, 0.8f, 1.0f)
                    bukkitWorld.spawnParticle(Particle.BLOCK, block.location.add(0.5, 0.5, 0.5), 8, block.blockData)
                }

                villager.digTicks++
                val breakDuration = if (block.type.hardness > 2.0) 20 else 10
                if (villager.digTicks >= breakDuration) {
                    block.breakNaturally()
                    villager.digTicks = 0
                    villager.nextBuildAvailableTime = world.gameTime + 2L

                    val itemToHold = if (assigned.blockData.material.isItem) assigned.blockData.material else assigned.material
                    villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(itemToHold)))
                }
            } else {
                // === РЕЖИМ УСТАНОВКИ БЛОКА (Placing) ===
                val material = assigned.material
                val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory

                if (!bukkitInv.contains(material)) {
                    job.unclaimBlock(assigned)
                    villager.assignedBlock = null
                    villager.digTicks = 0
                    villager.buildTicks = 0
                    doStop(world, villager, time)
                    return
                }

                val itemToHold = if (assigned.blockData.material.isItem) assigned.blockData.material else assigned.material
                val expectedBlock = CraftItemStack.asNMSCopy(ItemStack(itemToHold))
                if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, expectedBlock)) {
                    villager.setItemInHand(InteractionHand.MAIN_HAND, expectedBlock)
                }

                if (villager.buildTicks % 5 == 0) villager.swing(InteractionHand.MAIN_HAND)
                villager.buildTicks++

                if (villager.buildTicks >= 10) {
                    block.setBlockData(assigned.blockData, true)
                    bukkitWorld.playSound(block.location, assigned.blockData.soundGroup.placeSound, 1.0f, 1.0f)
                    takeItem(bukkitInv, material, 1)

                    job.completeBlock(assigned)
                    villager.assignedBlock = null
                    villager.digTicks = 0
                    villager.buildTicks = 0
                    villager.nextBuildAvailableTime = world.gameTime + 2L
                    doStop(world, villager, time)
                }
            }
        }

        // Если NPC находится далеко, он все равно будет пытаться подойти ближе для естественного вида
        if (distSqr > 9.0 && !villager.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            val groundPos = getGroundPos(world, blockPos)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(groundPos), speedModifier, 2))
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val assigned = villager.assignedBlock
        if (assigned != null) {
            villager.activeBuildJob?.unclaimBlock(assigned)
            villager.assignedBlock = null
        }
        villager.digTicks = 0
        villager.buildTicks = 0

        if (villager.previousMainHandItem != null) {
            villager.setItemInHand(InteractionHand.MAIN_HAND, villager.previousMainHandItem!!)
            villager.previousMainHandItem = null
        }
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}
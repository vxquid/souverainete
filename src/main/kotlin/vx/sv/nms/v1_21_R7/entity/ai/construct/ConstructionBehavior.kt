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
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import org.bukkit.entity.Villager as BukkitVillager

class ConstructionBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT
    ),
    1200
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

    private fun removeWholeTree(startBlock: Block) {
        val checked = mutableSetOf<Block>()
        val queue = ArrayDeque<Block>()
        queue.add(startBlock)

        var processedCount = 0
        while (queue.isNotEmpty() && processedCount++ < 250) {
            val current = queue.poll()
            if (!checked.add(current)) continue

            val type = current.type
            val isLog = type.name.contains("LOG") || type.name.contains("WOOD")
            val isLeaves = type.name.contains("LEAVES")

            if (isLog || isLeaves) {
                if (isLog) {
                    current.breakNaturally()
                } else {
                    current.type = Material.AIR
                }

                for (face in BlockFace.entries) {
                    if (face == BlockFace.SELF) continue
                    val neighbor = current.getRelative(face)
                    if (neighbor.location.distanceSquared(startBlock.location) <= 225.0) {
                        queue.add(neighbor)
                    }
                }
            }
        }
    }

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        if (!world.world.isDayTime) return false
        if (world.gameTime < villager.nextBuildAvailableTime) return false

        val job = villager.activeBuildJob ?: return false
        if (job.isFinished()) {
            villager.activeBuildJob = null
            return false
        }

        val assigned = villager.assignedBlock ?: job.claimNextBlock(villager) ?: return false
        villager.assignedBlock = assigned

        val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory
        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)

        val isClear = currentBlock.type.isAir || currentBlock.isLiquid
        // Если это трансформация травы в тропинку — ресурсы инвентаря не требуются для старта задачи!
        val isPathTransformation = currentBlock.type.isShovelable() && assigned.material == Material.DIRT_PATH

        if (isClear && !isPathTransformation && !assigned.material.isAir && !bukkitInv.contains(assigned.material)) {
            job.unclaimBlock(assigned)
            villager.assignedBlock = null
            return false
        }
        return true
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val job = villager.activeBuildJob ?: return false
        if (job.isFinished()) return false

        val assigned = villager.assignedBlock ?: return false

        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)
        val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory

        val isClear = currentBlock.type.isAir || currentBlock.isLiquid
        val isPathTransformation = currentBlock.type.isShovelable() && assigned.material == Material.DIRT_PATH
        val hasResources = !isClear || isPathTransformation || assigned.material.isAir || bukkitInv.contains(assigned.material)

        return world.world.isDayTime && hasResources
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val assigned = villager.assignedBlock ?: return
        villager.previousMainHandItem = villager.mainHandItem.copy()

        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)

        val tool = if (!currentBlock.type.isAir && !currentBlock.isLiquid) {
            if (currentBlock.type.isShovelable()) {
                ItemStack(Material.STONE_SHOVEL)
            } else {
                ItemStack(Material.STONE_PICKAXE)
            }
        } else if (assigned.material.isAir) {
            ItemStack(Material.BUCKET)
        } else {
            // Если мы трансформируем траву в тропинку лопатой — берем в руки лопату
            val isPathTransformation = currentBlock.type.isShovelable() && assigned.material == Material.DIRT_PATH
            val itemToHold = if (isPathTransformation) {
                Material.STONE_SHOVEL
            } else if (assigned.blockData.material.isItem) {
                assigned.blockData.material
            } else {
                assigned.material
            }
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

        villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(blockPos))

        val dX = blockPos.x + 0.5 - villager.x
        val dY = blockPos.y + 0.5 - villager.eyeY
        val dZ = blockPos.z + 0.5 - villager.z
        val distance = sqrt(dX * dX + dZ * dZ)

        villager.yRot = (Math.toDegrees(atan2(dZ, dX)) - 90.0).toFloat()
        villager.yHeadRot = villager.yRot
        villager.yBodyRot = villager.yRot
        villager.xRot = (-Math.toDegrees(atan2(dY, distance))).toFloat()
        villager.lookControl.setLookAt(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)

        val npcPos = villager.blockPosition()
        val diffX = abs(blockPos.x - npcPos.x)
        val diffZ = abs(blockPos.z - npcPos.z)
        val diffY = abs(blockPos.y - npcPos.y)

        // Лимитируем только дороги (isRoad == true). Обычные структуры строим с бесконечной дистанции!
        val isWithinReach = if (assigned.isRoad) {
            (diffX * diffX + diffZ * diffZ <= 25.0) && (diffY <= 4)
        } else {
            true
        }

        if (isWithinReach) {
            villager.stuckTicks = 0
            villager.lastPosition = null

            if (distance <= 3.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            }

            if (!block.type.isAir && !block.isLiquid) {
                if (block.type.name.contains("LOG") || block.type.name.contains("WOOD")) {
                    removeWholeTree(block)
                    villager.digTicks = 0
                    doStop(world, villager, time)
                    return
                }

                val expectedToolType = if (block.type.isShovelable()) Material.IRON_SHOVEL else Material.IRON_PICKAXE
                val expectedTool = CraftItemStack.asNMSCopy(ItemStack(expectedToolType))
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
                    doStop(world, villager, time)
                }
            } else {
                // === РЕЖИМ УСТАНОВКИ БЛОКА ===
                val material = assigned.material

                // Осушение жидкости пустым ведром
                if (material.isAir) {
                    if (villager.buildTicks % 5 == 0) villager.swing(InteractionHand.MAIN_HAND)
                    villager.buildTicks++

                    if (villager.buildTicks >= 10) {
                        block.type = Material.AIR
                        bukkitWorld.playSound(block.location, Sound.ITEM_BUCKET_FILL, 1.0f, 1.0f)

                        job.completeBlock(assigned)
                        villager.assignedBlock = null
                        villager.buildTicks = 0
                        villager.nextBuildAvailableTime = world.gameTime + 2L
                        doStop(world, villager, time)
                    }
                    return
                }

                val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory

                // Проверяем: если это трансформация земли в тропинку лопатой — доски/блоки DIRT не требуются!
                val isPathTransformation = block.type.isShovelable() && material == Material.DIRT_PATH

                if (!isPathTransformation && !bukkitInv.contains(material)) {
                    job.unclaimBlock(assigned)
                    villager.assignedBlock = null
                    villager.buildTicks = 0
                    doStop(world, villager, time)
                    return
                }

                // Экипируем лопату при трансформации, иначе держим в руке целевой блок
                val itemToHold = if (isPathTransformation) {
                    Material.STONE_SHOVEL
                } else if (assigned.blockData.material.isItem) {
                    assigned.blockData.material
                } else {
                    assigned.material
                }

                val expectedBlock = CraftItemStack.asNMSCopy(ItemStack(itemToHold))
                if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, expectedBlock)) {
                    villager.setItemInHand(InteractionHand.MAIN_HAND, expectedBlock)
                }

                if (villager.buildTicks % 5 == 0) villager.swing(InteractionHand.MAIN_HAND)
                villager.buildTicks++

                if (villager.buildTicks >= 10) {
                    block.setBlockData(assigned.blockData, true)

                    if (isPathTransformation) {
                        // Нативный звук разравнивания земли лопатой (ресурсы НЕ тратятся)
                        bukkitWorld.playSound(block.location, Sound.ITEM_SHOVEL_FLATTEN, 1.0f, 1.0f)
                    } else {
                        // Обычная установка блока со звуком материала и списанием 1 ресурса
                        bukkitWorld.playSound(block.location, assigned.blockData.soundGroup.placeSound, 1.0f, 1.0f)
                        takeItem(bukkitInv, material, 1)
                    }

                    job.completeBlock(assigned)
                    villager.assignedBlock = null
                    villager.buildTicks = 0
                    villager.nextBuildAvailableTime = world.gameTime + 2L
                    doStop(world, villager, time)
                }
            }
        }

        val needsWalk = (assigned.isRoad && !isWithinReach) || (!assigned.isRoad && distance > 3.0)
        if (needsWalk && !villager.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            val groundPos = getGroundPos(world, blockPos)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(groundPos), speedModifier, 2))
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val job = villager.activeBuildJob
        val assigned = villager.assignedBlock

        if (assigned != null) {
            job?.unclaimBlock(assigned)
            villager.assignedBlock = null
        }

        if (job?.isFinished() == true) {
            villager.activeBuildJob = null
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
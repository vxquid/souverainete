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
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import java.util.*
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
        val highestY = world.world.getHighestBlockYAt(pos.x, pos.z)
        return BlockPos(pos.x, highestY + 1, pos.z)
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
        if (world.gameTime < villager.buildBreakUntilTime) return false

        val settlement = villager.settlement ?: return false
        val job = SettlementPlanner.getActiveOrNextJob(settlement) ?: return false
        villager.activeBuildJob = job

        if (job.isFinished()) {
            villager.activeBuildJob = null
            return false
        }

        val assigned = villager.assignedBlock ?: job.claimNextBlock(villager) ?: return false
        villager.assignedBlock = assigned

        val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory
        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)

        if (!assigned.material.isAir && !bukkitInv.contains(assigned.material)) {
            bukkitInv.addItem(ItemStack(assigned.material, 64))
            if (!bukkitInv.contains(Material.COBBLESTONE)) {
                bukkitInv.addItem(ItemStack(Material.COBBLESTONE, 64))
            }
            if (!bukkitInv.contains(Material.DIRT)) {
                bukkitInv.addItem(ItemStack(Material.DIRT, 64))
            }
            if (!bukkitInv.contains(Material.IRON_SHOVEL)) {
                bukkitInv.addItem(ItemStack(Material.IRON_SHOVEL, 1))
            }
        }

        val isClear = currentBlock.isIgnorableObstacle()
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

        val isClear = currentBlock.isIgnorableObstacle()
        val isPathTransformation = currentBlock.type.isShovelable() && assigned.material == Material.DIRT_PATH
        val hasResources = !isClear || isPathTransformation || assigned.material.isAir || bukkitInv.contains(assigned.material)

        return world.world.isDayTime && hasResources
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val assigned = villager.assignedBlock ?: return

        // Сбрасываем таймеры застревания при начале новой работы
        villager.previousMainHandItem = villager.mainHandItem.copy()
        villager.lastBuildActionTime = world.gameTime
        villager.isBuildDistanceHackActive = false

        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)

        val tool = if (!currentBlock.isIgnorableObstacle()) {
            if (currentBlock.type.isShovelable()) ItemStack(Material.STONE_SHOVEL) else ItemStack(Material.STONE_PICKAXE)
        } else if (assigned.material.isAir) {
            ItemStack(Material.BUCKET)
        } else {
            val isPathTransformation = currentBlock.type.isShovelable() && assigned.material == Material.DIRT_PATH
            val itemToHold = if (isPathTransformation) Material.STONE_SHOVEL
            else if (assigned.blockData.material.isItem) assigned.blockData.material
            else assigned.material
            ItemStack(itemToHold)
        }

        villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(tool))

        val groundPos = getGroundPos(world, assigned.pos)
        villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(groundPos), speedModifier, 2))
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val assigned = villager.assignedBlock ?: return
        val job = villager.activeBuildJob ?: return

        val bukkitWorld = world.world
        val blockPos = assigned.pos
        val block = bukkitWorld.getBlockAt(blockPos.x, blockPos.y, blockPos.z)

        // =========================================================================
        // === УМНЫЙ СТЕЙТЧЕКЕР НА ЗАСТРЕВАНИЕ NPC В ЛОКАЦИИ                     ===
        // =========================================================================
        val idleTicks = world.gameTime - villager.lastBuildActionTime

        if (idleTicks > 120L) { // 6 секунд бездействия
            if (!villager.isBuildDistanceHackActive) {
                villager.isBuildDistanceHackActive = true
                // Спавним светящиеся частицы на застрявшем ИИ в знак отладки
                bukkitWorld.spawnParticle(Particle.GLOW, villager.bukkitEntity.location.add(0.0, 1.5, 0.0), 6, 0.2, 0.2, 0.2)
            }
        }

        if (idleTicks > 240L) { // 12 секунд полной неудачи (даже с хаком не дотянуться, например, мешает стена)
            // Принудительно освобождаем блок и вешаем штрафной кулдаун
            villager.isBuildDistanceHackActive = false
            job.unclaimBlock(assigned)
            villager.assignedBlock = null
            villager.digTicks = 0
            villager.buildTicks = 0
            villager.nextBuildAvailableTime = world.gameTime + 80L // Кулдаун 4 секунды
            doStop(world, villager, time)
            return
        }
        // =========================================================================

        villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(blockPos))

        val dX = blockPos.x + 0.5 - villager.x
        val dY = blockPos.y + 0.5 - villager.eyeY
        val dZ = blockPos.z + 0.5 - villager.z
        val distance = kotlin.math.sqrt(dX * dX + dZ * dZ)

        villager.yRot = (Math.toDegrees(kotlin.math.atan2(dZ, dX)) - 90.0).toFloat()
        villager.yHeadRot = villager.yRot
        villager.yBodyRot = villager.yRot
        villager.xRot = (-Math.toDegrees(kotlin.math.atan2(dY, distance))).toFloat()
        villager.lookControl.setLookAt(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)

        val npcPos = villager.blockPosition()
        val diffX = kotlin.math.abs(blockPos.x - npcPos.x)
        val diffZ = kotlin.math.abs(blockPos.z - npcPos.z)

        // Разделение reach-дистанции
        val isWithinReach = if (villager.isBuildDistanceHackActive) {
            true // ХАК АКТИВЕН: Разрешаем бесконечную дистанцию, чтобы достроить блок телекинетически
        } else if (assigned.isRoad) {
            val diffY = kotlin.math.abs(blockPos.y - npcPos.y)
            (diffX * diffX + diffZ * diffZ <= 25.0) && (diffY <= 4)
        } else {
            (diffX * diffX + diffZ * diffZ <= 36.0)
        }

        if (isWithinReach) {
            villager.stuckTicks = 0
            villager.lastPosition = null
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)

            if (!block.isIgnorableObstacle()) {
                if (block.type.name.contains("LOG") || block.type.name.contains("WOOD")) {
                    removeWholeTree(block)
                    villager.digTicks = 0

                    // Сбрасываем хаки при успехе
                    villager.isBuildDistanceHackActive = false
                    villager.lastBuildActionTime = world.gameTime

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

                    // Сбрасываем хаки при успехе
                    villager.isBuildDistanceHackActive = false
                    villager.lastBuildActionTime = world.gameTime

                    doStop(world, villager, time)
                }
            } else {
                // === РЕЖИМ УСТАНОВКИ БЛОКА ===
                val material = assigned.material

                // Осушение жидкости
                if (material.isAir) {
                    if (block.isLiquid) {
                        if (villager.buildTicks % 5 == 0) villager.swing(InteractionHand.MAIN_HAND)
                        villager.buildTicks++

                        if (villager.buildTicks >= 10) {
                            block.type = Material.AIR
                            bukkitWorld.playSound(block.location, Sound.ITEM_BUCKET_FILL, 1.0f, 1.0f)

                            job.completeBlock(assigned)
                            villager.assignedBlock = null
                            villager.buildTicks = 0
                            villager.nextBuildAvailableTime = world.gameTime + 2L

                            // Сбрасываем хаки при успехе
                            villager.isBuildDistanceHackActive = false
                            villager.lastBuildActionTime = world.gameTime

                            doStop(world, villager, time)
                        }
                    } else {
                        job.completeBlock(assigned)
                        villager.assignedBlock = null
                        villager.buildTicks = 0
                        villager.nextBuildAvailableTime = world.gameTime + 2L

                        // Сбрасываем хаки при успехе
                        villager.isBuildDistanceHackActive = false
                        villager.lastBuildActionTime = world.gameTime

                        doStop(world, villager, time)
                    }
                    return
                }

                val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory
                val isPathTransformation = block.type.isShovelable() && material == Material.DIRT_PATH

                if (!isPathTransformation && !bukkitInv.contains(material)) {
                    job.unclaimBlock(assigned)
                    villager.assignedBlock = null
                    villager.buildTicks = 0
                    doStop(world, villager, time)
                    return
                }

                // Анти-удушение (Nudge)
                if (assigned.blockData.material.isSolid) {
                    val targetBox = BoundingBox(block.x.toDouble(), block.y.toDouble(), block.z.toDouble(), block.x + 1.0, block.y + 1.0, block.z + 1.0)
                    val bukkitNpc = villager.bukkitEntity
                    if (targetBox.overlaps(bukkitNpc.boundingBox)) {
                        val blockCenter = block.location.add(0.5, 0.5, 0.5)
                        val direction = bukkitNpc.location.subtract(blockCenter).toVector().setY(0.0)
                        if (direction.lengthSquared() > 0.0) {
                            direction.normalize().multiply(1.0)
                            bukkitNpc.teleport(bukkitNpc.location.add(direction))
                        } else {
                            bukkitNpc.teleport(bukkitNpc.location.add(1.0, 0.0, 0.0))
                        }
                    }
                }

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
                        bukkitWorld.playSound(block.location, Sound.ITEM_SHOVEL_FLATTEN, 1.0f, 1.0f)
                    } else {
                        bukkitWorld.playSound(block.location, assigned.blockData.soundGroup.placeSound, 1.0f, 1.0f)
                        takeItem(bukkitInv, material, 1)
                    }

                    job.completeBlock(assigned)
                    villager.assignedBlock = null
                    villager.buildTicks = 0
                    villager.nextBuildAvailableTime = world.gameTime + 2L

                    // Сбрасываем хаки при успехе
                    villager.isBuildDistanceHackActive = false
                    villager.lastBuildActionTime = world.gameTime

                    doStop(world, villager, time)
                }
            }
        }

        // Логика перемещения по горизонтали
        val distHorizontalSq = diffX * diffX + diffZ * diffZ
        val needsWalk = (assigned.isRoad && !isWithinReach) || (!assigned.isRoad && distHorizontalSq > 9.0)
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

/**
 * Слушатель безопасности: отслеживает получение урона от удушья (застраивания)
 * и мгновенно эвакуирует жителей-строителей в безопасную зону (к колоколу поселения).
 */
class BuilderSafetyListener : Listener {

    @EventHandler
    fun onNpcDamage(event: EntityDamageEvent) {
        val villager = event.entity as? BukkitVillager ?: return

        if (event.cause == EntityDamageEvent.DamageCause.SUFFOCATION) {
            val nmsVillager = (villager as? CraftVillager)?.handle as? HumanoidVillager ?: return

            // Срабатывает только если NPC в данный момент активно строит здание/дорогу
            if (nmsVillager.activeBuildJob != null) {
                event.isCancelled = true // Полностью отменяем наносящийся урон

                val settlement = nmsVillager.settlement
                val safeLoc = if (settlement != null) {
                    // Телепортируем на ратушу к колоколу
                    settlement.data.center.clone().add(0.5, 1.0, 0.5)
                } else {
                    // Если бездомный - переносим вверх на поверхность земли
                    val loc = villager.location
                    val highestY = loc.world.getHighestBlockYAt(loc.blockX, loc.blockZ)
                    Location(loc.world, loc.x, highestY + 1.0, loc.z, loc.yaw, loc.pitch)
                }

                // Сбрасываем текущие задачи ИИ, чтобы NPC пересчитал свой путь и не застрял снова
                nmsVillager.assignedBlock = null
                nmsVillager.digTicks = 0
                nmsVillager.buildTicks = 0
                nmsVillager.isBuildDistanceHackActive = false // Отключаем хаки дальности
                nmsVillager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)

                // Безопасно перемещаем
                villager.teleport(safeLoc)

                // Эффекты паники и звуки спасения
                villager.world.playSound(safeLoc, Sound.ENTITY_VILLAGER_HURT, 1.0f, 1.0f)
                villager.world.spawnParticle(Particle.ANGRY_VILLAGER, safeLoc.clone().add(0.0, 1.5, 0.0), 5, 0.2, 0.2, 0.2)
            }
        }
    }
}
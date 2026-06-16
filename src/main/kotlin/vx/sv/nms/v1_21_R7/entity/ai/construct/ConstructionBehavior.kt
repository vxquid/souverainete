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
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.plugin
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
        val index = inventory.first(material)
        if (index != -1) {
            val item = inventory.getItem(index) ?: return
            if (item.amount <= amount) {
                inventory.setItem(index, null)
            } else {
                item.amount -= amount
                inventory.setItem(index, item)
            }
        }
    }

    private fun addItemsSmart(inventory: Inventory, material: Material, amount: Int) {
        val maxStack = material.maxStackSize
        if (maxStack == 1) {
            if (!inventory.contains(material)) {
                inventory.addItem(ItemStack(material, 1))
            }
        } else {
            val currentAmount = inventory.filterNotNull()
                .filter { it.type == material }
                .sumOf { it.amount }
            if (currentAmount < amount) {
                inventory.addItem(ItemStack(material, amount - currentAmount))
            }
        }
    }

    private fun clearConstructionBlocks(inventory: Inventory) {
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            val type = item.type
            val isToolOrWeapon = type.name.contains("SWORD") ||
                    type.name.contains("BOW") ||
                    type.name.contains("AXE") ||
                    type.name.contains("SHOVEL") ||
                    type.name.contains("PICKAXE") ||
                    type.name.contains("SHIELD") ||
                    type.name.contains("HELMET") ||
                    type.name.contains("CHESTPLATE") ||
                    type.name.contains("LEGGINGS") ||
                    type.name.contains("BOOTS")
            if (!isToolOrWeapon) {
                inventory.setItem(i, null)
            }
        }
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

    /**
     * ИСПРАВЛЕНО: Умный поиск пола.
     * Если целевой блок находится высоко (крыша) или в стене, ИИ сканирует блоки вниз
     * и находит твердый пол, на который можно физически встать.
     */
    private fun getWalkablePos(world: ServerLevel, targetPos: BlockPos, villagerPos: BlockPos): BlockPos {
        var y = targetPos.y
        val bukkitWorld = world.world
        // Ищем твердый блок вниз от цели (до 10 блоков в глубину)
        while (y > targetPos.y - 10 && y > bukkitWorld.minHeight) {
            val block = bukkitWorld.getBlockAt(targetPos.x, y, targetPos.z)
            // Игнорируем листву, ищем твердый пол (камень, дерево, грязь)
            if (block.type.isSolid && !block.type.name.contains("LEAVES")) {
                return BlockPos(targetPos.x, y + 1, targetPos.z)
            }
            y--
        }
        // Если ничего не найдено (например, висит над пропастью), идем на текущей высоте жителя
        return BlockPos(targetPos.x, villagerPos.y, targetPos.z)
    }

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        if (!world.world.isDayTime) return false
        if (world.gameTime < villager.nextBuildAvailableTime) return false
        if (world.gameTime < villager.buildBreakUntilTime) return false

        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        val prof = bukkitVillager.profession

        if (prof == org.bukkit.entity.Villager.Profession.FARMER ||
            prof == org.bukkit.entity.Villager.Profession.SHEPHERD ||
            prof == org.bukkit.entity.Villager.Profession.BUTCHER ||
            prof == org.bukkit.entity.Villager.Profession.TOOLSMITH) {
            return false
        }

        val settlement = villager.settlement ?: return false

        if (villager.activeBuildJob == null && villager.savedJobId != null) {
            val active = SettlementPlanner.activeJobs[settlement.data.id]
            if (active != null && active.jobId == villager.savedJobId) {
                villager.activeBuildJob = active
            } else {
                villager.savedJobId = null
            }
        }

        val job = villager.activeBuildJob ?: SettlementPlanner.getActiveOrNextJob(settlement) ?: return false
        villager.activeBuildJob = job

        val pdc = bukkitVillager.persistentDataContainer
        val jobUuidKey = NamespacedKey(plugin, "active_build_job_uuid")
        if (pdc.get(jobUuidKey, PersistentDataType.STRING) != job.jobId.toString()) {
            pdc.set(jobUuidKey, PersistentDataType.STRING, job.jobId.toString())
            villager.savedJobId = job.jobId
        }

        if (job.isFinished()) {
            villager.activeBuildJob = null
            pdc.remove(jobUuidKey)
            villager.savedJobId = null
            return false
        }

        val assigned = villager.assignedBlock ?: job.claimNextBlock(villager) ?: return false
        villager.assignedBlock = assigned

        val bukkitInv = bukkitVillager.inventory

        if (!assigned.material.isAir) {
            val materialToGive = if (assigned.material == Material.FARMLAND) Material.DIRT else assigned.material
            addItemsSmart(bukkitInv, materialToGive, 64)

            if (assigned.material == Material.FARMLAND) {
                addItemsSmart(bukkitInv, Material.IRON_HOE, 1)
            }
        }
        addItemsSmart(bukkitInv, Material.COBBLESTONE, 64)
        addItemsSmart(bukkitInv, Material.DIRT, 64)
        addItemsSmart(bukkitInv, Material.IRON_SHOVEL, 1)

        val currentBlock = world.world.getBlockAt(assigned.pos.x, assigned.pos.y, assigned.pos.z)
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

        val isFarmlandTransformation = (currentBlock.type == Material.DIRT || currentBlock.type == Material.FARMLAND) && assigned.material == Material.FARMLAND
        val hasResources = !isClear || isPathTransformation || isFarmlandTransformation || assigned.material.isAir ||
                bukkitInv.contains(assigned.material) || (assigned.material == Material.FARMLAND && bukkitInv.contains(Material.DIRT))

        return world.world.isDayTime && hasResources
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val assigned = villager.assignedBlock ?: return

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

        // ИСПРАВЛЕНО: Идем к безопасному полу под блоком
        val walkPos = getWalkablePos(world, assigned.pos, villager.blockPosition())
        villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(walkPos), speedModifier, 2))
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val assigned = villager.assignedBlock ?: return
        val job = villager.activeBuildJob ?: return

        val bukkitWorld = world.world
        val blockPos = assigned.pos
        val block = bukkitWorld.getBlockAt(blockPos.x, blockPos.y, blockPos.z)

        val idleTicks = world.gameTime - villager.lastBuildActionTime

        if (idleTicks > 120L) {
            if (!villager.isBuildDistanceHackActive) {
                villager.isBuildDistanceHackActive = true
                bukkitWorld.spawnParticle(Particle.GLOW, villager.bukkitEntity.location.add(0.0, 1.5, 0.0), 6, 0.2, 0.2, 0.2)
            }
        }

        if (idleTicks > 240L) {
            villager.isBuildDistanceHackActive = false
            job.unclaimBlock(assigned)
            villager.assignedBlock = null
            villager.digTicks = 0
            villager.buildTicks = 0
            villager.nextBuildAvailableTime = world.gameTime + 80L
            doStop(world, villager, time)
            return
        }

        val npcPos = villager.blockPosition()
        val diffX = kotlin.math.abs(blockPos.x - npcPos.x)
        val diffZ = kotlin.math.abs(blockPos.z - npcPos.z)
        val diffY = kotlin.math.abs(blockPos.y - npcPos.y)

        // ИСПРАВЛЕНО: Радиус взаимодействия (reach) урезан до 3 блоков по горизонтали (было 6).
        // Это заставит жителей подбегать вплотную к стене перед началом постройки.
        val isWithinReach = if (villager.isBuildDistanceHackActive) {
            true
        } else if (assigned.isRoad) {
            (diffX * diffX + diffZ * diffZ <= 9.0) && (diffY <= 4)
        } else {
            (diffX * diffX + diffZ * diffZ <= 9.0) && (diffY <= 5)
        }

        if (isWithinReach) {
            villager.stuckTicks = 0
            villager.lastPosition = null
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)

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

            if (!block.isIgnorableObstacle()) {
                if (block.type.name.contains("LOG") || block.type.name.contains("WOOD")) {
                    removeWholeTree(block)
                    villager.digTicks = 0
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
                    villager.isBuildDistanceHackActive = false
                    villager.lastBuildActionTime = world.gameTime
                    doStop(world, villager, time)
                }
            } else {
                val material = assigned.material
                val isFarmland = material == Material.FARMLAND

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
                            villager.isBuildDistanceHackActive = false
                            villager.lastBuildActionTime = world.gameTime
                            doStop(world, villager, time)
                        }
                    } else {
                        job.completeBlock(assigned)
                        villager.assignedBlock = null
                        villager.buildTicks = 0
                        villager.nextBuildAvailableTime = world.gameTime + 2L
                        villager.isBuildDistanceHackActive = false
                        villager.lastBuildActionTime = world.gameTime
                        doStop(world, villager, time)
                    }
                    return
                }

                val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory
                val isPathTransformation = block.type.isShovelable() && material == Material.DIRT_PATH

                if (isFarmland) {
                    if (block.type != Material.DIRT && block.type != Material.FARMLAND) {
                        val expectedTool = CraftItemStack.asNMSCopy(ItemStack(Material.DIRT))
                        if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, expectedTool)) {
                            villager.setItemInHand(InteractionHand.MAIN_HAND, expectedTool)
                        }

                        if (villager.buildTicks % 5 == 0) villager.swing(InteractionHand.MAIN_HAND)
                        villager.buildTicks++

                        if (villager.buildTicks >= 10) {
                            block.type = Material.DIRT
                            bukkitWorld.playSound(block.location, Sound.BLOCK_GRASS_PLACE, 1.0f, 1.0f)
                            takeItem(bukkitInv, Material.DIRT, 1)
                            villager.buildTicks = 0
                        }
                        return
                    }

                    if (block.type == Material.DIRT) {
                        val expectedTool = CraftItemStack.asNMSCopy(ItemStack(Material.IRON_HOE))
                        if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, expectedTool)) {
                            villager.setItemInHand(InteractionHand.MAIN_HAND, expectedTool)
                        }

                        if (villager.buildTicks % 5 == 0) villager.swing(InteractionHand.MAIN_HAND)
                        villager.buildTicks++

                        if (villager.buildTicks >= 10) {
                            block.setBlockData(assigned.blockData, true)
                            bukkitWorld.playSound(block.location, Sound.ITEM_HOE_TILL, 1.0f, 1.0f)

                            job.completeBlock(assigned)
                            villager.assignedBlock = null
                            villager.buildTicks = 0
                            villager.nextBuildAvailableTime = world.gameTime + 2L
                            villager.isBuildDistanceHackActive = false
                            villager.lastBuildActionTime = world.gameTime
                            doStop(world, villager, time)
                        }
                        return
                    }
                }

                if (!isPathTransformation && !isFarmland && !bukkitInv.contains(material)) {
                    job.unclaimBlock(assigned)
                    villager.assignedBlock = null
                    villager.buildTicks = 0
                    doStop(world, villager, time)
                    return
                }

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
                    villager.isBuildDistanceHackActive = false
                    villager.lastBuildActionTime = world.gameTime
                    doStop(world, villager, time)
                }
            }
        } else {
            villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)

            // ИСПРАВЛЕНО: Безопасное прокладывание пути по твердому полу
            if (!villager.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                val walkPos = getWalkablePos(world, blockPos, npcPos)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(walkPos), speedModifier, 2))
            }
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
            villager.buildBreakUntilTime = world.gameTime + 400L

            val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory
            clearConstructionBlocks(bukkitInv)

            val pdc = (villager.bukkitEntity as BukkitVillager).persistentDataContainer
            pdc.remove(NamespacedKey(plugin, "active_build_job_uuid"))
            villager.savedJobId = null
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
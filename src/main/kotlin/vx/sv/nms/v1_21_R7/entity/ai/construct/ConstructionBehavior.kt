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
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
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

    companion object {
        private val debugVisualsMap = mutableMapOf<UUID, Pair<BlockDisplay, BlockDisplay>>()
        private val lastLocationMap = java.util.WeakHashMap<HumanoidVillager, Location>()

        // УЧЕТ ВРЕМЕНИ ДЛЯ УМНОЙ АКТИВАЦИИ ДИСТАНЦИОННОГО ХАКА И СБРОСОВ
        private val assignedBlockTicksMap = java.util.WeakHashMap<HumanoidVillager, Int>()
    }

    private fun handleDebugVisuals(world: ServerLevel, villager: HumanoidVillager, block: Block) {
        val bukkitWorld = world.world
        val playersWithSpyglass = bukkitWorld.players.any {
            it.inventory.itemInMainHand.type == Material.SPYGLASS ||
                    it.inventory.itemInOffHand.type == Material.SPYGLASS
        }

        val uuid = villager.uuid

        if (playersWithSpyglass) {
            var (line, blockDisplay) = debugVisualsMap[uuid] ?: Pair<BlockDisplay?, BlockDisplay?>(null, null)

            if (line == null || line.isDead) {
                line = bukkitWorld.spawn(villager.bukkitEntity.location, BlockDisplay::class.java) {
                    it.block = Bukkit.createBlockData(Material.WHITE_CONCRETE)
                    it.brightness = Display.Brightness(15, 15)
                    try { it.teleportDuration = 1 } catch (_: Exception) {}
                }
            }
            if (blockDisplay == null || blockDisplay.isDead) {
                blockDisplay = bukkitWorld.spawn(block.location, BlockDisplay::class.java) {
                    it.brightness = Display.Brightness(15, 15)
                    try { it.teleportDuration = 1 } catch (_: Exception) {}
                }
            }

            debugVisualsMap[uuid] = Pair(line, blockDisplay)

            val startLoc = villager.bukkitEntity.location.add(0.0, 1.75, 0.0)
            val targetLoc = block.location.clone().add(0.5, 0.5, 0.5)
            val dir = targetLoc.toVector().subtract(startLoc.toVector())
            val length = dir.length().toFloat()

            if (length > 0) {
                val lineLoc = startLoc.clone()
                lineLoc.direction = dir
                line.teleport(lineLoc)

                val lineTransform = line.transformation
                lineTransform.scale.set(0.02f, 0.02f, length)
                lineTransform.translation.set(-0.01f, -0.01f, 0f)
                line.transformation = lineTransform
            }

            val isBreaking = !block.isIgnorableObstacle()
            val targetMaterial = if (isBreaking) Material.RED_STAINED_GLASS else Material.LIME_STAINED_GLASS

            if (blockDisplay.block.material != targetMaterial) {
                blockDisplay.block = Bukkit.createBlockData(targetMaterial)
            }

            blockDisplay.teleport(block.location)
            val blockTransform = blockDisplay.transformation
            blockTransform.scale.set(1.02f, 1.02f, 1.02f)
            blockTransform.translation.set(-0.01f, -0.01f, -0.01f)
            blockDisplay.transformation = blockTransform

        } else {
            clearDebugVisuals(villager)
        }
    }

    private fun clearDebugVisuals(villager: HumanoidVillager) {
        val displays = debugVisualsMap.remove(villager.uuid)
        displays?.first?.remove()
        displays?.second?.remove()
    }

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
            val isLog = type.name.contains("LOG") || type.name.contains("WOOD") || type.name.contains("STEM") || type.name.contains("HYPHAE")
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

    private fun findConnectedTrunk(startBlock: Block): Block? {
        val checked = mutableSetOf<Block>()
        val queue = ArrayDeque<Block>()
        queue.add(startBlock)

        var processedCount = 0
        while (queue.isNotEmpty() && processedCount++ < 100) {
            val current = queue.poll()
            if (!checked.add(current)) continue

            val type = current.type
            val isLog = type.name.contains("LOG") || type.name.contains("WOOD") || type.name.contains("STEM") || type.name.contains("HYPHAE")
            if (isLog) {
                return current
            }

            if (type.name.contains("LEAVES")) {
                for (face in BlockFace.entries) {
                    if (face == BlockFace.SELF) continue
                    val neighbor = current.getRelative(face)
                    if (neighbor.location.distanceSquared(startBlock.location) <= 49.0) {
                        queue.add(neighbor)
                    }
                }
            }
        }
        return null
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

        if (villager.brain.hasMemoryValue(MemoryModuleType.INTERACTION_TARGET)) {
            villager.brain.eraseMemory(MemoryModuleType.INTERACTION_TARGET)
        }
        if (villager.brain.hasMemoryValue(MemoryModuleType.BREED_TARGET)) {
            villager.brain.eraseMemory(MemoryModuleType.BREED_TARGET)
        }

        if (villager.activeBuildJob == null && villager.savedJobId != null) {
            val activeList = SettlementPlanner.activeJobs[settlement.data.id]
            val active = activeList?.find { it.jobId == villager.savedJobId }
            if (active != null && !active.isFinished()) {
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
            addItemsSmart(bukkitInv, assigned.material, 64)
        }
        addItemsSmart(bukkitInv, Material.COBBLESTONE, 64)
        addItemsSmart(bukkitInv, Material.DIRT, 64)
        addItemsSmart(bukkitInv, Material.IRON_SHOVEL, 1)

        return true
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val job = villager.activeBuildJob ?: return false
        if (job.isFinished()) return false

        val assigned = villager.assignedBlock ?: return false

        return world.world.isDayTime
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
        villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(assigned.pos), speedModifier, 1))
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        if (!villager.bukkitEntity.isValid) {
            clearDebugVisuals(villager)
            return
        }

        val assigned = villager.assignedBlock ?: return
        val job = villager.activeBuildJob ?: return

        val bukkitWorld = world.world
        var blockPos = assigned.pos
        var block = bukkitWorld.getBlockAt(blockPos.x, blockPos.y, blockPos.z)

        if (block.type.name.contains("LEAVES")) {
            val trunk = findConnectedTrunk(block)
            if (trunk != null) {
                block = trunk
                blockPos = BlockPos(trunk.x, trunk.y, trunk.z)
            }
        }

        handleDebugVisuals(world, villager, block)

        if (villager.brain.hasMemoryValue(MemoryModuleType.INTERACTION_TARGET)) {
            villager.brain.eraseMemory(MemoryModuleType.INTERACTION_TARGET)
        }
        if (villager.brain.hasMemoryValue(MemoryModuleType.BREED_TARGET)) {
            villager.brain.eraseMemory(MemoryModuleType.BREED_TARGET)
        }

        val currentLoc = villager.bukkitEntity.location
        val lastLoc = lastLocationMap[villager]

        // Ведем учет тиков работы над конкретной задачей блока
        val pursuitTicks = (assignedBlockTicksMap[villager] ?: 0) + 1
        assignedBlockTicksMap[villager] = pursuitTicks

        if (villager.digTicks > 0 || villager.buildTicks > 0) {
            villager.lastBuildActionTime = world.gameTime
        } else if (lastLoc != null && currentLoc.world == lastLoc.world) {
            val distanceMovedSq = currentLoc.distanceSquared(lastLoc)
            if (distanceMovedSq > 0.0025) {
                villager.lastBuildActionTime = world.gameTime
            }
        }
        lastLocationMap[villager] = currentLoc

        // 1. Быстрая активация телекинеза (дистанционного хака), если житель тупит из-за рельефа (прошло 80 тиков / 4 сек)
        if (pursuitTicks > 80) {
            if (!villager.isBuildDistanceHackActive) {
                villager.isBuildDistanceHackActive = true
                bukkitWorld.spawnParticle(Particle.GLOW, villager.bukkitEntity.location.add(0.0, 1.5, 0.0), 6, 0.2, 0.2, 0.2)
            }
        }

        // 2. Сброс зависшей задачи, если за 240 тиков (12 сек) физически нет прогресса ИЛИ общий таймаут работы над блоком превысил 300 тиков (15 сек)
        val idleTicks = world.gameTime - villager.lastBuildActionTime
        if (idleTicks > 240L || pursuitTicks > 300) {
            assignedBlockTicksMap.remove(villager)
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

        // ИСПРАВЛЕНО: Добавлено +1 к радиусу горизонтального строительства ( Roads: 4 блока, Buildings: 5 блоков )
        val isWithinReach = if (villager.isBuildDistanceHackActive) {
            true
        } else if (assigned.isRoad) {
            (diffX * diffX + diffZ * diffZ <= 16.0) // Добавлено +1 к радиусу (макс. 4 блока горизонтально)
        } else {
            (diffX * diffX + diffZ * diffZ <= 25.0) // Добавлено +1 к радиусу (макс. 5 блоков горизонтально)
        }

        val material = assigned.material
        // ИСПРАВЛЕНО: Игрок прокладывает дорожки лопатой по ПКМ. Если это дорожная трансформация, землю копать и ломать НЕ НУЖНО.
        val isPathTransformation = block.type.isShovelable() && material == Material.DIRT_PATH

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

            // ИСПРАВЛЕНО: Пропускаем шаг разрушения блоков, если это трансформация лопатой в тропинку
            if (!block.isIgnorableObstacle() && !isPathTransformation) {
                if (block.type.name.contains("LOG") || block.type.name.contains("WOOD") || block.type.name.contains("STEM") || block.type.name.contains("HYPHAE")) {
                    removeWholeTree(block)

                    assignedBlockTicksMap.remove(villager)
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

                    assignedBlockTicksMap.remove(villager)

                    doStop(world, villager, time)
                }
            } else {

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

                            assignedBlockTicksMap.remove(villager)

                            doStop(world, villager, time)
                        }
                    } else {
                        job.completeBlock(assigned)
                        villager.assignedBlock = null
                        villager.buildTicks = 0
                        villager.nextBuildAvailableTime = world.gameTime + 2L

                        villager.isBuildDistanceHackActive = false
                        villager.lastBuildActionTime = world.gameTime

                        assignedBlockTicksMap.remove(villager)

                        doStop(world, villager, time)
                    }
                    return
                }

                val bukkitInv = (villager.bukkitEntity as BukkitVillager).inventory

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
                    }

                    job.completeBlock(assigned)
                    villager.assignedBlock = null
                    villager.buildTicks = 0
                    villager.nextBuildAvailableTime = world.gameTime + 2L

                    villager.isBuildDistanceHackActive = false
                    villager.lastBuildActionTime = world.gameTime

                    assignedBlockTicksMap.remove(villager)

                    doStop(world, villager, time)
                }
            }
        } else {
            villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)

            if (world.gameTime % 30L == 0L || !villager.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(blockPos), speedModifier, 1))
            }
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        clearDebugVisuals(villager)

        val job = villager.activeBuildJob
        val assigned = villager.assignedBlock

        assignedBlockTicksMap.remove(villager)

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

class BuilderSafetyListener : Listener {

    @EventHandler
    fun onNpcDamage(event: EntityDamageEvent) {
        val villager = event.entity as? BukkitVillager ?: return
        val nmsVillager = (villager as? CraftVillager)?.handle as? HumanoidVillager ?: return

        if (nmsVillager.activeBuildJob != null) {
            if (event.cause == EntityDamageEvent.DamageCause.SUFFOCATION) {
                event.isCancelled = true

                val settlement = nmsVillager.settlement
                val safeLoc = if (settlement != null) {
                    val center = settlement.data.center

                    // ИСПРАВЛЕНО: Продвинутый поиск безопасного блока в радиусе 3 блоков, Y-высота которого гарантированно НЕ превышает высоту центра (колокола) поселения
                    var targetY = center.blockY
                    var targetX = center.blockX + 2
                    var targetZ = center.blockZ + 2

                    val world = center.world!!
                    var foundSafeSpot = false
                    for (ox in listOf(2, -2, 3, -3, 1, -1)) {
                        for (oz in listOf(2, -2, 3, -3, 1, -1)) {
                            val tx = center.blockX + ox
                            val tz = center.blockZ + oz
                            val gy = world.getHighestBlockYAt(tx, tz)

                            // Строго ограничиваем высоту Y-координатой центра, чтобы житель не очутился на крыше meeting_point/town_hall
                            if (gy <= center.blockY + 1) {
                                val feetBlock = world.getBlockAt(tx, gy + 1, tz)
                                val headBlock = world.getBlockAt(tx, gy + 2, tz)
                                if (feetBlock.type.isAir && headBlock.type.isAir) {
                                    targetX = tx
                                    targetY = gy
                                    targetZ = tz
                                    foundSafeSpot = true
                                    break
                                }
                            }
                        }
                        if (foundSafeSpot) break
                    }
                    Location(world, targetX + 0.5, targetY + 1.0, targetZ + 0.5)
                } else {
                    val loc = villager.location
                    val highestY = loc.world.getHighestBlockYAt(loc.blockX, loc.blockZ)
                    Location(loc.world, loc.x, highestY + 1.0, loc.z, loc.yaw, loc.pitch)
                }

                val job = nmsVillager.activeBuildJob
                val assigned = nmsVillager.assignedBlock
                if (assigned != null && job != null) {
                    job.unclaimBlock(assigned)
                }

                nmsVillager.assignedBlock = null
                nmsVillager.digTicks = 0
                nmsVillager.buildTicks = 0
                nmsVillager.isBuildDistanceHackActive = false
                nmsVillager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)

                villager.teleport(safeLoc)

                villager.world.playSound(safeLoc, Sound.ENTITY_VILLAGER_HURT, 1.0f, 1.0f)
                villager.world.spawnParticle(Particle.ANGRY_VILLAGER, safeLoc.clone().add(0.0, 1.5, 0.0), 5, 0.2, 0.2, 0.2)
            }
            else if (event.cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK ||
                event.cause == EntityDamageEvent.DamageCause.PROJECTILE) {

                val job = nmsVillager.activeBuildJob
                val assigned = nmsVillager.assignedBlock

                if (assigned != null && job != null) {
                    job.unclaimBlock(assigned)
                }

                nmsVillager.assignedBlock = null
                nmsVillager.digTicks = 0
                nmsVillager.buildTicks = 0
                nmsVillager.isBuildDistanceHackActive = false
                nmsVillager.lastBuildActionTime = nmsVillager.level().gameTime

                nmsVillager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                nmsVillager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
                nmsVillager.refreshBrain(nmsVillager.level() as ServerLevel)

                val loc = villager.location
                loc.world.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0.0, 1.5, 0.0), 5, 0.2, 0.2, 0.2)
            }
        }
    }
}
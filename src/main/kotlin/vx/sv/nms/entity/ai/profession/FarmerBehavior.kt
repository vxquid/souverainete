package vx.sv.gameplay.humanoid

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
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
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.ItemStack
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.nms.entity.HumanoidVillager
import vx.sv.nms.entity.ai.construct.SettlementPlanner
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

class FarmerBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ),
    200
) {
    private var farmTicks = 0
    private var lastCropSearchTime = 0L
    private var noWorkUntil = 0L

    private var targetCropPos: BlockPos? = null
    private var targetPlantPos: BlockPos? = null
    private var targetTillPos: BlockPos? = null
    private var targetBoneMealPos: BlockPos? = null

    private var targetWaterPos: BlockPos? = null
    private var targetShorePos: BlockPos? = null

    private var cachedWaterPos: BlockPos? = null
    private var cachedShorePos: BlockPos? = null

    companion object {
        private val activeBobbers = ConcurrentHashMap<UUID, Int>()
        private val reservedWaterSpots = ConcurrentHashMap<BlockPos, UUID>()
        private val activeFishermenPositions = ConcurrentHashMap<UUID, BlockPos>()

        private val biteTicks = ConcurrentHashMap<UUID, Int>()
        private val fishAngles = ConcurrentHashMap<UUID, Double>()

        fun releaseWaterSpot(villagerUuid: UUID) {
            activeFishermenPositions.remove(villagerUuid)
            biteTicks.remove(villagerUuid)
            fishAngles.remove(villagerUuid)
            val iterator = reservedWaterSpots.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value == villagerUuid) {
                    iterator.remove()
                }
            }
        }
    }

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.FARMER || villager.settlement == null) {
            return false
        }
        val gameTime = world.gameTime
        if (gameTime < noWorkUntil) return false

        val timeOfDay = world.world.time
        return timeOfDay in 0..12000
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.FARMER || villager.settlement == null) {
            return false
        }
        val gameTime = world.gameTime
        if (gameTime < noWorkUntil) return false

        val timeOfDay = world.world.time
        return timeOfDay in 0..12000
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location
        val gameTime = world.gameTime
        val timeOfDay = bukkitWorld.time

        val isBoneMealTime = timeOfDay in 6000..8000

        val composterPos = SettlementPlanner.getWorkstationFor(villager)
        if (composterPos == null) {
            val center = settlement.data.center
            val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 3))
            return
        }

        if (targetCropPos != null) {
            val cropBlock = bukkitWorld.getBlockAt(targetCropPos!!.x, targetCropPos!!.y, targetCropPos!!.z)
            val ageable = cropBlock.blockData as? Ageable
            if (cropBlock.type == Material.AIR || ageable == null || ageable.age != ageable.maximumAge) {
                targetCropPos = null
            }
        }
        if (targetPlantPos != null) {
            val farmlandBlock = bukkitWorld.getBlockAt(targetPlantPos!!.x, targetPlantPos!!.y, targetPlantPos!!.z)
            if (farmlandBlock.type != Material.FARMLAND || farmlandBlock.getRelative(BlockFace.UP).type != Material.AIR) {
                targetPlantPos = null
            }
        }
        if (targetTillPos != null) {
            val dirtBlock = bukkitWorld.getBlockAt(targetTillPos!!.x, targetTillPos!!.y, targetTillPos!!.z)
            if ((dirtBlock.type != Material.DIRT && dirtBlock.type != Material.GRASS_BLOCK) || dirtBlock.getRelative(BlockFace.UP).type != Material.AIR) {
                targetTillPos = null
            }
        }
        if (targetWaterPos != null) {
            if (bukkitWorld.getBlockAt(targetWaterPos!!.x, targetWaterPos!!.y, targetWaterPos!!.z).type != Material.WATER) {
                targetWaterPos = null
                targetShorePos = null
                releaseWaterSpot(villager.uuid)
                val bobberId = activeBobbers.remove(villager.uuid)
                if (bobberId != null) {
                    val destroyPacket = WrapperPlayServerDestroyEntities(bobberId)
                    bukkitWorld.players.forEach { p ->
                        PacketEvents.getAPI().playerManager.getUser(p)?.sendPacket(destroyPacket)
                    }
                }
            }
        }

        if (targetCropPos == null && targetPlantPos == null && targetTillPos == null && targetBoneMealPos == null && targetWaterPos == null && gameTime - lastCropSearchTime > 60L) {
            lastCropSearchTime = gameTime

            val farmBoxes = SettlementPlanner.buildings[settlement.data.id]?.filter { it.type.startsWith("FARM") }?.map { it.box } ?: emptyList()
            val hasBoneMeal = settlement.villageInventory.any { it.type == Material.BONE_MEAL }

            var foundCrop: BlockPos? = null
            var foundPlant: BlockPos? = null
            var foundTill: BlockPos? = null
            var foundBoneMeal: BlockPos? = null

            scanCrops@ for (box in farmBoxes) {
                for (x in box.minX.toInt() until box.maxX.toInt()) {
                    for (z in box.minZ.toInt() until box.maxZ.toInt()) {
                        if (!bukkitWorld.isChunkLoaded(x shr 4, z shr 4)) continue

                        for (y in box.minY.toInt()..box.maxY.toInt()) {
                            val block = bukkitWorld.getBlockAt(x, y, z)
                            val type = block.type
                            val typeAbove = block.getRelative(BlockFace.UP).type

                            if (type == Material.WHEAT || type == Material.CARROTS || type == Material.POTATOES || type == Material.BEETROOTS) {
                                val data = block.blockData as? Ageable
                                if (data != null) {
                                    if (data.age == data.maximumAge) {
                                        foundCrop = BlockPos(x, y, z)
                                    } else if (foundBoneMeal == null && isBoneMealTime && hasBoneMeal) {
                                        foundBoneMeal = BlockPos(x, y, z)
                                    }
                                }
                            }

                            if (type == Material.FARMLAND && typeAbove == Material.AIR && foundPlant == null) {
                                foundPlant = BlockPos(x, y, z)
                            }

                            if ((type == Material.DIRT || type == Material.GRASS_BLOCK) && typeAbove == Material.AIR && foundTill == null) {
                                if (y <= composterPos.y && y >= composterPos.y - 2) {
                                    foundTill = BlockPos(x, y, z)
                                }
                            }

                            if (foundCrop != null && foundPlant != null && foundTill != null && foundBoneMeal != null) {
                                break@scanCrops
                            }
                        }
                    }
                }
            }

            targetCropPos = foundCrop
            targetPlantPos = foundPlant
            targetTillPos = foundTill
            targetBoneMealPos = foundBoneMeal

            if (foundCrop == null && foundPlant == null && foundTill == null && foundBoneMeal == null) {
                val cachedW = cachedWaterPos
                val cachedS = cachedShorePos

                if (cachedW != null && cachedS != null) {
                    if (bukkitWorld.isChunkLoaded(cachedW.x shr 4, cachedW.z shr 4) &&
                        bukkitWorld.getBlockAt(cachedW.x, cachedW.y, cachedW.z).type == Material.WATER) {
                        targetWaterPos = cachedW
                        targetShorePos = cachedS
                    } else {
                        cachedWaterPos = null
                        cachedShorePos = null
                    }
                }

                if (targetWaterPos == null) {
                    var foundWater: BlockPos? = null
                    var foundShore: BlockPos? = null
                    val r = 12

                    waterScan@ for (wx in -r..r) {
                        for (wz in -r..r) {
                            if (wx == 0 && wz == 0) continue
                            val px = npcLoc.blockX + wx
                            val pz = npcLoc.blockZ + wz

                            if (!bukkitWorld.isChunkLoaded(px shr 4, pz shr 4)) continue

                            val isFarmWater = farmBoxes.any { box ->
                                px >= box.minX && px <= box.maxX && pz >= box.minZ && pz <= box.maxZ
                            }
                            if (isFarmWater) continue

                            val highestY = bukkitWorld.getHighestBlockYAt(px, pz)
                            val waterSpot = BlockPos(px, highestY, pz)

                            val res = reservedWaterSpots[waterSpot]
                            if (res != null && res != villager.uuid) continue

                            var isWaterTooClose = false
                            for ((otherWater, otherUuid) in reservedWaterSpots) {
                                if (otherUuid != villager.uuid) {
                                    val dx = waterSpot.x - otherWater.x
                                    val dz = waterSpot.z - otherWater.z
                                    if (dx * dx + dz * dz <= 36) { // 6 блоков дистанции
                                        isWaterTooClose = true
                                        break
                                    }
                                }
                            }
                            if (isWaterTooClose) continue

                            val block = bukkitWorld.getBlockAt(px, highestY, pz)
                            val blockType = block.type

                            if (blockType == Material.WATER || block.isLiquid) {

                                var isLargeWaterBody = true
                                checkWaterBody@ for (dx in -2..2) {
                                    for (dz in -2..2) {
                                        val sx = px + dx
                                        val sz = pz + dz
                                        if (!bukkitWorld.isChunkLoaded(sx shr 4, sz shr 4)) {
                                            isLargeWaterBody = false
                                            break@checkWaterBody
                                        }
                                        val hY = bukkitWorld.getHighestBlockYAt(sx, sz)
                                        val bType = bukkitWorld.getBlockAt(sx, hY, sz).type

                                        if (bType != Material.WATER && bType != Material.KELP &&
                                            bType != Material.SEAGRASS && bType != Material.TALL_SEAGRASS &&
                                            bType != Material.LILY_PAD) {
                                            isLargeWaterBody = false
                                            break@checkWaterBody
                                        }
                                    }
                                }
                                if (!isLargeWaterBody) continue

                                shoreScan@ for (sx in -6..6) {
                                    for (sz in -6..6) {
                                        if (max(abs(sx), abs(sz)) in 5..6) {
                                            val tx = px + sx
                                            val tz = pz + sz

                                            if (!bukkitWorld.isChunkLoaded(tx shr 4, tz shr 4)) continue

                                            var isTooCloseToAnotherFisher = false
                                            val proposedShore = BlockPos(tx, 0, tz)

                                            for ((otherUuid, otherShore) in activeFishermenPositions) {
                                                if (otherUuid != villager.uuid) {
                                                    val dx = proposedShore.x - otherShore.x
                                                    val dz = proposedShore.z - otherShore.z
                                                    val distSq = dx * dx + dz * dz
                                                    if (distSq <= 100) {
                                                        isTooCloseToAnotherFisher = true
                                                        break
                                                    }
                                                }
                                            }
                                            if (isTooCloseToAnotherFisher) continue@shoreScan

                                            val sY = bukkitWorld.getHighestBlockYAt(tx, tz)
                                            val sBlock = bukkitWorld.getBlockAt(tx, sY, tz)
                                            val sType = sBlock.type

                                            if (sType.isSolid && sType != Material.WATER && sType != Material.LAVA) {
                                                val feetBlock = sBlock.getRelative(BlockFace.UP)
                                                val headBlock = feetBlock.getRelative(BlockFace.UP)
                                                if (feetBlock.type == Material.AIR && headBlock.type == Material.AIR) {
                                                    foundWater = waterSpot
                                                    foundShore = BlockPos(tx, sY, tz)
                                                    break@waterScan
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (foundWater != null && foundShore != null) {
                        cachedWaterPos = foundWater
                        cachedShorePos = foundShore
                        targetWaterPos = foundWater
                        targetShorePos = foundShore

                        reservedWaterSpots[foundWater] = villager.uuid
                        activeFishermenPositions[villager.uuid] = foundShore
                    }
                }
            }

            if (targetCropPos == null && targetPlantPos == null && targetTillPos == null && targetBoneMealPos == null && targetWaterPos == null) {
                noWorkUntil = gameTime + 200L
            }
        }

        // ==========================================
        // Увеличенная дистанция (12.0 sqr = ~3.4 блока)
        // Ускорено время выполнения (farmTicks >= 5 / 10)
        // ==========================================

        if (targetCropPos != null) {
            val cropBlock = bukkitWorld.getBlockAt(targetCropPos!!.x, targetCropPos!!.y, targetCropPos!!.z)
            val ageable = cropBlock.blockData as? Ageable
            if (cropBlock.type == Material.AIR || ageable == null || ageable.age != ageable.maximumAge) {
                targetCropPos = null
                return
            }

            val distSq = npcLoc.distanceSquared(Location(bukkitWorld, targetCropPos!!.x + 0.5, targetCropPos!!.y + 0.5, targetCropPos!!.z + 0.5))

            if (distSq <= 12.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetCropPos!!))

                farmTicks++
                if (farmTicks % 3 == 0) villager.swing(InteractionHand.MAIN_HAND)

                if (farmTicks >= 10) {
                    farmTicks = 0
                    val type = cropBlock.type
                    val drop = when (type) {
                        Material.WHEAT -> Material.WHEAT
                        Material.CARROTS -> Material.CARROT
                        Material.POTATOES -> Material.POTATO
                        Material.BEETROOTS -> Material.BEETROOT
                        else -> Material.WHEAT
                    }

                    val virtualInv = settlement.villageInventory
                    val amount = 1 + world.random.nextInt(3)
                    val copy = ItemStack(drop, amount)
                    var remaining = copy.amount
                    val maxStack = copy.type.maxStackSize

                    for (stored in virtualInv) {
                        if (stored.isSimilar(copy)) {
                            val space = maxStack - stored.amount
                            if (space > 0) {
                                val toAdd = minOf(space, remaining)
                                stored.amount += toAdd
                                remaining -= toAdd
                                if (remaining <= 0) break
                            }
                        }
                    }
                    if (remaining > 0) {
                        val newCopy = copy.clone()
                        newCopy.amount = remaining
                        virtualInv.add(newCopy)
                    }
                    SettlementManager.saveSettlements(world.world)

                    cropBlock.type = Material.AIR
                    bukkitWorld.playSound(cropBlock.location, Sound.BLOCK_CROP_BREAK, 1.0f, 1.0f)
                    targetCropPos = null
                }
            } else {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetCropPos!!), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetCropPos!!))
            }

        } else if (targetPlantPos != null) {
            val farmlandBlock = bukkitWorld.getBlockAt(targetPlantPos!!.x, targetPlantPos!!.y, targetPlantPos!!.z)
            val blockAbove = farmlandBlock.getRelative(BlockFace.UP)

            if (farmlandBlock.type != Material.FARMLAND || blockAbove.type != Material.AIR) {
                targetPlantPos = null
                return
            }

            val distSq = npcLoc.distanceSquared(Location(bukkitWorld, blockAbove.x + 0.5, blockAbove.y + 0.5, blockAbove.z + 0.5))

            if (distSq <= 12.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPlantPos!!))

                farmTicks++
                if (farmTicks % 3 == 0) villager.swing(InteractionHand.MAIN_HAND)

                if (farmTicks >= 5) {
                    farmTicks = 0
                    val seeds = settlement.villageInventory.find {
                        it.type == Material.WHEAT_SEEDS || it.type == Material.CARROT || it.type == Material.POTATO || it.type == Material.BEETROOT_SEEDS
                    }
                    val cropType = when (seeds?.type) {
                        Material.WHEAT_SEEDS -> Material.WHEAT
                        Material.CARROT -> Material.CARROTS
                        Material.POTATO -> Material.POTATOES
                        Material.BEETROOT_SEEDS -> Material.BEETROOTS
                        else -> Material.WHEAT
                    }

                    if (seeds != null) {
                        if (seeds.amount <= 1) settlement.villageInventory.remove(seeds) else seeds.amount -= 1
                        SettlementManager.saveSettlements(world.world)
                    }

                    blockAbove.type = cropType
                    bukkitWorld.playSound(blockAbove.location, Sound.ITEM_CROP_PLANT, 1.0f, 1.0f)
                    targetPlantPos = null
                }
            } else {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPlantPos!!), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPlantPos!!))
            }

        } else if (targetTillPos != null) {
            val dirtBlock = bukkitWorld.getBlockAt(targetTillPos!!.x, targetTillPos!!.y, targetTillPos!!.z)
            val blockAbove = dirtBlock.getRelative(BlockFace.UP)

            if ((dirtBlock.type != Material.DIRT && dirtBlock.type != Material.GRASS_BLOCK) || blockAbove.type != Material.AIR) {
                targetTillPos = null
                return
            }

            val distSq = npcLoc.distanceSquared(Location(bukkitWorld, dirtBlock.x + 0.5, dirtBlock.y + 0.5, dirtBlock.z + 0.5))

            if (distSq <= 12.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetTillPos!!))

                val expectedTool = CraftItemStack.asNMSCopy(ItemStack(Material.IRON_HOE))
                if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, expectedTool)) {
                    villager.setItemInHand(InteractionHand.MAIN_HAND, expectedTool)
                }

                farmTicks++
                if (farmTicks % 3 == 0) villager.swing(InteractionHand.MAIN_HAND)

                if (farmTicks >= 5) {
                    farmTicks = 0
                    dirtBlock.type = Material.FARMLAND
                    bukkitWorld.playSound(dirtBlock.location, Sound.ITEM_HOE_TILL, 1.0f, 1.0f)
                    targetTillPos = null
                    villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
                }
            } else {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetTillPos!!), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetTillPos!!))
            }

        } else if (targetBoneMealPos != null) {
            val cropBlock = bukkitWorld.getBlockAt(targetBoneMealPos!!.x, targetBoneMealPos!!.y, targetBoneMealPos!!.z)
            val ageable = cropBlock.blockData as? org.bukkit.block.data.Ageable

            if (cropBlock.type == Material.AIR || ageable == null || ageable.age == ageable.maximumAge || !isBoneMealTime || !settlement.villageInventory.any { it.type == Material.BONE_MEAL }) {
                targetBoneMealPos = null
                return
            }

            val distSq = npcLoc.distanceSquared(Location(bukkitWorld, cropBlock.x + 0.5, cropBlock.y + 0.5, cropBlock.z + 0.5))

            if (distSq <= 12.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetBoneMealPos!!))

                val expectedTool = CraftItemStack.asNMSCopy(ItemStack(Material.BONE_MEAL))
                if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, expectedTool)) {
                    villager.setItemInHand(InteractionHand.MAIN_HAND, expectedTool)
                }

                farmTicks++
                if (farmTicks % 3 == 0) villager.swing(InteractionHand.MAIN_HAND)

                if (farmTicks >= 10) {
                    farmTicks = 0

                    val boneMeal = settlement.villageInventory.find { it.type == Material.BONE_MEAL }
                    if (boneMeal != null) {
                        if (boneMeal.amount <= 1) settlement.villageInventory.remove(boneMeal) else boneMeal.amount -= 1
                        SettlementManager.saveSettlements(world.world)

                        ageable.age = minOf(ageable.maximumAge, ageable.age + 2 + world.random.nextInt(3))
                        cropBlock.blockData = ageable

                        bukkitWorld.playSound(cropBlock.location, Sound.ITEM_BONE_MEAL_USE, 1.0f, 1.0f)
                        bukkitWorld.spawnParticle(Particle.HAPPY_VILLAGER, cropBlock.location.add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3)
                    }
                    targetBoneMealPos = null
                    villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
                }
            } else {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetBoneMealPos!!), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetBoneMealPos!!))
            }

        } else if (targetWaterPos != null && targetShorePos != null) {
            val shoreLoc = Location(bukkitWorld, targetShorePos!!.x + 0.5, targetShorePos!!.y + 1.0, targetShorePos!!.z + 0.5)
            val distSq = npcLoc.distanceSquared(shoreLoc)

            if (distSq > 4.0) {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetShorePos!!), speedModifier, 1))
            } else {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetWaterPos!!))

                val fishingRod = CraftItemStack.asNMSCopy(ItemStack(Material.FISHING_ROD))
                if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, fishingRod)) {
                    villager.setItemInHand(InteractionHand.MAIN_HAND, fishingRod)
                }

                val waterLoc = Location(bukkitWorld, targetWaterPos!!.x + 0.5, targetWaterPos!!.y + 0.8, targetWaterPos!!.z + 0.5)
                val villagerEye = (villager.bukkitEntity as org.bukkit.entity.Villager).eyeLocation

                farmTicks++
                if (farmTicks == 10) {
                    villager.swing(InteractionHand.MAIN_HAND)

                    val bobberId = 1_500_000 + world.random.nextInt(100_000)
                    activeBobbers[villager.uuid] = bobberId

                    val dir = waterLoc.toVector().subtract(villagerEye.toVector())
                    val dist = dir.length()

                    val velX = dir.x * 0.18
                    val velY = 0.25 + (dist * 0.04)
                    val velZ = dir.z * 0.18

                    val velocity = com.github.retrooper.packetevents.util.Vector3d(velX, velY, velZ)

                    val packetLoc = com.github.retrooper.packetevents.protocol.world.Location(
                        villagerEye.x,
                        villagerEye.y - 0.2,
                        villagerEye.z,
                        villagerEye.yaw,
                        villagerEye.pitch
                    )

                    val spawnPacket = WrapperPlayServerSpawnEntity(
                        bobberId,
                        UUID.randomUUID(),
                        EntityTypes.FISHING_BOBBER,
                        packetLoc,
                        villagerEye.yaw,
                        villager.bukkitEntity.entityId,
                        velocity
                    )

                    val nearbyPlayers = bukkitWorld.players.filter {
                        it.world == bukkitWorld && it.location.distanceSquared(npcLoc) <= 2500.0
                    }

                    for (player in nearbyPlayers) {
                        val user = com.github.retrooper.packetevents.PacketEvents.getAPI().playerManager.getUser(player)
                        user?.sendPacket(spawnPacket)
                    }

                    val sessionBiteTick = 120 + world.random.nextInt(120)
                    biteTicks[villager.uuid] = sessionBiteTick
                    fishAngles[villager.uuid] = world.random.nextDouble() * 2 * Math.PI
                }

                val currentBiteTick = biteTicks[villager.uuid] ?: 200
                val currentFishAngle = fishAngles[villager.uuid] ?: 0.0

                if (farmTicks > 10 && farmTicks < currentBiteTick) {
                    val ticksLeft = currentBiteTick - farmTicks
                    if (ticksLeft <= 50) {
                        val progress = (50 - ticksLeft).toDouble() / 50.0
                        val startDist = 3.5
                        val currentDist = startDist * (1.0 - progress)

                        val fx = waterLoc.x + Math.cos(currentFishAngle) * currentDist
                        val fz = waterLoc.z + Math.sin(currentFishAngle) * currentDist
                        val particleLoc = Location(bukkitWorld, fx, waterLoc.y + 0.1, fz)

                        bukkitWorld.spawnParticle(Particle.BUBBLE, particleLoc, 1, 0.05, 0.0, 0.05, 0.0)
                        if (farmTicks % 3 == 0) {
                            bukkitWorld.spawnParticle(Particle.FISHING, particleLoc, 2, 0.1, 0.0, 0.1, 0.01)
                        }
                    } else {
                        if (farmTicks % 30 == 0) {
                            bukkitWorld.spawnParticle(Particle.FISHING, waterLoc, 3, 0.15, 0.0, 0.15, 0.0)
                        }
                    }
                }

                if (farmTicks == currentBiteTick) {
                    bukkitWorld.playSound(waterLoc, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.2f, 0.8f + world.random.nextFloat() * 0.4f)
                    bukkitWorld.spawnParticle(Particle.SPLASH, waterLoc, 25, 0.2, 0.1, 0.2, 0.1)
                    bukkitWorld.spawnParticle(Particle.BUBBLE, waterLoc, 15, 0.15, 0.1, 0.15, 0.2)
                    bukkitWorld.spawnParticle(Particle.FISHING, waterLoc, 10, 0.3, 0.0, 0.3, 0.05)

                    villager.swing(InteractionHand.MAIN_HAND)
                }

                if (farmTicks >= currentBiteTick + 15) {
                    farmTicks = 0
                    biteTicks.remove(villager.uuid)
                    fishAngles.remove(villager.uuid)

                    val bobberId = activeBobbers.remove(villager.uuid)
                    if (bobberId != null) {
                        val destroyPacket = WrapperPlayServerDestroyEntities(bobberId)
                        val nearbyPlayers = bukkitWorld.players.filter {
                            it.world == bukkitWorld && it.location.distanceSquared(npcLoc) <= 2500.0
                        }
                        for (player in nearbyPlayers) {
                            val user = com.github.retrooper.packetevents.PacketEvents.getAPI().playerManager.getUser(player)
                            user?.sendPacket(destroyPacket)
                        }
                    }

                    bukkitWorld.playSound(villagerEye, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 1.0f)

                    val fish = when (world.random.nextInt(10)) {
                        in 0..5 -> Material.COD
                        in 6..8 -> Material.SALMON
                        else -> Material.PUFFERFISH
                    }

                    val directionToVillager = villagerEye.toVector().subtract(waterLoc.toVector()).normalize()
                    bukkitWorld.spawnParticle(Particle.FALLING_WATER, waterLoc.clone().add(0.0, 0.2, 0.0), 10, directionToVillager.x * 0.2, directionToVillager.y * 0.2 + 0.3, directionToVillager.z * 0.2, 0.3)

                    val virtualInv = settlement.villageInventory
                    val copy = ItemStack(fish, 1)
                    val maxStack = copy.type.maxStackSize
                    var remaining = copy.amount

                    for (stored in virtualInv) {
                        if (stored.isSimilar(copy)) {
                            val space = maxStack - stored.amount
                            if (space > 0) {
                                val toAdd = minOf(space, remaining)
                                stored.amount += toAdd
                                remaining -= toAdd
                                if (remaining <= 0) break
                            }
                        }
                    }
                    if (remaining > 0) {
                        virtualInv.add(copy)
                    }
                    SettlementManager.saveSettlements(bukkitWorld)
                }
            }
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
        targetCropPos = null
        targetPlantPos = null
        targetTillPos = null
        targetBoneMealPos = null
        targetWaterPos = null
        targetShorePos = null
        biteTicks.remove(villager.uuid)
        fishAngles.remove(villager.uuid)
        villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)

        releaseWaterSpot(villager.uuid)
        val bobberId = activeBobbers.remove(villager.uuid)
        if (bobberId != null) {
            val destroyPacket = WrapperPlayServerDestroyEntities(bobberId)
            val nearbyPlayers = world.world.players.filter {
                it.world == world.world && it.location.distanceSquared(villager.bukkitEntity.location) <= 2500.0
            }
            for (player in nearbyPlayers) {
                val user = com.github.retrooper.packetevents.PacketEvents.getAPI().playerManager.getUser(player)
                user?.sendPacket(destroyPacket)
            }
        }
    }
}
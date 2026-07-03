package vx.sv.nms.entity.ai.profession

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
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.ItemStack
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.nms.entity.HumanoidVillager
import vx.sv.nms.entity.ai.construct.SettlementPlanner
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

class FishermanBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ),
    200
) {
    private var farmTicks = 0
    private var lastWaterSearchTime = 0L
    private var noWorkUntil = 0L

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
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.FISHERMAN || villager.settlement == null) {
            return false
        }
        val gameTime = world.gameTime
        if (gameTime < noWorkUntil) return false

        val timeOfDay = world.world.time
        return timeOfDay in 0..12000
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.FISHERMAN || villager.settlement == null) {
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

        val barrelPos = SettlementPlanner.getWorkstationFor(villager)
        if (barrelPos == null) {
            val center = settlement.data.center
            val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 3))
            return
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

        if (targetWaterPos == null && gameTime - lastWaterSearchTime > 60L) {
            lastWaterSearchTime = gameTime

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
                val r = 16

                waterScan@ for (wx in -r..r) {
                    for (wz in -r..r) {
                        if (wx == 0 && wz == 0) continue
                        val px = npcLoc.blockX + wx
                        val pz = npcLoc.blockZ + wz

                        if (!bukkitWorld.isChunkLoaded(px shr 4, pz shr 4)) continue

                        val highestY = bukkitWorld.getHighestBlockYAt(px, pz)
                        val waterSpot = BlockPos(px, highestY, pz)

                        val res = reservedWaterSpots[waterSpot]
                        if (res != null && res != villager.uuid) continue

                        var isWaterTooClose = false
                        for ((otherWater, otherUuid) in reservedWaterSpots) {
                            if (otherUuid != villager.uuid) {
                                val dx = waterSpot.x - otherWater.x
                                val dz = waterSpot.z - otherWater.z
                                if (dx * dx + dz * dz <= 36) {
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

        if (targetWaterPos == null) {
            noWorkUntil = gameTime + 200L
            return
        }

        if (targetWaterPos != null && targetShorePos != null) {
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
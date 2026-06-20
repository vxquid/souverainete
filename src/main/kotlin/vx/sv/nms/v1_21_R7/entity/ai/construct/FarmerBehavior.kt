package vx.sv.nms.v1_21_R7.entity.ai

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
import org.bukkit.Sound
import org.bukkit.block.data.Ageable
import org.bukkit.inventory.ItemStack
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import vx.sv.nms.v1_21_R7.entity.ai.construct.SettlementPlanner

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

    @Volatile private var isScanningCrops = false

    private var targetCropPos: BlockPos? = null
    private var targetPlantPos: BlockPos? = null

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.FARMER || villager.settlement == null) {
            return false
        }
        val timeOfDay = world.world.time
        return timeOfDay in 0..12000
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.FARMER || villager.settlement == null) {
            return false
        }
        val timeOfDay = world.world.time
        return timeOfDay in 0..12000
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location
        val gameTime = world.gameTime

        val composterPos = SettlementPlanner.getWorkstationFor(villager)
        if (composterPos == null) {
            val center = settlement.data.center
            val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 3))
            return
        }

        // АСИНХРОННЫЙ ПОИСК УРОЖАЯ ЧЕРЕЗ CHUNK SNAPSHOTS
        if (targetCropPos == null && targetPlantPos == null && gameTime - lastCropSearchTime > 60L && !isScanningCrops) {
            lastCropSearchTime = gameTime
            isScanningCrops = true

            val r = 12
            val minX = composterPos.x - r
            val maxX = composterPos.x + r
            val minZ = composterPos.z - r
            val maxZ = composterPos.z + r

            val minCX = minX shr 4
            val maxCX = maxX shr 4
            val minCZ = minZ shr 4
            val maxCZ = maxZ shr 4

            // 1. Берем слепки чанков на главном потоке
            val snapshots = mutableMapOf<Pair<Int, Int>, org.bukkit.ChunkSnapshot>()
            for (cx in minCX..maxCX) {
                for (cz in minCZ..maxCZ) {
                    if (bukkitWorld.isChunkLoaded(cx, cz)) {
                        snapshots[Pair(cx, cz)] = bukkitWorld.getChunkAt(cx, cz).chunkSnapshot
                    }
                }
            }

            val yMin = composterPos.y - 3
            val yMax = composterPos.y + 3

            // 2. Ищем блоки асинхронно
            plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                var foundCrop: BlockPos? = null
                var foundFarmland: BlockPos? = null

                scan@ for (x in minX..maxX) {
                    for (z in minZ..maxZ) {
                        val cx = x shr 4
                        val cz = z shr 4
                        val snap = snapshots[Pair(cx, cz)] ?: continue
                        val localX = x and 15
                        val localZ = z and 15

                        for (y in yMin..yMax) {
                            val type = snap.getBlockType(localX, y, localZ)
                            if (type == Material.WHEAT || type == Material.CARROTS || type == Material.POTATOES || type == Material.BEETROOTS) {
                                val data = snap.getBlockData(localX, y, localZ) as? org.bukkit.block.data.Ageable
                                if (data != null && data.age == data.maximumAge) {
                                    foundCrop = BlockPos(x, y, z)
                                    break@scan
                                }
                            }
                            if (type == Material.FARMLAND && foundFarmland == null) {
                                val typeAbove = snap.getBlockType(localX, y + 1, localZ)
                                if (typeAbove == Material.AIR) {
                                    foundFarmland = BlockPos(x, y, z)
                                }
                            }
                        }
                    }
                }

                // 3. Возвращаем координаты в память
                plugin.server.scheduler.runTask(plugin, Runnable {
                    targetCropPos = foundCrop
                    targetPlantPos = foundFarmland
                    isScanningCrops = false
                })
            })
        }

        // Если идет сканирование, просто ждем
        if (isScanningCrops) return

        if (targetCropPos != null) {
            val cropBlock = bukkitWorld.getBlockAt(targetCropPos!!.x, targetCropPos!!.y, targetCropPos!!.z)
            val ageable = cropBlock.blockData as? Ageable

            // Валидация перед сбором
            if (cropBlock.type == Material.AIR || ageable == null || ageable.age != ageable.maximumAge) {
                targetCropPos = null
                return
            }

            val distSq = npcLoc.distanceSquared(Location(bukkitWorld, targetCropPos!!.x + 0.5, targetCropPos!!.y + 0.5, targetCropPos!!.z + 0.5))

            if (distSq <= 4.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetCropPos!!))

                farmTicks++
                if (farmTicks % 5 == 0) villager.swing(InteractionHand.MAIN_HAND)

                if (farmTicks >= 15) {
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
            val blockAbove = farmlandBlock.getRelative(org.bukkit.block.BlockFace.UP)

            // Валидация
            if (farmlandBlock.type != Material.FARMLAND || blockAbove.type != Material.AIR) {
                targetPlantPos = null
                return
            }

            val distSq = npcLoc.distanceSquared(Location(bukkitWorld, blockAbove.x + 0.5, blockAbove.y + 0.5, blockAbove.z + 0.5))

            if (distSq <= 4.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPlantPos!!))

                farmTicks++
                if (farmTicks % 5 == 0) villager.swing(InteractionHand.MAIN_HAND)

                if (farmTicks >= 15) {
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
                        if (seeds.amount <= 1) {
                            settlement.villageInventory.remove(seeds)
                        } else {
                            seeds.amount -= 1
                        }
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
        } else {
            val distSq = npcLoc.distanceSquared(Location(bukkitWorld, composterPos.x + 0.5, composterPos.y + 0.5, composterPos.z + 0.5))
            val targetPos = BlockPos(composterPos.x, composterPos.y, composterPos.z)

            if (distSq <= 6.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))

                if (world.random.nextInt(60) == 0) {
                    villager.swing(InteractionHand.MAIN_HAND)
                    bukkitWorld.playSound(Location(bukkitWorld, composterPos.x + 0.5, composterPos.y + 0.5, composterPos.z + 0.5), Sound.BLOCK_COMPOSTER_FILL, 1.0f, 1.0f)
                }
            } else {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
        targetCropPos = null
        targetPlantPos = null
    }
}
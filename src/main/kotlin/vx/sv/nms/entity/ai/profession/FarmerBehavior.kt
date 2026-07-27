package vx.sv.gameplay.humanoid

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
        val settlementId = settlement.data.id

        // Фильтруем фермерские зоны — берём только те, у которых завершено строительство
        val farmBoxes = SettlementPlanner.buildings[settlementId]?.filter { record ->
            if (!record.type.startsWith("FARM")) return@filter false

            // Проверяем, нет ли этой фермы среди отложенных или выполняющихся задач застройки
            val isPending = SettlementPlanner.pendingJobs[settlementId]?.any { it.jobId == record.jobId } ?: false
            val isActive = SettlementPlanner.activeJobs[settlementId]?.any { it.jobId == record.jobId } ?: false

            !isPending && !isActive
        }?.map { it.box } ?: emptyList()

        if (farmBoxes.isEmpty()) {
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

        if (targetCropPos == null && targetPlantPos == null && targetTillPos == null && targetBoneMealPos == null && gameTime - lastCropSearchTime > 60L) {
            lastCropSearchTime = gameTime

            val hasBoneMeal = settlement.villageInventory.any { it.type == Material.BONE_MEAL }

            var foundCrop: BlockPos? = null
            var foundPlant: BlockPos? = null
            var foundTill: BlockPos? = null
            var foundBoneMeal: BlockPos? = null

            val nmsLevel = (bukkitWorld as org.bukkit.craftbukkit.CraftWorld).handle
            val mutablePos = BlockPos.MutableBlockPos()
            val mutablePosAbove = BlockPos.MutableBlockPos()

            scanCrops@ for (box in farmBoxes) {
                val minX = box.minX.toInt()
                val maxX = box.maxX.toInt()
                val minZ = box.minZ.toInt()
                val maxZ = box.maxZ.toInt()
                val minY = box.minY.toInt()
                val maxY = box.maxY.toInt()

                for (x in minX until maxX) {
                    for (z in minZ until maxZ) {
                        if (!bukkitWorld.isChunkLoaded(x shr 4, z shr 4)) continue

                        for (y in minY..maxY) {
                            mutablePos.set(x, y, z)
                            mutablePosAbove.set(x, y + 1, z)

                            val state = nmsLevel.getBlockState(mutablePos)
                            val type = org.bukkit.craftbukkit.util.CraftMagicNumbers.getMaterial(state.block)
                            val typeAbove = org.bukkit.craftbukkit.util.CraftMagicNumbers.getMaterial(nmsLevel.getBlockState(mutablePosAbove).block)

                            if (type == Material.WHEAT || type == Material.CARROTS || type == Material.POTATOES || type == Material.BEETROOTS) {
                                val block = bukkitWorld.getBlockAt(x, y, z)
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
                                foundTill = BlockPos(x, y, z)
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
        }

        if (targetCropPos == null && targetPlantPos == null && targetTillPos == null && targetBoneMealPos == null) {
            noWorkUntil = gameTime + 200L
            return
        }

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
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
        targetCropPos = null
        targetPlantPos = null
        targetTillPos = null
        targetBoneMealPos = null
        villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
    }
}
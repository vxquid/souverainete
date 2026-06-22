package vx.sv.nms.entity.ai.profession

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
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.nms.entity.HumanoidVillager
import vx.sv.nms.entity.ai.construct.SettlementPlanner
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
        if (bukkitVillager.profession != Villager.Profession.TOOLSMITH || villager.settlement == null) {
            return false
        }
        return world.world.time in 0..12000
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        if (bukkitVillager.profession != Villager.Profession.TOOLSMITH || villager.settlement == null) {
            return false
        }
        return world.world.time in 0..12000
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val center = settlement.data.center
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location

        // Instant workstation cache read
        val tablePos = SettlementPlanner.Companion.getWorkstationFor(villager)

        if (tablePos == null) {
            // Remove the miner PDC tag if the villager has lost their workstation or structure
            val bukkitVillager = villager.bukkitEntity as? Villager
            if (bukkitVillager != null) {
                bukkitVillager.persistentDataContainer.remove(NamespacedKey(plugin, "is_miner"))
            }
            val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 3))
            return
        }

        // Dynamically assign the miner PDC tag if they are active at the mine workstation
        val bukkitVillager = villager.bukkitEntity as? Villager
        if (bukkitVillager != null) {
            val minerKey = NamespacedKey(plugin, "is_miner")
            if (!bukkitVillager.persistentDataContainer.has(minerKey, PersistentDataType.BYTE)) {
                bukkitVillager.persistentDataContainer.set(minerKey, PersistentDataType.BYTE, 1.toByte())
            }
        }

        // FIXED: Smart search for stone (taking into account that the mine could be rotated upon spawning)
        var stonePos: BlockPos? = null
        val adjacentOffsets = listOf(BlockPos(1, 0, 0), BlockPos(-1, 0, 0), BlockPos(0, 0, 1), BlockPos(0, 0, -1))

        for (offset in adjacentOffsets) {
            val bp = BlockPos(tablePos.x + offset.x, tablePos.y, tablePos.z + offset.z)
            if (bukkitWorld.getBlockAt(bp.x, bp.y, bp.z).type == Material.STONE) {
                stonePos = bp
                break
            }
        }

        // If there is no stone (e.g. just broken), look for an air block next to the table
        if (stonePos == null) {
            for (offset in adjacentOffsets) {
                val bp = BlockPos(tablePos.x + offset.x, tablePos.y, tablePos.z + offset.z)
                if (bukkitWorld.getBlockAt(bp.x, bp.y, bp.z).type == Material.AIR) {
                    stonePos = bp
                    break
                }
            }
        }

        // Safety fallback
        if (stonePos == null) {
            stonePos = BlockPos(tablePos.x, tablePos.y, tablePos.z + 1)
        }

        val stoneBlock = bukkitWorld.getBlockAt(stonePos.x, stonePos.y, stonePos.z)
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

            val pickaxe = CraftItemStack.asNMSCopy(ItemStack(Material.IRON_PICKAXE))
            if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, pickaxe)) {
                villager.setItemInHand(InteractionHand.MAIN_HAND, pickaxe)
            }

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

                val dropMaterial = when (world.random.nextInt(100)) {
                    in 0..4 -> Material.EMERALD
                    in 5..14 -> Material.RAW_GOLD
                    in 15..34 -> Material.RAW_IRON
                    in 35..59 -> Material.COAL
                    in 60..69 -> Material.LAPIS_LAZULI
                    else -> Material.COBBLESTONE
                }

                val amount = when (dropMaterial) {
                    Material.COBBLESTONE, Material.COAL -> 2 + world.random.nextInt(3)
                    else -> 1 + world.random.nextInt(3)
                }

                // FIXED: Direct adding to the village inventory (saving TPS on physical items)
                val virtualInv = settlement.villageInventory
                val copy = ItemStack(dropMaterial, amount)
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
                while (remaining > 0) {
                    val newCopy = copy.clone()
                    val toAdd = minOf(maxStack, remaining)
                    newCopy.amount = toAdd
                    virtualInv.add(newCopy)
                    remaining -= toAdd
                }
            }
        } else {
            val targetPos = BlockPos(stonePos.x, stonePos.y, stonePos.z)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
            villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))

            val pickaxe = CraftItemStack.asNMSCopy(ItemStack(Material.IRON_PICKAXE))
            if (!net.minecraft.world.item.ItemStack.matches(villager.mainHandItem, pickaxe)) {
                villager.setItemInHand(InteractionHand.MAIN_HAND, pickaxe)
            }
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}
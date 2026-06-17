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
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
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

    // ИСПРАВЛЕНО: Кэширование рабочей станции для защиты от ежетикового спама поиска
    private var cachedTablePos: BlockPos? = null
    private var lastSearchTime = 0L

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        if (bukkitVillager.profession != Villager.Profession.TOOLSMITH || villager.settlement == null) {
            return false
        }

        val timeOfDay = world.world.time
        return timeOfDay in 2000..9000
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        if (bukkitVillager.profession != Villager.Profession.TOOLSMITH || villager.settlement == null) {
            return false
        }

        val timeOfDay = world.world.time
        return timeOfDay in 2000..9000
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val center = settlement.data.center
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location
        val gameTime = world.gameTime

        // Проверяем актуальность кэша стола
        var tablePos = cachedTablePos
        if (tablePos != null) {
            val chunkX = tablePos.x shr 4
            val chunkZ = tablePos.z shr 4
            if (!bukkitWorld.isChunkLoaded(chunkX, chunkZ) ||
                bukkitWorld.getBlockAt(tablePos.x, tablePos.y, tablePos.z).type != Material.SMITHING_TABLE) {
                tablePos = null
                cachedTablePos = null
            }
        }

        // ИСПРАВЛЕНО: Поиск кузнечного стола выполняется максимум один раз в 10 секунд (200 тиков)
        // и только в уже загруженных чанках во избежание фризов основного потока.
        if (tablePos == null && gameTime - lastSearchTime > 200L) {
            lastSearchTime = gameTime
            val r = 35
            searchLoop@ for (cx in -r..r) {
                for (cz in -r..r) {
                    val px = center.blockX + cx
                    val pz = center.blockZ + cz

                    if (!bukkitWorld.isChunkLoaded(px shr 4, pz shr 4)) continue

                    for (cy in -12..12) {
                        val py = center.blockY + cy
                        val block = bukkitWorld.getBlockAt(px, py, pz)
                        if (block.type == Material.SMITHING_TABLE) {
                            tablePos = BlockPos(px, py, pz)
                            cachedTablePos = tablePos
                            break@searchLoop
                        }
                    }
                }
            }
        }

        if (tablePos == null) {
            val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 3))
            return
        }

        val stonePos = BlockPos(tablePos.x, tablePos.y, tablePos.z + 1)
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

            villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.IRON_PICKAXE)))

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

                val random = world.random
                val roll = random.nextInt(100)

                val dropMaterial = when (roll) {
                    in 0..4 -> Material.EMERALD
                    in 5..14 -> Material.RAW_GOLD
                    in 15..34 -> Material.RAW_IRON
                    in 35..59 -> Material.COAL
                    in 60..69 -> Material.LAPIS_LAZULI
                    else -> Material.COBBLESTONE
                }

                val amount = when (dropMaterial) {
                    Material.COBBLESTONE, Material.COAL -> 2 + random.nextInt(3)
                    else -> 1 + random.nextInt(3)
                }

                bukkitWorld.dropItemNaturally(stoneBlock.location.add(0.5, 0.5, 0.5), ItemStack(dropMaterial, amount))
            }
        } else {
            val targetPos = BlockPos(stonePos.x, stonePos.y, stonePos.z)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
            villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))

            villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.IRON_PICKAXE)))
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}
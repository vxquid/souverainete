package vx.sv.nms.entity.ai

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import org.bukkit.Location
import org.bukkit.block.data.type.Bed
import vx.sv.nms.entity.HumanoidVillager
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class SleepBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ),
    200
) {
    companion object {
        // Карта зарезервированных кроватей (BlockPos -> UUID жителя)
        private val reservedBeds = ConcurrentHashMap<BlockPos, UUID>()

        fun releaseBed(villagerUuid: UUID) {
            val iterator = reservedBeds.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value == villagerUuid) {
                    iterator.remove()
                }
            }
        }
    }

    private var targetBedPos: BlockPos? = null

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val time = world.world.time
        // Время сна в Minecraft: с 13000 (закат) до 23000 тиков (рассвет)
        if (time < 13000 || time > 23000) {
            if (villager.isSleeping) {
                villager.stopSleeping()
                releaseBed(villager.uuid)
            }
            return false
        }
        return villager.settlement != null
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val timeOfDay = world.world.time
        if (timeOfDay < 13000 || timeOfDay > 23000) {
            if (villager.isSleeping) {
                villager.stopSleeping()
                releaseBed(villager.uuid)
            }
            return false
        }
        return villager.settlement != null
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        if (villager.isSleeping) {
            return
        }

        val npcLoc = villager.bukkitEntity.location
        val bukkitWorld = world.world

        // Ищем свободную кровать в радиусе 24 блоков, если цель еще не выбрана
        var bedPos = targetBedPos
        if (bedPos == null) {
            val r = 24
            searchLoop@ for (cx in -r..r) {
                for (cz in -r..r) {
                    val px = npcLoc.blockX + cx
                    val pz = npcLoc.blockZ + cz
                    val py = npcLoc.blockY

                    for (cy in -6..6) {
                        val absY = py + cy
                        val block = bukkitWorld.getBlockAt(px, absY, pz)
                        if (block.type.name.endsWith("_BED")) {
                            val bedData = block.blockData as? Bed ?: continue
                            // Проверяем только изголовье кровати (HEAD), так как на него ложится сущность
                            if (bedData.part != Bed.Part.HEAD) continue

                            val bp = BlockPos(px, absY, pz)
                            val res = reservedBeds[bp]
                            if (res == null || res == villager.uuid) {
                                reservedBeds[bp] = villager.uuid
                                bedPos = bp
                                targetBedPos = bp
                                break@searchLoop
                            }
                        }
                    }
                }
            }
        }

        if (bedPos == null) {
            // Если кроватей нет — отправляем жителя спать в центр (к колоколу)
            val settlement = villager.settlement ?: return
            val center = settlement.data.center
            val bellPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            if (npcLoc.distanceSquared(center) > 16.0) {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(bellPos), speedModifier, 3))
            }
            return
        }

        val distSq = npcLoc.distanceSquared(Location(bukkitWorld, bedPos.x.toDouble() + 0.5, bedPos.y.toDouble() + 0.5, bedPos.z.toDouble() + 0.5))

        if (distSq <= 4.0) {
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            try {
                villager.startSleeping(bedPos)
            } catch (e: Exception) {
                // Фоллбек на случай системных заклиниваний
                villager.pose = net.minecraft.world.entity.Pose.SLEEPING
            }
        } else {
            // Ведем жителя к кровати
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(bedPos), speedModifier, 1))
            villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(bedPos))
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        if (villager.isSleeping) {
            villager.stopSleeping()
        }
        releaseBed(villager.uuid)
        targetBedPos = null
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}
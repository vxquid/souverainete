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
        if (time < 13000 || time > 23000) {
            if (villager.isSleeping) {
                villager.stopSleeping()
            }
            villager.pose = net.minecraft.world.entity.Pose.STANDING
            releaseBed(villager.uuid)
            return false
        }

        if (villager.brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            if (villager.isSleeping) {
                villager.stopSleeping()
            }
            villager.pose = net.minecraft.world.entity.Pose.STANDING
            releaseBed(villager.uuid)
            return false
        }

        return villager.settlement != null
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val timeOfDay = world.world.time
        if (timeOfDay < 13000 || timeOfDay > 23000) {
            if (villager.isSleeping) {
                villager.stopSleeping()
            }
            villager.pose = net.minecraft.world.entity.Pose.STANDING
            releaseBed(villager.uuid)
            return false
        }

        if (villager.brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
            if (villager.isSleeping) {
                villager.stopSleeping()
            }
            villager.pose = net.minecraft.world.entity.Pose.STANDING
            releaseBed(villager.uuid)
            return false
        }

        return villager.settlement != null
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        if (villager.isSleeping) {
            if (villager.brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                villager.stopSleeping()
                villager.pose = net.minecraft.world.entity.Pose.STANDING
                releaseBed(villager.uuid)
                return
            }

            if (villager.navigation.isInProgress) {
                villager.navigation.stop()
            }
            if (villager.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            }
            if (villager.brain.hasMemoryValue(MemoryModuleType.LOOK_TARGET)) {
                villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
            }
            return
        }

        val npcLoc = villager.bukkitEntity.location
        val bukkitWorld = world.world

        var bedPos = targetBedPos
        if (bedPos == null) {
            val settlement = villager.settlement
            if (settlement != null) {
                // Сортируем кровати по удаленности от жителя, чтобы выбрать ближайшую
                val sortedBeds = settlement.cachedBeds.sortedBy { bp ->
                    val loc = Location(bukkitWorld, bp.x + 0.5, bp.y + 0.5, bp.z + 0.5)
                    npcLoc.distanceSquared(loc)
                }

                // Ищем первую свободную или зарезервированную этим же жителем кровать
                val availableBed = sortedBeds.find { bp ->
                    val res = reservedBeds[bp]
                    res == null || res == villager.uuid
                }

                if (availableBed != null) {
                    reservedBeds[availableBed] = villager.uuid
                    bedPos = availableBed
                    targetBedPos = availableBed
                }
            }
        }

        if (bedPos == null) {
            val settlement = villager.settlement ?: return
            val center = settlement.data.center
            val bellPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            if (npcLoc.distanceSquared(center) > 16.0) {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(bellPos), speedModifier, 3))
            }
            return
        }

        val distSq = npcLoc.distanceSquared(Location(bukkitWorld, bedPos.x + 0.5, bedPos.y + 0.5, bedPos.z + 0.5))

        if (distSq <= 4.0) {
            villager.navigation.stop()
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)

            val preciseBedLoc = Location(bukkitWorld, bedPos.x + 0.5, bedPos.y + 0.2, bedPos.z + 0.5, npcLoc.yaw, npcLoc.pitch)
            villager.bukkitEntity.teleport(preciseBedLoc)

            try {
                villager.startSleeping(bedPos)
            } catch (e: Exception) {
                villager.pose = net.minecraft.world.entity.Pose.SLEEPING
            }
        } else {
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(bedPos), speedModifier, 1))
            villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(bedPos))
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        if (villager.isSleeping) {
            villager.stopSleeping()
        }
        villager.pose = net.minecraft.world.entity.Pose.STANDING
        releaseBed(villager.uuid)
        targetBedPos = null
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}
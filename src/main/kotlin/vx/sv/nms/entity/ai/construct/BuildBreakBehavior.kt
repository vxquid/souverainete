package vx.sv.nms.entity.ai.construct

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import vx.sv.nms.entity.HumanoidVillager

class BuildBreakBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT
    ),
    1200
) {

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        val prof = bukkitVillager.profession

        // ИСПРАВЛЕНО: Шахтеры освобождаются от перекуров
        if (prof == org.bukkit.entity.Villager.Profession.FARMER ||
            prof == org.bukkit.entity.Villager.Profession.SHEPHERD ||
            prof == org.bukkit.entity.Villager.Profession.BUTCHER ||
            prof == org.bukkit.entity.Villager.Profession.TOOLSMITH) {
            return false
        }

        return world.gameTime < villager.buildBreakUntilTime && villager.settlement != null
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        return world.gameTime < villager.buildBreakUntilTime && villager.settlement != null
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bellLoc = settlement.data.center
        val bellPos = BlockPos(bellLoc.blockX, bellLoc.blockY, bellLoc.blockZ)

        villager.brain.setMemory(
            MemoryModuleType.WALK_TARGET,
            WalkTarget(BlockPosTracker(bellPos), speedModifier, 3)
        )
        villager.brain.setMemory(
            MemoryModuleType.LOOK_TARGET,
            BlockPosTracker(bellPos)
        )
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bellLoc = settlement.data.center
        val bellPos = BlockPos(bellLoc.blockX, bellLoc.blockY, bellLoc.blockZ)

        val npcPos = villager.blockPosition()
        val distSqr = npcPos.distSqr(bellPos)

        if (distSqr <= 16.0) {
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)

            if (world.random.nextInt(40) == 0) {
                val randomOffset = BlockPos(
                    bellPos.x + world.random.nextInt(7) - 3,
                    bellPos.y,
                    bellPos.z + world.random.nextInt(7) - 3
                )
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(randomOffset))
            }
        } else {
            if (!villager.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                villager.brain.setMemory(
                    MemoryModuleType.WALK_TARGET,
                    WalkTarget(BlockPosTracker(bellPos), speedModifier, 3)
                )
            }
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}
package vx.sv.nms.v1_21_R7.entity.ai.construct

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager

class BuildBreakBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT // Отдых прекращается во время боя
    ),
    1200 // Максимальное время непрерывного выполнения тика (1 минута)
) {

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        // Условие старта: только если у жителя активен перекур и он привязан к поселению
        return world.gameTime < villager.buildBreakUntilTime && villager.settlement != null
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        return world.gameTime < villager.buildBreakUntilTime && villager.settlement != null
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bellLoc = settlement.data.center
        val bellPos = BlockPos(bellLoc.blockX, bellLoc.blockY, bellLoc.blockZ)

        // Прокладываем путь к колоколу поселения (Meeting Point)
        villager.brain.setMemory(
            MemoryModuleType.WALK_TARGET,
            WalkTarget(BlockPosTracker(bellPos), speedModifier, 3) // Останавливаемся в радиусе 3 блоков
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
            // Пришли к колоколу, останавливаемся и отдыхаем
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            
            // Время от времени лениво оглядываемся в случайные точки вокруг колокола
            if (world.random.nextInt(40) == 0) {
                val randomOffset = BlockPos(
                    bellPos.x + world.random.nextInt(7) - 3,
                    bellPos.y,
                    bellPos.z + world.random.nextInt(7) - 3
                )
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(randomOffset))
            }
        } else {
            // Если путь сбился — заново ведем жителя к колоколу
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
package vx.sv.nms.v1_21_R7.entity.ai.construct

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import org.bukkit.Bukkit
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager

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

    private fun broadcastDebug(message: String) {
        val component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
            .deserialize("§d[AI-DEBUG] §f$message")
        Bukkit.broadcast(component)
    }

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val name = villager.bukkitEntity.name
        val check = world.gameTime < villager.buildBreakUntilTime && villager.settlement != null
        if (check) {
            broadcastDebug("§d§l[ПЕРЕКУР] §e$name §dначинает перекур до тика ${villager.buildBreakUntilTime} (текущее: ${world.gameTime})")
        }
        return check
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        return world.gameTime < villager.buildBreakUntilTime && villager.settlement != null
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val name = villager.bukkitEntity.name
        val settlement = villager.settlement ?: return
        val bellLoc = settlement.data.center
        val bellPos = BlockPos(bellLoc.blockX, bellLoc.blockY, bellLoc.blockZ)

        broadcastDebug("§e$name §7направляется к колоколу отдыхать.")

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
        val name = villager.bukkitEntity.name
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
                broadcastDebug("§e$name §7осматривается вокруг колокола.")
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
        val name = villager.bukkitEntity.name
        broadcastDebug("§e$name §dзакончил перекур и готов к новым задачам! (текущее время: ${world.gameTime})")
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}
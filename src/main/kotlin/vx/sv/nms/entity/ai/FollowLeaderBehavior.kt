package vx.sv.nms.entity.ai

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.EntityTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import vx.sv.gameplay.party.PartyManager
import vx.sv.gameplay.party.PartyManager.Companion.partyLeaderUUID
import vx.sv.gameplay.party.PartyManager.Companion.partyState
import vx.sv.nms.entity.HumanoidVillager

class FollowLeaderBehavior(
    private val speedModifier: Float,
    private val stopDistance: Float = 4.0f,
    private val teleportDistance: Float = 20.0f
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ), Int.MAX_VALUE
) {

    override fun checkExtraStartConditions(level: ServerLevel, villager: HumanoidVillager): Boolean {
        val leader = getLeader(villager) ?: return false

        // Если приказано стоять — не двигаемся
        if (villager.bukkitLivingEntity.partyState == PartyManager.PartyState.STAY) {
            return false
        }

        // Если деремся — не убегаем за лидером (если только лидер не улетел совсем далеко)
        val isFighting = villager.brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
        val distSqr = villager.distanceToSqr(leader)

        // Если мы деремся, но лидер ушел ОЧЕНЬ далеко (> 25 блоков), то всё равно бежим за ним (или телепортируемся)
        if (isFighting && distSqr < 25.0 * 25.0) {
            return false
        }

        return distSqr > (stopDistance * stopDistance)
    }

    override fun canStillUse(level: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        return checkExtraStartConditions(level, villager)
    }

    override fun tick(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        val leader = getLeader(villager) ?: return
        val distanceSqr = villager.distanceToSqr(leader)

        villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(leader, true))

        if (distanceSqr > (teleportDistance * teleportDistance)) {
            teleportToLeader(level, villager, leader)
        } else {
            villager.brain.setMemory(
                MemoryModuleType.WALK_TARGET,
                WalkTarget(EntityTracker(leader, false), speedModifier, stopDistance.toInt())
            )
        }
    }

    override fun stop(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }

    private fun getLeader(villager: HumanoidVillager): ServerPlayer? {
        val uuid = villager.bukkitLivingEntity.partyLeaderUUID ?: return null
        val bukkitPlayer = Bukkit.getPlayer(uuid) ?: return null
        return (bukkitPlayer as CraftPlayer).handle
    }

    private fun teleportToLeader(level: ServerLevel, villager: HumanoidVillager, leader: ServerPlayer) {
        val leaderPos = leader.blockPosition()

        for (i in 0..9) {
            val dx = villager.random.nextInt(5) - 2
            val dz = villager.random.nextInt(5) - 2
            val dy = villager.random.nextInt(2) - 1

            val targetPos = leaderPos.offset(dx, dy, dz)

            if (canTeleportTo(level, targetPos)) {
                // 1. Стоп
                villager.navigation.stop()

                // 2. Телепорт через Bukkit
                val location = org.bukkit.Location(
                    level.world,
                    targetPos.x.toDouble() + 0.5,
                    targetPos.y.toDouble(),
                    targetPos.z.toDouble() + 0.5,
                    villager.yRot,
                    villager.xRot
                )
                villager.bukkitEntity.teleport(location)

                // 3. Чистка памяти
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                return
            }
        }
    }

    private fun canTeleportTo(level: ServerLevel, pos: BlockPos): Boolean {
        val blockState = level.getBlockState(pos)
        val belowState = level.getBlockState(pos.below())

        val solidGround = belowState.isValidSpawn(level, pos.below(), net.minecraft.world.entity.EntityType.VILLAGER)
        val spaceBody = blockState.occlusionShape.isEmpty || blockState.isAir
        val spaceHead = level.getBlockState(pos.above()).occlusionShape.isEmpty || level.getBlockState(pos.above()).isAir

        return solidGround && spaceBody && spaceHead
    }
}
package vx.ignis.gameplay.humanoid.entity.behavior

import com.google.common.collect.ImmutableMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.Brain
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.EntityTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import org.bukkit.craftbukkit.entity.CraftPlayer
import vx.ignis.gameplay.humanoid.entity.HumanoidVillager

class LookAndFollowDuringConversation(private val speedModifier: Float) :
    Behavior<HumanoidVillager>(
        ImmutableMap.of(
            MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
            MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
        ), Int.MAX_VALUE
    ) {

    private val closeDistance = 2

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val player = ((villager.talkingPlayer ?: return false) as CraftPlayer).handle
        val distanceSqr = 20.0
        return villager.isAlive && villager.distanceToSqr(player) <= distanceSqr
    }

    override fun canStillUse(var0: ServerLevel, villager: HumanoidVillager, var2: Long): Boolean {
        return this.checkExtraStartConditions(var0, villager)
    }

    override fun start(var0: ServerLevel, villager: HumanoidVillager, var2: Long) {
        this.followPlayer(villager)
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, var2: Long) {
        val var4: Brain<*> = villager.brain
        var4.eraseMemory(MemoryModuleType.WALK_TARGET)
        var4.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }

    override fun tick(var0: ServerLevel, villager: HumanoidVillager, var2: Long) {
        this.followPlayer(villager)
    }

    override fun timedOut(var0: Long): Boolean {
        return false
    }

    private fun followPlayer(villager: HumanoidVillager) {
        val brain: Brain<*> = villager.brain
        val player = (villager.talkingPlayer as? CraftPlayer)?.handle
        brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(EntityTracker(player, false), this.speedModifier, closeDistance))
        brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(player, true))
    }
}

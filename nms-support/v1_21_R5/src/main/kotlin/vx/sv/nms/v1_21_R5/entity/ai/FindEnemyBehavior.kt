package vx.sv.nms.v1_21_R5.entity.ai

import com.google.common.collect.ImmutableMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.monster.Monster
import org.bukkit.Bukkit
import vx.sv.event.VillagerStartFightEvent
import vx.sv.nms.v1_21_R5.entity.HumanoidVillager

class FindEnemyBehavior(private val rangeSqr: Double = 144.0) :
    Behavior<HumanoidVillager>(
        ImmutableMap.of(
            MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED,
            MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, MemoryStatus.VALUE_PRESENT
        )
    ) {

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val currentTarget = villager.brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null)
        if (currentTarget != null && currentTarget.isAlive) {
            return false
        }
        return true
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val brain = villager.brain
        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)

        val nearestVisible = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES).orElse(null) ?: return

        // Ищем ближайшую валидную цель
        val target = nearestVisible.findClosest { entity ->
            isValidTarget(villager, entity)
        }.orElse(null)

        if (target != null) {
            // --- ВЫЗОВ ИВЕНТА ---
            val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager
            val bukkitTarget = target.bukkitEntity as? org.bukkit.entity.LivingEntity

            if (bukkitVillager != null && bukkitTarget != null) {
                val event = VillagerStartFightEvent(bukkitVillager, bukkitTarget)
                Bukkit.getPluginManager().callEvent(event)

                if (event.isCancelled) {
                    return // Ивент отменен, цель не ставим
                }
            }
            // --------------------

            brain.setMemory(MemoryModuleType.ATTACK_TARGET, target)
            brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        }
    }

    private fun isValidTarget(attacker: HumanoidVillager, target: LivingEntity): Boolean {
        if (target == attacker) return false
        if (!target.isAlive) return false
        if (target is Monster) return true
        return false
    }
}
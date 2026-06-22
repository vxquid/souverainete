package vx.sv.nms.entity.ai.fight

import com.google.common.collect.ImmutableMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.monster.spider.Spider
import org.bukkit.Bukkit
import org.bukkit.entity.Villager
import vx.sv.event.VillagerStartFightEvent
import vx.sv.nms.entity.HumanoidVillager

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

        val targetsList = mutableListOf<LivingEntity>()

        // 1. Попытка получить видимых целей через сенсоры NMS
        val nearestVisible = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES).orElse(null)
        if (nearestVisible != null) {
            nearestVisible.findAll { entity -> isValidTarget(villager, entity) }.forEach { targetsList.add(it) }
        }

        // 2. Если житель спит или ничего не видит глазами (из-за темноты или стен),
        // задействуем тактильно-слуховой радар в радиусе 12 блоков
        if (targetsList.isEmpty()) {
            val npcLoc = villager.bukkitEntity.location
            val nearby = npcLoc.getNearbyEntities(12.0, 6.0, 12.0)
            for (entity in nearby) {
                if (entity is org.bukkit.entity.LivingEntity) {
                    val nmsEntity = (entity as? org.bukkit.craftbukkit.entity.CraftLivingEntity)?.handle
                    if (nmsEntity != null && isValidTarget(villager, nmsEntity)) {
                        targetsList.add(nmsEntity)
                    }
                }
            }
        }

        // Находим ближайшую валидную цель из всех обнаруженных
        val target = targetsList.minByOrNull { it.distanceToSqr(villager) }

        if (target != null) {
            val bukkitVillager = villager.bukkitEntity as? Villager
            val bukkitTarget = target.bukkitEntity as? org.bukkit.entity.LivingEntity

            if (bukkitVillager != null && bukkitTarget != null) {
                val event = VillagerStartFightEvent(bukkitVillager, bukkitTarget)
                Bukkit.getPluginManager().callEvent(event)

                if (event.isCancelled) {
                    return // Ивент отменен, прерываем установку цели
                }
            }

            brain.setMemory(MemoryModuleType.ATTACK_TARGET, target)
            brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        }
    }

    private fun isValidTarget(attacker: HumanoidVillager, target: LivingEntity): Boolean {
        if (target == attacker) return false
        if (!target.isAlive) return false

        if (target is Monster) {
            if (target.target == attacker) return true
            if (target is Creeper) return false
            if (target is EnderMan) return false

            if (target is Spider) {
                val timeOfDay = attacker.level().world.time
                if (timeOfDay in 0..12000) return false
            }

            return true
        }

        return false
    }
}
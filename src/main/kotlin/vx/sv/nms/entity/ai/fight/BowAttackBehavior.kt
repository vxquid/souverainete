package vx.sv.nms.entity.ai.fight

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.behavior.EntityTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.Items
import vx.sv.gameplay.party.PartyManager
import vx.sv.gameplay.party.PartyManager.Companion.combatTactic
import vx.sv.nms.entity.HumanoidVillager

class BowAttackBehavior(
    private val speedModifier: Float,
    private val attackIntervalMin: Int = 20
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ), 1200
) {

    private var attackTime = -1
    private var strafingTime = -1
    private var strafingClockwise = true
    private var strafingBackwards = true

    override fun checkExtraStartConditions(level: ServerLevel, villager: HumanoidVillager): Boolean {

        val tactic = (villager as Villager).bukkitLivingEntity.combatTactic
        if (tactic == PartyManager.CombatTactic.MELEE) {
            return false
        }

        return villager.isHolding { it.`is`(Items.BOW) } && getTarget(villager) != null
    }

    override fun canStillUse(level: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        return checkExtraStartConditions(level, villager)
    }

    override fun start(level: ServerLevel, entity: HumanoidVillager, time: Long) {
        entity.isAggressive = true
    }

    override fun stop(level: ServerLevel, entity: HumanoidVillager, time: Long) {
        entity.isAggressive = false
        entity.stopUsingItem()
        entity.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        entity.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }

    override fun tick(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        val target = getTarget(villager) ?: return
        val distSqr = villager.distanceToSqr(target)
        val canSee = villager.sensing.hasLineOfSight(target)

        villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(target, true))

        // --- НОВАЯ ЛОГИКА ДВИЖЕНИЯ (АГРЕССИВНЫЙ КАЙТ) ---

        val safeDistanceSqr = 10.0 * 10.0 // 10 блоков - безопасная зона
        val optimalDistanceSqr = 20.0 * 20.0 // До 20 блоков - можно стрелять

        if (distSqr <= safeDistanceSqr) {
            // ВРАГ СЛИШКОМ БЛИЗКО! ОТСТУПАЕМ!
            val awayDir = villager.position().subtract(target.position()).normalize()
            val fleePos = villager.position().add(awayDir.scale(6.0)) // Бежим на 6 блоков назад

            // Если мы натягиваем лук, мы медленные. Пытаемся бежать быстрее (speed * 1.3), чтобы компенсировать
            villager.brain.setMemory(
                MemoryModuleType.WALK_TARGET,
                WalkTarget(BlockPosTracker(BlockPos.containing(fleePos)), speedModifier * 1.15f, 0)
            )
        } else if (distSqr <= optimalDistanceSqr && canSee) {
            // Мы в идеальной позиции. Стираем цель ходьбы, чтобы включился стрейф (MoveControl)
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        } else {
            // Слишком далеко, подходим
            villager.brain.setMemory(
                MemoryModuleType.WALK_TARGET,
                WalkTarget(EntityTracker(target, false), speedModifier, 0)
            )
        }

        updateStrafing(villager, target, distSqr, canSee)

        // Логика стрельбы
        if (villager.isUsingItem) {
            if (!canSee && attackTime < -60) {
                villager.stopUsingItem()
            } else if (canSee) {
                val chargeTicks = villager.ticksUsingItem
                if (chargeTicks >= 20) {
                    villager.performRangedAttack(target, BowItem.getPowerForTime(chargeTicks))
                    villager.stopUsingItem()
                    attackTime = attackIntervalMin
                }
            }
        } else if (--attackTime <= 0 && canSee) {
            villager.startUsingItem(ProjectileUtil.getWeaponHoldingHand(villager, Items.BOW))
        }
    }

    private fun updateStrafing(villager: HumanoidVillager, target: LivingEntity, distSqr: Double, canSee: Boolean) {
        if (strafingTime++ > 20 || (villager.navigation.isDone && strafingTime > 5)) {
            strafingTime = 0
            strafingClockwise = villager.random.nextBoolean()
            strafingBackwards = villager.random.nextBoolean()
        }

        // Стрейфим, если мы в боевой зоне (между 8 и 25 блоками)
        if (canSee && distSqr >= 64.0 && distSqr <= 625.0) {
            val strafeSpeed = if (villager.isUsingItem) speedModifier * 0.5f else speedModifier
            villager.moveControl.strafe(
                if (strafingBackwards) -strafeSpeed else strafeSpeed,
                if (strafingClockwise) strafeSpeed else -strafeSpeed
            )
            villager.lookControl.setLookAt(target, 30.0f, 30.0f)
        }
    }

    private fun getTarget(villager: HumanoidVillager) = villager.brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null)
}
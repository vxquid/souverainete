package vx.sv.nms.v1_21_R3.entity.ai

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.behavior.EntityTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.Items
import vx.sv.gameplay.party.PartyManager
import vx.sv.gameplay.party.PartyManager.Companion.combatTactic
import vx.sv.nms.v1_21_R3.entity.HumanoidVillager

class CrossbowAttackBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ), 1200
) {

    enum class CrossbowState { UNCHARGED, CHARGING, CHARGED, READY_TO_ATTACK }

    private var state = CrossbowState.UNCHARGED
    private var attackDelay = 0

    override fun checkExtraStartConditions(level: ServerLevel, villager: HumanoidVillager): Boolean {
        // Получаем Bukkit сущность для проверки PDC
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false

        val tactic = bukkitVillager.combatTactic
        if (tactic == PartyManager.CombatTactic.MELEE) {
            return false
        }

        return villager.isHolding { it.`is`(Items.CROSSBOW) } && getTarget(villager) != null
    }

    // --- ВАЖНЕЙШЕЕ ИСПРАВЛЕНИЕ ---
    // Без этого метода поведение сбрасывается каждый тик, прерывая зарядку и ходьбу.
    override fun canStillUse(level: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        return checkExtraStartConditions(level, villager)
    }
    // -----------------------------

    override fun start(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.isAggressive = true
        state = CrossbowState.UNCHARGED
    }

    override fun stop(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.isAggressive = false
        villager.stopUsingItem()
        villager.setChargingCrossbow(false) // Визуально опускаем руки
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }

    override fun tick(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        val target = getTarget(villager) ?: return
        val brain = villager.brain

        brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(target, true))

        val hand = ProjectileUtil.getWeaponHoldingHand(villager, Items.CROSSBOW)
        val itemStack = villager.getItemInHand(hand)

        // --- 1. STATE MACHINE (Зарядка) ---
        if (state == CrossbowState.UNCHARGED) {
            if (!CrossbowItem.isCharged(itemStack)) {
                villager.startUsingItem(hand)
                state = CrossbowState.CHARGING
                villager.setChargingCrossbow(true)
            } else {
                state = CrossbowState.CHARGED
            }
        } else if (state == CrossbowState.CHARGING) {
            if (!villager.isUsingItem) {
                state = CrossbowState.UNCHARGED
            }
            // getChargeDuration возвращает время в тиках (25 по дефолту, меньше с Quick Charge)
            val chargeDuration = CrossbowItem.getChargeDuration(itemStack, villager)
            if (villager.ticksUsingItem >= chargeDuration) {
                villager.releaseUsingItem() // Этот метод ставит компонент CHARGED
                state = CrossbowState.CHARGED
                attackDelay = 20 + villager.random.nextInt(20)
                villager.setChargingCrossbow(false)
            }
        } else if (state == CrossbowState.CHARGED) {
            if (attackDelay-- <= 0) {
                state = CrossbowState.READY_TO_ATTACK
            }
        } else if (state == CrossbowState.READY_TO_ATTACK) {
            // Огонь!
            villager.performRangedAttack(target, 1.0f)
            state = CrossbowState.UNCHARGED
        }

        // --- 2. ДВИЖЕНИЕ (БЕЗОПАСНОСТЬ) ---
        val distSqr = villager.distanceToSqr(target)

        // Дистанции
        val retreatDistCharging = 14.0 * 14.0
        val retreatDistCharged = 8.0 * 8.0
        val approachDist = 25.0 * 25.0

        var shouldRetreat = false

        if (state == CrossbowState.CHARGING && distSqr < retreatDistCharging) {
            shouldRetreat = true
        } else if (state != CrossbowState.CHARGING && distSqr < retreatDistCharged) {
            shouldRetreat = true
        }

        if (shouldRetreat) {
            // Бежим назад
            val awayDir = villager.position().subtract(target.position()).normalize()
            val fleePos = villager.position().add(awayDir.scale(5.0))

            // Если заряжаем - мы медленные, компенсируем скоростью 1.15f
            brain.setMemory(
                MemoryModuleType.WALK_TARGET,
                WalkTarget(BlockPosTracker(BlockPos.containing(fleePos)), speedModifier * 1.15f, 0)
            )
        } else if (distSqr > approachDist) {
            // Подходим
            brain.setMemory(
                MemoryModuleType.WALK_TARGET,
                WalkTarget(EntityTracker(target, false), speedModifier, 0)
            )
        } else {
            // Стоим и стреляем
            brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        }
    }

    private fun getTarget(villager: HumanoidVillager) = villager.brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null)
}
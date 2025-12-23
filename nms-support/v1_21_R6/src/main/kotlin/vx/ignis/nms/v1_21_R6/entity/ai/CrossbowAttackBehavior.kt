package vx.ignis.nms.v1_21_R6.entity.ai

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
import vx.ignis.nms.v1_21_R6.entity.HumanoidVillager

// ВАЖНО: Твой HumanoidVillager должен реализовывать интерфейс CrossbowAttackMob!
// class HumanoidVillager ... : Villager(...), CrossbowAttackMob { ... }

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
        return villager.isHolding { it.`is`(Items.CROSSBOW) } && getTarget(villager) != null
    }

    override fun start(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.isAggressive = true
        state = CrossbowState.UNCHARGED
    }

    override fun stop(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.isAggressive = false
        villager.stopUsingItem()
    }

    override fun tick(level: ServerLevel, villager: HumanoidVillager, time: Long) {
        val target = getTarget(villager) ?: return
        val brain = villager.brain

        brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(target, true))

        val hand = ProjectileUtil.getWeaponHoldingHand(villager, Items.CROSSBOW)
        val itemStack = villager.getItemInHand(hand)
        
        // --- 1. ПЕРЕКЛЮЧЕНИЕ СОСТОЯНИЙ ---
        if (state == CrossbowState.UNCHARGED) {
            if (!CrossbowItem.isCharged(itemStack)) {
                villager.startUsingItem(hand)
                state = CrossbowState.CHARGING
                villager.setChargingCrossbow(true) // Нужно для анимации (CrossbowAttackMob)
            } else {
                state = CrossbowState.CHARGED
            }
        } else if (state == CrossbowState.CHARGING) {
            if (!villager.isUsingItem) {
                state = CrossbowState.UNCHARGED // Сбилось
            }
            if (villager.ticksUsingItem >= CrossbowItem.getChargeDuration(itemStack, villager)) {
                villager.releaseUsingItem() // Это зарядит арбалет
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

        // --- 2. ДВИЖЕНИЕ ---
        // Пока заряжаем - идем медленно и подальше от врага
        // Когда заряжен - можем подходить
        
        val distSqr = villager.distanceToSqr(target)
        
        if (state == CrossbowState.CHARGING) {
             // Кайт пока заряжаем
             if (distSqr < 64.0) { // Слишком близко
                 val away = villager.position().subtract(target.position()).normalize()
                 val pos = villager.position().add(away.scale(4.0))
                 brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(BlockPos.containing(pos)), speedModifier * 0.5f, 0))
             } else {
                 // Просто стоим или медленно идем
                 brain.eraseMemory(MemoryModuleType.WALK_TARGET)
             }
        } else {
            // Заряжен - ведем себя как обычно, держим среднюю дистанцию
             if (distSqr > 100.0) { // Дальше 10 блоков
                 brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(EntityTracker(target, false), speedModifier, 0))
             } else {
                 brain.eraseMemory(MemoryModuleType.WALK_TARGET) // Стоим и целимся
             }
        }
    }

    private fun getTarget(villager: HumanoidVillager) = villager.brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null)
}
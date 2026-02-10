package vx.sv.nms.v1_21_R5.entity.ai

import com.google.common.collect.ImmutableMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.behavior.EntityTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.item.Items
import net.minecraft.world.item.ShieldItem
import net.minecraft.world.phys.Vec3
import vx.sv.gameplay.party.PartyManager
import vx.sv.gameplay.party.PartyManager.Companion.combatTactic
import vx.sv.nms.v1_21_R5.entity.HumanoidVillager
import kotlin.random.Random

class TacticalAttackBehavior(
    private val speedModifier: Float,
    private val attackDelay: Int = 20
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ), 1200
) {

    private var attackCooldown: Int = 0
    private var retreatCooldown: Int = 0
    private var strafeTime: Int = 0
    private var strafeClockwise: Boolean = true
    private var lastTargetPos: Vec3? = null

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {

        val tactic = (villager as Villager).bukkitLivingEntity.combatTactic
        if (tactic == PartyManager.CombatTactic.RANGED) {
            return false
        }

        // --- ГЛАВНОЕ ИСПРАВЛЕНИЕ ---
        // Если в руках стрелковое оружие — мы НЕ должны использовать тактику ближнего боя.
        if (villager.isHolding { it.`is`(Items.BOW) || it.`is`(Items.CROSSBOW) }) {
            return false
        }

        val target = getAttackTarget(villager)
        return villager.isAlive && target != null && target.isAlive && villager.isWithinCombatRange(target)
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        if (villager.isHolding { it.`is`(Items.BOW) || it.`is`(Items.CROSSBOW) }) {
            return false
        }
        val target = getAttackTarget(villager)
        return target != null && target.isAlive && villager.isWithinCombatRange(target)
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        attackCooldown = 0
        retreatCooldown = 0
        strafeTime = 0
        strafeClockwise = Random.nextBoolean()
        villager.isAggressive = true // Поднимает руки, если нет предмета (визуально)
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val brain = villager.brain
        val target = getAttackTarget(villager)

        villager.isAggressive = false
        villager.stopUsingItem() // Опускаем щит при конце боя

        if (target == null || !target.isAlive) {
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
        }
        brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val target = getAttackTarget(villager) ?: return
        val brain = villager.brain

        brain.setMemory(MemoryModuleType.LOOK_TARGET, EntityTracker(target, true))

        // --- 1. ЛОГИКА НИЗКОГО HP (ESCAPE) ---
        if (villager.health < villager.maxHealth * 0.30) {
            handleLowHealthFlee(villager, target)
            return
        }

        // --- 2. ЛОГИКА ЩИТА ---
        handleShield(villager, target)

        val distanceSqr = villager.distanceToSqr(target)
        val reachSqr = getAttackReachSqr(villager, target)

        // --- 3. ДВИЖЕНИЕ ---
        // Если используем щит, скорость сильно падает
        val currentSpeed = if (villager.isBlocking) speedModifier * 0.5f else speedModifier
        updateMovement(villager, target, distanceSqr, reachSqr, currentSpeed)

        // --- 4. АТАКА ---
        if (attackCooldown > 0) attackCooldown--
        if (retreatCooldown > 0) retreatCooldown--

        // Бьем, если достаем и кд прошел
        if (distanceSqr <= reachSqr && attackCooldown <= 0 && retreatCooldown <= 5) {
            // Если держали щит — опускаем на секунду для удара
            if (villager.isBlocking) villager.stopUsingItem()

            attackCooldown = attackDelay
            retreatCooldown = 15 // Отскок после удара

            villager.swing(InteractionHand.MAIN_HAND)
            villager.doHurtTarget(villager.level() as ServerLevel, target)
        }
    }

    private fun handleLowHealthFlee(villager: HumanoidVillager, target: LivingEntity) {
        villager.stopUsingItem() // Нельзя бежать с щитом быстро

        // Вектор ОТ врага
        val fleeDir = villager.position().subtract(target.position()).normalize()
        val fleePos = villager.position().add(fleeDir.scale(8.0)) // Бежим на 8 блоков от него

        villager.brain.setMemory(
            MemoryModuleType.WALK_TARGET,
            WalkTarget(BlockPosTracker(net.minecraft.core.BlockPos.containing(fleePos)), speedModifier * 1.15f, 0)
        )
    }

    private fun handleShield(villager: HumanoidVillager, target: LivingEntity) {
        // Проверяем, есть ли щит
        val hasShield = villager.mainHandItem.item is ShieldItem || villager.offhandItem.item is ShieldItem
        if (!hasShield) return

        val distanceSqr = villager.distanceToSqr(target)

        // Логика блокирования:
        // Блокируем, если враг близко (< 5 блоков) ИЛИ если кулдаун атаки еще не прошел (ждем в блоке)
        val shouldBlock = (distanceSqr < 25.0 || attackCooldown > 5) && !villager.isBlocking

        if (shouldBlock) {
            val hand = if (villager.offhandItem.item is ShieldItem) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
            villager.startUsingItem(hand)
        }
    }

    private fun updateMovement(villager: HumanoidVillager, target: LivingEntity, distanceSqr: Double, reachSqr: Double, speed: Float) {
        if (retreatCooldown > 0) {
            // Быстрый отход назад (не работает, если поднят щит, см. currentSpeed выше)
            val retreatDir = villager.position().subtract(target.position()).normalize()
            val retreatPos = villager.position().add(retreatDir.scale(4.0))
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(net.minecraft.core.BlockPos.containing(retreatPos)), speed * 1.2f, 0))
            return
        }

        if (strafeTime++ > 40 || (villager.navigation.isDone && strafeTime > 5)) {
            strafeTime = 0
            if (Random.nextDouble() < 0.3) strafeClockwise = !strafeClockwise
        }

        val chaseThreshold = (reachSqr + 3.5)
        if (distanceSqr > chaseThreshold) {
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(EntityTracker(target, false), speed, 0))
        } else {
            // Стрейф
            if (lastTargetPos == null || lastTargetPos!!.distanceToSqr(target.position()) > 1.0 || strafeTime % 5 == 0) {
                lastTargetPos = target.position()
                val strafePos = getStrafePosition(villager, target)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(strafePos), speed, 0))
            }
        }
    }

    private fun getStrafePosition(attacker: HumanoidVillager, target: LivingEntity): net.minecraft.core.BlockPos {
        val forward = target.position().subtract(attacker.position()).normalize()
        val side = Vec3(-forward.z, 0.0, forward.x)
        val direction = if (strafeClockwise) side else side.reverse()
        val targetVec = target.position().add(direction.scale(3.0)).add(forward.scale(-1.0))
        return net.minecraft.core.BlockPos.containing(targetVec)
    }

    private fun getAttackTarget(villager: HumanoidVillager): LivingEntity? = villager.brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null)

    private fun getAttackReachSqr(attacker: HumanoidVillager, target: LivingEntity): Double {
        val rangeBonus = 0.8
        val realWidth = attacker.bbWidth * 2.0f + target.bbWidth + rangeBonus
        return (realWidth * realWidth)
    }

    private fun HumanoidVillager.isWithinCombatRange(target: LivingEntity): Boolean = this.distanceToSqr(target) <= 24.0 * 24.0
}
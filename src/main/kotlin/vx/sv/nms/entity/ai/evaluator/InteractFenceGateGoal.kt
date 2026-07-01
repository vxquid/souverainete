package vx.sv.nms.entity.ai.evaluator

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB

class InteractFenceGateGoal(private val mob: Mob) : Goal() {
    private var gatePos: BlockPos? = null
    private var hasOpenedGate = false
    private var ticksSinceOpened = 0

    override fun canUse(): Boolean {
        if (!mob.navigation.isInProgress) return false
        val path = mob.navigation.path ?: return false
        if (path.isDone) return false

        val currentPos = mob.blockPosition()
        val positionsToCheck = mutableSetOf<BlockPos>()

        positionsToCheck.add(currentPos)
        positionsToCheck.add(currentPos.above())
        positionsToCheck.add(currentPos.above(2))

        for (i in 0..2) {
            val nodeIndex = path.nextNodeIndex + i
            if (nodeIndex < path.nodeCount) {
                val node = path.getNode(nodeIndex)
                positionsToCheck.add(BlockPos(node.x, node.y, node.z))
                positionsToCheck.add(BlockPos(node.x, node.y + 1, node.z))
                positionsToCheck.add(BlockPos(node.x, node.y + 2, node.z))
            }
        }

        for (pos in positionsToCheck) {
            val state = mob.level().getBlockState(pos)
            if (state.`is`(BlockTags.FENCE_GATES)) {
                if (!state.getValue(FenceGateBlock.OPEN)) {
                    this.gatePos = pos
                    return true
                }
            }
        }
        return false
    }

    override fun start() {
        this.hasOpenedGate = false
        this.ticksSinceOpened = 0
    }

    override fun tick() {
        val pos = this.gatePos ?: return
        val state = mob.level().getBlockState(pos)
        if (!state.`is`(BlockTags.FENCE_GATES)) return

        if (!hasOpenedGate) {
            val distanceSq = mob.distanceToSqr(pos.x.toDouble() + 0.5, pos.y.toDouble(), pos.z.toDouble() + 0.5)

            if (distanceSq <= 9.0) {
                toggleGate(pos, state, open = true)
                this.hasOpenedGate = true
            }
        }
    }

    override fun canContinueToUse(): Boolean {
        val pos = this.gatePos ?: return false
        val state = mob.level().getBlockState(pos)
        if (!state.`is`(BlockTags.FENCE_GATES)) return false

        if (hasOpenedGate) {
            ticksSinceOpened++

            if (ticksSinceOpened > 100) return false

            val distanceSq = mob.distanceToSqr(pos.x.toDouble() + 0.5, pos.y.toDouble(), pos.z.toDouble() + 0.5)

            // ОПТИМИЗАЦИЯ ЗВУКА: Закрываем калитку только если прошло минимум 30 тиков (1.5 сек),
            // чтобы избежать бесконечного цикла быстрого открытия-закрытия при подходе к калитке.
            if (ticksSinceOpened >= 30 && distanceSq > 3.0) {
                // Увеличиваем высоту коробки проверки, чтобы захватить и верхнюю калитку
                val gateBox = AABB(
                    pos.x.toDouble(), pos.y.toDouble() - 1.0, pos.z.toDouble(),
                    pos.x + 1.0, pos.y + 2.5, pos.z + 1.0
                ).inflate(0.2)

                val entitiesInGate = mob.level().getEntitiesOfClass(LivingEntity::class.java, gateBox)

                if (entitiesInGate.isEmpty()) {
                    toggleGate(pos, state, open = false)
                    hasOpenedGate = false
                    return false
                }
            }
            return true
        }

        return !mob.navigation.isDone && mob.distanceToSqr(pos.x.toDouble() + 0.5, pos.y.toDouble(), pos.z.toDouble() + 0.5) <= 25.0
    }

    override fun stop() {
        val pos = this.gatePos ?: return
        val state = mob.level().getBlockState(pos)

        if (hasOpenedGate && state.`is`(BlockTags.FENCE_GATES)) {
            val gateBox = AABB(
                pos.x.toDouble(), pos.y.toDouble() - 1.0, pos.z.toDouble(),
                pos.x + 1.0, pos.y + 2.5, pos.z + 1.0
            ).inflate(0.2)

            val entitiesInGate = mob.level().getEntitiesOfClass(LivingEntity::class.java, gateBox)
            if (entitiesInGate.isEmpty()) {
                toggleGate(pos, state, open = false)
            }
        }

        this.gatePos = null
        this.hasOpenedGate = false
        this.ticksSinceOpened = 0
    }

    private fun getGateSound(block: net.minecraft.world.level.block.Block, open: Boolean): net.minecraft.sounds.SoundEvent {
        return try {
            val fieldName = if (open) "openSound" else "closeSound"
            val field = FenceGateBlock::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(block) as net.minecraft.sounds.SoundEvent
        } catch (e: Exception) {
            if (open) net.minecraft.sounds.SoundEvents.FENCE_GATE_OPEN else net.minecraft.sounds.SoundEvents.FENCE_GATE_CLOSE
        }
    }

    private fun toggleGate(pos: BlockPos, state: BlockState, open: Boolean) {
        val level = mob.level()

        // Открываем основную калитку
        val updatedState = state.setValue(FenceGateBlock.OPEN, open)
        level.setBlock(pos, updatedState, 3)

        val sound = getGateSound(state.getBlock(), open)
        level.playSound(
            null,
            pos.x.toDouble() + 0.5,
            pos.y.toDouble() + 0.5,
            pos.z.toDouble() + 0.5,
            sound,
            net.minecraft.sounds.SoundSource.BLOCKS,
            1.0f,
            level.random.nextFloat() * 0.1f + 0.9f
        )

        // ДВОЙНЫЕ КАЛИТКИ: Проверяем калитки строго над и под текущей, синхронизируя их
        val adjacentPositions = listOf(pos.above(), pos.below())
        for (adjacentPos in adjacentPositions) {
            val adjacentState = level.getBlockState(adjacentPos)
            if (adjacentState.`is`(BlockTags.FENCE_GATES)) {
                val isAdjacentOpen = adjacentState.getValue(FenceGateBlock.OPEN)
                if (isAdjacentOpen != open) {
                    val updatedAdjacentState = adjacentState.setValue(FenceGateBlock.OPEN, open)
                    level.setBlock(adjacentPos, updatedAdjacentState, 3)
                    // Звук повторно не проигрываем, чтобы не спамить уши
                }
            }
        }
    }
}
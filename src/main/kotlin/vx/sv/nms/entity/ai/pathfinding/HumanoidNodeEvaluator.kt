package vx.sv.nms.entity.ai.pathfinding

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import org.bukkit.enchantments.Enchantment
import vx.sv.nms.entity.HumanoidVillager

class HumanoidNodeEvaluator : WalkNodeEvaluator() {

    override fun getPathType(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        val pos = BlockPos(x, y, z)
        val state = context.level().getBlockState(pos)

        // Если это калитка, заставляем навигатор видеть её как открытую/закрытую дверь
        if (state.`is`(BlockTags.FENCE_GATES)) {
            val isOpen = state.getValue(FenceGateBlock.OPEN)
            return if (isOpen) {
                PathType.DOOR_OPEN
            } else {
                PathType.DOOR_WOOD_CLOSED
            }
        }

        return super.getPathType(context, x, y, z)
    }

    override fun findAcceptedNode(
        x: Int,
        y: Int,
        z: Int,
        stepHeight: Int,
        distance: Double,
        direction: Direction,
        pathType: PathType
    ): Node? {
        val node = super.findAcceptedNode(x, y, z, stepHeight, distance, direction, pathType) ?: return null

        val villager = this.mob as? HumanoidVillager ?: return node

        val landingPos = BlockPos(node.x, node.y, node.z)
        val fallDistance = calculateFallDistance(villager, landingPos, direction)

        // Если движение предполагает падение более чем на 1 блок
        if (fallDistance > 1) {
            // Максимальный предел падения — 10 блоков
            if (fallDistance > 10) {
                return null
            }

            val damage = calculateEstimatedFallDamage(villager, fallDistance, landingPos)
            val currentHealth = villager.health

            // Отклоняем прыжок, если урон приведет к смерти (оставляем минимум 1 HP)
            if (damage >= currentHealth - 1.0f) {
                return null
            }

            // Если падение наносит нелетальный урон, добавляем штраф к стоимости узла,
            // чтобы NPC предпочитал обычные лестницы/ступени при их наличии
            if (damage > 0f) {
                node.costMalus += damage * 4.0f
            }
        }

        return node
    }

    private fun calculateFallDistance(villager: HumanoidVillager, landingPos: BlockPos, direction: Direction): Int {
        val level = villager.level()

        // Вычисляем координаты блока, с которого совершается шаг (в противоположную сторону движения)
        val parentX = landingPos.x - direction.stepX
        val parentZ = landingPos.z - direction.stepZ
        val y = landingPos.y

        var parentY = y
        // Ищем твёрдую поверхность на родительской координате X/Z в пределах высоты прыжка
        for (tempY in y..y + 11) {
            val pos = BlockPos(parentX, tempY, parentZ)
            val blockState = level.getBlockState(pos)
            val above1 = level.getBlockState(pos.above())
            val above2 = level.getBlockState(pos.above(2))

            // Если блок под ногами не воздух и сверху есть свободное пространство для жителя
            if (!blockState.isAir && !above1.blocksMotion() && !above2.blocksMotion()) {
                parentY = tempY
            }
        }

        // Высота падения — это разница между уровнем уступа и точкой приземления
        return (parentY - y).coerceAtLeast(0)
    }

    private fun calculateEstimatedFallDamage(
        villager: HumanoidVillager,
        fallDistance: Int,
        landingPos: BlockPos
    ): Float {
        // 1. Эффект "Медленное падение"
        if (villager.hasEffect(MobEffects.SLOW_FALLING)) {
            return 0.0f
        }

        // 2. Эффект "Прыгучесть" (увеличивает безопасную высоту на уровень эффекта)
        var safeDistance = 3.0f
        val jumpBoost = villager.getEffect(MobEffects.JUMP_BOOST)
        if (jumpBoost != null) {
            safeDistance += (jumpBoost.amplifier + 1).toFloat()
        }

        val effectiveFall = fallDistance.toFloat() - safeDistance
        if (effectiveFall <= 0f) {
            return 0.0f
        }

        val level = villager.level()
        val landingState = level.getBlockState(landingPos)

        // 3. Блоки, полностью гасящие урон (Вода, Рыхлый снег, Слизь, Паутина)
        val fluidState = level.getFluidState(landingPos)
        if (!fluidState.isEmpty ||
            landingState.`is`(Blocks.WATER) ||
            landingState.`is`(Blocks.POWDER_SNOW) ||
            landingState.`is`(Blocks.SLIME_BLOCK) ||
            landingState.`is`(Blocks.COBWEB)
        ) {
            return 0.0f
        }

        var baseDamage = effectiveFall

        // 4. Блоки, смягчающие урон от падения
        if (landingState.`is`(Blocks.HAY_BLOCK) || landingState.`is`(Blocks.HONEY_BLOCK)) {
            baseDamage *= 0.2f // -80% урона (получает 20%)
        } else if (landingState.`is`(BlockTags.BEDS)) {
            baseDamage *= 0.5f // -50% урона (получает 50%)
        }

        if (baseDamage <= 0f) return 0.0f

        // 5. Зачарования на ботинках ("Невесомость" и "Защита")
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.LivingEntity
        val boots = bukkitVillager?.equipment?.boots
        if (boots != null && boots.hasItemMeta()) {
            val meta = boots.itemMeta

            val featherFallingLevel = meta?.getEnchantLevel(Enchantment.FEATHER_FALLING) ?: 0
            if (featherFallingLevel > 0) {
                val reduction = (featherFallingLevel * 0.12f).coerceAtMost(0.48f)
                baseDamage *= (1.0f - reduction)
            }

            val protectionLevel = meta?.getEnchantLevel(Enchantment.PROTECTION) ?: 0
            if (protectionLevel > 0) {
                val protReduction = (protectionLevel * 0.04f).coerceAtMost(0.16f)
                baseDamage *= (1.0f - protReduction)
            }
        }

        return baseDamage
    }
}
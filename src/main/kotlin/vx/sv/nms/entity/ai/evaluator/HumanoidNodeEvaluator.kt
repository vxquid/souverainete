package vx.sv.nms.entity.ai.evaluator

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator

class HumanoidNodeEvaluator : WalkNodeEvaluator() {

    override fun getPathType(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        val pos = BlockPos(x, y, z)
        val state = context.level().getBlockState(pos)

        // Если это калитка, заставляем навигатор NMS видеть её как стандартную деревянную дверь.
        // Это отключает проверку твердой физической коллизии AABB калитки во время поиска пути.
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
}
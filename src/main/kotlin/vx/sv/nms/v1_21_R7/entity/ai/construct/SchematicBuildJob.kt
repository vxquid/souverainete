package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import org.bukkit.entity.Villager as BukkitVillager

class SchematicBuildJob(val world: World) {
    private val blocksMap = mutableMapOf<BlockPos, BlockToPlace>()
    private var sortedBlocksCache: List<BlockToPlace>? = null
    private val lock = Any()

    fun addBlock(pos: BlockPos, blockData: BlockData, isRoad: Boolean = false) {
        synchronized(lock) {
            blocksMap[pos] = BlockToPlace(pos, blockData, isRoad = isRoad)
            sortedBlocksCache = null
        }
    }

    fun getBlocks(): List<BlockToPlace> {
        synchronized(lock) {
            if (sortedBlocksCache == null) {
                sortedBlocksCache = blocksMap.values.sortedWith(
                    compareBy<BlockToPlace> { it.isRoad }.thenBy { it.pos.y }
                )
            }
            return sortedBlocksCache!!
        }
    }

    fun claimNextBlock(npc: HumanoidVillager): BlockToPlace? {
        synchronized(lock) {
            val npcPos = npc.blockPosition()
            val bukkitInv = (npc.bukkitEntity as BukkitVillager).inventory
            val blocksList = getBlocks()

            // 0. АВТО-ЗАВЕРШЕНИЕ
            blocksList.filter { !it.isPlaced && it.claimedBy == null }.forEach {
                val currentBlock = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z)
                if ((currentBlock.type.isAir && it.blockData.material.isAir) || currentBlock.blockData == it.blockData) {
                    it.isPlaced = true
                }
            }

            // 1. ФАЗА РАСЧИСТКИ
            val obstacles = blocksList.filter {
                if (it.isPlaced || it.claimedBy != null) return@filter false
                val currentBlock = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z)
                val currentType = currentBlock.type

                // Если текущий блок — трава или цветы, пропускаем их в фазе расчистки
                if (currentBlock.isIgnorableObstacle()) return@filter false

                if (currentType == it.blockData.material) return@filter false

                if (currentType.isShovelable() && it.blockData.material == Material.DIRT_PATH) return@filter false

                if (currentBlock.isLiquid) {
                    it.blockData.material.isAir
                } else {
                    true
                }
            }

            if (obstacles.isNotEmpty()) {
                val maxY = obstacles.maxOf { it.pos.y }
                val highestObstacles = obstacles.filter { it.pos.y == maxY }
                val closest = highestObstacles.minByOrNull { it.pos.distSqr(npcPos) }
                closest?.claimedBy = npc
                return closest
            }

            // 2. ФАЗА СТРОИТЕЛЬСТВА
            val buildCandidates = blocksList.filter {
                val currentBlock = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z)
                val isPathTransformation = it.isRoad && it.material == Material.DIRT_PATH && currentBlock.type.isShovelable()

                !it.isPlaced && it.claimedBy == null && (isPathTransformation || bukkitInv.contains(it.material))
            }

            if (buildCandidates.isEmpty()) return null

            val minY = buildCandidates.minOf { it.pos.y }
            val lowestYBlocks = buildCandidates.filter { it.pos.y == minY }
            val closest = lowestYBlocks.minByOrNull { it.pos.distSqr(npcPos) }

            closest?.claimedBy = npc
            return closest
        }
    }

    fun unclaimBlock(block: BlockToPlace) {
        synchronized(lock) {
            if (block.claimedBy == block.claimedBy) {
                block.claimedBy = null
            }
        }
    }

    fun completeBlock(block: BlockToPlace) {
        synchronized(lock) {
            block.isPlaced = true
            block.claimedBy = null
        }
    }

    fun isFinished(): Boolean {
        synchronized(lock) {
            return getBlocks().all { it.isPlaced }
        }
    }
}
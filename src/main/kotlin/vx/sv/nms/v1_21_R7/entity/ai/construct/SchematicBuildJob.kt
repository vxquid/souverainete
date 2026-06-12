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
                // Сначала полностью строим все здания (isRoad = false),
                // и только после их готовности прокладываем дороги (isRoad = true).
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

                if (currentType.isAir || currentType == it.blockData.material) return@filter false

                // Если мы прокладываем дорогу (DIRT_PATH) поверх земельного блока (травы/грязи) —
                // он НЕ является препятствием. Мы трансформируем его лопатой прямо во 2 фазе.
                if (currentType.isShovelable() && it.blockData.material == Material.DIRT_PATH) return@filter false

                if (currentBlock.isLiquid) {
                    it.blockData.material.isAir
                } else {
                    true
                }
            }

            // Расчищаем препятствия строго сверху вниз
            if (obstacles.isNotEmpty()) {
                val maxY = obstacles.maxOf { it.pos.y }
                val highestObstacles = obstacles.filter { it.pos.y == maxY }
                val closest = highestObstacles.minByOrNull { it.pos.distSqr(npcPos) }
                closest?.claimedBy = npc
                return closest
            }

            // 2. ФАЗА СТРОИТЕЛЬСТВА
            val buildCandidates = blocksList.filter {
                // Если мы трансформируем траву в дорогу лопатой — ресурс DIRT не требуется
                val isPathTransformation = it.isRoad && it.material == Material.DIRT_PATH && world.getBlockAt(it.pos.x, it.pos.y, it.pos.z).type.isShovelable()

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
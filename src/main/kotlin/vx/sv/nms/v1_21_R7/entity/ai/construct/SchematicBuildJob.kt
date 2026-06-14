package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager

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

    /**
     * Возвращает вес приоритета установки блока.
     * Твёрдые блоки строятся первыми, декор — после них, жидкости — строго в самом конце.
     */
    private fun getPlacementPriority(material: Material): Int {
        val name = material.name
        return when {
            material == Material.WATER || material == Material.LAVA -> 100 // Жидкости разливаются строго последними
            name.contains("DOOR") || name.contains("BED") -> 50 // Двухблочные структуры
            name.contains("TORCH") || name.contains("LANTERN") || name.contains("CARPET") || name.contains("SIGN") -> 20 // Навесной декор
            else -> 0 // Твердый каркас, несущие стены и фундамент строятся в первую очередь
        }
    }

    fun getBlocks(): List<BlockToPlace> {
        synchronized(lock) {
            if (sortedBlocksCache == null) {
                // Сортировка: Сначала здания (isRoad = false) -> Снизу-вверх по Y -> По приоритету материала
                sortedBlocksCache = blocksMap.values.sortedWith(
                    compareBy<BlockToPlace> { it.isRoad }
                        .thenBy { it.pos.y }
                        .thenBy { getPlacementPriority(it.blockData.material) }
                )
            }
            return sortedBlocksCache!!
        }
    }

    fun claimNextBlock(npc: HumanoidVillager): BlockToPlace? {
        synchronized(lock) {
            val npcPos = npc.blockPosition()
            val blocksList = getBlocks()

            // 1. Очистка зависших клеймов
            blocksList.forEach { block ->
                val claimer = block.claimedBy
                if (claimer != null) {
                    if (!claimer.isAlive || claimer.activeBuildJob != this || claimer.assignedBlock != block) {
                        block.claimedBy = null
                    }
                }
            }

            // 2. Глобальное авто-завершение
            blocksList.filter { !it.isPlaced }.forEach {
                val currentBlock = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z)
                if ((currentBlock.type.isAir && it.blockData.material.isAir) || currentBlock.blockData == it.blockData) {
                    it.isPlaced = true
                    it.claimedBy = null
                }
            }

            // 3. Фаза расчистки препятствий
            val obstacles = blocksList.filter {
                if (it.isPlaced || it.claimedBy != null) return@filter false
                val currentBlock = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z)
                val currentType = currentBlock.type

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

            // 4. ФАЗА СТРОИТЕЛЬСТВА
            val buildCandidates = blocksList.filter {
                !it.isPlaced && it.claimedBy == null
            }

            if (buildCandidates.isEmpty()) return null

            val minY = buildCandidates.minOf { it.pos.y }
            val lowestYBlocks = buildCandidates.filter { it.pos.y == minY }

            // ИСПРАВЛЕНО: Находим минимальный приоритет среди оставшихся блоков на текущем слое Y
            val minPriority = lowestYBlocks.minOf { getPlacementPriority(it.blockData.material) }

            // Оставляем только те блоки, у которых приоритет равен минимальному (сначала 0, затем 20, 50, 100)
            val priorityBlocks = lowestYBlocks.filter { getPlacementPriority(it.blockData.material) == minPriority }

            // Среди блоков наивысшего приоритета на слое выбираем ближайший к жителю
            val closest = priorityBlocks.minByOrNull { it.pos.distSqr(npcPos) }

            closest?.claimedBy = npc
            return closest
        }
    }

    fun unclaimBlock(block: BlockToPlace) {
        synchronized(lock) {
            block.claimedBy = null
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
            val blocksList = getBlocks()

            blocksList.filter { !it.isPlaced }.forEach {
                val currentBlock = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z)
                if ((currentBlock.type.isAir && it.blockData.material.isAir) || currentBlock.blockData == it.blockData) {
                    it.isPlaced = true
                    it.claimedBy = null
                }
            }

            return blocksList.all { it.isPlaced }
        }
    }
}
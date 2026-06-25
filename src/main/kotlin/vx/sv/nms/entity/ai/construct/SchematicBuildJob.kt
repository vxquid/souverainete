package vx.sv.nms.entity.ai.construct

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import vx.sv.nms.entity.HumanoidVillager
import java.util.*

class SchematicBuildJob(val world: World, var jobId: UUID = UUID.randomUUID()) {

    private val blocksMap = mutableMapOf<BlockPos, BlockToPlace>()
    private var sortedBlocksCache: List<BlockToPlace>? = null
    private val lock = Any()

    fun addBlock(pos: BlockPos, blockData: BlockData, isRoad: Boolean = false) {
        synchronized(lock) {
            blocksMap[pos] = BlockToPlace(pos, blockData, isRoad = isRoad)
            sortedBlocksCache = null
        }
    }

    private fun getPlacementPriority(material: Material): Int {
        val name = material.name
        return when {
            material == Material.WATER || material == Material.LAVA -> 1 // liquids
            material == Material.AIR -> 2 // clearing
            name.contains("DOOR") || name.contains("BED") -> 10 // doors/beds last
            name.contains("TORCH") || name.contains("LANTERN") || name.contains("CARPET") || name.contains("SIGN") -> 8
            else -> 5
        }
    }

    fun getBlocks(): List<BlockToPlace> {
        synchronized(lock) {
            if (sortedBlocksCache == null) {
                sortedBlocksCache = blocksMap.values.sortedWith(
                    compareBy<BlockToPlace> { it.isRoad }
                        .thenBy { it.pos.y }
                        .thenBy { getPlacementPriority(it.blockData.material) }
                )
            }
            return sortedBlocksCache!!
        }
    }

    private fun claimFromListOptimized(list: List<BlockToPlace>, npc: HumanoidVillager, npcPos: BlockPos): BlockToPlace? {
        if (list.isEmpty()) return null

        // 1. Сначала обрабатываем блоки раскопок (цель — воздух/AIR) сверху вниз (от наибольшего Y к наименьшему)
        val excavationList = list.filter { it.blockData.material.isAir }
        if (excavationList.isNotEmpty()) {
            val yLevels = excavationList.map { it.pos.y }.distinct().sortedDescending()
            for (y in yLevels) {
                val activeLayer = excavationList.filter { it.pos.y == y }
                val candidates = mutableListOf<BlockToPlace>()
                for (block in activeLayer) {
                    val currentBlock = world.getBlockAt(block.pos.x, block.pos.y, block.pos.z)
                    if (currentBlock.type.isAir) {
                        block.isPlaced = true // Уже воздух, помечаем выполненным
                        continue
                    }
                    candidates.add(block)
                }
                if (candidates.isNotEmpty()) {
                    val closest = candidates.minByOrNull { it.pos.distSqr(npcPos) }
                    closest?.claimedBy = npc
                    return closest
                }
            }
        }

        // 2. Если все раскопки завершены, строим твердые блоки фундамента и стен снизу вверх (от наименьшего Y к наибольшему)
        val constructionList = list.filter { !it.blockData.material.isAir }
        if (constructionList.isNotEmpty()) {
            val yLevels = constructionList.map { it.pos.y }.distinct().sorted()
            for (y in yLevels) {
                val activeLayer = constructionList.filter { it.pos.y == y }

                val obstacles = mutableListOf<BlockToPlace>()
                val candidates = mutableListOf<BlockToPlace>()

                for (block in activeLayer) {
                    val currentBlock = world.getBlockAt(block.pos.x, block.pos.y, block.pos.z)
                    val currentType = currentBlock.type

                    // Если блок уже стоит правильный — помечаем готовым и скипаем
                    if (currentBlock.blockData == block.blockData) {
                        block.isPlaced = true
                        continue
                    }

                    // Проверка на превращение тропинки лопатой
                    val isPathTransformation = currentType.isShovelable() && block.blockData.material == Material.DIRT_PATH
                    if (isPathTransformation) {
                        candidates.add(block)
                        continue
                    }

                    // Если на пути стоит игнорируемое препятствие (трава, вода, воздух) — можно строить поверх
                    if (currentBlock.isIgnorableObstacle()) {
                        candidates.add(block)
                        continue
                    }

                    // ИНАЧЕ: Это твердый блок (например, камень), который мешает постройке стены. Его нужно сломать!
                    obstacles.add(block)
                }

                // ВАЖНО: Сначала жители должны разбить твердые препятствия на текущем Y-слое
                if (obstacles.isNotEmpty()) {
                    val closest = obstacles.minByOrNull { it.pos.distSqr(npcPos) }
                    closest?.claimedBy = npc
                    return closest
                }

                // Если препятствий нет, ставим сами блоки постройки
                if (candidates.isNotEmpty()) {
                    val minPriority = candidates.minOf { getPlacementPriority(it.blockData.material) }
                    val priorityBlocks = candidates.filter { getPlacementPriority(it.blockData.material) == minPriority }
                    val closest = priorityBlocks.minByOrNull { it.pos.distSqr(npcPos) }
                    closest?.claimedBy = npc
                    return closest
                }
            }
        }

        return null
    }

    fun claimNextBlock(npc: HumanoidVillager): BlockToPlace? {
        synchronized(lock) {
            val npcPos = npc.blockPosition()
            val available = getBlocks().filter { !it.isPlaced && it.claimedBy == null }

            if (available.isEmpty()) return null

            val buildingBlocks = available.filter { !it.isRoad }
            val buildingClaim = claimFromListOptimized(buildingBlocks, npc, npcPos)
            if (buildingClaim != null) return buildingClaim

            val roadBlocks = available.filter { it.isRoad }
            return claimFromListOptimized(roadBlocks, npc, npcPos)
        }
    }

    fun unclaimBlock(block: BlockToPlace) {
        synchronized(lock) {
            val claimer = block.claimedBy
            if (claimer != null && claimer.assignedBlock == block) {
                claimer.assignedBlock = null
                claimer.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                claimer.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
            }
            block.claimedBy = null
        }
    }

    fun completeBlock(block: BlockToPlace) {
        synchronized(lock) {
            block.isPlaced = true
            val claimer = block.claimedBy
            if (claimer != null && claimer.assignedBlock == block) {
                claimer.assignedBlock = null
                claimer.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                claimer.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
            }
            block.claimedBy = null
        }
    }

    fun isFinished(): Boolean {
        synchronized(lock) {
            return getBlocks().all { it.isPlaced }
        }
    }
}
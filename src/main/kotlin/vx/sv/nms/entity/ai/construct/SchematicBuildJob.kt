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
            material == Material.WATER || material == Material.LAVA -> 100
            name.contains("DOOR") || name.contains("BED") -> 50
            name.contains("TORCH") || name.contains("LANTERN") || name.contains("CARPET") || name.contains("SIGN") -> 20
            else -> 0
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

        // ОПТИМИЗАЦИЯ TPS: Мы сканируем только самый нижний незаконченный Y-слой,
        // вместо тысяч блоков по всей высоте здания.
        val minY = list.minOf { it.pos.y }
        val activeLayer = list.filter { it.pos.y == minY }

        val obstacles = mutableListOf<BlockToPlace>()
        val candidates = mutableListOf<BlockToPlace>()

        for (block in activeLayer) {
            val currentBlock = world.getBlockAt(block.pos.x, block.pos.y, block.pos.z)
            val currentType = currentBlock.type

            val isAirCorrect = currentType.isAir && block.blockData.material.isAir
            val isBlockCorrect = currentBlock.blockData == block.blockData

            // ЛЕНИВОЕ ЗАВЕРШЕНИЕ: Если игрок сам поставил блок, мы мгновенно помечаем его готовым
            if (isAirCorrect || isBlockCorrect) {
                block.isPlaced = true
                continue
            }

            if (currentBlock.isIgnorableObstacle()) {
                candidates.add(block)
                continue
            }
            if (currentType.isShovelable() && block.blockData.material == Material.DIRT_PATH) {
                candidates.add(block)
                continue
            }
            if (currentBlock.isLiquid && block.blockData.material.isAir) {
                candidates.add(block)
                continue
            }

            obstacles.add(block)
        }

        if (obstacles.isNotEmpty()) {
            val closest = obstacles.minByOrNull { it.pos.distSqr(npcPos) }
            closest?.claimedBy = npc
            return closest
        }

        if (candidates.isNotEmpty()) {
            val minPriority = candidates.minOf { getPlacementPriority(it.blockData.material) }
            val priorityBlocks = candidates.filter { getPlacementPriority(it.blockData.material) == minPriority }
            val closest = priorityBlocks.minByOrNull { it.pos.distSqr(npcPos) }
            closest?.claimedBy = npc
            return closest
        }

        // Если весь Y-слой уже был построен (игроками или сгенерен), возвращаем null.
        // В следующем тике ИИ автоматически возьмет в обработку следующий Y-слой выше.
        return null
    }

    fun claimNextBlock(npc: HumanoidVillager): BlockToPlace? {
        synchronized(lock) {
            val npcPos = npc.blockPosition()
            val blocksList = getBlocks()

            // Сбрасываем зависшие брони от убитых/переназначенных рабочих
            blocksList.forEach { block ->
                val claimer = block.claimedBy
                if (claimer != null) {
                    if (!claimer.isAlive || claimer.activeBuildJob != this || claimer.assignedBlock != block) {
                        block.claimedBy = null
                    }
                }
            }

            val available = blocksList.filter { !it.isPlaced && it.claimedBy == null }
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
            // ОПТИМИЗАЦИЯ TPS: Никаких вызовов world.getBlockAt() в этом методе больше нет.
            // ИИ доверяет кэшированным флагам isPlaced.
            return getBlocks().all { it.isPlaced }
        }
    }
}
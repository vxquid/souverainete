package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import java.util.*

class SchematicBuildJob(val world: World) {
    var jobId: UUID = UUID.randomUUID()

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
                // ИСПРАВЛЕНО: Теперь isRoad = true строится в самую первую очередь!
                // Жители сначала прокладывают инфраструктуру, а уже затем возводят здание.
                sortedBlocksCache = blocksMap.values.sortedWith(
                    compareBy<BlockToPlace> { !it.isRoad }
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

            blocksList.forEach { block ->
                val claimer = block.claimedBy
                if (claimer != null) {
                    if (!claimer.isAlive || claimer.activeBuildJob != this || claimer.assignedBlock != block) {
                        block.claimedBy = null
                    }
                }
            }

            blocksList.filter { !it.isPlaced }.forEach {
                val currentBlock = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z)
                if ((currentBlock.type.isAir && it.blockData.material.isAir) || currentBlock.blockData == it.blockData) {
                    it.isPlaced = true
                    it.claimedBy = null
                }
            }

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

            val buildCandidates = blocksList.filter {
                !it.isPlaced && it.claimedBy == null
            }

            if (buildCandidates.isEmpty()) return null

            val minY = buildCandidates.minOf { it.pos.y }
            val lowestYBlocks = buildCandidates.filter { it.pos.y == minY }

            val minPriority = lowestYBlocks.minOf { getPlacementPriority(it.blockData.material) }
            val priorityBlocks = lowestYBlocks.filter { getPlacementPriority(it.blockData.material) == minPriority }

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
package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import org.bukkit.entity.Villager as BukkitVillager

class SchematicBuildJob(val world: World) {
    private val blocks = mutableListOf<BlockToPlace>()
    private val lock = Any()

    fun addBlock(pos: BlockPos, blockData: BlockData) {
        synchronized(lock) {
            blocks.add(BlockToPlace(pos, blockData))
            // Сортируем блоки по высоте (Y), чтобы строить снизу вверх
            blocks.sortBy { it.pos.y }
        }
    }

    /**
     * Возвращает свободный блок для установки или расчистки на текущем Y-уровне,
     * который физически находится ближе всего к жителю.
     */
    fun claimNextBlock(npc: HumanoidVillager): BlockToPlace? {
        synchronized(lock) {
            val npcPos = npc.blockPosition()
            val bukkitInv = (npc.bukkitEntity as BukkitVillager).inventory

            // 1. ПРОВЕРКА НА ПРЕПЯТСТВИЯ: Сканируем схему на наличие блоков, мешающих постройке
            val hasObstructions = blocks.any {
                if (it.isPlaced) return@any false
                val currentType = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z).type
                currentType != Material.AIR && currentType != it.blockData.material
            }

            if (hasObstructions) {
                // ФАЗА РАСЧИСТКИ: ИИ разрешает только расчищать (ломать) блоки, которые мешают
                val candidates = blocks.filter {
                    if (it.isPlaced || it.claimedBy != null) return@filter false
                    val currentType = world.getBlockAt(it.pos.x, it.pos.y, it.pos.z).type
                    currentType != Material.AIR && currentType != it.blockData.material
                }

                if (candidates.isNotEmpty()) {
                    // Раскапываем препятствия строго снизу вверх
                    val minY = candidates.minOf { it.pos.y }
                    val lowestYBlocks = candidates.filter { it.pos.y == minY }

                    // Из нижнего слоя препятствий выбираем ближайший блок к рабочему
                    val closest = lowestYBlocks.minByOrNull { it.pos.distSqr(npcPos) }
                    closest?.claimedBy = npc
                    return closest
                }
            }

            // 2. ФАЗА СТРОИТЕЛЬСТВА: Начинается только когда все препятствия расчищены до состояния воздуха
            val candidates = blocks.filter {
                !it.isPlaced && it.claimedBy == null && bukkitInv.contains(it.material)
            }
            if (candidates.isEmpty()) return null

            val minY = candidates.minOf { it.pos.y }
            val lowestYBlocks = candidates.filter { it.pos.y == minY }
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
            return blocks.all { it.isPlaced }
        }
    }

    fun getBlocks(): List<BlockToPlace> = blocks

}
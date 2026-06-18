package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.type.Stairs
import org.bukkit.util.BlockVector
import vx.sv.util.RelativeBlock

// Модель кандидата входа для расчета вектора разворота
data class EntranceCandidate(val pos: BlockPos, val facing: BlockFace?, val weight: Double)

object VanillaStructureLoader {

    // ИСПРАВЛЕНО: Автоматическое определение реального размера структуры из NBT без хардкода
    fun getStructureSize(structurePath: String): BlockVector {
        if (structurePath == "custom/mine") {
            return BlockVector(7, 6, 7)
        }
        val key = NamespacedKey.minecraft(structurePath)
        val structureManager = Bukkit.getStructureManager()
        val structure = structureManager.getStructure(key)
            ?: structureManager.loadStructure(key)
            ?: return BlockVector(10, 10, 10)
        return structure.size
    }

    /**
     * Нативно загружает встроенную структуру Minecraft из ресурсов сервера по NamespacedKey.
     * Добавлена процедурная генерация шахты "custom/mine" без внешних схематиков.
     */
    fun loadVanillaStructure(structurePath: String): List<RelativeBlock> {
        if (structurePath == "custom/mine") {
            return generateCustomMine()
        }

        val key = NamespacedKey.minecraft(structurePath)
        val structureManager = Bukkit.getStructureManager()

        val structure = structureManager.getStructure(key)
            ?: structureManager.loadStructure(key)
            ?: return emptyList()

        val relativeBlocks = mutableListOf<RelativeBlock>()
        val palette = structure.palettes.firstOrNull() ?: return emptyList()

        palette.blocks.forEach { blockState ->
            if (blockState.type == Material.AIR || blockState.type == Material.JIGSAW) return@forEach

            val relPos = BlockPos(blockState.x, blockState.y, blockState.z)
            val blockData = blockState.blockData

            if (blockData is Ageable) {
                val matName = blockData.material.name
                if (matName.contains("WHEAT") ||
                    matName.contains("CARROT") ||
                    matName.contains("POTATO") ||
                    matName.contains("BEETROOT") ||
                    matName.contains("SWEET_BERRY") ||
                    matName.contains("COCOA") ||
                    matName.contains("NETHER_WART") ||
                    matName.contains("PUMPKIN_STEM") ||
                    matName.contains("MELON_STEM")
                ) {
                    blockData.age = 0
                }
            }

            relativeBlocks.add(RelativeBlock(relPos, blockData))
        }

        return relativeBlocks
    }

    /**
     * Алгоритмически выстраивает шахту со ступенями, балками поддержки и центром забоя.
     */
    private fun generateCustomMine(): List<RelativeBlock> {
        val blocks = mutableListOf<RelativeBlock>()

        val width = 7
        val length = 7
        val minHeight = -4
        val maxHeight = 1

        val airData = Material.AIR.createBlockData()
        val cobbleData = Material.COBBLESTONE.createBlockData()
        val logData = Material.OAK_LOG.createBlockData()
        val fenceData = Material.OAK_FENCE.createBlockData()
        val stoneData = Material.STONE.createBlockData()
        val tableData = Material.SMITHING_TABLE.createBlockData()

        val stairData = Material.COBBLESTONE_STAIRS.createBlockData() as Stairs
        stairData.facing = BlockFace.NORTH

        for (x in 0 until width) {
            for (z in 0 until length) {
                for (y in minHeight..maxHeight) {
                    val pos = BlockPos(x, y, z)
                    val isWall = x == 0 || x == width - 1 || z == 0 || z == length - 1

                    if (isWall) {
                        if (y <= 0) {
                            blocks.add(RelativeBlock(pos, cobbleData))
                        } else if (y == 1) {
                            if ((x == 1 && z == 0) || (x == 0 && z == 1)) {
                                blocks.add(RelativeBlock(pos, airData))
                            } else if ((x == 0 || x == width - 1) && (z == 0 || z == length - 1)) {
                                blocks.add(RelativeBlock(pos, logData)) // угловые столбы
                            } else {
                                blocks.add(RelativeBlock(pos, fenceData))
                            }
                        }
                    } else {
                        if (y == minHeight) {
                            if (x == 3 && z == 3) {
                                blocks.add(RelativeBlock(pos, stoneData))
                            } else if (x == 3 && z == 2) {
                                blocks.add(RelativeBlock(pos, tableData))
                            } else {
                                blocks.add(RelativeBlock(pos, cobbleData))
                            }
                        } else {
                            blocks.add(RelativeBlock(pos, airData))
                        }
                    }
                }
            }
        }

        blocks.add(RelativeBlock(BlockPos(1, 0, 1), stairData))
        blocks.add(RelativeBlock(BlockPos(1, -1, 2), stairData))
        blocks.add(RelativeBlock(BlockPos(1, -2, 3), stairData))
        blocks.add(RelativeBlock(BlockPos(1, -3, 4), stairData))

        for (y in minHeight + 1..0) {
            blocks.add(RelativeBlock(BlockPos(1, y, 1), logData))
            blocks.add(RelativeBlock(BlockPos(5, y, 1), logData))
            blocks.add(RelativeBlock(BlockPos(1, y, 5), logData))
            blocks.add(RelativeBlock(BlockPos(5, y, 5), logData))
        }

        return blocks
    }

    // ИСПРАВЛЕНО: Умный поиск дверей с учетом их направления (facing) и веса для разворота к дороге
    fun getRawEntranceCandidates(structurePath: String): List<EntranceCandidate> {
        if (structurePath == "custom/mine") {
            return listOf(EntranceCandidate(BlockPos(1, 1, 1), BlockFace.NORTH, 10.0))
        }

        val key = NamespacedKey.minecraft(structurePath)
        val structureManager = Bukkit.getStructureManager()
        val structure = structureManager.getStructure(key)
            ?: structureManager.loadStructure(key)
            ?: return emptyList()
        val palette = structure.palettes.firstOrNull() ?: return emptyList()

        val candidates = mutableListOf<EntranceCandidate>()
        palette.blocks.forEach { blockState ->
            val type = blockState.type
            val pos = BlockPos(blockState.x, blockState.y, blockState.z)
            val blockData = blockState.blockData

            if (blockData is org.bukkit.block.data.type.Door) {
                if (blockData.half == org.bukkit.block.data.Bisected.Half.BOTTOM) {
                    candidates.add(EntranceCandidate(pos, blockData.facing, 50.0))
                }
            } else if (blockData is org.bukkit.block.data.type.Stairs) {
                candidates.add(EntranceCandidate(pos, blockData.facing, 10.0))
            } else if (type == Material.JIGSAW) {
                candidates.add(EntranceCandidate(pos, null, 1.0))
            }
        }
        return candidates
    }
}
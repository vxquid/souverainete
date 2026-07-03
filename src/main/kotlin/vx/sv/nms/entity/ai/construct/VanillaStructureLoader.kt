package vx.sv.nms.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.type.Stairs
import org.bukkit.craftbukkit.block.CraftBlockEntityState
import org.bukkit.craftbukkit.block.CraftJigsaw
import org.bukkit.util.BlockVector
import vx.sv.util.RelativeBlock

data class EntranceCandidate(val pos: BlockPos, val facing: BlockFace?, val weight: Double)

object VanillaStructureLoader {

    fun getStructureSize(structurePath: String): BlockVector {
        if (structurePath == "custom/mine") {
            return BlockVector(7, 6, 7)
        }
        if (structurePath == "custom/iron_golem") {
            return BlockVector(3, 3, 3)
        }
        val key = NamespacedKey.minecraft(structurePath)
        val structureManager = Bukkit.getStructureManager()
        val structure = structureManager.getStructure(key)
            ?: structureManager.loadStructure(key)
            ?: return BlockVector(10, 10, 10)
        return structure.size
    }

    fun loadVanillaStructure(structurePath: String): List<RelativeBlock> {
        if (structurePath == "custom/mine") {
            return generateCustomMine()
        }
        if (structurePath == "custom/iron_golem") {
            return generateCustomIronGolem()
        }

        val key = NamespacedKey.minecraft(structurePath)
        val structureManager = Bukkit.getStructureManager()

        val structure = structureManager.getStructure(key)
            ?: structureManager.loadStructure(key)
            ?: return emptyList()

        val relativeBlocks = mutableListOf<RelativeBlock>()
        val palette = structure.palettes.firstOrNull() ?: return emptyList()

        palette.blocks.forEach { blockState ->
            if (blockState.type == Material.AIR || blockState.type == Material.STRUCTURE_VOID) return@forEach

            val relPos = BlockPos(blockState.x, blockState.y, blockState.z)
            var blockData = blockState.blockData

            if (blockState.type == Material.JIGSAW) {
                try {
                    val craftJigsaw = blockState as CraftJigsaw
                    var finalStateStr: String? = null

                    try {
                        val nbt = craftJigsaw.snapshotNBT
                        if (nbt != null && nbt.contains("final_state")) {
                            finalStateStr = nbt.getString("final_state").get()
                        }
                    } catch (_: Exception) {}

                    if (finalStateStr.isNullOrEmpty()) {
                        try {
                            val snapshotField = CraftBlockEntityState::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
                            val nmsJigsaw = snapshotField.get(craftJigsaw) as net.minecraft.world.level.block.entity.JigsawBlockEntity
                            finalStateStr = nmsJigsaw.finalState
                        } catch (_: Exception) {}
                    }

                    if (finalStateStr.isNullOrEmpty()) {
                        try {
                            val tileEntityField = CraftBlockEntityState::class.java.getDeclaredField("tileEntity").apply { isAccessible = true }
                            val nmsJigsaw = tileEntityField.get(craftJigsaw) as net.minecraft.world.level.block.entity.JigsawBlockEntity
                            finalStateStr = nmsJigsaw.finalState
                        } catch (_: Exception) {}
                    }

                    blockData = if (finalStateStr.isNullOrEmpty() || finalStateStr == "minecraft:air" || finalStateStr == "air") {
                        Material.AIR.createBlockData()
                    } else {
                        Bukkit.createBlockData(finalStateStr)
                    }
                } catch (e: Exception) {
                    blockData = Material.AIR.createBlockData()
                }
            }

            if (blockData is Ageable) {
                val matName = blockData.material.name
                if (matName.contains("WHEAT") ||
                    matName.contains("CARROT") ||
                    matName.contains("POTATO") ||
                    matName.contains("BEETROOT") ||
                    matName.contains("SWEET_BERRY") ||
                    matName.contains("COCOA") ||
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

    private fun generateCustomIronGolem(): List<RelativeBlock> {
        val blocks = mutableListOf<RelativeBlock>()
        val iron = Material.IRON_BLOCK.createBlockData()
        val pumpkin = Material.CARVED_PUMPKIN.createBlockData()

        blocks.add(RelativeBlock(BlockPos(1, 0, 1), iron)) // Ноги
        blocks.add(RelativeBlock(BlockPos(1, 1, 1), iron)) // Тело
        blocks.add(RelativeBlock(BlockPos(0, 1, 1), iron)) // Рука 1
        blocks.add(RelativeBlock(BlockPos(2, 1, 1), iron)) // Рука 2
        blocks.add(RelativeBlock(BlockPos(1, 2, 1), pumpkin)) // Голова

        return blocks
    }

    private fun generateCustomMine(): List<RelativeBlock> {
        val blocks = mutableListOf<RelativeBlock>()

        val width = 7
        val length = 7
        val minHeight = -4
        val groundY = 0
        val topY = 1

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
                for (y in minHeight..topY) {
                    val pos = BlockPos(x, y, z)
                    val isWall = x == 0 || x == width - 1 || z == 0 || z == length - 1

                    val isEntrance = (x == 3 && z == 0 && (y == groundY || y == topY))

                    if (isEntrance) {
                        blocks.add(RelativeBlock(pos, airData))
                        continue
                    }

                    if (isWall) {
                        if (y < groundY) {
                            blocks.add(RelativeBlock(pos, cobbleData))
                        } else if (y == groundY) {
                            blocks.add(RelativeBlock(pos, cobbleData))
                        } else if (y == topY) {
                            if ((x == 0 || x == width - 1) && (z == 0 || z == length - 1)) {
                                blocks.add(RelativeBlock(pos, logData))
                            } else {
                                blocks.add(RelativeBlock(pos, fenceData))
                            }
                        }
                    } else {
                        if (y == minHeight) {
                            if (x == 3 && z == 4) {
                                blocks.add(RelativeBlock(pos, tableData))
                            } else if (x == 3 && z == 5) {
                                blocks.add(RelativeBlock(pos, stoneData))
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

        blocks.add(RelativeBlock(BlockPos(3, -1, 1), stairData))
        blocks.add(RelativeBlock(BlockPos(3, -2, 2), stairData))
        blocks.add(RelativeBlock(BlockPos(3, -3, 3), stairData))

        for (y in minHeight + 1..topY) {
            blocks.add(RelativeBlock(BlockPos(1, y, 1), logData))
            blocks.add(RelativeBlock(BlockPos(5, y, 1), logData))
            blocks.add(RelativeBlock(BlockPos(1, y, 5), logData))
            blocks.add(RelativeBlock(BlockPos(5, y, 5), logData))
        }

        return blocks
    }

    fun getRawEntranceCandidates(structurePath: String): List<EntranceCandidate> {
        if (structurePath == "custom/mine") {
            return listOf(EntranceCandidate(BlockPos(3, 0, 0), BlockFace.NORTH, 100.0))
        }
        if (structurePath == "custom/iron_golem") {
            return listOf(EntranceCandidate(BlockPos(1, 0, 0), BlockFace.NORTH, 100.0))
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
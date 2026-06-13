package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.Ageable
import vx.sv.util.RelativeBlock

object VanillaStructureLoader {

    /**
     * Нативно загружает встроенную структуру Minecraft из ресурсов сервера по NamespacedKey.
     * Исключает воздух и технические блоки пазлов (JIGSAW).
     */
    fun loadVanillaStructure(structurePath: String): List<RelativeBlock> {
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

            // УЛУЧШЕНИЕ: Если блок является растением/урожаем, сбрасываем его возраст на 0,
            // чтобы жители сажали именно ростки/семена, а не зрелую пшеницу/морковь.
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
}
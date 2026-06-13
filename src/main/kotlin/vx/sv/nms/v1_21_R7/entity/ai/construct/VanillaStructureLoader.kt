package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
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
            // УЛУЧШЕНИЕ: Игнорируем воздух и технические блоки пазлов (Jigsaw)
            if (blockState.type == Material.AIR || blockState.type == Material.JIGSAW) return@forEach

            val relPos = BlockPos(blockState.x, blockState.y, blockState.z)
            relativeBlocks.add(RelativeBlock(relPos, blockState.blockData))
        }

        return relativeBlocks
    }
}
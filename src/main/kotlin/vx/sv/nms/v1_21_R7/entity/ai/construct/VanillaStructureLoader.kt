package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.BlockData

object VanillaStructureLoader {

    /**
     * Нативно загружает встроенную структуру Minecraft из ресурсов сервера по NamespacedKey.
     * Например: "village/plains/houses/plains_weaponsmith_1"
     */
    fun loadVanillaStructure(structurePath: String): List<RelativeBlock> {
        val key = NamespacedKey.minecraft(structurePath)
        val structureManager = Bukkit.getStructureManager()
        
        // Пытаемся получить зарегистрированную структуру или загрузить её из ресурсов Minecraft
        val structure = structureManager.getStructure(key) 
            ?: structureManager.loadStructure(key) 
            ?: return emptyList()

        val relativeBlocks = mutableListOf<RelativeBlock>()

        // Считываем первую палитру блоков структуры (обычно она одна)
        val palette = structure.palettes.firstOrNull() ?: return emptyList()

        palette.blocks.forEach { blockState ->
            // Пропускаем воздух для оптимизации очереди
            if (blockState.type == Material.AIR) return@forEach

            // Нативные координаты BlockState внутри структуры УЖЕ являются относительным смещением!
            val relPos = BlockPos(blockState.x, blockState.y, blockState.z)
            relativeBlocks.add(RelativeBlock(relPos, blockState.blockData))
        }

        return relativeBlocks
    }
}

/**
 * Отрезок структуры с относительными координатами
 */
data class RelativeBlock(
    val relativePos: BlockPos,
    val blockData: BlockData
)
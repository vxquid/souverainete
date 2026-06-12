package vx.sv.util

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import vx.sv.Souverainete.Companion.plugin
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

object SchematicLoader {

    private fun findResourceStream(path: String): InputStream? {
        var stream = plugin.getResource(path)
        if (stream != null) return stream

        stream = SchematicLoader::class.java.classLoader.getResourceAsStream(path)
        if (stream != null) return stream

        stream = SchematicLoader::class.java.getResourceAsStream("/$path")
        if (stream != null) return stream

        val externalFile = File(plugin.dataFolder, path)
        if (externalFile.exists()) {
            return FileInputStream(externalFile)
        }

        return null
    }

    /**
     * Загружает файл Sponge Schematic (.schem).
     * Возвращает список блоков с относительными координатами.
     */
    fun loadSchematicFromJar(resourcePath: String): List<RelativeBlock> {
        val stream: InputStream = findResourceStream(resourcePath)
            ?: throw FileNotFoundException("Файл схемы '$resourcePath' не найден ни в ресурсах JAR, ни в папке плагина!")

        val relativeBlocks = mutableListOf<RelativeBlock>()

        try {
            plugin.logger.info("[SchematicLoader] Начинаю чтение NBT из: $resourcePath")

            // Читаем сжатый GZip NBT файл
            val rootTag: CompoundTag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap())

            // WorldEdit иногда оборачивает схему в тег "Schematic"
            val schematicTag = if (rootTag.contains("Schematic")) {
                rootTag.getCompound("Schematic").orElse(rootTag)
            } else rootTag

            val width = schematicTag.getShort("Width").orElse(0).toInt()
            val height = schematicTag.getShort("Height").orElse(0).toInt()
            val length = schematicTag.getShort("Length").orElse(0).toInt()

            plugin.logger.info("[SchematicLoader] Размеры схемы: W:$width x H:$height x L:$length")

            if (width == 0 || height == 0 || length == 0) {
                plugin.logger.warning("[SchematicLoader] Ошибка: Одно из измерений равно нулю. Файл поврежден?")
                return emptyList()
            }

            val offset = schematicTag.getIntArray("Offset").orElse(intArrayOf(0, 0, 0))
            val offsetX = offset.getOrNull(0) ?: 0
            val offsetY = offset.getOrNull(1) ?: 0
            val offsetZ = offset.getOrNull(2) ?: 0

            val paletteCompound: CompoundTag
            val blockDataBytes: ByteArray

            // Проверяем формат файла (Sponge V2 или Sponge V3)
            if (schematicTag.contains("Palette") && schematicTag.contains("BlockData")) {
                plugin.logger.info("[SchematicLoader] Формат распознан как Sponge Schematic V2")
                paletteCompound = schematicTag.getCompound("Palette").orElse(null) ?: return emptyList()
                blockDataBytes = schematicTag.getByteArray("BlockData").orElse(null) ?: return emptyList()
            } else if (schematicTag.contains("Blocks")) {
                plugin.logger.info("[SchematicLoader] Формат распознан как Sponge Schematic V3")
                val blocksTag = schematicTag.getCompound("Blocks").orElse(null) ?: return emptyList()
                paletteCompound = blocksTag.getCompound("Palette").orElse(null) ?: return emptyList()
                blockDataBytes = blocksTag.getByteArray("Data").orElse(null) ?: return emptyList()
            } else {
                plugin.logger.warning("[SchematicLoader] Ошибка: Несовместимый формат! Доступные ключи NBT: ${schematicTag.keySet().joinToString(", ")}")
                return emptyList()
            }

            // Переводим палитру из NBT в объекты Bukkit BlockData
            val reversePalette = HashMap<Int, BlockData>()
            for (key in paletteCompound.keySet()) {
                val value = paletteCompound.getInt(key).orElse(0)
                try {
                    reversePalette[value] = Bukkit.createBlockData(key)
                } catch (e: Exception) {
                    plugin.logger.warning("[SchematicLoader] Неизвестный блок в палитре (пропущен и заменён на воздух): $key")
                    reversePalette[value] = Material.AIR.createBlockData()
                }
            }

            plugin.logger.info("[SchematicLoader] Загружено элементов в палитре: ${reversePalette.size}")

            val decodedIndices = readVarInts(blockDataBytes)
            plugin.logger.info("[SchematicLoader] Распаковано индексов: ${decodedIndices.size} (Ожидалось: ${width * height * length})")

            var index = 0
            for (y in 0 until height) {
                for (z in 0 until length) {
                    for (x in 0 until width) {
                        if (index >= decodedIndices.size) break

                        val paletteIndex = decodedIndices[index]
                        index++

                        val blockData = reversePalette[paletteIndex] ?: continue

                        // Пропускаем воздух, чтобы NPC не тратили время на установку пустоты
                        if (blockData.material == Material.AIR) continue

                        // Складываем относительную позицию с учетом WorldEdit Offset
                        val relativePos = BlockPos(x + offsetX, y + offsetY, z + offsetZ)
                        relativeBlocks.add(RelativeBlock(relativePos, blockData))
                    }
                }
            }

            plugin.logger.info("[SchematicLoader] Успешно сформировано ${relativeBlocks.size} реальных блоков для постройки (воздух пропущен).")

        } catch (e: Exception) {
            plugin.logger.severe("[SchematicLoader] Критическая ошибка при чтении NBT:")
            e.printStackTrace()
        } finally {
            stream.close()
        }

        return relativeBlocks
    }

    /**
     * Побитово разбирает массив байтов в массив целочисленных VarInt индексов палитры.
     */
    private fun readVarInts(bytes: ByteArray): IntArray {
        val list = mutableListOf<Int>()
        var index = 0
        while (index < bytes.size) {
            var value = 0
            var shift = 0
            while (index < bytes.size) {
                val b = bytes[index].toInt()
                index++
                value = value or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            list.add(value)
        }
        return list.toIntArray()
    }
}

/**
 * Отрезок структуры с относительными координатами от точки привязки
 */
data class RelativeBlock(
    val relativePos: BlockPos,
    val blockData: BlockData
)
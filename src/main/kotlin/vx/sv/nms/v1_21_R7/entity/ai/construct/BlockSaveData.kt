package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Облегченные DTO структуры для безопасной JSON-сериализации
data class BlockSaveData(
    val x: Int, val y: Int, val z: Int,
    val blockDataStr: String,
    val isPlaced: Boolean
)

data class JobSaveData(
    val jobId: String,
    val blocks: List<BlockSaveData>
)

object BuildJobManager {

    private val worldJobsKey = NamespacedKey(plugin, "active_build_jobs")
    val villagerJobKey = NamespacedKey(plugin, "active_build_job_uuid")

    // Глобальная карта активных строительных задач в оперативной памяти
    val activeJobs = ConcurrentHashMap<UUID, SchematicBuildJob>()

    /**
     * Сериализует и записывает все активные задачи из ОЗУ в PDC главного мира.
     */
    fun saveJobsToWorld() {
        val world = Bukkit.getWorlds().firstOrNull() ?: return
        val pdc = world.persistentDataContainer

        val saveDataList = activeJobs.map { (uuid, job) ->
            val blockSaves = job.getBlocks().map { block ->
                BlockSaveData(
                    block.pos.x, block.pos.y, block.pos.z,
                    block.blockData.asString,
                    block.isPlaced
                )
            }
            JobSaveData(uuid.toString(), blockSaves)
        }

        pdc.set(worldJobsKey, PersistentDataType.STRING, gson.toJson(saveDataList))
    }

    /**
     * Загружает сохраненные сессии из PDC мира обратно в память сервера.
     * Вызовите этот метод внутри onEnable() вашего главного класса плагина.
     */
    fun loadJobsFromWorld() {
        val world = Bukkit.getWorlds().firstOrNull() ?: return
        val pdc = world.persistentDataContainer

        val json = pdc.get(worldJobsKey, PersistentDataType.STRING) ?: return
        try {
            val typeToken = object : com.google.gson.reflect.TypeToken<List<JobSaveData>>() {}.type
            val savedList: List<JobSaveData> = gson.fromJson(json, typeToken) ?: return

            activeJobs.clear()

            savedList.forEach { saveData ->
                val jobId = UUID.fromString(saveData.jobId)
                val job = SchematicBuildJob(world)

                saveData.blocks.forEach { bSave ->
                    val pos = BlockPos(bSave.x, bSave.y, bSave.z)
                    val blockData = Bukkit.createBlockData(bSave.blockDataStr)
                    
                    job.addBlock(pos, blockData)
                    // Восстанавливаем флаг успешной установки блока
                    if (bSave.isPlaced) {
                        job.getBlocks().lastOrNull()?.isPlaced = true
                    }
                }

                activeJobs[jobId] = job
            }
            plugin.logger.info("[BuildJobManager] Успешно восстановлено строительных задач из PDC мира: ${activeJobs.size}")
        } catch (e: Exception) {
            plugin.logger.severe("[BuildJobManager] Критическая ошибка при загрузке задач из PDC:")
            e.printStackTrace()
        }
    }
}
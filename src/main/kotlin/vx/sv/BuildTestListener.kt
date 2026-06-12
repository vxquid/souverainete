package vx.sv

import net.minecraft.core.BlockPos
import org.bukkit.Material
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import vx.sv.Souverainete.Companion.plugin
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import vx.sv.nms.v1_21_R7.entity.ai.construct.SchematicBuildJob
import vx.sv.nms.v1_21_R7.entity.ai.construct.toBaseIngredient
import vx.sv.util.SchematicLoader
import java.util.*
import org.bukkit.entity.Villager as BukkitVillager

class BuildTestListener : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player

        if (event.action == Action.RIGHT_CLICK_BLOCK && event.material == Material.STICK) {
            val clickedBlock = event.clickedBlock ?: return
            event.isCancelled = true

            player.sendMessage("§7[DEBUG] Клик палкой по блоку обнаружен успешно.")

            val origin = clickedBlock.location
            val world = origin.world ?: return

            val relativeBlocks = try {
                SchematicLoader.loadSchematicFromJar("orc_hall_t1.schem")
            } catch (e: Exception) {
                player.sendMessage("§c[DEBUG] Ошибка при распаковке NBT файла: ${e.message}")
                e.printStackTrace()
                return
            }

            if (relativeBlocks.isEmpty()) {
                player.sendMessage("§c[DEBUG] Схематик пуст или не найден!")
                return
            }

            player.sendMessage("§a[DEBUG] Схематик загружен! Найдено блоков для установки: ${relativeBlocks.size}")

            val buildJob = SchematicBuildJob(world)
            relativeBlocks.forEach { relBlock ->
                val absolutePos = BlockPos(
                    origin.blockX + relBlock.relativePos.x,
                    origin.blockY + relBlock.relativePos.y,
                    origin.blockZ + relBlock.relativePos.z
                )
                buildJob.addBlock(absolutePos, relBlock.blockData)
            }

            val nearbyEntities = player.getNearbyEntities(20.0, 20.0, 20.0)
            val nearbyVillagers = nearbyEntities
                .filterIsInstance<BukkitVillager>()
                .mapNotNull { (it as? CraftVillager)?.handle as? HumanoidVillager }

            if (nearbyVillagers.isEmpty()) {
                player.sendMessage("§cПоблизости не найдено кастомных NPC HumanoidVillager!")
                return
            }

            player.sendMessage("§aНайдено ${nearbyVillagers.size} рабочих. Запуск строительства...")

            // Генерируем уникальный ID для новой задачи
            val jobId = UUID.randomUUID()
            BuildJobManager.activeJobs[jobId] = buildJob
            BuildJobManager.saveJobsToWorld() // Записываем в PDC

            nearbyVillagers.forEach { npc ->
                val bukkitNpc = npc.bukkitEntity as BukkitVillager

                // Выдаем все нужные блоки из шематика, конвертируя их в базовые
                relativeBlocks.forEach { block ->
                    bukkitNpc.inventory.addItem(ItemStack(block.blockData.material.toBaseIngredient()))
                }

                npc.activeBuildJob = buildJob
                npc.assignedBlock = null
                npc.digTicks = 0
                npc.buildTicks = 0

                // Записываем UUID задачи в PDC жителя для персистентности
                bukkitNpc.persistentDataContainer.set(
                    org.bukkit.NamespacedKey(plugin, "active_build_job_uuid"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    jobId.toString()
                )

                npc.brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
                npc.brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET)
            }
        }
    }
}
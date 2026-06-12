package vx.sv

import net.minecraft.core.BlockPos
import org.bukkit.Material
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import vx.sv.nms.v1_21_R7.entity.ai.build.SchematicBuildJob
import org.bukkit.entity.Villager as BukkitVillager

class BuildTestListener : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        
        // Срабатывает при клике ПРАВОЙ кнопкой мыши обычной ПАЛКОЙ по блоку
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.material == Material.STICK) {
            val clickedBlock = event.clickedBlock ?: return
            event.isCancelled = true

            val origin = clickedBlock.location
            val world = origin.world ?: return

            // 1. Создаем задачу постройки стены 3x3 из дубовых досок (OAK_PLANKS)
            val buildJob = SchematicBuildJob(world)

            for (yOffset in 1..3) { // 3 блока в высоту
                for (xOffset in -1..1) { // 3 блока в ширину (влево-вправо от клика)
                    val targetLoc = origin.clone().add(xOffset.toDouble(), yOffset.toDouble(), 0.0)
                    
                    // Тест разрушения препятствия: установим землю (DIRT) в центральный нижний слот
                    if (yOffset == 1 && xOffset == 0) {
                        targetLoc.block.type = Material.DIRT
                    } else {
                        targetLoc.block.type = Material.AIR
                    }

                    // Целевой блок для установки
                    val blockData = Material.OAK_PLANKS.createBlockData()
                    val nmsPos = BlockPos(targetLoc.blockX, targetLoc.blockY, targetLoc.blockZ)
                    buildJob.addBlock(nmsPos, blockData)
                }
            }

            // 2. Ищем ваших кастомных HumanoidVillager в радиусе 20 блоков
            val nearbyVillagers = player.getNearbyEntities(20.0, 20.0, 20.0)
                .filterIsInstance<BukkitVillager>()
                .mapNotNull { (it as? CraftVillager)?.handle as? HumanoidVillager }

            if (nearbyVillagers.isEmpty()) {
                player.sendMessage("§cПоблизости не найдено кастомных NPC HumanoidVillager!")
                return
            }

            player.sendMessage("§aНайдено ${nearbyVillagers.size} рабочих. Запуск строительства...")

            // 3. Выдаем им блоки дубовых досок в нативный инвентарь и назначаем задачу
            nearbyVillagers.forEach { npc ->
                val bukkitNpc = npc.bukkitEntity as BukkitVillager
                
                // Выдаем стак досок
                bukkitNpc.inventory.addItem(ItemStack(Material.OAK_PLANKS, 64))
                
                // Назначаем задачу и сбрасываем предыдущие состояния
                npc.activeBuildJob = buildJob
                npc.assignedBlock = null
                npc.digTicks = 0
                
                // Очищаем старые цели ИИ, чтобы форсировать пересчет планировщика
                npc.brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
                npc.brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET)
            }
        }
    }
}
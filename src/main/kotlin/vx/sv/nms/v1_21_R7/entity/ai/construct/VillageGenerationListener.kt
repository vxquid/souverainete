package vx.sv.nms.v1_21_R7.entity.ai.construct

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.AsyncStructureSpawnEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import java.util.*
import org.bukkit.entity.Villager as BukkitVillager

class VillageGenerationListener : Listener {

    /**
     * Программное выравнивание центральной плазы (ратуши) перед спавном колокола и жителей.
     * Срезает любые возвышенности и заполняет пустоты снизу.
     */
    private fun terraformPlaza(center: Location, radius: Int) {
        val world = center.world ?: return
        val targetY = center.blockY

        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val blockX = center.blockX + x
                val blockZ = center.blockZ + z

                // 1. Укладываем плоскую поверхность
                val groundBlock = world.getBlockAt(blockX, targetY, blockZ)
                if (groundBlock.type != Material.GRASS_BLOCK) {
                    groundBlock.type = Material.GRASS_BLOCK
                }

                // 2. Расчищаем воздушное пространство над плазой (срезаем холмы, листву и деревья)
                for (y in 1..4) {
                    val airBlock = world.getBlockAt(blockX, targetY + y, blockZ)
                    if (airBlock.type != Material.AIR && airBlock.type != Material.BEDROCK) {
                        airBlock.type = Material.AIR
                    }
                }

                // 3. Засыпаем ямы под плазой землей на 3 блока вниз
                for (y in 1..3) {
                    val dirtBlock = world.getBlockAt(blockX, targetY - y, blockZ)
                    if (dirtBlock.type.isAir) {
                        dirtBlock.type = Material.DIRT
                    }
                }
            }
        }
    }

    @EventHandler
    fun onVanillaVillageSpawn(event: AsyncStructureSpawnEvent) {
        val structure = event.structure

        // Перехватываем генерацию любой ванильной деревни
        if (structure.key.key.lowercase().contains("village")) {
            event.isCancelled = true // Полностью отменяем ванильную застройку чанка

            val world = event.world
            val boundingBox = event.boundingBox

            // Вычисляем географический центр отмененной деревни
            val centerX = (boundingBox.minX + boundingBox.maxX) / 2
            val centerZ = (boundingBox.minZ + boundingBox.maxZ) / 2

            // Откладываем выполнение на 20 тиков (1 секунда) для безопасности потоков Paper
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!world.worldFolder.exists()) return@Runnable

                val centerY = world.getHighestBlockYAt(centerX.toInt(), centerZ.toInt())
                val centerLoc = Location(world, centerX.toDouble(), centerY.toDouble(), centerZ.toDouble())

                // 1. Производим программный терраформинг центральной площади (радиус 5 блоков = плаза 11x11)
                terraformPlaza(centerLoc, 5)

                // 2. Устанавливаем каменную плиту в центре
                val slabBlock = centerLoc.block
                slabBlock.type = Material.STONE_SLAB

                // 3. Устанавливаем колокол поверх плиты
                val bellBlock = slabBlock.getRelative(BlockFace.UP)
                bellBlock.type = Material.BELL

                // 4. Создаем стартовых жителей и спавним их плотно в центре плазы
                val citizens = mutableSetOf<BukkitVillager>()
                for (i in 0 until 4) {
                    val spawnLoc = centerLoc.clone().add(0.5, 1.0, 0.5)
                    val v = world.spawn(spawnLoc, BukkitVillager::class.java) { villager ->
                        villager.profession = BukkitVillager.Profession.NONE
                        villager.villagerLevel = 1
                    }
                    citizens.add(v)
                }

                // 5. Формируем нативный пакет данных кастомного поселения
                val newData = Settlement.SettlementData(
                    UUID.randomUUID(),
                    world.uid,
                    "Settlement",
                    centerLoc,
                    System.currentTimeMillis(),
                    "VILLAGER_RACE"
                )

                val settlement = Settlement(newData, citizens)

                // 6. Запускаем генерацию имени
                val manager = SettlementManager()
                manager.generateSettlementName(settlement)

                // 7. Инициализируем планировщик постройки
                val planner = SettlementPlanner(settlement)

                // Запускаем автоматическую планировку кастомной деревни
                planner.planBuilding("BAKERY", 12, 12)
                planner.planBuilding("BLACKSMITH", 14, 14)
                planner.planBuilding("WOOD_FARM", 8, 9)
            }, 20L)
        }
    }
}
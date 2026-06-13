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

    private fun terraformPlaza(center: Location, radius: Int) {
        val world = center.world ?: return
        val targetY = center.blockY

        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val blockX = center.blockX + x
                val blockZ = center.blockZ + z

                val groundBlock = world.getBlockAt(blockX, targetY, blockZ)
                if (groundBlock.type != Material.GRASS_BLOCK) {
                    groundBlock.type = Material.GRASS_BLOCK
                }

                // Очищаем воздух только над поверхностью плато
                for (y in 1..4) {
                    val airBlock = world.getBlockAt(blockX, targetY + y, blockZ)
                    if (airBlock.type != Material.AIR && airBlock.type != Material.BEDROCK) {
                        airBlock.type = Material.AIR
                    }
                }

                // Заполняем пустоты под плато, чтобы ратуша не висела в воздухе
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

        if (structure.key.key.lowercase().contains("village")) {
            event.isCancelled = true // Полностью отменяем ванильную застройку чанка

            val world = event.world
            val boundingBox = event.boundingBox

            val centerX = (boundingBox.minX + boundingBox.maxX) / 2
            val centerZ = (boundingBox.minZ + boundingBox.maxZ) / 2

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!world.worldFolder.exists()) return@Runnable

                // === УЛУЧШЕННЫЙ АЛГОРИТМ ПОИСКА ПЛОСКОГО ПЛАТО ===
                var bestX = centerX.toInt()
                var bestZ = centerZ.toInt()
                var bestY = world.getHighestBlockYAt(bestX, bestZ)
                var minHeightDifference = Int.MAX_VALUE
                var highestElevationOfFlattest = Int.MIN_VALUE

                // Сканируем небольшое смещение в радиусе 6 блоков для поиска оптимальной площадки
                for (ox in -6..6) {
                    for (oz in -6..6) {
                        val cx = centerX.toInt() + ox
                        val cz = centerZ.toInt() + oz

                        var minY = Int.MAX_VALUE
                        var maxY = Int.MIN_VALUE

                        // Проверяем footprint центральной площади (радиус 5 -> площадка 11x11)
                        for (px in -5..5) {
                            for (pz in -5..5) {
                                val hy = SettlementPlanner.getHighestGroundYAt(world, cx + px, cz + pz)
                                if (hy < minY) minY = hy
                                if (hy > maxY) maxY = hy
                            }
                        }

                        val diff = maxY - minY

                        // Предпочтение отдается:
                        // 1. Местам с минимальным перепадом высот (flattest)
                        // 2. При равной плоскости — местам с максимальной высотой (highest elevation / plateau)
                        if (diff < minHeightDifference || (diff == minHeightDifference && maxY > highestElevationOfFlattest)) {
                            minHeightDifference = diff
                            highestElevationOfFlattest = maxY
                            bestX = cx
                            bestZ = cz
                            bestY = maxY // Базовая высота площади выставляется на самый верхний уровень плато
                        }
                    }
                }

                // Итоговая точка центра поселения на плоской возвышенности
                val centerLoc = Location(world, bestX.toDouble(), bestY.toDouble(), bestZ.toDouble())

                // 1. Терраформинг центральной площади (теперь только выравнивает досыпанием, не копает ям)
                terraformPlaza(centerLoc, 5)

                // 2. Устанавливаем каменную плиту в центре
                val slabBlock = centerLoc.block
                slabBlock.type = Material.STONE_SLAB

                // 3. Устанавливаем колокол поверх плиты
                val bellBlock = slabBlock.getRelative(BlockFace.UP)
                bellBlock.type = Material.BELL

                // 4. Создаем стартовых жителей (спавним чуть в стороне, чтобы не застряли в колоколе)
                val citizens = mutableSetOf<BukkitVillager>()
                for (i in 0 until 4) {
                    val spawnLoc = centerLoc.clone().add(1.5, 1.0, 1.5)
                    val v = world.spawn(spawnLoc, BukkitVillager::class.java) { villager ->
                        villager.profession = BukkitVillager.Profession.NONE
                        villager.villagerLevel = 1
                    }
                    citizens.add(v)
                }

                // 5. Формируем пакет данных кастомного поселения
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

                // 8. Планируем домики (они теперь привяжутся к высокому уровню центра и не будут проваливаться вниз)
                planner.planBuilding(VanillaBuildingType.BLACKSMITH)
                planner.planBuilding(VanillaBuildingType.BAKERY)
                planner.planBuilding(VanillaBuildingType.FARM)
                planner.planBuilding(VanillaBuildingType.LIBRARY)
                planner.planBuilding(VanillaBuildingType.HOUSE_SMALL)
            }, 20L)
        }
    }
}
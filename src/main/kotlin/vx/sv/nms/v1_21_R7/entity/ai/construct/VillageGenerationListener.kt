package vx.sv.nms.v1_21_R7.entity.ai.construct

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.world.AsyncStructureSpawnEvent
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.SettlementManager.Companion.settlements
import java.util.*
import org.bukkit.entity.Villager as BukkitVillager

class VillageGenerationListener : Listener {

    private fun spawnVillageAnimals(center: Location) {
        val world = center.world ?: return
        val random = java.util.Random()

        val sheepNames = listOf("Шон", "Кудряш", "Облачко", "Снежок", "Зефир", "Долли", "Пуговка")
        val sheepCount = 4 + random.nextInt(3)

        for (i in 0 until sheepCount) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 4.0 + random.nextDouble() * 4.0
            val spawnLoc = center.clone().add(Math.cos(angle) * distance, 1.0, Math.sin(angle) * distance)

            val groundY = SettlementPlanner.getHighestGroundYAt(world, spawnLoc.blockX, spawnLoc.blockZ)
            spawnLoc.y = groundY.toDouble() + 1.0

            world.spawn(spawnLoc, org.bukkit.entity.Sheep::class.java) { sheep ->
                val name = sheepNames.getOrElse(i % sheepNames.size) { "Деревенская овца" }
                // ИСПРАВЛЕНО: Убран цветовой код § из текста компонента для предотвращения LegacyFormattingDetected
                sheep.customName(net.kyori.adventure.text.Component.text(name, net.kyori.adventure.text.format.NamedTextColor.GREEN))
                sheep.isCustomNameVisible = true

                sheep.persistentDataContainer.set(
                    NamespacedKey(plugin, "village_animal"),
                    PersistentDataType.BYTE,
                    1.toByte()
                )
            }
        }

        val catNames = listOf("Мурзик", "Барсик", "Рыжик", "Уголек", "Пушок")
        for (i in 0 until 2) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 2.0 + random.nextDouble() * 3.0
            val spawnLoc = center.clone().add(Math.cos(angle) * distance, 1.0, Math.sin(angle) * distance)

            val groundY = SettlementPlanner.getHighestGroundYAt(world, spawnLoc.blockX, spawnLoc.blockZ)
            spawnLoc.y = groundY.toDouble() + 1.0

            world.spawn(spawnLoc, org.bukkit.entity.Cat::class.java) { cat ->
                val name = catNames.getOrElse(i % catNames.size) { "Деревенский кот" }
                // ИСПРАВЛЕНО: Убран цветовой код § из текста компонента для предотвращения LegacyFormattingDetected
                cat.customName(net.kyori.adventure.text.Component.text(name, net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                cat.isCustomNameVisible = true

                cat.persistentDataContainer.set(
                    NamespacedKey(plugin, "village_animal"),
                    PersistentDataType.BYTE,
                    1.toByte()
                )
            }
        }
    }

    @EventHandler
    fun onPlayerBreakMineBlock(event: BlockBreakEvent) {
        val block = event.block
        if (block.type == Material.STONE) {
            val northBlock = block.getRelative(BlockFace.NORTH)
            if (northBlock.type == Material.SMITHING_TABLE) {
                val world = block.world
                val worldSettlements = SettlementManager.settlements[world] ?: return
                val vector = block.location.toVector()
                val settlement = worldSettlements.find { it.territory.contains(vector) }

                if (settlement != null) {
                    event.isCancelled = true
                    event.player.sendMessage("§c§l[Souverainete] §cВы не можете добывать бесконечную каменную жилу шахты поселения! Она принадлежит шахтерам.")
                }
            }
        }
    }

    @EventHandler
    fun onCampfireDamage(event: EntityDamageEvent) {
        val cause = event.cause
        if (cause == EntityDamageEvent.DamageCause.FIRE ||
            cause == EntityDamageEvent.DamageCause.FIRE_TICK ||
            cause == EntityDamageEvent.DamageCause.HOT_FLOOR) {

            val loc = event.entity.location
            val block = loc.block
            val blockBelow = block.getRelative(BlockFace.DOWN)

            if (block.type == Material.CAMPFIRE ||
                block.type == Material.SOUL_CAMPFIRE ||
                blockBelow.type == Material.CAMPFIRE ||
                blockBelow.type == Material.SOUL_CAMPFIRE) {

                val worldSettlements = settlements[loc.world] ?: return
                // ИСПРАВЛЕНО: Увеличен радиус защиты от горения у костра до 36 блоков, чтобы смещенный костер тоже защищал
                val isNearCenter = worldSettlements.any { it.data.center.distanceSquared(loc) <= 36.0 }
                if (isNearCenter) {
                    event.isCancelled = true
                    event.entity.fireTicks = 0
                }
            }
        }
    }

    @EventHandler
    fun onVanillaVillageSpawn(event: AsyncStructureSpawnEvent) {
        val structure = event.structure

        if (structure.key.key.lowercase().contains("village")) {
            event.isCancelled = true

            val world = event.world
            val boundingBox = event.boundingBox

            val centerX = (boundingBox.minX + boundingBox.maxX) / 2
            val centerZ = (boundingBox.minZ + boundingBox.maxZ) / 2

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!world.worldFolder.exists()) return@Runnable

                var bestX = centerX.toInt()
                var bestZ = centerZ.toInt()
                var bestY = world.getHighestBlockYAt(bestX, bestZ)
                var minHeightDifference = Int.MAX_VALUE
                var highestElevationOfFlattest = Int.MIN_VALUE
                var foundDryLand = false

                for (ox in -12..12) {
                    for (oz in -12..12) {
                        val cx = centerX.toInt() + ox
                        val cz = centerZ.toInt() + oz

                        var minY = Int.MAX_VALUE
                        var maxY = Int.MIN_VALUE
                        var hasWater = false

                        for (px in -5..5) {
                            for (pz in -5..5) {
                                val absX = cx + px
                                val absZ = cz + pz

                                val highestY = world.getHighestBlockYAt(absX, absZ)
                                val surfaceBlock = world.getBlockAt(absX, highestY, absZ)

                                if (surfaceBlock.isLiquid || surfaceBlock.type == Material.WATER || surfaceBlock.type.name.contains("ICE") || surfaceBlock.type.name.contains("WATER")) {
                                    hasWater = true
                                    break
                                }

                                val hy = SettlementPlanner.getHighestGroundYAt(world, absX, absZ)
                                if (hy < minY) minY = hy
                                if (hy > maxY) maxY = hy
                            }
                            if (hasWater) break
                        }

                        if (hasWater) continue

                        foundDryLand = true
                        val diff = maxY - minY

                        if (diff < minHeightDifference || (diff == minHeightDifference && maxY > highestElevationOfFlattest)) {
                            minHeightDifference = diff
                            highestElevationOfFlattest = maxY
                            bestX = cx
                            bestZ = cz
                            bestY = maxY
                        }
                    }
                }

                if (!foundDryLand) {
                    plugin.logger.info("[Souverainete] Спавн поселения отменен: в зоне генерации пригодная суша отсутствует.")
                    return@Runnable
                }

                val centerLoc = Location(world, bestX.toDouble(), bestY.toDouble(), bestZ.toDouble())

                // ИСПРАВЛЕНО: Костер теперь спавнится со смещением на 4 блока на Восток от центра
                // Это предотвращает его автоматическое стирание (замену блоками) при последующей застройке Meeting Point (беседки)
                val groundY = SettlementPlanner.getHighestGroundYAt(world, bestX + 4, bestZ)
                val safeCampfireLoc = Location(world, bestX.toDouble() + 4.0, groundY.toDouble() + 1.0, bestZ.toDouble())
                safeCampfireLoc.block.type = Material.CAMPFIRE

                val citizens = mutableSetOf<BukkitVillager>()
                for (i in 0 until 15) {
                    val spawnLoc = centerLoc.clone().add(1.5, 1.0, 1.5)
                    val v = world.spawn(spawnLoc, BukkitVillager::class.java) { villager ->
                        villager.profession = BukkitVillager.Profession.NONE
                        villager.villagerLevel = 1
                    }
                    citizens.add(v)
                }

                val newData = Settlement.SettlementData(
                    UUID.randomUUID(),
                    world.uid,
                    "Settlement",
                    centerLoc,
                    System.currentTimeMillis(),
                    "VILLAGER_RACE"
                )

                val settlement = Settlement(newData, citizens)

                val manager = SettlementManager()
                manager.generateSettlementName(settlement)

                val planner = SettlementPlanner(settlement)

                spawnVillageAnimals(centerLoc)

                planner.planBuilding(VanillaBuildingType.FARM)
                planner.planBuilding(VanillaBuildingType.FARM)
                planner.planBuilding(VanillaBuildingType.MINE)
                planner.planBuilding(VanillaBuildingType.SHEPHERD)

                planner.planBuilding(VanillaBuildingType.TOWN_HALL)

                planner.planMeetingPointAtCenter()

                repeat(10) {
                    planner.planNextPriorityBuilding()
                }

                SettlementManager.saveSettlements(world)
            }, 20L)
        }
    }
}
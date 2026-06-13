package vx.sv.nms.v1_21_R7.entity.ai.construct

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.AsyncStructureSpawnEvent
import org.bukkit.persistence.PersistentDataType
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

                for (y in 1..4) {
                    val airBlock = world.getBlockAt(blockX, targetY + y, blockZ)
                    if (airBlock.type != Material.AIR && airBlock.type != Material.BEDROCK) {
                        airBlock.type = Material.AIR
                    }
                }

                for (y in 1..3) {
                    val dirtBlock = world.getBlockAt(blockX, targetY - y, blockZ)
                    if (dirtBlock.type.isAir) {
                        dirtBlock.type = Material.DIRT
                    }
                }
            }
        }
    }

    /**
     * Создает начальных деревенских животных с именами вокруг площади
     * и отмечает их в PDC для последующих игровых механик.
     */
    private fun spawnVillageAnimals(center: Location) {
        val world = center.world ?: return
        val random = java.util.Random()

        // 1. Спавним 4-6 овец со случайными именами
        val sheepNames = listOf("Шон", "Кудряш", "Облачко", "Снежок", "Зефир", "Долли", "Пуговка")
        val sheepCount = 4 + random.nextInt(3) // 4 to 6

        for (i in 0 until sheepCount) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 4.0 + random.nextDouble() * 4.0
            val spawnLoc = center.clone().add(Math.cos(angle) * distance, 1.0, Math.sin(angle) * distance)
            spawnLoc.y = world.getHighestBlockYAt(spawnLoc.blockX, spawnLoc.blockZ).toDouble() + 1.0

            world.spawn(spawnLoc, org.bukkit.entity.Sheep::class.java) { sheep ->
                val name = sheepNames.getOrElse(i % sheepNames.size) { "Деревенская овца" }
                sheep.customName(net.kyori.adventure.text.Component.text("§a$name", net.kyori.adventure.text.format.NamedTextColor.GREEN))
                sheep.isCustomNameVisible = true

                // Запись PDC маркера для животного
                sheep.persistentDataContainer.set(
                    NamespacedKey(plugin, "village_animal"),
                    PersistentDataType.BYTE,
                    1.toByte()
                )
            }
        }

        // 2. Спавним 2 деревенских котов около площади
        val catNames = listOf("Мурзик", "Барсик", "Рыжик", "Уголек", "Пушок")
        for (i in 0 until 2) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 2.0 + random.nextDouble() * 3.0
            val spawnLoc = center.clone().add(Math.cos(angle) * distance, 1.0, Math.sin(angle) * distance)
            spawnLoc.y = world.getHighestBlockYAt(spawnLoc.blockX, spawnLoc.blockZ).toDouble() + 1.0

            world.spawn(spawnLoc, org.bukkit.entity.Cat::class.java) { cat ->
                val name = catNames.getOrElse(i % catNames.size) { "Деревенский кот" }
                cat.customName(net.kyori.adventure.text.Component.text("§e$name", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                cat.isCustomNameVisible = true

                // Запись PDC маркера для кота
                cat.persistentDataContainer.set(
                    NamespacedKey(plugin, "village_animal"),
                    PersistentDataType.BYTE,
                    1.toByte()
                )
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

                for (ox in -6..6) {
                    for (oz in -6..6) {
                        val cx = centerX.toInt() + ox
                        val cz = centerZ.toInt() + oz

                        var minY = Int.MAX_VALUE
                        var maxY = Int.MIN_VALUE

                        for (px in -5..5) {
                            for (pz in -5..5) {
                                val hy = SettlementPlanner.getHighestGroundYAt(world, cx + px, cz + pz)
                                if (hy < minY) minY = hy
                                if (hy > maxY) maxY = hy
                            }
                        }

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

                val centerLoc = Location(world, bestX.toDouble(), bestY.toDouble(), bestZ.toDouble())

                terraformPlaza(centerLoc, 5)

                val slabBlock = centerLoc.block
                slabBlock.type = Material.STONE_SLAB

                val bellBlock = slabBlock.getRelative(BlockFace.UP)
                bellBlock.type = Material.BELL

                val citizens = mutableSetOf<BukkitVillager>()
                for (i in 0 until 4) {
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

                // Спавним овец и котов
                spawnVillageAnimals(centerLoc)

                // Инициализация стартовой приоритетной застройки (прогрессивно, одна за другой)
                repeat(5) {
                    planner.planNextPriorityBuilding()
                }

                SettlementManager.saveSettlements(world)
            }, 20L)
        }
    }
}
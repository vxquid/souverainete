package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.Settlement
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

data class BuildingRecord(val type: String, val box: BoundingBox)

class SettlementPlanner(val settlement: Settlement) {

    private val world = settlement.world

    companion object {
        val buildings = ConcurrentHashMap<Settlement, MutableList<BuildingRecord>>()
        val masterBuildJobs = ConcurrentHashMap<Settlement, SchematicBuildJob>()

        init {
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                Bukkit.getOnlinePlayers().forEach { player ->
                    val mainHand = player.inventory.itemInMainHand.type
                    val offHand = player.inventory.itemInOffHand.type

                    if (mainHand == Material.SPYGLASS || offHand == Material.SPYGLASS) {
                        val playerLoc = player.location

                        buildings.forEach { (settlement, records) ->
                            if (settlement.world == player.world && settlement.data.center.distanceSquared(playerLoc) <= 10000.0) {
                                records.forEach { record ->
                                    drawBoxOutline(player, record.box)
                                }
                            }
                        }
                    }
                }
            }, 0L, 10L)
        }

        private fun drawBoxOutline(player: Player, box: BoundingBox) {
            val yMin = box.minY
            val yMax = box.maxY
            val xMin = box.minX
            val xMax = box.maxX
            val zMin = box.minZ
            val zMax = box.maxZ

            drawHorizontalLine(player, xMin, xMax, zMin, yMin, true)
            drawHorizontalLine(player, xMin, xMax, zMax, yMin, true)
            drawHorizontalLine(player, zMin, zMax, xMin, yMin, false)
            drawHorizontalLine(player, zMin, zMax, xMax, yMin, false)

            drawHorizontalLine(player, xMin, xMax, zMin, yMax, true)
            drawHorizontalLine(player, xMin, xMax, zMax, yMax, true)
            drawHorizontalLine(player, zMin, zMax, xMin, yMax, false)
            drawHorizontalLine(player, zMin, zMax, xMax, yMax, false)

            drawVerticalLine(player, xMin, zMin, yMin, yMax)
            drawVerticalLine(player, xMax, zMin, yMin, yMax)
            drawVerticalLine(player, xMin, zMax, yMin, yMax)
            drawVerticalLine(player, xMax, zMax, yMin, yMax)
        }

        private fun drawHorizontalLine(player: Player, min: Double, max: Double, fixed: Double, y: Double, isX: Boolean) {
            var step = min
            while (step <= max) {
                val loc = if (isX) Location(player.world, step, y, fixed) else Location(player.world, fixed, y, step)
                player.spawnParticle(Particle.HAPPY_VILLAGER, loc, 1, 0.0, 0.0, 0.0, 0.0)
                step += 1.0
            }
        }

        private fun drawVerticalLine(player: Player, x: Double, z: Double, yMin: Double, yMax: Double) {
            var stepY = yMin
            while (stepY <= yMax) {
                val loc = Location(player.world, x, stepY, z)
                player.spawnParticle(Particle.HAPPY_VILLAGER, loc, 1, 0.0, 0.0, 0.0, 0.0)
                stepY += 0.5
            }
        }
    }

    init {
        val center = settlement.data.center
        val thBox = BoundingBox(
            center.x - 6.0, center.y - 1.0, center.z - 6.0,
            center.x + 6.0, center.y + 6.0, center.z + 6.0
        )
        val list = buildings.computeIfAbsent(settlement) { mutableListOf() }
        if (list.none { it.type == "TOWN_HALL" }) {
            list.add(BuildingRecord("TOWN_HALL", thBox))
        }
    }

    fun planBuilding(buildingType: String, width: Int, length: Int): Boolean {
        val center = settlement.data.center
        val maxRadius = 50
        val minRadius = 15
        val buildingHeight = 6

        val rand = Random()
        val recordsList = buildings.computeIfAbsent(settlement) { mutableListOf() }

        for (attempt in 0..150) {
            val angle = rand.nextDouble() * 2 * Math.PI
            val distance = rand.nextInt(maxRadius - minRadius) + minRadius

            val cx = center.blockX + (cos(angle) * distance).toInt()
            val cz = center.blockZ + (sin(angle) * distance).toInt()

            val y1 = world.getHighestBlockYAt(cx - width / 2, cz - length / 2)
            val y2 = world.getHighestBlockYAt(cx + width / 2, cz - length / 2)
            val y3 = world.getHighestBlockYAt(cx - width / 2, cz + length / 2)
            val y4 = world.getHighestBlockYAt(cx + width / 2, cz + length / 2)
            val yC = world.getHighestBlockYAt(cx, cz)

            val maxY = max(max(y1, y2), max(y3, max(y4, yC)))
            val minY = min(min(y1, y2), min(y3, min(y4, yC)))

            if (maxY - minY > 6) continue

            val baseY = maxY

            val potentialBox = BoundingBox(
                (cx - width / 2).toDouble(), (baseY - 2).toDouble(), (cz - length / 2).toDouble(),
                (cx + width / 2).toDouble(), (baseY + buildingHeight).toDouble(), (cz + length / 2).toDouble()
            )

            if (recordsList.any { it.box.overlaps(potentialBox) }) continue

            recordsList.add(BuildingRecord(buildingType, potentialBox))
            startProceduralPlatformConstruction(cx, baseY, cz, buildingType, width, length, buildingHeight)
            return true
        }

        plugin.logger.warning("Не удалось найти плоское место для здания $buildingType в поселении ${settlement.data.settlementName}.")
        return false
    }

    private fun startProceduralPlatformConstruction(cx: Int, baseY: Int, cz: Int, buildingType: String, width: Int, length: Int, height: Int) {
        val buildJob = masterBuildJobs.getOrPut(settlement) { SchematicBuildJob(world) }
        val center = settlement.data.center

        // === 1. ГЕНЕРАЦИЯ ШИРОКОЙ РЕЛЬЕФНОЙ ДОРОГИ ===
        var currentX = center.blockX
        var currentZ = center.blockZ

        val dX = abs(cx - currentX)
        val dZ = abs(cz - currentZ)
        val sX = if (currentX < cx) 1 else -1
        val sZ = if (currentZ < cz) 1 else -1
        var err = dX - dZ

        val roadBlockData = Material.DIRT_PATH.createBlockData()
        val airData = Material.AIR.createBlockData()
        val cobbleData = Material.COBBLESTONE.createBlockData()

        while (true) {
            for (wx in -1..1) {
                for (wz in -1..1) {
                    val px = currentX + wx
                    val pz = currentZ + wz

                    if (abs(px - center.blockX) <= 5 && abs(pz - center.blockZ) <= 5) continue

                    val roadY = world.getHighestBlockYAt(px, pz)
                    val currentBlock = world.getBlockAt(px, roadY, pz)

                    // Срезаем воздух над дорогой
                    for (dy in 1..3) {
                        buildJob.addBlock(BlockPos(px, roadY + dy, pz), airData, isRoad = true)
                    }

                    if (currentBlock.isLiquid) {
                        var groundY = roadY
                        while (groundY > world.minHeight && !world.getBlockAt(px, groundY, pz).type.isSolid) {
                            groundY--
                        }
                        for (dy in groundY..roadY) {
                            buildJob.addBlock(BlockPos(px, dy, pz), cobbleData, isRoad = true)
                        }
                    } else {
                        buildJob.addBlock(BlockPos(px, roadY, pz), roadBlockData, isRoad = true)
                    }
                }
            }

            if (currentX == cx && currentZ == cz) break
            val e2 = 2 * err
            if (e2 > -dZ) {
                err -= dZ
                currentX += sX
            }
            if (e2 < dX) {
                err += dX
                currentZ += sZ
            }
        }

        // === 2. СТРОИТЕЛЬСТВО ПЛАТФОРМЫ И ФУНДАМЕНТА ===
        val targetMaterial = when (buildingType) {
            "BAKERY" -> Material.OAK_PLANKS
            "BLACKSMITH" -> Material.STONE
            "WOOD_FARM" -> Material.SPRUCE_PLANKS
            else -> Material.OAK_PLANKS
        }
        val blockData = targetMaterial.createBlockData()

        val halfWidth = width / 2
        val halfLength = length / 2

        for (x in -halfWidth..halfWidth) {
            for (z in -halfLength..halfLength) {
                val absX = cx + x
                val absZ = cz + z
                val highest = world.getHighestBlockYAt(absX, absZ)

                for (y in baseY..baseY + height) {
                    if (y <= highest) {
                        buildJob.addBlock(BlockPos(absX, y, absZ), airData, isRoad = false)
                    }
                }

                for (y in highest + 1 until baseY) {
                    buildJob.addBlock(BlockPos(absX, y, absZ), cobbleData, isRoad = false)
                }

                val absolutePos = BlockPos(absX, baseY, absZ)
                buildJob.addBlock(absolutePos, blockData, isRoad = false)
            }
        }

        // === 3. УСТАНОВКА ТЕМАТИЧЕСКИХ РАБОЧИХ СТАНЦИЙ В ЦЕНТРЕ ===
        val workstation = when (buildingType) {
            "BAKERY" -> Material.SMOKER
            "BLACKSMITH" -> Material.BLAST_FURNACE
            "WOOD_FARM" -> Material.STONECUTTER
            else -> Material.CRAFTING_TABLE
        }
        // Рабочая станция ставится на +1 блок выше уровня пола платформы
        buildJob.addBlock(BlockPos(cx, baseY + 1, cz), workstation.createBlockData(), isRoad = false)

        // === 4. ПОДКЛЮЧЕНИЕ РАБОЧИХ ===
        val citizens = settlement.villagers.mapNotNull {
            (it as? CraftVillager)?.handle as? HumanoidVillager
        }

        citizens.forEach { npc ->
            val bukkitNpc = npc.bukkitEntity as Villager
            val baseIngredient = targetMaterial.toBaseIngredient()

            bukkitNpc.inventory.addItem(ItemStack(baseIngredient, 640))
            bukkitNpc.inventory.addItem(ItemStack(Material.COBBLESTONE, 640))
            bukkitNpc.inventory.addItem(ItemStack(Material.DIRT, 640))
            bukkitNpc.inventory.addItem(ItemStack(Material.IRON_SHOVEL, 1))

            if (npc.activeBuildJob != buildJob) {
                npc.activeBuildJob = buildJob
                npc.assignedBlock = null
                npc.digTicks = 0
                npc.buildTicks = 0

                npc.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                npc.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
            }
        }
    }
}
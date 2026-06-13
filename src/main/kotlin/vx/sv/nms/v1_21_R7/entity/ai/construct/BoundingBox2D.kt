package vx.sv.nms.v1_21_R7.entity.ai.construct

import com.google.gson.reflect.TypeToken
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import org.bukkit.*
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class BuildingRecord(val type: String, val box: BoundingBox)

// Облегченная DTO структура для безопасного JSON-сохранения 3D-зон застройки
data class BuildingSaveData(
    val type: String,
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double
)

class SettlementPlanner(val settlement: Settlement) {

    private val world = settlement.world

    companion object {
        val buildings = ConcurrentHashMap<Settlement, MutableList<BuildingRecord>>()

        // Очередь невыполненных строительных задач поселения
        val pendingJobs = ConcurrentHashMap<Settlement, Queue<SchematicBuildJob>>()
        // Текущая активная строительная задача поселения
        val activeJobs = ConcurrentHashMap<Settlement, SchematicBuildJob>()

        private val worldBuildingsKey = NamespacedKey(plugin, "settlement_buildings")

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

        /**
         * Получает активную или следующую в очереди задачу строительства для поселения.
         */
        fun getActiveOrNextJob(settlement: Settlement): SchematicBuildJob? {
            val active = activeJobs[settlement]
            if (active != null && !active.isFinished()) {
                return active
            }
            val queue = pendingJobs[settlement] ?: return null
            synchronized(queue) {
                val next = queue.poll()
                if (next != null) {
                    activeJobs[settlement] = next
                    return next
                } else {
                    activeJobs.remove(settlement)
                }
            }
            return null
        }

        /**
         * Сканирует высоту и спускается вертикально вниз сквозь листву, бревна и растения,
         * чтобы найти реальную координату грунта (землю/камень).
         */
        private fun getHighestGroundYAt(world: World, x: Int, z: Int): Int {
            var y = world.getHighestBlockYAt(x, z)
            while (y > world.minHeight) {
                val block = world.getBlockAt(x, y, z)
                val type = block.type
                val isTreeOrPlant = type.name.contains("LEAVES") ||
                        type.name.contains("LOG") ||
                        type.name.contains("WOOD") ||
                        block.isIgnorableObstacle()
                if (isTreeOrPlant) {
                    y--
                } else {
                    break
                }
            }
            return y
        }

        /**
         * Сохраняет все спланированные 3D здания из ОЗУ в PDC мира.
         */
        fun saveBuildingsToWorld(world: World) {
            val pdc = world.persistentDataContainer
            val saveData = mutableMapOf<String, List<BuildingSaveData>>()

            buildings.forEach { (settlement, records) ->
                if (settlement.world == world) {
                    val saves = records.map { record ->
                        BuildingSaveData(
                            record.type,
                            record.box.minX, record.box.minY, record.box.minZ,
                            record.box.maxX, record.box.maxY, record.box.maxZ
                        )
                    }
                    saveData[settlement.data.id.toString()] = saves
                }
            }
            pdc.set(worldBuildingsKey, PersistentDataType.STRING, gson.toJson(saveData))
        }

        /**
         * Загружает все спланированные здания из PDC мира обратно в ОЗУ.
         */
        fun loadBuildingsFromWorld(world: World) {
            val pdc = world.persistentDataContainer
            val json = pdc.get(worldBuildingsKey, PersistentDataType.STRING) ?: return

            try {
                val typeToken = object : TypeToken<Map<String, List<BuildingSaveData>>>() {}.type
                val loadedData: Map<String, List<BuildingSaveData>> = gson.fromJson(json, typeToken) ?: return

                val worldSettlements = SettlementManager.settlements[world] ?: return

                loadedData.forEach { (uuidStr, saves) ->
                    val settlementId = UUID.fromString(uuidStr)
                    val settlement = worldSettlements.find { it.data.id == settlementId } ?: return@forEach

                    val recordList = buildings.computeIfAbsent(settlement) { mutableListOf() }
                    recordList.clear()

                    saves.forEach { save ->
                        val box = BoundingBox(save.minX, save.minY, save.minZ, save.maxX, save.maxY, save.maxZ)
                        recordList.add(BuildingRecord(save.type, box))
                    }
                }
                plugin.logger.info("[SettlementPlanner] Успешно загружено зданий из PDC мира: ${buildings.values.sumOf { it.size }}")
            } catch (e: Exception) {
                plugin.logger.severe("[SettlementPlanner] Ошибка при загрузке зданий из PDC:")
                e.printStackTrace()
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

    fun planBuilding(type: VanillaBuildingType): Boolean {
        val center = settlement.data.center
        val maxRadius = 45
        val minRadius = 15

        val width = type.width
        val length = type.length
        val buildingHeight = type.height

        val rand = Random()
        val recordsList = buildings.computeIfAbsent(settlement) { mutableListOf() }

        for (attempt in 0..250) {
            val angle = rand.nextDouble() * 2 * Math.PI
            val distance = rand.nextInt(maxRadius - minRadius) + minRadius

            val cx = center.blockX + (cos(angle) * distance).toInt()
            val cz = center.blockZ + (sin(angle) * distance).toInt()

            // 1. Полное сканирование отпечатка (Footprint Scan) с фильтрацией деревьев
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var validTerrain = true

            for (x in -width / 2..width / 2) {
                for (z in -length / 2..length / 2) {
                    val absX = cx + x
                    val absZ = cz + z

                    // Сканируем высоту именно реального грунта, пропуская кроны деревьев
                    val hy = getHighestGroundYAt(world, absX, absZ)

                    val blockType = world.getBlockAt(absX, hy, absZ).type
                    if (blockType == Material.WATER || blockType == Material.LAVA) {
                        validTerrain = false
                        break
                    }

                    if (hy < minY) minY = hy
                    if (hy > maxY) maxY = hy
                }
                if (!validTerrain) break
            }

            if (!validTerrain) continue

            // 2. Требование ровности: перепад высот не должен превышать 4 блоков
            if (maxY - minY > 4) continue

            // 3. Правило Плато: Здание не должно быть на дне каньона или высоко на горе относительно ратуши
            if (abs(maxY - center.blockY) > 8) continue

            // 4. Трассировка дороги: проверяем, что на пути нет обрывов или каньонов
            var pathValid = true
            var px = center.blockX
            var pz = center.blockZ
            val dX = abs(cx - px)
            val dZ = abs(cz - pz)
            val sX = if (px < cx) 1 else -1
            val sZ = if (pz < cz) 1 else -1
            var err = dX - dZ

            while (true) {
                val hy = getHighestGroundYAt(world, px, pz)

                // Если дорога падает в каньон или упирается в отвесную скалу — бракуем место
                if (abs(hy - center.blockY) > 9) {
                    pathValid = false
                    break
                }

                if (px == cx && pz == cz) break
                val e2 = 2 * err
                if (e2 > -dZ) { err -= dZ; px += sX }
                if (e2 < dX) { err += dX; pz += sZ }
            }

            if (!pathValid) continue

            val baseY = maxY

            val potentialBox = BoundingBox(
                (cx - width / 2).toDouble(), (baseY - 2).toDouble(), (cz - length / 2).toDouble(),
                (cx + width / 2).toDouble(), (baseY + buildingHeight).toDouble(), (cz + length / 2).toDouble()
            )

            // Проверка на пересечение с другими зданиями (с раздутым буфером)
            val collisionCheckBoundedBox = potentialBox.clone().expand(5.0, 0.0, 5.0)
            if (recordsList.any { it.box.overlaps(collisionCheckBoundedBox) }) continue

            recordsList.add(BuildingRecord(type.typeName, potentialBox))

            // Начинаем застройку
            val buildJob = startVanillaStructureConstruction(cx, baseY, cz, type)

            // Складываем задачу в очередь поселения
            val queue = pendingJobs.computeIfAbsent(settlement) { ConcurrentLinkedQueue() }
            synchronized(queue) {
                queue.offer(buildJob)
            }
            return true
        }

        plugin.logger.warning("Не удалось найти безопасное место для здания ${type.displayName} в поселении ${settlement.data.settlementName}.")
        return false
    }

    private fun startVanillaStructureConstruction(cx: Int, baseY: Int, cz: Int, type: VanillaBuildingType): SchematicBuildJob {
        val buildJob = SchematicBuildJob(world)
        val center = settlement.data.center

        val width = type.width
        val length = type.length
        val height = type.height
        val vanillaPath = type.vanillaPath
        val workstation = type.workstation

        // === 1. ГЕНЕРАЦИЯ БЕЗОПАСНЫХ РЕЛЬЕФНЫХ ДОРОГ ===
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

                    // Находим именно уровень грунта, пропуская кроны деревьев
                    val roadY = getHighestGroundYAt(world, px, pz)
                    val currentBlock = world.getBlockAt(px, roadY, pz)

                    for (dy in 1..3) {
                        buildJob.addBlock(BlockPos(px, roadY + dy, pz), airData, isRoad = true)
                    }

                    if (currentBlock.isLiquid) {
                        // Брод поверх воды
                        buildJob.addBlock(BlockPos(px, roadY, pz), cobbleData, isRoad = true)
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

        // === 2. ТЕРРАФОРМИРОВАНИЕ ПЛОЩАДКИ ===
        val halfWidth = width / 2
        val halfLength = length / 2

        for (x in 0 until width) {
            for (z in 0 until length) {
                val absX = cx - width / 2 + x
                val absZ = cz - length / 2 + z

                // Находим истинный уровень грунта
                val highest = getHighestGroundYAt(world, absX, absZ)

                for (y in baseY..baseY + height) {
                    if (y <= highest) {
                        buildJob.addBlock(BlockPos(absX, y, absZ), airData, isRoad = false)
                    }
                }

                for (y in highest + 1 until baseY) {
                    buildJob.addBlock(BlockPos(absX, y, absZ), cobbleData, isRoad = false)
                }
            }
        }

        // === 3. СТРОИТЕЛЬСТВО НАСТОЯЩЕГО ВАНИЛЬНОГО ЗДАНИЯ ===
        val relativeBlocks = VanillaStructureLoader.loadVanillaStructure(vanillaPath)
        if (relativeBlocks.isEmpty()) return buildJob

        relativeBlocks.forEach { relBlock ->
            val absoluteX = cx - width / 2 + relBlock.relativePos.x
            val absoluteY = baseY + relBlock.relativePos.y
            val absoluteZ = cz - length / 2 + relBlock.relativePos.z
            val absPos = BlockPos(absoluteX, absoluteY, absoluteZ)

            buildJob.addBlock(absPos, relBlock.blockData, isRoad = false)
        }

        // === 4. УСТАНОВКА РАБОЧЕЙ СТАНЦИИ ===
        buildJob.addBlock(BlockPos(cx, baseY + 1, cz), workstation.createBlockData(), isRoad = false)

        // === 5. ПОДКЛЮЧЕНИЕ РАБОЧИХ ===
        val citizens = settlement.villagers.mapNotNull {
            (it as? CraftVillager)?.handle as? HumanoidVillager
        }

        val targetMaterial = when (type) {
            VanillaBuildingType.BAKERY, VanillaBuildingType.HOUSE_SMALL, VanillaBuildingType.HOUSE_MEDIUM -> Material.OAK_PLANKS
            else -> Material.STONE
        }

        citizens.forEach { npc ->
            val bukkitNpc = npc.bukkitEntity as Villager
            val baseIngredient = targetMaterial.toBaseIngredient()

            relativeBlocks.forEach { block ->
                bukkitNpc.inventory.addItem(ItemStack(block.blockData.material.toBaseIngredient()))
            }
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

        return buildJob
    }
}
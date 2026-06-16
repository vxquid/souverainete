package vx.sv.nms.v1_21_R7.entity.ai.construct

import com.google.gson.reflect.TypeToken
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import org.bukkit.*
import org.bukkit.block.structure.StructureRotation
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class BuildingRecord(val type: String, val box: BoundingBox)

data class BuildingSaveData(
    val type: String,
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double
)

data class BlockSaveData(
    val x: Int, val y: Int, val z: Int,
    val blockDataStr: String,
    val isPlaced: Boolean,
    val isRoad: Boolean
)

data class SchematicJobSaveData(
    val jobId: String,
    val blocks: List<BlockSaveData>
)

data class SettlementPlannerSaveData(
    val buildings: List<BuildingSaveData>,
    val activeJob: SchematicJobSaveData?,
    val pendingJobs: List<SchematicJobSaveData>
)

class SettlementPlanner(val settlement: Settlement) {

    private val world = settlement.world

    companion object {
        val buildings = ConcurrentHashMap<UUID, MutableList<BuildingRecord>>()
        val pendingJobs = ConcurrentHashMap<UUID, Queue<SchematicBuildJob>>()
        val activeJobs = ConcurrentHashMap<UUID, SchematicBuildJob>()
        val settlementRoads = ConcurrentHashMap<UUID, MutableSet<BlockPos>>()

        private val worldBuildingsKey = NamespacedKey(plugin, "settlement_buildings_data")

        init {
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                Bukkit.getOnlinePlayers().forEach { player ->
                    val mainHand = player.inventory.itemInMainHand.type
                    val offHand = player.inventory.itemInOffHand.type

                    if (mainHand == Material.SPYGLASS || offHand == Material.SPYGLASS) {
                        val playerLoc = player.location

                        buildings.forEach { (settlementId, records) ->
                            val worldSettlements = SettlementManager.settlements[player.world] ?: return@forEach
                            val settlement = worldSettlements.find { it.data.id == settlementId } ?: return@forEach

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

        fun getActiveOrNextJob(settlement: Settlement): SchematicBuildJob? {
            val settlementId = settlement.data.id
            val active = activeJobs[settlementId]
            if (active != null && !active.isFinished()) {
                return active
            }
            val queue = pendingJobs[settlementId] ?: return null
            synchronized(queue) {
                val next = queue.poll()
                if (next != null) {
                    activeJobs[settlementId] = next
                    return next
                } else {
                    activeJobs.remove(settlementId)
                }
            }
            return null
        }

        fun getHighestGroundYAt(world: World, x: Int, z: Int): Int {
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

        fun compress(data: String): ByteArray {
            val bos = ByteArrayOutputStream()
            val gzos = GZIPOutputStream(bos)
            gzos.write(data.toByteArray(Charsets.UTF_8))
            gzos.close()
            return bos.toByteArray()
        }

        fun decompress(compressed: ByteArray): String {
            val bis = ByteArrayInputStream(compressed)
            val gzis = GZIPInputStream(bis)
            val result = gzis.readBytes().toString(Charsets.UTF_8)
            gzis.close()
            return result
        }

        private fun serializeJob(job: SchematicBuildJob): SchematicJobSaveData {
            val blockSaves = job.getBlocks().map { block ->
                BlockSaveData(
                    block.pos.x, block.pos.y, block.pos.z,
                    block.blockData.asString,
                    block.isPlaced,
                    block.isRoad
                )
            }
            return SchematicJobSaveData(job.jobId.toString(), blockSaves)
        }

        private fun deserializeJob(world: World, saveData: SchematicJobSaveData): SchematicBuildJob {
            val job = SchematicBuildJob(world)
            job.jobId = UUID.fromString(saveData.jobId)

            saveData.blocks.forEach { bSave ->
                val pos = BlockPos(bSave.x, bSave.y, bSave.z)
                val blockData = Bukkit.createBlockData(bSave.blockDataStr)
                job.addBlock(pos, blockData, bSave.isRoad)
                if (bSave.isPlaced) {
                    job.getBlocks().lastOrNull()?.isPlaced = true
                }
            }
            return job
        }

        fun saveBuildingsToWorld(world: World) {
            val pdc = world.persistentDataContainer
            val saveData = mutableMapOf<String, SettlementPlannerSaveData>()

            val worldSettlements = SettlementManager.settlements[world] ?: return
            worldSettlements.forEach { settlement ->
                val settlementId = settlement.data.id
                val records = buildings[settlementId] ?: emptyList()

                val active = activeJobs[settlementId]
                val activeSave = active?.let { serializeJob(it) }

                val queue = pendingJobs[settlementId] ?: emptyList()
                val pendingSaves = queue.map { serializeJob(it) }

                val plannerSaves = records.map { record ->
                    BuildingSaveData(
                        record.type,
                        record.box.minX, record.box.minY, record.box.minZ,
                        record.box.maxX, record.box.maxY, record.box.maxZ
                    )
                }

                saveData[settlementId.toString()] = SettlementPlannerSaveData(
                    plannerSaves,
                    activeSave,
                    pendingSaves
                )
            }

            val compressedBytes = compress(gson.toJson(saveData))
            pdc.set(worldBuildingsKey, PersistentDataType.BYTE_ARRAY, compressedBytes)
        }

        fun loadBuildingsFromWorld(world: World) {
            val pdc = world.persistentDataContainer

            val json = if (pdc.has(worldBuildingsKey, PersistentDataType.BYTE_ARRAY)) {
                val compressedBytes = pdc.get(worldBuildingsKey, PersistentDataType.BYTE_ARRAY) ?: return
                decompress(compressedBytes)
            } else if (pdc.has(worldBuildingsKey, PersistentDataType.STRING)) {
                val legacyJson = pdc.get(worldBuildingsKey, PersistentDataType.STRING) ?: return

                val compressedBytes = compress(legacyJson)
                pdc.set(worldBuildingsKey, PersistentDataType.BYTE_ARRAY, compressedBytes)
                pdc.remove(worldBuildingsKey)

                legacyJson
            } else {
                return
            }

            try {
                val typeToken = object : TypeToken<Map<String, SettlementPlannerSaveData>>() {}.type
                val loadedData: Map<String, SettlementPlannerSaveData> = gson.fromJson(json, typeToken) ?: return

                val worldSettlements = SettlementManager.settlements[world] ?: return

                loadedData.forEach { (uuidStr, data) ->
                    val settlementId = UUID.fromString(uuidStr)
                    val settlement = worldSettlements.find { it.data.id == settlementId } ?: return@forEach

                    val recordList = buildings.computeIfAbsent(settlementId) { mutableListOf() }
                    recordList.clear()
                    data.buildings.forEach { save ->
                        val box = BoundingBox(save.minX, save.minY, save.minZ, save.maxX, save.maxY, save.maxZ)
                        recordList.add(BuildingRecord(save.type, box))
                    }

                    if (data.activeJob != null) {
                        activeJobs[settlementId] = deserializeJob(world, data.activeJob)
                    } else {
                        activeJobs.remove(settlementId)
                    }

                    val queue = pendingJobs[settlementId] ?: return@forEach
                    queue.clear()
                    data.pendingJobs.forEach { jobSave ->
                        queue.offer(deserializeJob(world, jobSave))
                    }
                }
                plugin.logger.info("[SettlementPlanner] Успешно загружено зданий из PDC мира: ${buildings.values.sumOf { it.size }}")
            } catch (e: Exception) {
                plugin.logger.severe("[SettlementPlanner] Ошибка при загрузке зданий из PDC:")
                e.printStackTrace()
            }
        }

        fun rotateCoords(pos: BlockPos, rotation: StructureRotation, width: Int, length: Int): BlockPos {
            return when (rotation) {
                StructureRotation.NONE -> pos
                StructureRotation.CLOCKWISE_90 -> BlockPos(length - 1 - pos.z, pos.y, pos.x)
                StructureRotation.CLOCKWISE_180 -> BlockPos(width - 1 - pos.x, pos.y, length - 1 - pos.z)
                StructureRotation.COUNTERCLOCKWISE_90 -> BlockPos(pos.z, pos.y, width - 1 - pos.x)
            }
        }

        fun getDominantGroundMaterialAt(world: World, cx: Int, cz: Int, width: Int, length: Int): Material {
            val materialCounts = mutableMapOf<Material, Int>()

            for (x in -width / 2..width / 2) {
                for (z in -length / 2..length / 2) {
                    val hy = getHighestGroundYAt(world, cx + x, cz + z)
                    val groundBlock = world.getBlockAt(cx + x, hy, cz + z)
                    val type = groundBlock.type
                    if (type.isSolid && !type.name.contains("LEAVES") && !type.name.contains("LOG") && !type.name.contains("WOOD")) {
                        materialCounts[type] = (materialCounts[type] ?: 0) + 1
                    }
                }
            }

            val dominant = materialCounts.maxByOrNull { it.value }?.key ?: Material.COBBLESTONE
            return when (dominant) {
                Material.GRASS_BLOCK, Material.DIRT_PATH, Material.MYCELIUM, Material.PODZOL -> Material.DIRT
                Material.SAND, Material.RED_SAND, Material.SANDSTONE -> dominant
                else -> if (dominant.isSolid) dominant else Material.COBBLESTONE
            }
        }
    }

    init {
        val center = settlement.data.center
        val thBox = BoundingBox(
            center.x - 6.0, center.y - 1.0, center.z - 6.0,
            center.x + 6.0, center.y + 6.0, center.z + 6.0
        )
        val list = buildings.computeIfAbsent(settlement.data.id) { mutableListOf() }

        if (list.none { it.type == "MEETING_POINT" }) {
            list.add(BuildingRecord("MEETING_POINT", thBox))
        }
    }

    fun planMeetingPointAtCenter(): Boolean {
        val center = settlement.data.center
        val cx = center.blockX
        val cz = center.blockZ
        val type = VanillaBuildingType.MEETING_POINT
        val baseY = center.blockY

        val foundationMaterial = getDominantGroundMaterialAt(world, cx, cz, type.width, type.length)
        val buildJob = startVanillaStructureConstruction(cx, baseY, cz, type, StructureRotation.NONE, foundationMaterial)

        val queue = pendingJobs.computeIfAbsent(settlement.data.id) { ConcurrentLinkedQueue() }
        synchronized(queue) {
            queue.offer(buildJob)
        }
        return true
    }

    fun planNextPriorityBuilding(): Boolean {
        val records = buildings[settlement.data.id] ?: mutableListOf()

        val existingCounts = records.groupingBy { record ->
            val typeName = record.type
            if (typeName.contains("_")) {
                val lastPart = typeName.substringAfterLast("_")
                if (lastPart.toIntOrNull() != null) {
                    typeName.substringBeforeLast("_")
                } else {
                    typeName
                }
            } else {
                typeName
            }
        }.eachCount()

        val totalResidentialBuilt = (existingCounts["HOUSE_SMALL"] ?: 0) + (existingCounts["HOUSE_MEDIUM"] ?: 0)
        val maxResidentialAllowed = settlement.villagers.size

        val totalBuildingsBuilt = records.size
        val maxLampsAllowed = (totalBuildingsBuilt / 3).coerceAtMost(4)

        val priorityList = listOf(
            Pair(VanillaBuildingType.FARM, 2),
            Pair(VanillaBuildingType.MINE, 1),
            Pair(VanillaBuildingType.SHEPHERD, 1),
            Pair(VanillaBuildingType.HOUSE_SMALL, if (totalResidentialBuilt < maxResidentialAllowed) (existingCounts["HOUSE_SMALL"] ?: 0) + 1 else 0),
            Pair(VanillaBuildingType.HOUSE_MEDIUM, 2),
            Pair(VanillaBuildingType.MEETING_POINT, 1),
            Pair(VanillaBuildingType.LAMP, maxLampsAllowed),
            Pair(VanillaBuildingType.ANIMAL_PEN, 1),
            Pair(VanillaBuildingType.STABLE, 1),
            Pair(VanillaBuildingType.BLACKSMITH, 1),
            Pair(VanillaBuildingType.BAKERY, 1),
            Pair(VanillaBuildingType.LIBRARY, 1),
            Pair(VanillaBuildingType.ARMORY, 1),
            Pair(VanillaBuildingType.CARTOGRAPHER, 1),
            Pair(VanillaBuildingType.TEMPLE, 1)
        )

        for ((type, maxCount) in priorityList) {
            val currentCount = existingCounts[type.typeName] ?: 0
            if (currentCount < maxCount) {
                val success = planBuilding(type)
                if (success) {
                    return true
                }
            }
        }
        return false
    }

    fun planBuilding(type: VanillaBuildingType): Boolean {
        val center = settlement.data.center

        val isCritical = type == VanillaBuildingType.FARM ||
                type == VanillaBuildingType.SHEPHERD ||
                type == VanillaBuildingType.TOWN_HALL ||
                type == VanillaBuildingType.TEMPLE ||
                type == VanillaBuildingType.MINE

        val maxRadius = if (isCritical) 120 else 80
        val minRadius = if (isCritical) 20 else 15
        val maxAttempts = if (isCritical) 1500 else 800

        val rawWidth = type.width
        val rawLength = type.length
        val buildingHeight = type.height

        val rand = Random()
        val recordsList = buildings.computeIfAbsent(settlement.data.id) { mutableListOf() }

        val entranceCandidates = VanillaStructureLoader.getRawEntranceCandidates(type.vanillaPath)
        val entrancePos = if (entranceCandidates.isNotEmpty()) {
            var totalWeight = 0.0
            var sumX = 0.0
            var sumY = 0.0
            var sumZ = 0.0
            for (cand in entranceCandidates) {
                sumX += cand.pos.x * cand.weight
                sumY += cand.pos.y * cand.weight
                sumZ += cand.pos.z * cand.weight
                totalWeight += cand.weight
            }
            BlockPos((sumX / totalWeight).toInt(), (sumY / totalWeight).toInt(), (sumZ / totalWeight).toInt())
        } else {
            BlockPos(rawWidth / 2, 0, 0)
        }

        // ИСПРАВЛЕНО: Вычисляем относительную высоту самой нижней двери/порога в схеме, чтобы позже привязать её к дороге
        val lowestDoor = entranceCandidates.filter { it.weight == 5.0 }.minByOrNull { it.pos.y }
        val lowestJigsaw = entranceCandidates.filter { it.weight == 10.0 }.minByOrNull { it.pos.y }
        val bestYOffsetCandidate = lowestDoor ?: lowestJigsaw ?: entranceCandidates.minByOrNull { it.pos.y }
        val entranceYOffset = bestYOffsetCandidate?.pos?.y ?: 0

        for (attempt in 0..maxAttempts) {
            val angle = rand.nextDouble() * 2 * Math.PI
            val distance = rand.nextInt(maxRadius - minRadius) + minRadius

            val cx = center.blockX + (cos(angle) * distance).toInt()
            val cz = center.blockZ + (sin(angle) * distance).toInt()

            val settlementId = settlement.data.id
            val roads = settlementRoads.computeIfAbsent(settlementId) { ConcurrentHashMap.newKeySet() }
            var targetX = center.blockX
            var targetZ = center.blockZ

            if (roads.isNotEmpty()) {
                var minRoadDistSq = Double.MAX_VALUE
                for (pos in roads) {
                    val dx = pos.x - cx
                    val dz = pos.z - cz
                    val distSq = (dx * dx + dz * dz).toDouble()
                    if (distSq < minRoadDistSq) {
                        minRoadDistSq = distSq
                        targetX = pos.x
                        targetZ = pos.z
                    }
                }
            }

            var bestRotation = StructureRotation.NONE
            var minJigsawDistSq = Double.MAX_VALUE

            for (rot in StructureRotation.values()) {
                val rotJigsaw = rotateCoords(entrancePos, rot, rawWidth, rawLength)
                val rotWidth = if (rot == StructureRotation.CLOCKWISE_90 || rot == StructureRotation.COUNTERCLOCKWISE_90) rawLength else rawWidth
                val rotLength = if (rot == StructureRotation.CLOCKWISE_90 || rot == StructureRotation.COUNTERCLOCKWISE_90) rawWidth else rawLength

                val absX = cx - rotWidth / 2 + rotJigsaw.x
                val absZ = cz - rotLength / 2 + rotJigsaw.z

                val dx = absX - targetX
                val dz = absZ - targetZ
                val distSq = (dx * dx + dz * dz).toDouble()

                if (distSq < minJigsawDistSq) {
                    minJigsawDistSq = distSq
                    bestRotation = rot
                }
            }

            val width = if (bestRotation == StructureRotation.CLOCKWISE_90 || bestRotation == StructureRotation.COUNTERCLOCKWISE_90) rawLength else rawWidth
            val length = if (bestRotation == StructureRotation.CLOCKWISE_90 || bestRotation == StructureRotation.COUNTERCLOCKWISE_90) rawWidth else rawLength
            val halfW = width / 2 + 1
            val halfL = length / 2 + 1

            // === ИСПРАВЛЕНО: Виртуальная трассировка будущей дороги для расчета финальной высоты порога ===
            var simX = targetX
            var simZ = targetZ
            val simDX = abs(cx - simX)
            val simDZ = abs(cz - simZ)
            val simSX = if (simX < cx) 1 else -1
            val simSZ = if (simZ < cz) 1 else -1
            var simErr = simDX - simDZ

            var simPrevY: Int? = null
            var finalRoadY = center.blockY
            var pathValid = true

            while (true) {
                // Если дорога уперлась в границу (буфер) здания — запоминаем её итоговую высоту в этой точке
                if (abs(simX - cx) <= halfW && abs(simZ - cz) <= halfL) {
                    finalRoadY = simPrevY ?: getHighestGroundYAt(world, simX, simZ)
                    break
                }

                val natY = getHighestGroundYAt(world, simX, simZ)
                val surY = world.getHighestBlockYAt(simX, simZ)
                val isWater = world.getBlockAt(simX, surY, simZ).type == Material.WATER

                val curY = if (isWater) {
                    simPrevY ?: natY
                } else {
                    if (simPrevY == null) natY else simPrevY + (natY - simPrevY).coerceIn(-1, 1)
                }
                simPrevY = curY

                if (abs(curY - center.blockY) > 30) {
                    pathValid = false
                    break
                }

                if (simX == cx && simZ == cz) {
                    finalRoadY = curY
                    break
                }

                val e2 = 2 * simErr
                if (e2 > -simDZ) { simErr -= simDZ; simX += simSX }
                if (e2 < simDX) { simErr += simDX; simZ += simSZ }
            }

            if (!pathValid) continue

            // === ИСПРАВЛЕНО: Здание строится так, чтобы его порог/дверь была ровно на 1 блок выше законченной дороги ===
            val finalBaseY = finalRoadY + 1 - entranceYOffset

            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var validTerrain = true
            var floatingCount = 0

            for (x in -width / 2..width / 2) {
                for (z in -length / 2..length / 2) {
                    val absX = cx + x
                    val absZ = cz + z

                    val chunkX = absX shr 4
                    val chunkZ = absZ shr 4
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        validTerrain = false
                        break
                    }

                    val highestY = world.getHighestBlockYAt(absX, absZ)
                    if (abs(highestY - center.blockY) > 30) {
                        validTerrain = false
                        break
                    }

                    val surfaceBlockType = world.getBlockAt(absX, highestY, absZ).type
                    if (surfaceBlockType == Material.WATER || surfaceBlockType == Material.LAVA || surfaceBlockType.name.contains("ICE")) {
                        validTerrain = false
                        break
                    }

                    val hy = getHighestGroundYAt(world, absX, absZ)

                    if (hy < finalBaseY) floatingCount++
                    if (hy < minY) minY = hy
                    if (hy > maxY) maxY = hy
                }
                if (!validTerrain) break
            }

            if (!validTerrain) continue

            val maxAllowedVariance = if (isCritical) 12 else 8
            if (maxY - minY > maxAllowedVariance) continue

            val totalArea = width * length
            if (floatingCount.toDouble() / totalArea > 0.35) continue

            val minX = cx - width / 2
            val minZ = cz - length / 2
            val maxX = minX + width
            val maxZ = minZ + length

            val potentialBox = BoundingBox(
                minX.toDouble(), (finalBaseY - 2).toDouble(), minZ.toDouble(),
                maxX.toDouble(), (finalBaseY + buildingHeight).toDouble(), maxZ.toDouble()
            )

            val collisionCheckBoundedBox = potentialBox.clone().expand(5.0, 0.0, 5.0)
            if (recordsList.any { it.box.overlaps(collisionCheckBoundedBox) }) continue

            val uniqueName = type.typeName + "_" + (recordsList.count { it.type.startsWith(type.typeName) } + 1)
            recordsList.add(BuildingRecord(uniqueName, potentialBox))

            settlement.expandTerritory(15.0)

            val foundationMaterial = getDominantGroundMaterialAt(world, cx, cz, width, length)

            val buildJob = startVanillaStructureConstruction(cx, finalBaseY, cz, type, bestRotation, foundationMaterial)

            val queue = pendingJobs.computeIfAbsent(settlement.data.id) { ConcurrentLinkedQueue() }
            synchronized(queue) {
                queue.offer(buildJob)
            }
            return true
        }

        return false
    }

    private fun startVanillaStructureConstruction(
        cx: Int, baseY: Int, cz: Int,
        type: VanillaBuildingType,
        rotation: StructureRotation,
        foundationMaterial: Material
    ): SchematicBuildJob {
        val buildJob = SchematicBuildJob(world)
        val center = settlement.data.center

        val rawWidth = type.width
        val rawLength = type.length
        val height = type.height
        val vanillaPath = type.vanillaPath

        val width = if (rotation == StructureRotation.CLOCKWISE_90 || rotation == StructureRotation.COUNTERCLOCKWISE_90) rawLength else rawWidth
        val length = if (rotation == StructureRotation.CLOCKWISE_90 || rotation == StructureRotation.COUNTERCLOCKWISE_90) rawWidth else rawLength

        val settlementId = settlement.data.id
        val roads = settlementRoads.computeIfAbsent(settlementId) { ConcurrentHashMap.newKeySet() }
        val records = buildings[settlementId] ?: emptyList()

        var currentX = center.blockX
        var currentZ = center.blockZ

        if (roads.isNotEmpty()) {
            var minDistanceSq = Double.MAX_VALUE
            for (pos in roads) {
                val dx = pos.x - cx
                val dz = pos.z - cz
                val distSq = (dx * dx + dz * dz).toDouble()
                if (distSq < minDistanceSq) {
                    minDistanceSq = distSq
                    currentX = pos.x
                    currentZ = pos.z
                }
            }
        }

        val dX = abs(cx - currentX)
        val dZ = abs(cz - currentZ)
        val sX = if (currentX < cx) 1 else -1
        val sZ = if (currentZ < cz) 1 else -1
        var err = dX - dZ

        val roadBlockData = Material.DIRT_PATH.createBlockData()
        val airData = Material.AIR.createBlockData()
        val cobbleData = Material.COBBLESTONE.createBlockData()

        val halfW = width / 2 + 1
        val halfL = length / 2 + 1

        var prevCenterRoadY: Int? = null

        while (true) {
            if (abs(currentX - cx) <= halfW && abs(currentZ - cz) <= halfL) {
                break
            }

            val naturalCenterY = getHighestGroundYAt(world, currentX, currentZ)

            val surfaceY = world.getHighestBlockYAt(currentX, currentZ)
            val isWaterAtCenter = world.getBlockAt(currentX, surfaceY, currentZ).type == Material.WATER

            val centerRoadY = if (isWaterAtCenter) {
                prevCenterRoadY ?: naturalCenterY
            } else {
                if (prevCenterRoadY == null) {
                    naturalCenterY
                } else {
                    val diff = naturalCenterY - prevCenterRoadY
                    prevCenterRoadY + diff.coerceIn(-1, 1)
                }
            }
            prevCenterRoadY = centerRoadY

            for (wx in -1..1) {
                for (wz in -1..1) {
                    val px = currentX + wx
                    val pz = currentZ + wz

                    if (abs(px - center.blockX) <= 5 && abs(pz - center.blockZ) <= 5) continue

                    if (abs(px - cx) <= halfW - 1 && abs(pz - cz) <= halfL - 1) continue

                    val isInsideAnyBuilding = records.any { record ->
                        val box = record.box
                        px >= (box.minX - 1.0) && px <= (box.maxX + 1.0) &&
                                pz >= (box.minZ - 1.0) && pz <= (box.maxZ + 1.0)
                    }
                    if (isInsideAnyBuilding) continue

                    val naturalBlockY = getHighestGroundYAt(world, px, pz)
                    val currentBlock = world.getBlockAt(px, centerRoadY, pz)

                    val clearStart = centerRoadY + 1
                    val clearEnd = maxOf(naturalBlockY, centerRoadY + 3)
                    for (y in clearStart..clearEnd) {
                        buildJob.addBlock(BlockPos(px, y, pz), airData, isRoad = true)
                    }

                    for (y in naturalBlockY until centerRoadY) {
                        buildJob.addBlock(BlockPos(px, y, pz), Material.DIRT.createBlockData(), isRoad = true)
                    }

                    if (isWaterAtCenter) {
                        val isRoadEdge = if (abs(dX) > abs(dZ)) abs(wz) == 1 else abs(wx) == 1

                        if (isRoadEdge) {
                            buildJob.addBlock(BlockPos(px, centerRoadY + 1, pz), Material.OAK_FENCE.createBlockData(), isRoad = true)
                        }

                        buildJob.addBlock(BlockPos(px, centerRoadY, pz), Material.OAK_PLANKS.createBlockData(), isRoad = true)

                        for (y in naturalBlockY until centerRoadY) {
                            buildJob.addBlock(BlockPos(px, y, pz), Material.OAK_FENCE.createBlockData(), isRoad = true)
                        }
                    } else {
                        for (y in naturalBlockY until centerRoadY) {
                            buildJob.addBlock(BlockPos(px, y, pz), Material.DIRT.createBlockData(), isRoad = true)
                        }

                        if (currentBlock.isLiquid) {
                            buildJob.addBlock(BlockPos(px, centerRoadY, pz), cobbleData, isRoad = true)
                        } else {
                            buildJob.addBlock(BlockPos(px, centerRoadY, pz), roadBlockData, isRoad = true)
                        }
                    }

                    roads.add(BlockPos(px, centerRoadY, pz))
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

        val foundationData = foundationMaterial.createBlockData()

        for (x in 0 until width) {
            for (z in 0 until length) {
                val absX = cx - width / 2 + x
                val absZ = cz - length / 2 + z

                val highest = getHighestGroundYAt(world, absX, absZ)

                for (y in baseY..baseY + height) {
                    if (y <= highest) {
                        buildJob.addBlock(BlockPos(absX, y, absZ), airData, isRoad = false)
                    }
                }

                for (y in highest + 1 until baseY) {
                    buildJob.addBlock(BlockPos(absX, y, absZ), foundationData, isRoad = false)
                }
            }
        }

        val rawRelativeBlocks = VanillaStructureLoader.loadVanillaStructure(vanillaPath)
        if (rawRelativeBlocks.isEmpty()) return buildJob

        rawRelativeBlocks.forEach { relBlock ->
            val rotPos = rotateCoords(relBlock.relativePos, rotation, rawWidth, rawLength)

            val rotData = relBlock.blockData.clone()
            rotData.rotate(rotation)

            val absoluteX = cx - width / 2 + rotPos.x
            val absoluteY = baseY + rotPos.y
            val absoluteZ = cz - length / 2 + rotPos.z
            val absPos = BlockPos(absoluteX, absoluteY, absoluteZ)

            buildJob.addBlock(absPos, rotData, isRoad = false)
        }

        val citizens = settlement.villagers.mapNotNull {
            (it as? CraftVillager)?.handle as? HumanoidVillager
        }

        citizens.forEach { npc ->
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

fun Settlement.expandTerritory(amount: Double) {
    try {
        // Логика расширения территории
    } catch (e: Exception) {
        plugin.logger.warning("Не удалось расширить границу поселения: ${e.message}")
    }
}
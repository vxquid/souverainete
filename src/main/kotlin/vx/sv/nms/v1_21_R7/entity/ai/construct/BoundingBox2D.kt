package vx.sv.nms.v1_21_R7.entity.ai.construct

import com.google.gson.reflect.TypeToken
import net.minecraft.core.BlockPos
import org.bukkit.*
import org.bukkit.block.structure.StructureRotation
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.BlockDisplay
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

data class BuildingRecord(val type: String, val box: BoundingBox, val jobId: UUID)

data class BuildingSaveData(
    val type: String,
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
    val jobId: String?
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
    val pendingJobs: List<SchematicJobSaveData>,
    val activeJobsList: List<SchematicJobSaveData>? = null
)

class SettlementPlanner(val settlement: Settlement) {

    private val world = settlement.world

    companion object {
        val buildings = ConcurrentHashMap<UUID, MutableList<BuildingRecord>>()
        val pendingJobs = ConcurrentHashMap<UUID, Queue<SchematicBuildJob>>()
        val activeJobs = ConcurrentHashMap<UUID, ConcurrentLinkedQueue<SchematicBuildJob>>()
        val settlementRoads = ConcurrentHashMap<UUID, MutableSet<BlockPos>>()

        private val worldBuildingsKey = NamespacedKey(plugin, "settlement_buildings_data")
        private val buildingDisplays = ConcurrentHashMap<String, BlockDisplay>()
        private val planningCooldowns = ConcurrentHashMap<UUID, Long>()

        init {
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                val spyglassPlayers = Bukkit.getOnlinePlayers().filter {
                    it.inventory.itemInMainHand.type == Material.SPYGLASS ||
                            it.inventory.itemInOffHand.type == Material.SPYGLASS
                }

                if (spyglassPlayers.isEmpty()) {
                    buildingDisplays.values.forEach { it.remove() }
                    buildingDisplays.clear()
                    return@Runnable
                }

                val handledKeys = mutableSetOf<String>()

                spyglassPlayers.forEach { player ->
                    val playerLoc = player.location
                    val worldSettlements = SettlementManager.settlements[player.world] ?: return@forEach

                    buildings.forEach { (settlementId, records) ->
                        val currentSettlement = worldSettlements.find { it.data.id == settlementId } ?: return@forEach

                        if (currentSettlement.world == player.world && currentSettlement.data.center.distanceSquared(playerLoc) <= 10000.0) {
                            records.forEach { record ->
                                val key = "${settlementId}_${record.jobId}"
                                handledKeys.add(key)

                                val activeList = activeJobs[settlementId] ?: emptyList()
                                val isActive = activeList.any { it.jobId == record.jobId }
                                val targetMaterial = if (isActive) Material.LIME_STAINED_GLASS else Material.LIGHT_GRAY_STAINED_GLASS

                                var display = buildingDisplays[key]
                                if (display == null || display.isDead) {
                                    display = player.world.spawn(Location(player.world, record.box.minX, record.box.minY, record.box.minZ), BlockDisplay::class.java) {
                                        it.brightness = org.bukkit.entity.Display.Brightness(15, 15)
                                        it.block = Bukkit.createBlockData(targetMaterial)
                                        try { it.teleportDuration = 1 } catch (_: Exception) {}
                                    }
                                    buildingDisplays[key] = display
                                } else if (display.block.material != targetMaterial) {
                                    display.block = Bukkit.createBlockData(targetMaterial)
                                }

                                val transform = display.transformation
                                val w = (record.box.maxX - record.box.minX).toFloat()
                                val h = (record.box.maxY - record.box.minY).toFloat()
                                val l = (record.box.maxZ - record.box.minZ).toFloat()

                                transform.scale.set(w, h, l)
                                display.transformation = transform
                                display.teleport(Location(player.world, record.box.minX, record.box.minY, record.box.minZ))
                            }
                        }
                    }
                }

                val keysToRemove = buildingDisplays.keys - handledKeys
                keysToRemove.forEach { buildingDisplays.remove(it)?.remove() }
            }, 0L, 10L)
        }

        fun getActiveOrNextJob(settlement: Settlement): SchematicBuildJob? {
            val settlementId = settlement.data.id
            val activeList = activeJobs.computeIfAbsent(settlementId) { ConcurrentLinkedQueue() }
            activeList.removeIf { it.isFinished() }

            val builders = settlement.villagers.mapNotNull {
                (it as? CraftVillager)?.handle as? HumanoidVillager
            }

            val availableActiveJob = activeList.find { job ->
                val workersCount = builders.count { it.activeBuildJob?.jobId == job.jobId }
                workersCount < 4
            }

            if (availableActiveJob != null) {
                settlement.data.activeProjectId = availableActiveJob.jobId
                return availableActiveJob
            }

            val queue = pendingJobs[settlementId] ?: return null
            synchronized(queue) {
                val nextJob = queue.poll()
                if (nextJob != null) {
                    activeList.add(nextJob)
                    settlement.data.activeProjectId = nextJob.jobId
                    return nextJob
                }
            }

            val fallbackJob = activeList.firstOrNull()
            if (fallbackJob != null) {
                settlement.data.activeProjectId = fallbackJob.jobId
            } else {
                settlement.data.activeProjectId = null
            }
            return fallbackJob
        }

        fun isTreeBlock(type: Material): Boolean {
            val name = type.name
            return name.contains("LEAVES") ||
                    name.contains("LOG") ||
                    name.contains("WOOD") ||
                    name.contains("STEM") ||
                    name.contains("HYPHAE") ||
                    name.contains("ROOTS") ||
                    name.contains("CHERRY") ||
                    name.contains("SAPLING")
        }

        fun getHighestGroundYAt(world: World, x: Int, z: Int): Int {
            var y = world.getHighestBlockYAt(x, z)
            while (y > world.minHeight) {
                val block = world.getBlockAt(x, y, z)
                val type = block.type
                val isTreeOrPlant = isTreeBlock(type) || block.isIgnorableObstacle()
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
            val job = SchematicBuildJob(world, UUID.fromString(saveData.jobId))
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

                val activeList = activeJobs[settlementId] ?: emptyList()
                val activeSave = activeList.firstOrNull()?.let { serializeJob(it) }
                val activeJobsListSaves = activeList.map { serializeJob(it) }

                val queue = pendingJobs[settlementId] ?: emptyList()
                val pendingSaves = queue.map { serializeJob(it) }

                val plannerSaves = records.map { record ->
                    BuildingSaveData(
                        record.type,
                        record.box.minX, record.box.minY, record.box.minZ,
                        record.box.maxX, record.box.maxY, record.box.maxZ,
                        record.jobId.toString()
                    )
                }

                saveData[settlementId.toString()] = SettlementPlannerSaveData(
                    plannerSaves,
                    activeSave,
                    pendingSaves,
                    activeJobsListSaves
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
                        val jobId = try { UUID.fromString(save.jobId) } catch (e: Exception) { UUID.randomUUID() }
                        recordList.add(BuildingRecord(save.type, box, jobId))
                    }

                    val list = activeJobs.computeIfAbsent(settlementId) { ConcurrentLinkedQueue() }
                    list.clear()

                    if (data.activeJobsList != null) {
                        data.activeJobsList.forEach { jobSave ->
                            list.add(deserializeJob(world, jobSave))
                        }
                    } else if (data.activeJob != null) {
                        list.add(deserializeJob(world, data.activeJob))
                    }

                    val currentActive = list.firstOrNull()
                    if (currentActive != null) {
                        settlement.data.activeProjectId = currentActive.jobId
                    } else {
                        settlement.data.activeProjectId = null
                    }

                    val queue = pendingJobs.computeIfAbsent(settlementId) { ConcurrentLinkedQueue() }
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
    }

    init {
        val center = settlement.data.center
        val thBox = BoundingBox(
            center.x - 6.0, center.y - 1.0, center.z - 6.0,
            center.x + 6.0, center.y + 6.0, center.z + 6.0
        )
        val list = buildings.computeIfAbsent(settlement.data.id) { mutableListOf() }

        if (list.none { it.type == "MEETING_POINT" }) {
            list.add(BuildingRecord("MEETING_POINT", thBox, UUID.randomUUID()))
        }
    }

    fun planMeetingPointAtCenter(): Boolean {
        val center = settlement.data.center
        val cx = center.blockX
        val cz = center.blockZ
        val type = VanillaBuildingType.MEETING_POINT
        val baseY = center.blockY

        val records = buildings[settlement.data.id] ?: emptyList()
        val jobId = records.find { it.type == "MEETING_POINT" }?.jobId ?: UUID.randomUUID()

        // ИСПРАВЛЕНО: Теперь метод возвращает список сегментов дороги и финального здания
        val buildJobsList = startVanillaStructureConstruction(cx, baseY, cz, type, StructureRotation.NONE, jobId)

        val queue = pendingJobs.computeIfAbsent(settlement.data.id) { ConcurrentLinkedQueue() }
        synchronized(queue) {
            buildJobsList.forEach { queue.offer(it) }
        }
        return true
    }

    fun planNextPriorityBuilding(): Boolean {
        val settlementId = settlement.data.id
        val gameTime = world.gameTime
        val lastFailed = planningCooldowns[settlementId] ?: 0L

        if (gameTime - lastFailed < 6000L) {
            return false
        }

        val records = buildings[settlementId] ?: mutableListOf()

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

        planningCooldowns[settlementId] = gameTime
        return false
    }

    fun planBuilding(type: VanillaBuildingType): Boolean {
        val center = settlement.data.center

        val isCritical = type == VanillaBuildingType.FARM ||
                type == VanillaBuildingType.SHEPHERD ||
                type == VanillaBuildingType.TOWN_HALL ||
                type == VanillaBuildingType.TEMPLE ||
                type == VanillaBuildingType.MINE

        val maxRadius = if (isCritical) 90 else 75
        val minRadius = 18
        val maxAttempts = if (isCritical) 600 else 400

        val rawWidth = type.width
        val rawLength = type.length
        val buildingHeight = type.height

        val rand = Random()
        val recordsList = buildings.computeIfAbsent(settlement.data.id) { mutableListOf() }

        val jigsawPos = VanillaStructureLoader.getRawEntranceCandidates(type.vanillaPath)
        val entrancePos = if (jigsawPos.isNotEmpty()) {
            var totalWeight = 0.0
            var sumX = 0.0
            var sumY = 0.0
            var sumZ = 0.0
            for (cand in jigsawPos) {
                sumX += cand.pos.x * cand.weight
                sumY += cand.pos.y * cand.weight
                sumZ += cand.pos.z * cand.weight
                totalWeight += cand.weight
            }
            BlockPos((sumX / totalWeight).toInt(), (sumY / totalWeight).toInt(), (sumZ / totalWeight).toInt())
        } else {
            BlockPos(rawWidth / 2, 0, 0)
        }

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

            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE

            var sumY = 0
            var countY = 0

            var validTerrain = true

            for (x in -width / 2..width / 2) {
                for (z in -length / 2..length / 2) {
                    val absX = cx + x
                    val absZ = cz + z

                    if (!world.isChunkLoaded(absX shr 4, absZ shr 4)) {
                        validTerrain = false
                        break
                    }

                    val highestY = world.getHighestBlockYAt(absX, absZ)
                    val surfaceBlock = world.getBlockAt(absX, highestY, absZ)
                    val surfaceBlockType = surfaceBlock.type

                    if (surfaceBlock.isLiquid ||
                        surfaceBlockType == Material.WATER ||
                        surfaceBlockType == Material.LAVA ||
                        surfaceBlockType.name.contains("WATER") ||
                        surfaceBlockType.name.contains("ICE") ||
                        surfaceBlockType.name.contains("LAVA")) {
                        validTerrain = false
                        break
                    }

                    val hy = getHighestGroundYAt(world, absX, absZ)
                    if (hy < minY) minY = hy
                    if (hy > maxY) maxY = hy

                    sumY += hy
                    countY++
                }
                if (!validTerrain) break
            }

            if (!validTerrain) continue
            val maxAllowedVariance = if (isCritical) 9 else 5
            if (maxY - minY > maxAllowedVariance) continue
            if (abs(maxY - center.blockY) > 15) continue

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

            val averageY = if (countY > 0) Math.round(sumY.toDouble() / countY).toInt() else center.blockY
            val baseY = averageY

            var excavationCount = 0
            val totalArea = width * length
            for (x in -width / 2..width / 2) {
                for (z in -length / 2..length / 2) {
                    val absX = cx + x
                    val absZ = cz + z
                    val hy = getHighestGroundYAt(world, absX, absZ)
                    if (hy >= baseY) {
                        excavationCount++
                    }
                }
            }

            val finalBaseY = if (excavationCount.toDouble() / totalArea > 0.30) baseY + 1 else baseY

            val minX = cx - width / 2
            val minZ = cz - length / 2
            val maxX = minX + width
            val maxZ = minZ + length

            val potentialBox = BoundingBox(
                minX.toDouble(), (finalBaseY - 2).toDouble(), minZ.toDouble(),
                maxX.toDouble(), (finalBaseY + buildingHeight).toDouble(), maxZ.toDouble()
            )

            val buffer = 5.0
            val isColliding2D = recordsList.any { record ->
                val other = record.box
                val xOverlap = minX.toDouble() < other.maxX + buffer && maxX.toDouble() > other.minX - buffer
                val zOverlap = minZ.toDouble() < other.maxZ + buffer && maxZ.toDouble() > other.minZ - buffer
                xOverlap && zOverlap
            }
            if (isColliding2D) continue

            val uniqueName = type.typeName + "_" + (recordsList.count { it.type.startsWith(type.typeName) } + 1)
            val jobId = UUID.randomUUID()

            recordsList.add(BuildingRecord(uniqueName, potentialBox, jobId))

            settlement.expandTerritory(15.0)

            // ИСПРАВЛЕНО: Принимаем список пошаговых задач
            val buildJobsList = startVanillaStructureConstruction(cx, finalBaseY, cz, type, bestRotation, jobId)

            val queue = pendingJobs.computeIfAbsent(settlement.data.id) { ConcurrentLinkedQueue() }
            synchronized(queue) {
                buildJobsList.forEach { queue.offer(it) }
            }
            return true
        }

        plugin.logger.warning("Не удалось найти безопасное место для здания ${type.displayName} в поселении ${settlement.data.settlementName}.")
        return false
    }

    private fun startVanillaStructureConstruction(
        cx: Int, baseY: Int, cz: Int,
        type: VanillaBuildingType,
        rotation: StructureRotation,
        jobId: UUID
    ): List<SchematicBuildJob> { // ИСПРАВЛЕНО: Теперь возвращает пошаговые сегментированные задачи
        val jobsList = mutableListOf<SchematicBuildJob>()
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

        // Переменные для автоматического разбиения пути на маленькие отрезки
        var currentRoadJob = SchematicBuildJob(world)
        var stepsInSegment = 0
        val maxStepsPerSegment = 8 // Сегменты дороги ровно по 8 блоков для легкого Pathfinding у ИИ

        while (true) {
            val naturalCenterY = getHighestGroundYAt(world, currentX, currentZ)

            val surfaceY = world.getHighestBlockYAt(currentX, currentZ)
            val surfaceBlock = world.getBlockAt(currentX, surfaceY, currentZ)

            val isWaterAtCenter = surfaceBlock.type == Material.WATER || surfaceBlock.isLiquid

            val centerRoadY = if (isWaterAtCenter) {
                surfaceY
            } else {
                if (prevCenterRoadY == null) {
                    naturalCenterY
                } else {
                    val diff = naturalCenterY - prevCenterRoadY
                    prevCenterRoadY + diff.coerceIn(-1, 1)
                }
            }
            prevCenterRoadY = centerRoadY

            val isMovingX = abs(dX) > abs(dZ)

            val xMin = if (isWaterAtCenter) { if (isMovingX) -1 else -2 } else -1
            val xMax = if (isWaterAtCenter) { if (isMovingX) 1 else 2 } else 1
            val zMin = if (isWaterAtCenter) { if (isMovingX) -2 else -1 } else -1
            val zMax = if (isWaterAtCenter) { if (isMovingX) 2 else 1 } else 1

            for (wx in xMin..xMax) {
                for (wz in zMin..zMax) {
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
                        currentRoadJob.addBlock(BlockPos(px, y, pz), airData, isRoad = true)
                    }

                    if (isWaterAtCenter) {
                        val isEdge = if (isMovingX) abs(wz) == 2 else abs(wx) == 2

                        if (isEdge) {
                            currentRoadJob.addBlock(BlockPos(px, centerRoadY, pz), Material.OAK_LOG.createBlockData(), isRoad = true)
                            currentRoadJob.addBlock(BlockPos(px, centerRoadY + 1, pz), Material.OAK_FENCE.createBlockData(), isRoad = true)
                        } else {
                            currentRoadJob.addBlock(BlockPos(px, centerRoadY, pz), Material.OAK_PLANKS.createBlockData(), isRoad = true)
                            val slabData = Material.OAK_SLAB.createBlockData() as org.bukkit.block.data.type.Slab
                            slabData.type = org.bukkit.block.data.type.Slab.Type.TOP
                            currentRoadJob.addBlock(BlockPos(px, centerRoadY - 1, pz), slabData, isRoad = true)
                        }
                    } else {
                        for (y in naturalBlockY until centerRoadY) {
                            currentRoadJob.addBlock(BlockPos(px, y, pz), Material.DIRT.createBlockData(), isRoad = true)
                        }

                        if (currentBlock.isLiquid) {
                            currentRoadJob.addBlock(BlockPos(px, centerRoadY, pz), cobbleData, isRoad = true)
                        } else {
                            currentRoadJob.addBlock(BlockPos(px, centerRoadY, pz), roadBlockData, isRoad = true)
                        }
                    }

                    roads.add(BlockPos(px, centerRoadY, pz))
                }
            }

            stepsInSegment++
            // Складываем готовые короткие сегменты дорог в общий список задач
            if (stepsInSegment >= maxStepsPerSegment) {
                if (currentRoadJob.getBlocks().isNotEmpty()) {
                    jobsList.add(currentRoadJob)
                    currentRoadJob = SchematicBuildJob(world)
                }
                stepsInSegment = 0
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

        // Забираем последний кусочек дороги, если он не набрал лимит по шагам
        if (currentRoadJob.getBlocks().isNotEmpty()) {
            jobsList.add(currentRoadJob)
        }

        // ИСПРАВЛЕНО: Строго в самом конце создаем финализирующий проект с оригинальным jobId — само здание
        val buildJob = SchematicBuildJob(world, jobId)

        val materialCounts = mutableMapOf<Material, Int>()
        for (x in 0 until width) {
            for (z in 0 until length) {
                val absX = cx - width / 2 + x
                val absZ = cz - length / 2 + z
                val highest = getHighestGroundYAt(world, absX, absZ)
                val blockType = world.getBlockAt(absX, highest, absZ).type

                if (blockType.isSolid &&
                    !isTreeBlock(blockType) &&
                    blockType != Material.AIR) {
                    materialCounts[blockType] = (materialCounts[blockType] ?: 0) + 1
                }
            }
        }

        val mimicMaterial = materialCounts.maxByOrNull { it.value }?.key ?: Material.COBBLESTONE
        val finalMimicMaterial = if (mimicMaterial == Material.GRASS_BLOCK) Material.DIRT else mimicMaterial
        val foundationBlockData = finalMimicMaterial.createBlockData()

        // 1. Создаем котлован и фундамент
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
                    buildJob.addBlock(BlockPos(absX, y, absZ), foundationBlockData, isRoad = false)
                }
            }
        }

        // 2. Создаем блоки самой структуры здания
        val rawRelativeBlocks = VanillaStructureLoader.loadVanillaStructure(vanillaPath)
        if (rawRelativeBlocks.isNotEmpty()) {
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
        }

        jobsList.add(buildJob)
        return jobsList
    }
}

fun Settlement.expandTerritory(amount: Double) {
    try {
        // Логика расширения территории
    } catch (e: Exception) {
        plugin.logger.warning("Не удалось расширить границу поселения: ${e.message}")
    }
}
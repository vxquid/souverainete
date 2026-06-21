package vx.sv.nms.v1_21_R7.entity.ai.construct

import com.google.gson.reflect.TypeToken
import net.minecraft.core.BlockPos
import org.bukkit.*
import org.bukkit.block.structure.StructureRotation
import org.bukkit.craftbukkit.entity.CraftVillager
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
        private val planningCooldowns = ConcurrentHashMap<UUID, Long>()

        private val workstationCache = ConcurrentHashMap<String, BlockPos>()
        private val activeScans = ConcurrentHashMap.newKeySet<String>()

        private val activePlanning = ConcurrentHashMap.newKeySet<UUID>()

        fun getWorkstationFor(villager: HumanoidVillager): BlockPos? {
            val s = villager.settlement ?: return null
            val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return null
            val prof = bukkitVillager.profession

            val (buildingTypeName, targetMaterial) = when (prof) {
                org.bukkit.entity.Villager.Profession.FARMER -> Pair("FARM", Material.COMPOSTER)
                org.bukkit.entity.Villager.Profession.SHEPHERD -> Pair("SHEPHERD", Material.LOOM)
                org.bukkit.entity.Villager.Profession.TOOLSMITH -> Pair("MINE", Material.SMITHING_TABLE)
                org.bukkit.entity.Villager.Profession.BUTCHER -> Pair("BAKERY", Material.SMOKER)
                else -> return null
            }

            val key = "${s.data.id}_${buildingTypeName}"
            val cached = workstationCache[key]

            if (cached != null) {
                if (s.world.getBlockAt(cached.x, cached.y, cached.z).type == targetMaterial) {
                    return cached
                }
                workstationCache.remove(key)
            }

            if (activeScans.add(key)) {
                val records = buildings[s.data.id] ?: run { activeScans.remove(key); return null }
                val record = records.find { it.type.startsWith(buildingTypeName) } ?: run { activeScans.remove(key); return null }
                val box = record.box
                val world = s.world

                val minCX = box.minX.toInt() shr 4
                val maxCX = box.maxX.toInt() shr 4
                val minCZ = box.minZ.toInt() shr 4
                val maxCZ = box.maxZ.toInt() shr 4

                val snapshots = mutableMapOf<Pair<Int, Int>, ChunkSnapshot>()
                for (cx in minCX..maxCX) {
                    for (cz in minCZ..maxCZ) {
                        if (world.isChunkLoaded(cx, cz)) {
                            snapshots[Pair(cx, cz)] = world.getChunkAt(cx, cz).chunkSnapshot
                        }
                    }
                }

                plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                    var found: BlockPos? = null
                    scan@ for (x in box.minX.toInt()..box.maxX.toInt()) {
                        for (z in box.minZ.toInt()..box.maxZ.toInt()) {
                            val cx = x shr 4
                            val cz = z shr 4
                            val snap = snapshots[Pair(cx, cz)] ?: continue
                            val localX = x and 15
                            val localZ = z and 15

                            // FIXED: Scan 3 blocks below the box to guarantee coverage of old mines in saves!
                            val searchMinY = box.minY.toInt() - 3
                            val searchMaxY = box.maxY.toInt()
                            for (y in searchMinY..searchMaxY) {
                                if (snap.getBlockType(localX, y, localZ) == targetMaterial) {
                                    found = BlockPos(x, y, z)
                                    break@scan
                                }
                            }
                        }
                    }

                    if (found != null) {
                        workstationCache[key] = found
                    }
                    activeScans.remove(key)
                })
            }
            return null
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

        fun isIgnorableObstacleMat(type: Material): Boolean {
            if (type.isAir || type == Material.WATER || type == Material.LAVA) return true
            if (!type.isSolid && type != Material.WITHER_ROSE) return true
            return false
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

        fun snapshotGetHighestGroundYAt(snapshots: Map<Pair<Int, Int>, ChunkSnapshot>, world: World, x: Int, z: Int): Int {
            val cx = x shr 4
            val cz = z shr 4
            val snap = snapshots[Pair(cx, cz)] ?: return world.minHeight - 1

            var y = snap.getHighestBlockYAt(x and 15, z and 15)
            val minHeight = world.minHeight
            while (y > minHeight) {
                val type = snap.getBlockType(x and 15, y, z and 15)
                if (isTreeBlock(type) || isIgnorableObstacleMat(type)) {
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
                plugin.logger.info("[SettlementPlanner] Successfully loaded buildings from world PDC: ${buildings.values.sumOf { it.size }}")
            } catch (e: Exception) {
                plugin.logger.severe("[SettlementPlanner] Error loading buildings from PDC:")
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

        fun applyStoneVariability(blockData: org.bukkit.block.data.BlockData): org.bukkit.block.data.BlockData {
            val currentMat = blockData.material
            var finalBlockData = blockData
            val r = java.util.Random().nextDouble()

            if (currentMat == Material.COBBLESTONE) {
                val targetMat = when {
                    r < 0.15 -> Material.STONE
                    r < 0.30 -> Material.ANDESITE
                    r < 0.40 -> Material.MOSSY_COBBLESTONE
                    else -> Material.COBBLESTONE
                }
                finalBlockData = targetMat.createBlockData()
            } else if (currentMat == Material.COBBLESTONE_STAIRS) {
                val targetMat = when {
                    r < 0.15 -> Material.STONE_STAIRS
                    r < 0.30 -> Material.ANDESITE_STAIRS
                    r < 0.40 -> Material.MOSSY_COBBLESTONE_STAIRS
                    else -> Material.COBBLESTONE_STAIRS
                }
                val dataStr = blockData.asString.replace("cobblestone", when (targetMat) {
                    Material.STONE_STAIRS -> "stone"
                    Material.ANDESITE_STAIRS -> "andesite"
                    Material.MOSSY_COBBLESTONE_STAIRS -> "mossy_cobblestone"
                    else -> "cobblestone"
                })
                try { finalBlockData = Bukkit.createBlockData(dataStr) } catch (_: Exception) {}
            } else if (currentMat == Material.COBBLESTONE_SLAB) {
                val targetMat = when {
                    r < 0.15 -> Material.STONE_SLAB
                    r < 0.30 -> Material.ANDESITE_SLAB
                    r < 0.40 -> Material.MOSSY_COBBLESTONE_SLAB
                    else -> Material.COBBLESTONE_SLAB
                }
                val dataStr = blockData.asString.replace("cobblestone", when (targetMat) {
                    Material.STONE_SLAB -> "stone"
                    Material.ANDESITE_SLAB -> "andesite"
                    Material.MOSSY_COBBLESTONE_SLAB -> "mossy_cobblestone"
                    else -> "cobblestone"
                })
                try { finalBlockData = Bukkit.createBlockData(dataStr) } catch (_: Exception) {}
            } else if (currentMat == Material.COBBLESTONE_WALL) {
                val targetMat = when {
                    r < 0.20 -> Material.ANDESITE_WALL
                    r < 0.40 -> Material.MOSSY_COBBLESTONE_WALL
                    else -> Material.COBBLESTONE_WALL
                }
                val dataStr = blockData.asString.replace("cobblestone", when (targetMat) {
                    Material.ANDESITE_WALL -> "andesite"
                    Material.MOSSY_COBBLESTONE_WALL -> "mossy_cobblestone"
                    else -> "cobblestone"
                })
                try { finalBlockData = Bukkit.createBlockData(dataStr) } catch (_: Exception) {}
            }
            return finalBlockData
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

        val buildJobsList = startVanillaStructureConstruction(cx, baseY, cz, type, StructureRotation.NONE, jobId)

        val queue = pendingJobs.computeIfAbsent(settlement.data.id) { ConcurrentLinkedQueue() }
        synchronized(queue) {
            buildJobsList.forEach { queue.offer(it) }
        }
        return true
    }

    fun planNextPriorityBuilding(): Boolean {
        val settlementId = settlement.data.id

        if (activePlanning.contains(settlementId)) {
            return false
        }

        val gameTime = world.gameTime
        val lastFailed = planningCooldowns[settlementId] ?: 0L

        // FIXED: Planning cooldown reduced from 5 minutes (6000L) to 1 minute (1200L) for a faster layout tick
        if (gameTime - lastFailed < 1200L) {
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

        val existingSmall = existingCounts["HOUSE_SMALL"] ?: 0
        val existingMedium = existingCounts["HOUSE_MEDIUM"] ?: 0
        val existingLarge = existingCounts["HOUSE_LARGE"] ?: 0

        val maxResidentialAllowed = maxOf(6, settlement.villagers.size)

        val allowedSmall = if (existingSmall < 2) 2 else if (existingLarge >= 2 && existingMedium >= 2) maxOf(2, maxResidentialAllowed / 3) else 2
        val allowedLarge = if (existingLarge < 2) 2 else if (existingSmall >= 2 && existingMedium >= 2) maxOf(2, maxResidentialAllowed / 3) else 2
        val allowedMedium = if (existingMedium < 2) 2 else if (existingSmall >= 2 && existingLarge >= 2) maxOf(2, maxResidentialAllowed / 3) else 2

        val totalBuildingsBuilt = records.size
        val maxLampsAllowed = (totalBuildingsBuilt / 3).coerceAtMost(4)

        val priorityList = listOf(
            Pair(VanillaBuildingType.FARM, 2),
            Pair(VanillaBuildingType.WOOD_FARM, 1),
            Pair(VanillaBuildingType.MINE, 1),
            Pair(VanillaBuildingType.SHEPHERD, 1),
            Pair(VanillaBuildingType.HOUSE_SMALL, allowedSmall),
            Pair(VanillaBuildingType.HOUSE_LARGE, allowedLarge),
            Pair(VanillaBuildingType.HOUSE_MEDIUM, allowedMedium),
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

        val houses = priorityList.filter { it.first.typeName.startsWith("HOUSE_") }
        val nonHouses = priorityList.filter { !it.first.typeName.startsWith("HOUSE_") }

        val lastPlanned = records.lastOrNull()
        val lastWasHouse = lastPlanned != null && lastPlanned.type.startsWith("HOUSE_")

        if (lastWasHouse) {
            for ((type, maxCount) in nonHouses) {
                val currentCount = existingCounts[type.typeName] ?: 0
                if (currentCount < maxCount) {
                    val success = planBuilding(type)
                    if (success) {
                        return true
                    }
                }
            }
            for ((type, maxCount) in houses) {
                val currentCount = existingCounts[type.typeName] ?: 0
                if (currentCount < maxCount) {
                    val success = planBuilding(type)
                    if (success) {
                        return true
                    }
                }
            }
        } else {
            for ((type, maxCount) in priorityList) {
                val currentCount = existingCounts[type.typeName] ?: 0
                if (currentCount < maxCount) {
                    val success = planBuilding(type)
                    if (success) {
                        return true
                    }
                }
            }
        }

        planningCooldowns[settlementId] = gameTime
        return false
    }

    fun planBuilding(type: VanillaBuildingType): Boolean {
        val settlementId = settlement.data.id
        if (activePlanning.contains(settlementId)) return false
        activePlanning.add(settlementId)

        val center = settlement.data.center

        val isCritical = type == VanillaBuildingType.FARM ||
                type == VanillaBuildingType.WOOD_FARM ||
                type == VanillaBuildingType.SHEPHERD ||
                type == VanillaBuildingType.TOWN_HALL ||
                type == VanillaBuildingType.TEMPLE ||
                type == VanillaBuildingType.MINE

        val maxRadius = if (isCritical) 90 else 75
        val minRadius = 18
        val maxAttempts = if (isCritical) 600 else 400

        val structureSize = VanillaStructureLoader.getStructureSize(type.vanillaPath)
        val rawWidth = structureSize.blockX
        val rawLength = structureSize.blockZ
        val buildingHeight = structureSize.blockY

        val recordsListCopy = buildings[settlementId]?.toList() ?: emptyList()
        val roadsCopy = settlementRoads[settlementId]?.toSet() ?: emptySet()

        val jigsawPos = VanillaStructureLoader.getRawEntranceCandidates(type.vanillaPath)
        val entrancePos = if (jigsawPos.isNotEmpty()) {
            jigsawPos.maxByOrNull { it.weight }?.pos ?: BlockPos(rawWidth / 2, 0, 0)
        } else {
            BlockPos(rawWidth / 2, 0, 0)
        }

        val snapshots = mutableMapOf<Pair<Int, Int>, ChunkSnapshot>()
        val maxDist = maxRadius + 20
        val minCX = (center.blockX - maxDist) shr 4
        val maxCX = (center.blockX + maxDist) shr 4
        val minCZ = (center.blockZ - maxDist) shr 4
        val maxCZ = (center.blockZ + maxDist) shr 4

        for (cx in minCX..maxCX) {
            for (cz in minCZ..maxCZ) {
                if (world.isChunkLoaded(cx, cz)) {
                    snapshots[Pair(cx, cz)] = world.getChunkAt(cx, cz).chunkSnapshot
                }
            }
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val rand = Random()
            var found = false

            for (attempt in 0..maxAttempts) {
                val angle = rand.nextDouble() * 2 * Math.PI
                val distance = rand.nextInt(maxRadius - minRadius) + minRadius

                val cx = center.blockX + (cos(angle) * distance).toInt()
                val cz = center.blockZ + (sin(angle) * distance).toInt()

                var targetX = center.blockX
                var targetZ = center.blockZ

                if (roadsCopy.isNotEmpty()) {
                    var minRoadDistSq = Double.MAX_VALUE
                    for (pos in roadsCopy) {
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

                        val chunkX = absX shr 4
                        val chunkZ = absZ shr 4
                        val snap = snapshots[Pair(chunkX, chunkZ)]

                        if (snap == null) {
                            validTerrain = false
                            break
                        }

                        val localX = absX and 15
                        val localZ = absZ and 15

                        val highestY = snap.getHighestBlockYAt(localX, localZ)
                        val surfaceBlockType = snap.getBlockType(localX, highestY, localZ)

                        if (surfaceBlockType == Material.WATER ||
                            surfaceBlockType == Material.LAVA ||
                            surfaceBlockType.name.contains("WATER") ||
                            surfaceBlockType.name.contains("ICE") ||
                            surfaceBlockType.name.contains("LAVA")) {
                            validTerrain = false
                            break
                        }

                        val isRoadBlock = surfaceBlockType == Material.DIRT_PATH ||
                                surfaceBlockType.name.contains("PATH") ||
                                roadsCopy.any { it.x == absX && it.z == absZ }
                        if (isRoadBlock) {
                            validTerrain = false
                            break
                        }

                        val hy = snapshotGetHighestGroundYAt(snapshots, world, absX, absZ)
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
                    val hy = snapshotGetHighestGroundYAt(snapshots, world, px, pz)

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

                val rotJigsaw = rotateCoords(entrancePos, bestRotation, rawWidth, rawLength)
                val absEntranceX = cx - width / 2 + rotJigsaw.x
                val absEntranceZ = cz - length / 2 + rotJigsaw.z
                val entranceGroundY = snapshotGetHighestGroundYAt(snapshots, world, absEntranceX, absEntranceZ)

                val averageY = if (countY > 0) Math.round(sumY.toDouble() / countY).toInt() else center.blockY
                val baseY = averageY

                var excavationCount = 0
                val totalArea = width * length
                for (x in -width / 2..width / 2) {
                    for (z in -length / 2..length / 2) {
                        val absX = cx + x
                        val absZ = cz + z
                        val hy = snapshotGetHighestGroundYAt(snapshots, world, absX, absZ)
                        if (hy >= baseY) {
                            excavationCount++
                        }
                    }
                }

                val tempBaseY = if (excavationCount.toDouble() / totalArea > 0.30) baseY + 1 else baseY

                // FIXED: Adjust the structure's base height so that the entrance jigsaw/door block
                // sits either exactly on ground level (entranceGroundY) or +1 above (entranceGroundY + 1).
                val currentEntranceY = tempBaseY + rotJigsaw.y
                val finalBaseY = when {
                    currentEntranceY > entranceGroundY + 1 -> entranceGroundY + 1 - rotJigsaw.y
                    currentEntranceY < entranceGroundY -> entranceGroundY - rotJigsaw.y
                    else -> tempBaseY
                }

                // FIXED: For the mine, expand the BoundingBox downward to Y-5 to include the workstation in the cache zone
                val boxMinY = if (type == VanillaBuildingType.MINE) finalBaseY - 5 else finalBaseY - 2

                val minX = cx - width / 2
                val minZ = cz - length / 2
                val maxX = minX + width
                val maxZ = minZ + length

                val potentialBox = BoundingBox(
                    minX.toDouble(), boxMinY.toDouble(), minZ.toDouble(),
                    maxX.toDouble(), (finalBaseY + buildingHeight).toDouble(), maxZ.toDouble()
                )

                val buffer = 5.0
                val isColliding2D = recordsListCopy.any { record ->
                    val other = record.box
                    val xOverlap = minX.toDouble() < other.maxX + buffer && maxX.toDouble() > other.minX - buffer
                    val zOverlap = minZ.toDouble() < other.maxZ + buffer && maxZ.toDouble() > other.minZ - buffer
                    xOverlap && zOverlap
                }
                if (isColliding2D) continue

                found = true

                plugin.server.scheduler.runTask(plugin, Runnable {
                    activePlanning.remove(settlementId)

                    val realRecordsList = buildings.computeIfAbsent(settlementId) { mutableListOf() }

                    val stillColliding = realRecordsList.any { record ->
                        val other = record.box
                        val xOverlap = minX.toDouble() < other.maxX + buffer && maxX.toDouble() > other.minX - buffer
                        val zOverlap = minZ.toDouble() < other.maxZ + buffer && maxZ.toDouble() > other.minZ - buffer
                        xOverlap && zOverlap
                    }
                    if (stillColliding) return@Runnable

                    val uniqueName = type.typeName + "_" + (realRecordsList.count { it.type.startsWith(type.typeName) } + 1)
                    val jobId = UUID.randomUUID()

                    realRecordsList.add(BuildingRecord(uniqueName, potentialBox, jobId))
                    settlement.expandTerritory(15.0)

                    val buildJobsList = startVanillaStructureConstruction(cx, finalBaseY, cz, type, bestRotation, jobId)

                    val queue = pendingJobs.computeIfAbsent(settlementId) { ConcurrentLinkedQueue() }
                    synchronized(queue) {
                        buildJobsList.forEach { queue.offer(it) }
                    }
                })

                return@Runnable
            }

            activePlanning.remove(settlementId)
        })

        return true
    }

    private fun startVanillaStructureConstruction(
        cx: Int, baseY: Int, cz: Int,
        type: VanillaBuildingType,
        rotation: StructureRotation,
        jobId: UUID
    ): List<SchematicBuildJob> {
        val jobsList = mutableListOf<SchematicBuildJob>()
        val center = settlement.data.center

        val structureSize = VanillaStructureLoader.getStructureSize(type.vanillaPath)
        val rawWidth = structureSize.blockX
        val rawLength = structureSize.blockZ
        val height = structureSize.blockY
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

        var currentRoadJob = SchematicBuildJob(world)
        var stepsInSegment = 0
        val maxStepsPerSegment = 8

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
                        if (record.jobId == jobId) {
                            px >= box.minX && px < box.maxX && pz >= box.minZ && pz < box.maxZ
                        } else {
                            px >= (box.minX - 1.5) && px <= (box.maxX + 1.5) &&
                                    pz >= (box.minZ - 1.5) && pz <= (box.maxZ + 1.5)
                        }
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

        if (currentRoadJob.getBlocks().isNotEmpty()) {
            jobsList.add(currentRoadJob)
        }

        // Process and rotate structure blocks first to obtain exact footprint coordinates
        val rawRelativeBlocks = VanillaStructureLoader.loadVanillaStructure(vanillaPath)
        val isBirchOverride = java.util.Random().nextDouble() < 0.30

        val rotatedBlocks = rawRelativeBlocks.map { relBlock ->
            val rotPos = rotateCoords(relBlock.relativePos, rotation, rawWidth, rawLength)

            var rotData = relBlock.blockData.clone()
            rotData.rotate(rotation)
            rotData = applyStoneVariability(rotData)

            if (isBirchOverride) {
                val dataStr = rotData.asString
                if (dataStr.contains("oak") && !dataStr.contains("dark_oak")) {
                    val birchDataStr = dataStr.replace("oak", "birch")
                    try {
                        rotData = Bukkit.createBlockData(birchDataStr)
                    } catch (_: Exception) {}
                }
            }
            Pair(rotPos, rotData)
        }

        // 2D Scanline Bounding footprint coordinates
        val minYMap = mutableMapOf<Pair<Int, Int>, Int>()
        val maxYMap = mutableMapOf<Pair<Int, Int>, Int>()

        rotatedBlocks.forEach { (pos, _) ->
            val col = Pair(pos.x, pos.z)
            val y = pos.y

            minYMap[col] = minOf(minYMap[col] ?: Int.MAX_VALUE, y)
            maxYMap[col] = maxOf(maxYMap[col] ?: Int.MIN_VALUE, y)
        }

        val occupiedColumns = minYMap.keys

        val materialCounts = mutableMapOf<Material, Int>()
        for (x in 0 until width) {
            for (z in 0 until length) {
                if (Pair(x, z) !in occupiedColumns) continue

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

        val foundationBlockData = applyStoneVariability(finalMimicMaterial.createBlockData())

        val buildJob = SchematicBuildJob(world, jobId)

        for (x in 0 until width) {
            for (z in 0 until length) {
                val colKey = Pair(x, z)
                if (colKey !in occupiedColumns) continue

                val absX = cx - width / 2 + x
                val absZ = cz - length / 2 + z

                val highest = getHighestGroundYAt(world, absX, absZ)
                val minY = minYMap[colKey] ?: 0
                val maxY = maxYMap[colKey] ?: 0

                // Excavate only the exact vertical span occupied by structure blocks in this specific column.
                // This preserves natural terrain under roof eaves, balconies, and outer decorations.
                val startClearY = baseY + minY
                val endClearY = baseY + maxY
                for (y in startClearY..endClearY) {
                    if (y <= highest) {
                        buildJob.addBlock(BlockPos(absX, y, absZ), airData, isRoad = false)
                    }
                }

                // Place foundation below the structure down to the ground ONLY if the column blocks actually
                // start at the floor/ground level (minY <= 1). This keeps overhangs and roofs floating naturally.
                if (minY <= 1) {
                    for (y in highest + 1 until baseY) {
                        val singleFoundationData = applyStoneVariability(foundationBlockData)
                        buildJob.addBlock(BlockPos(absX, y, absZ), singleFoundationData, isRoad = false)
                    }
                }
            }
        }

        if (rotatedBlocks.isNotEmpty()) {
            rotatedBlocks.forEach { (rotPos, rotData) ->
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
        val center = data.center
        val newRadius = (territory.maxX - center.x) + amount
        territory = BoundingBox.of(center, newRadius, 128.0, newRadius)
    } catch (e: Exception) {
        plugin.logger.warning("Failed to expand settlement boundary: ${e.message}")
    }
}
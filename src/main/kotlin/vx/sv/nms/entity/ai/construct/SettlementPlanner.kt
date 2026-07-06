package vx.sv.nms.entity.ai.construct

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
import vx.sv.gameplay.settlement.SettlementManager.Companion.settlements
import vx.sv.nms.entity.HumanoidVillager
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

data class PlanRequest(val settlement: Settlement, val type: VanillaBuildingType)

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
        private val scanCooldowns = ConcurrentHashMap<String, Long>()

        private val activePlanningRequests = ConcurrentHashMap<UUID, VanillaBuildingType>()

        private val planningQueue = ConcurrentLinkedQueue<PlanRequest>()
        private var isGlobalPlannerRunning = false

        fun requestPlanning(settlement: Settlement, type: VanillaBuildingType) {
            planningQueue.offer(PlanRequest(settlement, type))
            startGlobalPlanner()
        }

        private fun startGlobalPlanner() {
            if (isGlobalPlannerRunning) return
            isGlobalPlannerRunning = true

            object : org.bukkit.scheduler.BukkitRunnable() {
                var currentRequest: PlanRequest? = null
                var attemptsMade = 0

                var reqSettlement: Settlement? = null
                var reqType: VanillaBuildingType? = null
                var center: Location? = null
                var isCritical = false
                var maxRadius = 0
                var minRadius = 0
                var maxAttempts = 0
                var rawWidth = 0
                var rawLength = 0
                var buildingHeight = 0
                var entrancePos: BlockPos? = null
                var recordsListCopy: List<BuildingRecord> = emptyList()
                var roadsCopy: Set<BlockPos> = emptySet()
                val rand = java.util.Random()

                fun setupContext(request: PlanRequest) {
                    reqSettlement = request.settlement
                    reqType = request.type
                    center = reqSettlement!!.data.center

                    isCritical = reqType == VanillaBuildingType.FARM ||
                            reqType == VanillaBuildingType.WOOD_FARM ||
                            reqType == VanillaBuildingType.SHEPHERD ||
                            reqType == VanillaBuildingType.TOWN_HALL ||
                            reqType == VanillaBuildingType.TEMPLE ||
                            reqType == VanillaBuildingType.MINE

                    maxRadius = if (isCritical) 90 else 75
                    minRadius = 18
                    maxAttempts = if (isCritical) 600 else 400

                    val structureSize = VanillaStructureLoader.getStructureSize(reqType!!.vanillaPath)
                    rawWidth = structureSize.blockX
                    rawLength = structureSize.blockZ
                    buildingHeight = structureSize.blockY

                    val settlementId = reqSettlement!!.data.id
                    recordsListCopy = buildings[settlementId]?.toList() ?: emptyList()
                    roadsCopy = settlementRoads[settlementId]?.toSet() ?: emptySet()

                    val jigsawPos = VanillaStructureLoader.getRawEntranceCandidates(reqType!!.vanillaPath)
                    entrancePos = if (jigsawPos.isNotEmpty()) {
                        jigsawPos.maxByOrNull { it.weight }?.pos ?: BlockPos(rawWidth / 2, 0, 0)
                    } else {
                        BlockPos(rawWidth / 2, 0, 0)
                    }
                    attemptsMade = 0
                }

                fun processSingleAttempt(): Boolean {
                    val world = reqSettlement!!.world
                    val angle = rand.nextDouble() * 2 * Math.PI
                    val distance = rand.nextInt(maxRadius - minRadius) + minRadius

                    var cx = center!!.blockX + (cos(angle) * distance).toInt()
                    var cz = center!!.blockZ + (sin(angle) * distance).toInt()

                    if (!world.isChunkLoaded(cx shr 4, cz shr 4)) return false

                    var targetX = center!!.blockX
                    var targetZ = center!!.blockZ

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

                        if (reqType == VanillaBuildingType.LAMP) {
                            val offsets = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
                            var foundSide = false
                            for (offset in offsets) {
                                val checkX = targetX + offset.first
                                val checkZ = targetZ + offset.second
                                val isRoad = roadsCopy.any { it.x == checkX && it.z == checkZ }
                                if (!isRoad) {
                                    cx = checkX
                                    cz = checkZ
                                    foundSide = true
                                    break
                                }
                            }
                            if (!foundSide) return false
                        }
                    }

                    var bestRotation = StructureRotation.NONE
                    var minJigsawDistSq = Double.MAX_VALUE

                    for (rot in StructureRotation.entries) {
                        val rotJigsaw = rotateCoords(entrancePos!!, rot, rawWidth, rawLength)
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

                    val maxIter = (width + 2) * (length + 2)
                    val groundYCache = IntArray(maxIter)

                    for (x in -width / 2..width / 2) {
                        for (z in -length / 2..length / 2) {
                            val absX = cx + x
                            val absZ = cz + z

                            if (!world.isChunkLoaded(absX shr 4, absZ shr 4)) return false

                            val highestY = world.getHighestBlockYAt(absX, absZ)
                            val surfaceBlockType = world.getBlockAt(absX, highestY, absZ).type

                            if (surfaceBlockType == Material.WATER ||
                                surfaceBlockType.name.contains("WATER") ||
                                surfaceBlockType.name.contains("ICE") ||
                                surfaceBlockType.name.contains("LAVA")) {
                                return false
                            }

                            val isRoadBlock = surfaceBlockType == Material.DIRT_PATH ||
                                    surfaceBlockType.name.contains("PATH") ||
                                    roadsCopy.any { it.x == absX && it.z == absZ }
                            if (isRoadBlock) return false

                            val hy = getHighestGroundYAt(world, absX, absZ)
                            if (hy < minY) minY = hy
                            if (hy > maxY) maxY = hy

                            if (countY < groundYCache.size) {
                                groundYCache[countY] = hy
                            }

                            sumY += hy
                            countY++
                        }
                    }

                    val maxAllowedVariance = if (isCritical) 14 else 10
                    if (maxY - minY > maxAllowedVariance) return false

                    if (abs(maxY - center!!.blockY) > 25) return false

                    var px = center!!.blockX
                    var pz = center!!.blockZ
                    val dX = abs(cx - px)
                    val dZ = abs(cz - pz)
                    val sX = if (px < cx) 1 else -1
                    val sZ = if (pz < cz) 1 else -1
                    var err = dX - dZ

                    var lastHy = center!!.blockY
                    while (true) {
                        if (!world.isChunkLoaded(px shr 4, pz shr 4)) return false

                        val hy = getHighestGroundYAt(world, px, pz)

                        if (abs(hy - lastHy) > 4) return false
                        lastHy = hy

                        if (px == cx && pz == cz) break
                        val e2 = 2 * err
                        if (e2 > -dZ) { err -= dZ; px += sX }
                        if (e2 < dX) { err += dX; pz += sZ }
                    }

                    val rotJigsaw = rotateCoords(entrancePos!!, bestRotation, rawWidth, rawLength)
                    val absEntranceX = cx - width / 2 + rotJigsaw.x
                    val absEntranceZ = cz - length / 2 + rotJigsaw.z
                    val entranceGroundY = getHighestGroundYAt(world, absEntranceX, absEntranceZ)

                    val averageY = if (countY > 0) Math.round(sumY.toDouble() / countY).toInt() else center!!.blockY
                    val baseY = averageY

                    var excavationCount = 0
                    var cachedIndex = 0
                    for (x in -width / 2..width / 2) {
                        for (z in -length / 2..length / 2) {
                            val hy = if (cachedIndex < countY) groundYCache[cachedIndex] else baseY
                            cachedIndex++
                            if (hy >= baseY) {
                                excavationCount++
                            }
                        }
                    }

                    val tempBaseY = if (countY > 0 && excavationCount.toDouble() / countY > 0.30) baseY + 1 else baseY

                    val currentEntranceY = tempBaseY + rotJigsaw.y
                    val finalBaseY = when {
                        currentEntranceY > entranceGroundY + 1 -> entranceGroundY + 1 - rotJigsaw.y
                        currentEntranceY < entranceGroundY -> entranceGroundY - rotJigsaw.y
                        else -> tempBaseY
                    }

                    val absEntranceY = finalBaseY + rotJigsaw.y
                    val absEntrancePos = BlockPos(absEntranceX, absEntranceY, absEntranceZ)

                    val boxMinY = if (reqType == VanillaBuildingType.MINE) finalBaseY - 5 else finalBaseY - 2

                    val minX = cx - width / 2
                    val minZ = cz - length / 2
                    val maxX = minX + width
                    val maxZ = minZ + length

                    val potentialBox = BoundingBox(
                        minX.toDouble(), boxMinY.toDouble(), minZ.toDouble(),
                        maxX.toDouble(), (finalBaseY + buildingHeight).toDouble(), maxZ.toDouble()
                    )

                    // ИСПРАВЛЕНИЕ: Динамический буфер коллизий.
                    // Если новое здание ИЛИ существующее здание - это мелкий декор (Лампа или Голем),
                    // мы уменьшаем дистанцию отчуждения с огромных 5.0 до 1.0 блока.
                    val isColliding2D = recordsListCopy.any { record ->
                        val other = record.box
                        val currentBuffer = if (reqType == VanillaBuildingType.LAMP || reqType == VanillaBuildingType.IRON_GOLEM ||
                            record.type.startsWith("LAMP") || record.type.startsWith("IRON_GOLEM")) 1.0 else 5.0

                        val xOverlap = minX.toDouble() < other.maxX + currentBuffer && maxX.toDouble() > other.minX - currentBuffer
                        val zOverlap = minZ.toDouble() < other.maxZ + currentBuffer && maxZ.toDouble() > other.minZ - currentBuffer
                        xOverlap && zOverlap
                    }
                    if (isColliding2D) return false

                    val settlementId = reqSettlement!!.data.id
                    val realRecordsList = buildings.computeIfAbsent(settlementId) { mutableListOf() }

                    val stillColliding = realRecordsList.any { record ->
                        val other = record.box
                        val currentBuffer = if (reqType == VanillaBuildingType.LAMP || reqType == VanillaBuildingType.IRON_GOLEM ||
                            record.type.startsWith("LAMP") || record.type.startsWith("IRON_GOLEM")) 1.0 else 5.0

                        val xOverlap = minX.toDouble() < other.maxX + currentBuffer && maxX.toDouble() > other.minX - currentBuffer
                        val zOverlap = minZ.toDouble() < other.maxZ + currentBuffer && maxZ.toDouble() > other.minZ - currentBuffer
                        xOverlap && zOverlap
                    }
                    if (stillColliding) return false

                    val uniqueName = reqType!!.typeName + "_" + (realRecordsList.count { it.type.startsWith(reqType!!.typeName) } + 1)
                    val jobId = UUID.randomUUID()

                    realRecordsList.add(BuildingRecord(uniqueName, potentialBox, jobId))
                    reqSettlement!!.expandTerritory(15.0)

                    val planner = SettlementPlanner(reqSettlement!!)
                    val buildJobsList = planner.startVanillaStructureConstruction(cx, finalBaseY, cz, absEntrancePos, reqType!!, bestRotation, jobId)

                    val queue = pendingJobs.computeIfAbsent(settlementId) { ConcurrentLinkedQueue() }
                    synchronized(queue) {
                        buildJobsList.forEach { queue.offer(it) }
                    }

                    SettlementManager.saveSettlements(world)
                    return true
                }

                override fun run() {
                    val startNanos = System.nanoTime()
                    val timeBudget = 3_000_000L

                    while (System.nanoTime() - startNanos < timeBudget) {
                        if (currentRequest == null) {
                            currentRequest = planningQueue.poll()
                            if (currentRequest == null) {
                                isGlobalPlannerRunning = false
                                cancel()
                                return
                            }

                            val settlementId = currentRequest!!.settlement.data.id

                            if (activePlanningRequests.containsKey(settlementId)) {
                                planningQueue.offer(currentRequest)
                                currentRequest = null
                                continue
                            }

                            activePlanningRequests[settlementId] = currentRequest!!.type
                            setupContext(currentRequest!!)
                        }

                        val hasPlayersNearby = reqSettlement!!.world.players.any { player ->
                            player.location.distanceSquared(center!!) <= 65536.0
                        }

                        if (!hasPlayersNearby) {
                            activePlanningRequests.remove(reqSettlement!!.data.id)
                            currentRequest = null
                            continue
                        }

                        attemptsMade++
                        val success = processSingleAttempt()

                        if (success || attemptsMade >= maxAttempts) {
                            activePlanningRequests.remove(reqSettlement!!.data.id)
                            currentRequest = null
                        }
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L)
        }

        fun getWorkstationFor(villager: HumanoidVillager): BlockPos? {
            val s = villager.settlement ?: return null
            val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return null
            val prof = bukkitVillager.profession

            val (buildingTypeName, targetMaterial) = when (prof) {
                org.bukkit.entity.Villager.Profession.FARMER -> Pair("FARM", Material.COMPOSTER)
                org.bukkit.entity.Villager.Profession.SHEPHERD -> Pair("SHEPHERD", Material.LOOM)
                org.bukkit.entity.Villager.Profession.TOOLSMITH -> Pair("MINE", Material.SMITHING_TABLE)
                org.bukkit.entity.Villager.Profession.BUTCHER -> Pair("BAKERY", Material.SMOKER)
                org.bukkit.entity.Villager.Profession.FISHERMAN -> Pair("FISHERMAN", Material.BARREL)
                else -> return null
            }

            val key = "${s.data.id}_${buildingTypeName}"

            val cooldown = scanCooldowns[key] ?: 0L
            if (s.world.gameTime < cooldown) return null

            val cached = workstationCache[key]

            if (cached != null) {
                if (s.world.getBlockAt(cached.x, cached.y, cached.z).type == targetMaterial) {
                    return cached
                }
                workstationCache.remove(key)
            }

            if (activeScans.add(key)) {
                val records = buildings[s.data.id] ?: run { activeScans.remove(key); return null }
                val world = s.world

                var found: BlockPos? = null

                scan@ for (record in records) {
                    val box = record.box
                    for (x in box.minX.toInt()..box.maxX.toInt()) {
                        for (z in box.minZ.toInt()..box.maxZ.toInt()) {
                            if (!world.isChunkLoaded(x shr 4, z shr 4)) continue

                            val searchMinY = box.minY.toInt() - 3
                            val searchMaxY = box.maxY.toInt()
                            for (y in searchMinY..searchMaxY) {
                                if (world.getBlockAt(x, y, z).type == targetMaterial) {
                                    found = BlockPos(x, y, z)
                                    break@scan
                                }
                            }
                        }
                    }
                }

                if (found != null) {
                    workstationCache[key] = found
                } else {
                    scanCooldowns[key] = s.world.gameTime + 600L
                }
                activeScans.remove(key)
            }
            return null
        }

        fun getActiveOrNextJob(settlement: Settlement): SchematicBuildJob? {
            val settlementId = settlement.data.id
            val activeList = activeJobs.computeIfAbsent(settlementId) { ConcurrentLinkedQueue() }

            val iterator = activeList.iterator()
            while (iterator.hasNext()) {
                val job = iterator.next()
                if (job.isFinished()) {
                    iterator.remove()
                    val records = buildings[settlementId]
                    if (records != null) {
                        val golemRecord = records.find { it.jobId == job.jobId && it.type.startsWith("IRON_GOLEM") }
                        if (golemRecord != null) {
                            val loc = golemRecord.box.center.toLocation(settlement.world)

                            val alreadyHasGolem = settlement.world.getNearbyEntities(loc, 3.0, 3.0, 3.0).any { it is org.bukkit.entity.IronGolem }

                            if (!alreadyHasGolem) {
                                settlement.world.spawn(loc, org.bukkit.entity.IronGolem::class.java)
                            }

                            job.getBlocks().forEach { blockToPlace ->
                                val block = settlement.world.getBlockAt(blockToPlace.pos.x, blockToPlace.pos.y, blockToPlace.pos.z)
                                block.type = Material.AIR
                            }

                            settlement.world.playSound(loc, Sound.ENTITY_IRON_GOLEM_DEATH, 1.0f, 1.0f)
                            settlement.world.spawnParticle(Particle.CLOUD, loc.clone().add(0.0, 1.0, 0.0), 20, 0.5, 0.5, 0.5, 0.1)

                            records.remove(golemRecord)
                        }
                    }
                }
            }

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

            val queue = pendingJobs[settlementId]
            if (queue != null && queue.isNotEmpty()) {
                synchronized(queue) {
                    val nextJob = queue.poll()
                    if (nextJob != null) {
                        activeList.add(nextJob)
                        settlement.data.activeProjectId = nextJob.jobId
                        return nextJob
                    }
                }
            }

            val fallbackJob = activeList.firstOrNull()
            if (fallbackJob != null) {
                settlement.data.activeProjectId = fallbackJob.jobId
            } else {
                settlement.data.activeProjectId = null

                if (queue == null || queue.isEmpty()) {
                    SettlementPlanner(settlement).planNextPriorityBuilding()
                }
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
            val nmsLevel = (world as org.bukkit.craftbukkit.CraftWorld).handle
            var y = world.getHighestBlockYAt(x, z)

            val pos = BlockPos.MutableBlockPos(x, y, z)

            while (y > world.minHeight) {
                pos.set(x, y, z)
                val blockState = nmsLevel.getBlockState(pos)
                val material = org.bukkit.craftbukkit.util.CraftMagicNumbers.getMaterial(blockState.block)

                val isTreeOrPlant = isTreeBlock(material) || isIgnorableObstacleMat(material)
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

                val worldSettlements = settlements[world] ?: return

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

        val entrancePos = BlockPos(cx, baseY + 1, cz)
        val buildJobsList = startVanillaStructureConstruction(cx, baseY, cz, entrancePos, type, StructureRotation.NONE, jobId)

        val queue = pendingJobs.computeIfAbsent(settlement.data.id) { ConcurrentLinkedQueue() }
        synchronized(queue) {
            buildJobsList.forEach { queue.offer(it) }
        }
        return true
    }

    fun planBuilding(type: VanillaBuildingType): Boolean {
        requestPlanning(settlement, type)
        return true
    }

    fun planNextPriorityBuilding(): Boolean {
        val settlementId = settlement.data.id

        val gameTime = world.gameTime
        val lastFailed = planningCooldowns[settlementId] ?: 0L

        if (gameTime - lastFailed < 400L) {
            return false
        }

        val records = buildings[settlementId] ?: mutableListOf()

        val allTypes = mutableListOf<String>()

        records.forEach { record ->
            val typeName = record.type
            val baseName = if (typeName.contains("_") && typeName.substringAfterLast("_").toIntOrNull() != null) {
                typeName.substringBeforeLast("_")
            } else {
                typeName
            }
            allTypes.add(baseName)
        }

        var lastTypeName = allTypes.lastOrNull() ?: ""

        activePlanningRequests[settlementId]?.let { type ->
            allTypes.add(type.typeName)
            lastTypeName = type.typeName
        }

        planningQueue.filter { it.settlement.data.id == settlementId }.forEach { req ->
            val typeName = req.type.typeName
            allTypes.add(typeName)
            lastTypeName = typeName
        }

        val existingCounts = allTypes.groupingBy { it }.eachCount()

        // 1. Базовые жизненно необходимые постройки (строятся первыми)
        val coreBuildings = listOf(
            Pair(VanillaBuildingType.MEETING_POINT, 1),
            Pair(VanillaBuildingType.TOWN_HALL, 1),
            Pair(VanillaBuildingType.FARM, 2),
            Pair(VanillaBuildingType.WOOD_FARM, 1),
            Pair(VanillaBuildingType.MINE, 1),
            Pair(VanillaBuildingType.SHEPHERD, 1)
        )

        for ((type, maxCount) in coreBuildings) {
            if ((existingCounts[type.typeName] ?: 0) < maxCount) {
                requestPlanning(settlement, type)
                return true
            }
        }

        // 2. Декорации (Лампы)
        val totalBuildingsBuilt = allTypes.size
        val maxLampsAllowed = totalBuildingsBuilt / 3
        if ((existingCounts["LAMP"] ?: 0) < maxLampsAllowed && java.util.Random().nextDouble() < 0.4) {
            requestPlanning(settlement, VanillaBuildingType.LAMP)
            return true
        }

        // 3. Лимиты для жилых домов
        val existingSmall = existingCounts["HOUSE_SMALL"] ?: 0
        val existingMedium = existingCounts["HOUSE_MEDIUM"] ?: 0
        val existingLarge = existingCounts["HOUSE_LARGE"] ?: 0

        val maxResidentialAllowed = maxOf(6, settlement.villagers.size)

        val allowedSmall = if (existingSmall < 2) 2 else if (existingLarge >= 2 && existingMedium >= 2) maxOf(2, maxResidentialAllowed / 3) else 2
        val allowedLarge = if (existingLarge < 2) 2 else if (existingSmall >= 2 && existingMedium >= 2) maxOf(2, maxResidentialAllowed / 3) else 2
        val allowedMedium = if (existingMedium < 2) 2 else if (existingSmall >= 2 && existingLarge >= 2) maxOf(2, maxResidentialAllowed / 3) else 2

        val housePriority = listOf(
            Pair(VanillaBuildingType.HOUSE_SMALL, allowedSmall),
            Pair(VanillaBuildingType.HOUSE_LARGE, allowedLarge),
            Pair(VanillaBuildingType.HOUSE_MEDIUM, allowedMedium)
        )
        val canBuildHouse = housePriority.any { (existingCounts[it.first.typeName] ?: 0) < it.second }

        // 4. Продвинутые функциональные здания
        val advancedFunctional = listOf(
            VanillaBuildingType.BLACKSMITH,
            VanillaBuildingType.BAKERY,
            VanillaBuildingType.LIBRARY,
            VanillaBuildingType.ARMORY,
            VanillaBuildingType.CARTOGRAPHER,
            VanillaBuildingType.STABLE,
            VanillaBuildingType.ANIMAL_PEN,
            VanillaBuildingType.TEMPLE
        )

        val unbuiltFunctional = advancedFunctional.filter { (existingCounts[it.typeName] ?: 0) < 1 }

        val lastWasHouse = lastTypeName.startsWith("HOUSE_")

        if (lastWasHouse) {
            if (unbuiltFunctional.isNotEmpty()) {
                requestPlanning(settlement, unbuiltFunctional.random())
                return true
            } else if (canBuildHouse) {
                val nextHouse = housePriority.first { (existingCounts[it.first.typeName] ?: 0) < it.second }
                requestPlanning(settlement, nextHouse.first)
                return true
            }
        } else {
            if (canBuildHouse) {
                val nextHouse = housePriority.first { (existingCounts[it.first.typeName] ?: 0) < it.second }
                requestPlanning(settlement, nextHouse.first)
                return true
            } else if (unbuiltFunctional.isNotEmpty()) {
                requestPlanning(settlement, unbuiltFunctional.random())
                return true
            }
        }

        planningCooldowns[settlementId] = gameTime
        return false
    }

    private fun startVanillaStructureConstruction(
        cx: Int, baseY: Int, cz: Int,
        entrancePos: BlockPos,
        type: VanillaBuildingType,
        rotation: StructureRotation,
        jobId: UUID
    ): List<SchematicBuildJob> {
        val jobsList = mutableListOf<SchematicBuildJob>()
        val center = settlement.data.center
        val airData = Material.AIR.createBlockData()

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

        val isRoadEligible = type != VanillaBuildingType.IRON_GOLEM && type != VanillaBuildingType.LAMP
        val halfW = width / 2 + 1
        val halfL = length / 2 + 1

        if (isRoadEligible) {
            val pathPoints = mutableListOf<BlockPos>()
            var currentX = center.blockX
            var currentZ = center.blockZ

            if (roads.isNotEmpty()) {
                var minDistanceSq = Double.MAX_VALUE
                for (pos in roads) {
                    val dx = pos.x - entrancePos.x
                    val dz = pos.z - entrancePos.z
                    val distSq = (dx * dx + dz * dz).toDouble()
                    if (distSq < minDistanceSq) {
                        minDistanceSq = distSq
                        currentX = pos.x
                        currentZ = pos.z
                    }
                }
            }

            val dX = abs(entrancePos.x - currentX)
            val dZ = abs(entrancePos.z - currentZ)
            val sX = if (currentX < entrancePos.x) 1 else -1
            val sZ = if (currentZ < entrancePos.z) 1 else -1
            var err = dX - dZ

            while (true) {
                pathPoints.add(BlockPos(currentX, 0, currentZ))

                if (currentX == entrancePos.x && currentZ == entrancePos.z) break
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

            val isWaterArray = BooleanArray(pathPoints.size)
            val naturalYArray = DoubleArray(pathPoints.size)
            val surfaceYArray = DoubleArray(pathPoints.size)

            for (i in 0 until pathPoints.size) {
                val px = pathPoints[i].x
                val pz = pathPoints[i].z
                val natY = getHighestGroundYAt(world, px, pz).toDouble()
                val surfY = world.getHighestBlockYAt(px, pz).toDouble()
                val surfBlock = world.getBlockAt(px, surfY.toInt(), pz)

                isWaterArray[i] = surfBlock.type == Material.WATER || surfBlock.isLiquid
                naturalYArray[i] = natY
                surfaceYArray[i] = surfY
            }

            val heights = DoubleArray(pathPoints.size)
            heights[0] = if (isWaterArray[0]) surfaceYArray[0] else naturalYArray[0]

            heights[pathPoints.size - 1] = (entrancePos.y - 1).toDouble()

            for (i in 1 until pathPoints.size - 1) {
                heights[i] = if (isWaterArray[i]) surfaceYArray[i] else naturalYArray[i]
            }

            val isSuspendedArray = BooleanArray(pathPoints.size)

            for (i in 0 until pathPoints.size) {
                val isWater = isWaterArray[i]
                val isTrench = !isWater && (surfaceYArray[i] < heights[i] - 1.5)
                if (isTrench) {
                    isSuspendedArray[i] = true
                }
            }

            repeat(5) {
                val nextHeights = heights.clone()
                for (i in 1 until heights.size - 1) {
                    nextHeights[i] = (heights[i - 1] + heights[i] + heights[i + 1]) / 3.0
                }
                for (i in 1 until heights.size - 1) {
                    val isWater = isWaterArray[i]
                    val isBridge = isSuspendedArray[i]
                    val surfaceY = surfaceYArray[i]
                    val naturalY = naturalYArray[i]

                    val minAllowedY = when {
                        isWater -> surfaceY
                        isBridge -> heights[i] - 0.2
                        else -> naturalY - 2.0
                    }
                    val maxAllowedY = when {
                        isWater -> surfaceY + 3.0
                        isBridge -> heights[i] + 0.2
                        else -> naturalY + 2.0
                    }

                    heights[i] = nextHeights[i].coerceIn(minAllowedY, maxAllowedY)
                }
            }

            var inGap = false
            var gapStart = -1
            for (i in 0 until pathPoints.size) {
                val overCanyon = isSuspendedArray[i]
                if (overCanyon && !inGap) {
                    inGap = true
                    gapStart = i
                } else if (!overCanyon && inGap) {
                    inGap = false
                    val gapEnd = i - 1
                    val gapLength = gapEnd - gapStart + 1
                    if (gapLength >= 3) {
                        applySuspensionBridgeSag(gapStart, gapEnd, heights)
                    } else {
                        for (k in gapStart..gapEnd) isSuspendedArray[k] = false
                    }
                }
            }
            if (inGap) {
                val gapEnd = pathPoints.size - 2
                val gapLength = gapEnd - gapStart + 1
                if (gapLength >= 3) {
                    applySuspensionBridgeSag(gapStart, gapEnd, heights)
                } else {
                    for (k in gapStart..gapEnd) isSuspendedArray[k] = false
                }
            }

            val finalHeights = IntArray(pathPoints.size) { i ->
                Math.round(heights[i]).toInt()
            }

            for (i in 1 until finalHeights.size) {
                val diff = finalHeights[i] - finalHeights[i - 1]
                if (abs(diff) > 1) {
                    finalHeights[i] = finalHeights[i - 1] + diff.coerceIn(-1, 1)
                }
            }

            val roadBlockData = Material.DIRT_PATH.createBlockData()
            val cobbleData = Material.COBBLESTONE.createBlockData()

            val footprint2D = mutableMapOf<Pair<Int, Int>, Int>()
            val isWaterMap = mutableMapOf<Pair<Int, Int>, Boolean>()
            val isSuspendedMap = mutableMapOf<Pair<Int, Int>, Boolean>()

            for (i in pathPoints.indices) {
                val pt = pathPoints[i]
                val pathX = pt.x
                val pathZ = pt.z
                val centerRoadY = finalHeights[i]
                val isWaterAtCenter = isWaterArray[i]

                val isMovingX = abs(dX) > abs(dZ)

                val xMin = if (isWaterAtCenter) { if (isMovingX) -1 else -2 } else -1
                val xMax = if (isWaterAtCenter) { if (isMovingX) 1 else 2 } else 1
                val zMin = if (isWaterAtCenter) { if (isMovingX) -2 else -1 } else -1
                val zMax = if (isWaterAtCenter) { if (isMovingX) 2 else 1 } else 1

                for (wx in xMin..xMax) {
                    for (wz in zMin..zMax) {
                        val px = pathX + wx
                        val pz = pathZ + wz
                        val key = Pair(px, pz)

                        if (isWaterAtCenter || !isWaterMap.getOrDefault(key, false)) {
                            footprint2D[key] = centerRoadY
                            isWaterMap[key] = isWaterAtCenter
                            isSuspendedMap[key] = isSuspendedArray[i]
                        }
                    }
                }
            }

            var currentRoadJob = SchematicBuildJob(world)
            var blocksInJob = 0
            val maxBlocksPerJob = 64

            for ((key, py) in footprint2D) {
                val px = key.first
                val pz = key.second
                val isWaterAtCenter = isWaterMap[key] ?: false
                val isSuspended = isSuspendedMap[key] ?: false

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
                val currentBlock = world.getBlockAt(px, py, pz)

                val clearStart = py + 1
                val clearEnd = py + 3
                for (y in clearStart..clearEnd) {
                    currentRoadJob.addBlock(BlockPos(px, y, pz), airData, isRoad = true)
                }

                if (isWaterAtCenter) {
                    var isEdge = false
                    val neighbors = arrayOf(Pair(px + 1, pz), Pair(px - 1, pz), Pair(px, pz + 1), Pair(px, pz - 1))
                    for (n in neighbors) {
                        if (!footprint2D.containsKey(n)) {
                            isEdge = true
                            break
                        }
                    }

                    if (isEdge) {
                        currentRoadJob.addBlock(BlockPos(px, py, pz), Material.OAK_LOG.createBlockData(), isRoad = true)
                        currentRoadJob.addBlock(BlockPos(px, py + 1, pz), Material.OAK_FENCE.createBlockData(), isRoad = true)
                    } else {
                        currentRoadJob.addBlock(BlockPos(px, py, pz), Material.OAK_PLANKS.createBlockData(), isRoad = true)
                        val slabData = Material.OAK_SLAB.createBlockData() as org.bukkit.block.data.type.Slab
                        slabData.type = org.bukkit.block.data.type.Slab.Type.TOP
                        currentRoadJob.addBlock(BlockPos(px, py - 1, pz), slabData, isRoad = true)
                    }
                } else if (isSuspended) {
                    val isMovingX = abs(dX) > abs(dZ)
                    val isEdge = if (isMovingX) {
                        !footprint2D.containsKey(Pair(px, pz + 1)) || !footprint2D.containsKey(Pair(px, pz - 1))
                    } else {
                        !footprint2D.containsKey(Pair(px + 1, pz)) || !footprint2D.containsKey(Pair(px - 1, pz))
                    }

                    if (isEdge) {
                        currentRoadJob.addBlock(BlockPos(px, py, pz), Material.OAK_PLANKS.createBlockData(), isRoad = true)
                        currentRoadJob.addBlock(BlockPos(px, py + 1, pz), Material.OAK_FENCE.createBlockData(), isRoad = true)
                    } else {
                        val slabData = Material.OAK_SLAB.createBlockData() as org.bukkit.block.data.type.Slab
                        slabData.type = org.bukkit.block.data.type.Slab.Type.TOP
                        currentRoadJob.addBlock(BlockPos(px, py, pz), slabData, isRoad = true)
                    }
                } else {
                    for (y in naturalBlockY until py) {
                        currentRoadJob.addBlock(BlockPos(px, y, pz), Material.DIRT.createBlockData(), isRoad = true)
                    }

                    if (currentBlock.isLiquid) {
                        currentRoadJob.addBlock(BlockPos(px, py, pz), cobbleData, isRoad = true)
                    } else {
                        currentRoadJob.addBlock(BlockPos(px, py, pz), roadBlockData, isRoad = true)
                    }
                }

                roads.add(BlockPos(px, py, pz))

                blocksInJob++
                if (blocksInJob >= maxBlocksPerJob) {
                    if (currentRoadJob.getBlocks().isNotEmpty()) {
                        jobsList.add(currentRoadJob)
                        currentRoadJob = SchematicBuildJob(world)
                    }
                    blocksInJob = 0
                }
            }

            if (currentRoadJob.getBlocks().isNotEmpty()) {
                jobsList.add(currentRoadJob)
            }
        }

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
                    var birchDataStr = dataStr.replace("oak", "birch")

                    if (birchDataStr.contains("birch_log")) {
                        birchDataStr = birchDataStr.replace("birch_log", "stripped_birch_log")
                    } else if (birchDataStr.contains("birch_wood")) {
                        birchDataStr = birchDataStr.replace("birch_wood", "stripped_birch_wood")
                    }

                    try {
                        rotData = Bukkit.createBlockData(birchDataStr)
                    } catch (_: Exception) {}
                }
            }
            Pair(rotPos, rotData)
        }

        val minXArray = IntArray(length) { Int.MAX_VALUE }
        val maxXArray = IntArray(length) { Int.MIN_VALUE }
        val minZArray = IntArray(width) { Int.MAX_VALUE }
        val maxZArray = IntArray(width) { Int.MIN_VALUE }

        val minYArray = IntArray(width * length) { Int.MAX_VALUE }
        val maxYArray = IntArray(width * length) { Int.MIN_VALUE }

        rotatedBlocks.forEach { (pos, _) ->
            val x = pos.x
            val z = pos.z

            if (z in 0 until length) {
                minXArray[z] = minOf(minXArray[z], x)
                maxXArray[z] = maxOf(maxXArray[z], x)
            }
            if (x in 0 until width) {
                minZArray[x] = minOf(minZArray[x], z)
                maxZArray[x] = maxOf(maxZArray[x], z)
            }

            val idx = x * length + z
            if (idx in 0 until width * length) {
                minYArray[idx] = minOf(minYArray[idx], pos.y)
                maxYArray[idx] = maxOf(maxYArray[idx], pos.y)
            }
        }

        val insideFootprint = BooleanArray(width * length)
        for (x in 0 until width) {
            for (z in 0 until length) {
                val minX = minXArray[z]
                val maxX = maxXArray[z]
                val minZ = minZArray[x]
                val maxZ = maxZArray[x]

                insideFootprint[x * length + z] = minX != Int.MAX_VALUE && maxX != Int.MIN_VALUE &&
                        minZ != Int.MAX_VALUE && maxZ != Int.MIN_VALUE &&
                        x in minX..maxX && z in minZ..maxZ
            }
        }

        val highestYMap = HashMap<Pair<Int, Int>, Int>()
        fun getCachedHighest(absX: Int, absZ: Int, x: Int, z: Int): Int {
            return highestYMap.getOrPut(Pair(x, z)) {
                getHighestGroundYAt(world, absX, absZ)
            }
        }

        val materialCounts = mutableMapOf<Material, Int>()
        for (x in 0 until width) {
            for (z in 0 until length) {
                val idx = x * length + z
                if (!insideFootprint[idx]) continue

                val absX = cx - width / 2 + x
                val absZ = cz - length / 2 + z
                val highest = getCachedHighest(absX, absZ, x, z)
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
                val idx = x * length + z
                if (!insideFootprint[idx]) continue

                val absX = cx - width / 2 + x
                val absZ = cz - length / 2 + z

                val highest = getCachedHighest(absX, absZ, x, z)
                val minY = if (minYArray[idx] == Int.MAX_VALUE) 0 else minYArray[idx]
                val maxY = if (maxYArray[idx] == Int.MIN_VALUE) height else maxYArray[idx]

                val startClearY = baseY + minY
                val endClearY = baseY + maxY
                for (y in startClearY..endClearY) {
                    if (y <= highest) {
                        buildJob.addBlock(BlockPos(absX, y, absZ), airData, isRoad = false)
                    }
                }

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

    private fun applySuspensionBridgeSag(gapStart: Int, gapEnd: Int, heights: DoubleArray) {
        val hStart = heights[gapStart - 1]
        val hEnd = heights[gapEnd + 1]
        val len = gapEnd - gapStart + 2

        for (j in 1 until len) {
            val ratio = j.toDouble() / len
            val interpY = hStart + ratio * (hEnd - hStart)

            val maxSag = if (len <= 8) 1.0 else 2.0
            val sagOffset = 4.0 * maxSag * ratio * (1.0 - ratio)

            heights[gapStart - 1 + j] = interpY - sagOffset
        }
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
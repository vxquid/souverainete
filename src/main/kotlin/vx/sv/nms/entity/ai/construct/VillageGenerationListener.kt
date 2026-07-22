package vx.sv.nms.entity.ai.construct

import com.google.gson.reflect.TypeToken
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.world.AsyncStructureSpawnEvent
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.SettlementManager.Companion.settlements
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Villager as BukkitVillager

data class DormantSite(val x: Int, val z: Int)

class VillageGenerationListener : Listener {

    private val dormantKey = NamespacedKey(plugin, "dormant_village_sites")
    private val dormantSites = ConcurrentHashMap<UUID, MutableSet<DormantSite>>()

    init {
        // Фоновая проверка спящих деревень каждые 40 тиков (2 секунды)
        object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                for (world in Bukkit.getWorlds()) {
                    if (!plugin.gameplayConfig.worlds.allowedWorlds.contains(world.name)) continue
                    if (!plugin.gameplayConfig.general.enableConstruction) continue

                    val players = world.players
                    if (players.isEmpty()) continue

                    val sites = loadDormantSites(world)
                    if (sites.isEmpty()) continue

                    val sitesToActivate = mutableListOf<DormantSite>()

                    synchronized(sites) {
                        val iterator = sites.iterator()
                        while (iterator.hasNext()) {
                            val site = iterator.next()
                            val siteLoc = Location(world, site.x.toDouble(), 64.0, site.z.toDouble())
                            // Активируем, если игрок подошел ближе 160 блоков
                            val hasPlayer = players.any { p -> p.location.distanceSquared(siteLoc) <= 25600.0 }
                            if (hasPlayer) {
                                sitesToActivate.add(site)
                                iterator.remove()
                            }
                        }
                    }

                    if (sitesToActivate.isNotEmpty()) {
                        saveDormantSites(world)
                        for (site in sitesToActivate) {
                            plugin.logger.info("[Souverainete] Activating dormant village at (${site.x}, ${site.z}) as player came nearby.")
                            generateVillageAt(world, site.x, site.z)
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 40L)
    }

    private fun loadDormantSites(world: World): MutableSet<DormantSite> {
        return dormantSites.computeIfAbsent(world.uid) {
            val pdc = world.persistentDataContainer
            if (pdc.has(dormantKey, PersistentDataType.STRING)) {
                val json = pdc.get(dormantKey, PersistentDataType.STRING)
                if (!json.isNullOrEmpty()) {
                    try {
                        val type = object : TypeToken<Set<DormantSite>>() {}.type
                        val loaded: Set<DormantSite>? = gson.fromJson(json, type)
                        if (loaded != null) return@computeIfAbsent Collections.synchronizedSet(HashSet(loaded))
                    } catch (e: Exception) {
                        plugin.logger.warning("[Souverainete] Failed to load dormant sites for world ${world.name}: ${e.message}")
                    }
                }
            }
            Collections.synchronizedSet(HashSet())
        }
    }

    private fun saveDormantSites(world: World) {
        val sites = dormantSites[world.uid] ?: return
        val pdc = world.persistentDataContainer
        val snapshot = synchronized(sites) { ArrayList(sites) }
        if (snapshot.isEmpty()) {
            pdc.remove(dormantKey)
        } else {
            val json = gson.toJson(snapshot)
            pdc.set(dormantKey, PersistentDataType.STRING, json)
        }
    }

    private fun addDormantSite(world: World, x: Int, z: Int) {
        val sites = loadDormantSites(world)
        val newSite = DormantSite(x, z)
        var added = false
        synchronized(sites) {
            added = sites.add(newSite)
        }
        if (added) {
            saveDormantSites(world)
        }
    }

    @EventHandler
    fun onFarmlandTrample(event: EntityInteractEvent) {
        val block = event.block
        if (block.type == Material.FARMLAND) {
            val entity = event.entity
            if (entity is BukkitVillager) {
                event.isCancelled = true
            }
        }
    }

    private fun spawnVillageAnimals(center: Location) {
        val world = center.world ?: return
        val random = Random()

        val sheepCount = 4 + random.nextInt(3)

        for (i in 0 until sheepCount) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 4.0 + random.nextDouble() * 4.0
            val spawnLoc = center.clone().add(Math.cos(angle) * distance, 1.0, Math.sin(angle) * distance)

            val groundY = SettlementPlanner.getHighestGroundYAt(world, spawnLoc.blockX, spawnLoc.blockZ)
            spawnLoc.y = groundY.toDouble() + 1.0

            world.spawn(spawnLoc, org.bukkit.entity.Sheep::class.java) { sheep ->
                sheep.persistentDataContainer.set(
                    NamespacedKey(plugin, "village_animal"),
                    PersistentDataType.BYTE,
                    1.toByte()
                )
            }
        }

        for (i in 0 until 2) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = 2.0 + random.nextDouble() * 3.0
            val spawnLoc = center.clone().add(Math.cos(angle) * distance, 1.0, Math.sin(angle) * distance)

            val groundY = SettlementPlanner.getHighestGroundYAt(world, spawnLoc.blockX, spawnLoc.blockZ)
            spawnLoc.y = groundY.toDouble() + 1.0

            world.spawn(spawnLoc, org.bukkit.entity.Cat::class.java) { cat ->
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
        val world = event.world

        if (!plugin.gameplayConfig.worlds.allowedWorlds.contains(world.name)) {
            return
        }

        if (!plugin.gameplayConfig.general.enableConstruction) {
            return
        }

        val structure = event.structure

        if (structure.key.key.lowercase().contains("village")) {
            event.isCancelled = true

            val boundingBox = event.boundingBox
            val centerX = (boundingBox.minX + boundingBox.maxX) / 2
            val centerZ = (boundingBox.minZ + boundingBox.maxZ) / 2

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val centerApprox = Location(world, centerX.toDouble(), 64.0, centerZ.toDouble())
                val hasNearbyPlayer = world.players.any { player ->
                    player.location.distanceSquared(centerApprox) <= 65536.0 // 256 блоков
                }

                if (hasNearbyPlayer) {
                    generateVillageAt(world, centerX.toInt(), centerZ.toInt())
                } else {
                    addDormantSite(world, centerX.toInt(), centerZ.toInt())
                    plugin.logger.info("[Souverainete] Marked prospective village at ($centerX, $centerZ) as dormant (no players nearby during generation).")
                }
            })
        }
    }

    private fun generateVillageAt(world: World, centerX: Int, centerZ: Int) {
        if (!world.worldFolder.exists()) return

        val worldSettlements = SettlementManager.settlements[world] ?: emptyList()
        val approxLoc = Location(world, centerX.toDouble(), 64.0, centerZ.toDouble())
        if (worldSettlements.any { it.data.center.distanceSquared(approxLoc) < 10000.0 }) {
            return
        }

        var bestX = centerX
        var bestZ = centerZ
        var bestY = world.getHighestBlockYAt(bestX, bestZ)
        var minHeightDifference = Int.MAX_VALUE
        var highestElevationOfFlattest = Int.MIN_VALUE
        var foundDryLand = false

        for (ox in -12..12) {
            for (oz in -12..12) {
                val cx = centerX + ox
                val cz = centerZ + oz

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
            plugin.logger.info("[Souverainete] Spawn of the settlement is cancelled: suitable land is absent in the generation area.")
            return
        }

        val centerLoc = Location(world, bestX.toDouble(), bestY.toDouble(), bestZ.toDouble())

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
        planner.planBuilding(VanillaBuildingType.WOOD_FARM)
        planner.planBuilding(VanillaBuildingType.MINE)
        planner.planBuilding(VanillaBuildingType.SHEPHERD)
        planner.planBuilding(VanillaBuildingType.HOUSE_SMALL)
        planner.planBuilding(VanillaBuildingType.HOUSE_SMALL)
        planner.planBuilding(VanillaBuildingType.HOUSE_LARGE)
        planner.planBuilding(VanillaBuildingType.HOUSE_LARGE)

        planner.planBuilding(VanillaBuildingType.TOWN_HALL)
        planner.planMeetingPointAtCenter()

        repeat(10) {
            planner.planNextPriorityBuilding()
        }

        SettlementManager.saveSettlements(world)
    }
}
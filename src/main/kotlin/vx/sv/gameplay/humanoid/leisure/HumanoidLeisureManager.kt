package vx.sv.gameplay.humanoid.leisure

import com.destroystokyo.paper.entity.Pathfinder
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.dialogue.DialogueSession
import vx.sv.gameplay.dialogue.menu.InteractionHandler
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.random.Random

/**
 * Manages leisure activities for Humanoid NPCs, allowing them to dynamically search for
 * seating areas (benches, sofas), socialize with friends, and consume food or drinks.
 */
class HumanoidLeisureManager : Listener {

    private val config get() = plugin.gameplayManager.config.leisure

    /**
     * Represents the current phase of the NPC's leisure activity.
     */
    enum class LeisureState { PATHING, SITTING }

    /**
     * Represents the NPC's desired environment for their leisure activity.
     */
    enum class Preference { INDOOR, OUTDOOR }

    /**
     * Data class holding the properties of an ongoing leisure session.
     */
    data class LeisureSession(
        val villager: Villager,
        val targetSeat: Location,
        val preference: Preference,
        val maxDuration: Long,
        var state: LeisureState = LeisureState.PATHING,
        val startTime: Long = System.currentTimeMillis()
    )

    // Using ConcurrentHashMap prevents CMEs if an event (like EntityDamageEvent) removes
    // an entity during the ticker's iterator loop.
    private val activeSessions = ConcurrentHashMap<Villager, LeisureSession>()
    private val occupiedSeats = mutableSetOf<Location>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Global ticker: searches for idle NPCs that can start a leisure session.
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            if (activeSessions.size > config.maxActiveSessions) return@runTaskTimer

            val candidate = plugin.server.worlds
                .filter { plugin.gameplayManager.allowedWorlds.contains(it) }
                // Restrict leisure search during the night, as NPCs should be sleeping.
                .filter { !isNightTime(it.time) }
                .flatMap { it.entities }
                .filterIsInstance<Villager>()
                .filter {
                    it.isValid &&
                            !activeSessions.containsKey(it) &&
                            it.vehicle == null &&
                            // Ignore NPCs that are currently sleeping or getting into bed.
                            it.pose != org.bukkit.entity.Pose.SLEEPING &&
                            !it.isSleeping &&
                            // Ignore NPCs that are in an active dialogue session.
                            DialogueSession.activeDialogueSessions.none { session -> session.entity == it } &&
                            // Ignore NPCs that are interacting via a menu.
                            InteractionHandler.openedMenuList.none { menu -> menu.villager == it }
                }
                .randomOrNull() ?: return@runTaskTimer

            startLeisureSearch(candidate)
        }, 200L, config.globalTickerInterval)

        // Session control ticker: manages pathfinding, session timeouts, and eating/drinking chances.
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            val iterator = activeSessions.values.iterator()
            val time = System.currentTimeMillis()

            while (iterator.hasNext()) {
                val session = iterator.next()
                val npc = session.villager

                // Invalidate session if the NPC is dead, no longer valid, or somehow changed worlds.
                if (!npc.isValid || npc.isDead || npc.world != session.targetSeat.world) {
                    standUp(npc, session) // Ensures attributes are safely reset
                    iterator.remove()
                    continue
                }

                // Force NPCs sitting outdoors to stand up and go home when night falls
                val isNight = isNightTime(npc.world.time)
                if (isNight && session.preference == Preference.OUTDOOR) {
                    standUp(npc, session)
                    iterator.remove()
                    continue
                }

                // Terminate session if the maximum duration has been reached
                if (time - session.startTime > session.maxDuration) {
                    standUp(npc, session)
                    iterator.remove()
                    continue
                }

                // Process the current state of the session
                when (session.state) {
                    LeisureState.PATHING -> {
                        // Cancel leisure pathing if the player suddenly engages the NPC in a dialogue or menu
                        val inDialogue = DialogueSession.activeDialogueSessions.any { it.entity == npc }
                        val inMenu = InteractionHandler.openedMenuList.any { it.villager == npc }
                        if (inDialogue || inMenu) {
                            standUp(npc, session)
                            iterator.remove()
                            continue
                        }
                        handlePathing(session)
                    }
                    LeisureState.SITTING -> handleSitting(session)
                }
            }
        }, 20L, config.sessionTickerInterval)
    }

    // =======================================================================================
    // CORE LOGIC
    // =======================================================================================

    private fun isNightTime(time: Long): Boolean {
        val start = config.time.nightStart
        val end = config.time.nightEnd
        return if (start < end) {
            time in start..end
        } else {
            time !in (end + 1)..<start
        }
    }

    private fun isPersonalSpaceInvaded(loc: Location, occupiedSet: Set<Location> = occupiedSeats): Boolean {
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val checkLoc = loc.clone().add(dx.toDouble(), 0.0, dz.toDouble())
                if (occupiedSet.contains(checkLoc)) return true
            }
        }
        return false
    }

    private fun startLeisureSearch(npc: Villager) {
        val world = npc.world
        val centerChunk = npc.location.chunk

        // Synchronously capture metadata
        val npcY = npc.location.blockY
        val minHeight = world.minHeight
        val maxHeight = world.maxHeight
        val occupiedSnapshot = occupiedSeats.toSet()

        val desiredPreference = if (Random.nextDouble() < config.interaction.indoorPreferenceChance) Preference.INDOOR else Preference.OUTDOOR
        val isSocial = Random.nextBoolean()

        val snapshots = mutableListOf<org.bukkit.ChunkSnapshot>()
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (world.isChunkLoaded(centerChunk.x + dx, centerChunk.z + dz)) {
                    snapshots.add(world.getChunkAt(centerChunk.x + dx, centerChunk.z + dz).chunkSnapshot)
                }
            }
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val candidates = mutableListOf<Pair<Location, Int>>()
            val campfires = mutableListOf<Triple<Int, Int, Int>>()

            val getMat = { wx: Int, wy: Int, wz: Int ->
                val cx = wx shr 4
                val cz = wz shr 4
                val snap = snapshots.find { it.x == cx && it.z == cz }
                if (snap != null && wy in minHeight until maxHeight) {
                    snap.getBlockType(wx and 15, wy, wz and 15)
                } else {
                    Material.AIR
                }
            }

            for (snapshot in snapshots) {
                for (y in (npcY - 4)..(npcY + 4)) {
                    if (y !in minHeight until maxHeight) continue
                    for (x in 0..15) {
                        for (z in 0..15) {
                            val mat = snapshot.getBlockType(x, y, z)
                            if (mat == Material.CAMPFIRE || mat == Material.SOUL_CAMPFIRE) {
                                campfires.add(Triple((snapshot.x shl 4) + x, y, (snapshot.z shl 4) + z))
                            }
                        }
                    }
                }
            }

            for (snapshot in snapshots) {
                for (y in (npcY - 3)..(npcY + 2)) {
                    if (y !in minHeight until maxHeight) continue
                    for (x in 0..15) {
                        for (z in 0..15) {
                            val material = snapshot.getBlockType(x, y, z)
                            if (material.isAir) continue

                            val isStairs = material.name.endsWith("STAIRS")
                            val isSolid = material.isSolid

                            if (isStairs || isSolid) {
                                val worldX = (snapshot.x shl 4) + x
                                val worldZ = (snapshot.z shl 4) + z

                                if (isStairs) {
                                    val blockData = snapshot.getBlockData(x, y, z)
                                    if (blockData is org.bukkit.block.data.type.Stairs) {
                                        if (blockData.half == org.bukkit.block.data.Bisected.Half.TOP) continue

                                        val frontFace = blockData.facing.oppositeFace
                                        val frontX = worldX + frontFace.modX
                                        val frontZ = worldZ + frontFace.modZ

                                        val frontMat = getMat(frontX, y, frontZ)
                                        val frontMatUp = getMat(frontX, y + 1, frontZ)

                                        if (frontMat.isSolid || frontMatUp.isSolid) continue
                                    }

                                    var isStaircase = false
                                    for (dx in -1..1) {
                                        for (dz in -1..1) {
                                            val matUp = getMat(worldX + dx, y + 1, worldZ + dz)
                                            val matDown = getMat(worldX + dx, y - 1, worldZ + dz)
                                            if (matUp.name.endsWith("STAIRS") || matDown.name.endsWith("STAIRS")) {
                                                isStaircase = true
                                                break
                                            }
                                        }
                                        if (isStaircase) break
                                    }
                                    if (isStaircase) continue
                                }

                                val loc = Location(world, worldX.toDouble(), y.toDouble(), worldZ.toDouble())

                                if (occupiedSnapshot.contains(loc)) continue
                                if (isPersonalSpaceInvaded(loc, occupiedSnapshot)) continue
                                if (!getMat(worldX, y + 1, worldZ).isAir || !getMat(worldX, y + 2, worldZ).isAir) continue

                                var score = 0
                                val highestY = snapshot.getHighestBlockYAt(x, z)
                                val isIndoor = highestY > y + 2
                                val actualPreference = if (isIndoor) Preference.INDOOR else Preference.OUTDOOR

                                if (actualPreference == desiredPreference) score += config.scoring.preferenceMatchBonus
                                if (isIndoor) score += config.scoring.indoorBaseBonus
                                if (isStairs) score += config.scoring.stairBonus

                                if (isStairs) {
                                    val neighbors = listOf(
                                        Pair(worldX + 1, worldZ), Pair(worldX - 1, worldZ),
                                        Pair(worldX, worldZ + 1), Pair(worldX, worldZ - 1)
                                    )
                                    for (n in neighbors) {
                                        val neighborMat = getMat(n.first, y, n.second)
                                        if (neighborMat.name.endsWith("STAIRS")) {
                                            score += config.scoring.benchBonus
                                            break
                                        }
                                    }
                                }

                                if (desiredPreference == Preference.OUTDOOR && actualPreference == Preference.OUTDOOR) {
                                    val nearCampfire = campfires.any { cf ->
                                        abs(cf.first - worldX) <= 6 && abs(cf.second - y) <= 2 && abs(cf.third - worldZ) <= 6
                                    }
                                    if (nearCampfire) {
                                        score += config.scoring.campfireBonus
                                    }
                                }

                                candidates.add(Pair(loc, score))
                            }
                        }
                    }
                }
            }

            val bestSeat = candidates.maxByOrNull { it.second } ?: return@Runnable

            plugin.server.scheduler.runTask(plugin, Runnable {
                // Safety guard: NPC could have died/unloaded or been assigned another task while the async calculation ran
                if (!npc.isValid || npc.isDead || activeSessions.containsKey(npc)) return@Runnable
                if (occupiedSeats.contains(bestSeat.first)) return@Runnable

                assignSeat(npc, bestSeat.first, desiredPreference)

                if (isSocial && bestSeat.second >= config.scoring.minScoreForSocialInvite) {
                    val friends = npc.getNearbyEntities(config.interaction.socialInviteRadiusX, config.interaction.socialInviteRadiusY, config.interaction.socialInviteRadiusZ)
                        .filterIsInstance<Villager>()
                        .filter { it.isValid && !activeSessions.containsKey(it) && it.vehicle == null }
                        .shuffled()
                        .take(Random.nextInt(1, config.interaction.maxFriendsToInvite + 1))

                    val friendSeats = getAdjacentFreeSeats(bestSeat.first, friends.size)
                    friends.forEachIndexed { index, friend ->
                        friendSeats.getOrNull(index)?.let { fLoc ->
                            assignSeat(friend, fLoc, desiredPreference)
                        }
                    }
                }
            })
        })
    }

    private fun assignSeat(npc: Villager, loc: Location, pref: Preference) {
        if (occupiedSeats.contains(loc)) return
        occupiedSeats.add(loc)
        activeSessions[npc] = LeisureSession(
            villager = npc,
            targetSeat = loc,
            preference = pref,
            maxDuration = Random.nextLong(config.duration.minDurationMs, config.duration.maxDurationMs)
        )
    }

    private fun handlePathing(session: LeisureSession) {
        val npc = session.villager
        val target = session.targetSeat

        // Switch to sitting state once the NPC is close enough to the target
        if (npc.location.distanceSquared(target) < config.pathing.sitDistanceSquared) {
            session.state = LeisureState.SITTING
            plugin.gameplayManager.humanoidManager.protocolListener.actionController.toggleSitting(npc, true, target.block)
            return
        }

        // Manage pathfinding through the Paper API safely
        val pathfinder: Pathfinder = npc.pathfinder
        val finalPoint = pathfinder.currentPath?.finalPoint
        val distanceSq = finalPoint?.distanceSquared(target) ?: Double.MAX_VALUE

        if (!pathfinder.hasPath() || distanceSq > config.pathing.repathDistanceSquared) {
            pathfinder.moveTo(target, config.pathing.walkSpeed)
        }
    }

    private fun handleSitting(session: LeisureSession) {
        val npc = session.villager
        if (Random.nextDouble() < config.interaction.consumptionChancePerSecond) {
            triggerRandomConsumption(npc)
        }
    }

    private fun triggerRandomConsumption(npc: Villager) {
        val humanoid = plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(npc)

        val isDrink = Random.nextBoolean()
        val item = if (isDrink) {
            ItemStack(Material.POTION).apply {
                val meta = itemMeta as PotionMeta
                meta.color = org.bukkit.Color.fromRGB(Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
                itemMeta = meta
            }
        } else {
            val foods = listOf(Material.BREAD, Material.APPLE, Material.COOKED_BEEF, Material.CARROT)
            ItemStack(foods.random())
        }

        val sound = if (isDrink) Sound.ENTITY_GENERIC_DRINK else Sound.ENTITY_GENERIC_EAT

        humanoid?.consume(npc.world, item, sound, 7, npc.location, 7) {
            // Visual consumption completed; no status effects are applied.
        }
    }

    private fun standUp(npc: Villager, session: LeisureSession) {
        plugin.gameplayManager.humanoidManager.protocolListener.actionController.toggleSitting(npc, false)
        if (session.state == LeisureState.PATHING) {
            npc.pathfinder.stopPathfinding()
        }
        freeSeat(session)
    }

    private fun freeSeat(session: LeisureSession) {
        occupiedSeats.remove(session.targetSeat)
    }

    private fun getAdjacentFreeSeats(center: Location, count: Int): List<Location> {
        val seats = mutableListOf<Location>()
        val tempOccupied = occupiedSeats.toMutableSet()
        val world = center.world
        val minHeight = world.minHeight
        val maxHeight = world.maxHeight

        val offsets = listOf(
            Pair(2, 0), Pair(-2, 0), Pair(0, 2), Pair(0, -2),
            Pair(2, 2), Pair(-2, -2), Pair(2, -2), Pair(-2, 2),
            Pair(3, 0), Pair(-3, 0), Pair(0, 3), Pair(0, -3)
        )

        for (offset in offsets) {
            if (seats.size >= count) break
            val checkLoc = center.clone().add(offset.first.toDouble(), 0.0, offset.second.toDouble())

            if (checkLoc.blockY !in minHeight until maxHeight) continue

            // Guard against evaluating unloaded chunks synchronously.
            if (!world.isChunkLoaded(checkLoc.blockX shr 4, checkLoc.blockZ shr 4)) continue

            val block = checkLoc.block
            val mat = block.type
            var isSeat = mat.name.endsWith("STAIRS") || mat.isSolid

            if (mat.name.endsWith("STAIRS")) {
                val bData = block.blockData
                if (bData is org.bukkit.block.data.type.Stairs) {
                    if (bData.half == org.bukkit.block.data.Bisected.Half.TOP) {
                        isSeat = false
                    } else {
                        val frontFace = bData.facing.oppositeFace
                        val frontX = checkLoc.blockX + frontFace.modX
                        val frontZ = checkLoc.blockZ + frontFace.modZ

                        // Guard for the block directly in front of the stairs
                        if (!world.isChunkLoaded(frontX shr 4, frontZ shr 4)) {
                            isSeat = false
                        } else {
                            val frontBlock = block.getRelative(frontFace)
                            val frontBlockUp = frontBlock.getRelative(BlockFace.UP)

                            if (frontBlock.type.isSolid || frontBlockUp.type.isSolid) {
                                isSeat = false
                            }
                        }
                    }
                }

                if (isSeat) {
                    var isStaircase = false
                    for (dx in -1..1) {
                        for (dz in -1..1) {
                            val neighborX = checkLoc.blockX + dx
                            val neighborZ = checkLoc.blockZ + dz

                            if (!world.isChunkLoaded(neighborX shr 4, neighborZ shr 4)) continue

                            if (checkLoc.blockY + 1 < maxHeight) {
                                val up = world.getBlockAt(neighborX, checkLoc.blockY + 1, neighborZ).type
                                if (up.name.endsWith("STAIRS")) isStaircase = true
                            }
                            if (checkLoc.blockY - 1 >= minHeight) {
                                val down = world.getBlockAt(neighborX, checkLoc.blockY - 1, neighborZ).type
                                if (down.name.endsWith("STAIRS")) isStaircase = true
                            }
                            if (isStaircase) break
                        }
                        if (isStaircase) break
                    }
                    if (isStaircase) isSeat = false
                }
            }

            if (isSeat && !tempOccupied.contains(checkLoc)) {
                if (isPersonalSpaceInvaded(checkLoc, tempOccupied)) continue

                if (checkLoc.blockY + 2 < maxHeight &&
                    checkLoc.clone().add(0.0, 1.0, 0.0).block.type.isAir &&
                    checkLoc.clone().add(0.0, 2.0, 0.0).block.type.isAir) {
                    seats.add(checkLoc)
                    tempOccupied.add(checkLoc)
                }
            }
        }
        return seats
    }

    // =======================================================================================
    // EVENTS
    // =======================================================================================
    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin == plugin) {
            val iterator = activeSessions.values.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                try {
                    standUp(session.villager, session)
                } catch (_: Exception) {}
                iterator.remove()
            }
            occupiedSeats.clear()
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        val iterator = activeSessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            val npcLoc = session.villager.location
            val seatLoc = session.targetSeat

            // Cancel session if either the NPC or its designated destination gets unloaded
            val isNpcInChunk = npcLoc.world == chunk.world && (npcLoc.blockX shr 4) == chunk.x && (npcLoc.blockZ shr 4) == chunk.z
            val isSeatInChunk = seatLoc.world == chunk.world && (seatLoc.blockX shr 4) == chunk.x && (seatLoc.blockZ shr 4) == chunk.z

            if (isNpcInChunk || isSeatInChunk) {
                standUp(session.villager, session)
                iterator.remove()
            }
        }
    }

    @EventHandler
    fun onNpcDamage(event: EntityDamageEvent) {
        val npc = event.entity as? Villager ?: return
        val session = activeSessions[npc] ?: return

        standUp(npc, session)
        activeSessions.remove(npc)
    }

    @EventHandler
    fun onNpcDeath(event: EntityDeathEvent) {
        val npc = event.entity as? Villager ?: return
        activeSessions.remove(npc)?.let { session ->
            standUp(npc, session)
        }
    }
}
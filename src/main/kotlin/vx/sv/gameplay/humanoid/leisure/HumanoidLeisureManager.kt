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

    private val activeSessions = mutableMapOf<Villager, LeisureSession>()
    private val occupiedSeats = mutableSetOf<Location>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Global ticker: searches for idle NPCs that can start a leisure session.
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            if (activeSessions.size > config.maxActiveSessions) return@runTaskTimer

            val candidate = plugin.server.worlds
                .filter { plugin.gameplayManager.allowedWorlds.contains(it) }
                // Restrict leisure search during the night, as NPCs should be sleeping.
                .filter { it.time !in config.time.nightStart..config.time.nightEnd }
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

                // Invalidate session if the NPC is dead or no longer valid (e.g. chunk unexpectedly unloaded)
                if (!npc.isValid || npc.isDead) {
                    standUp(npc, session) // Ensures attributes are safely reset
                    iterator.remove()
                    continue
                }

                // Force NPCs sitting outdoors to stand up and go home when night falls
                val isNight = npc.world.time in config.time.nightStart..config.time.nightEnd
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

    /**
     * Checks if the 3x3 area around a potential seat is already occupied.
     * Enforces a personal space rule so NPCs do not sit shoulder-to-shoulder.
     */
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

    /**
     * Initiates an asynchronous search for the best seating block around the NPC.
     */
    private fun startLeisureSearch(npc: Villager) {
        val centerChunk = npc.location.chunk
        val world = npc.world

        // Determine NPC's environmental preference based on configuration
        val desiredPreference = if (Random.nextDouble() < config.interaction.indoorPreferenceChance) Preference.INDOOR else Preference.OUTDOOR
        val isSocial = Random.nextBoolean()

        // Synchronously collect snapshots of a 3x3 chunk area to allow safe async block reading.
        val snapshots = mutableListOf<org.bukkit.ChunkSnapshot>()
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (world.isChunkLoaded(centerChunk.x + dx, centerChunk.z + dz)) {
                    snapshots.add(world.getChunkAt(centerChunk.x + dx, centerChunk.z + dz).chunkSnapshot)
                }
            }
        }

        // Shift to async thread to perform the heavy block evaluation
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val npcY = npc.location.blockY
            val candidates = mutableListOf<Pair<Location, Int>>() // Maps a Location to its calculated Score
            val campfires = mutableListOf<Triple<Int, Int, Int>>() // Stores campfire coordinates: worldX, y, worldZ

            // Lambda for fast, cross-chunk block material retrieval
            val getMat = { wx: Int, wy: Int, wz: Int ->
                val cx = wx shr 4
                val cz = wz shr 4
                val snap = snapshots.find { it.x == cx && it.z == cz }
                snap?.getBlockType(wx and 15, wy, wz and 15) ?: Material.AIR
            }

            // Pre-pass: Scan for campfires to potentially boost outdoor seating priority.
            // A slightly larger vertical range is used to ensure ground-level campfires are found.
            for (snapshot in snapshots) {
                for (y in (npcY - 4)..(npcY + 4)) {
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

            // Main pass: Evaluate blocks for seating suitability
            for (snapshot in snapshots) {
                for (y in (npcY - 3)..(npcY + 2)) {
                    for (x in 0..15) {
                        for (z in 0..15) {
                            val material = snapshot.getBlockType(x, y, z)
                            if (material.isAir) continue

                            val isStairs = material.name.endsWith("STAIRS")

                            val worldX = (snapshot.x shl 4) + x
                            val worldZ = (snapshot.z shl 4) + z

                            if (isStairs) {
                                val blockData = snapshot.getBlockData(x, y, z)
                                if (blockData is org.bukkit.block.data.type.Stairs) {
                                    // Ignore upside-down stairs as they are invalid seats
                                    if (blockData.half == org.bukkit.block.data.Bisected.Half.TOP) continue

                                    // Directional check: ensure there is no solid block directly in front of the seat.
                                    // In Bukkit, the 'facing' of stairs is the back of the chair, so opposite is the front.
                                    val frontFace = blockData.facing.oppositeFace
                                    val frontX = worldX + frontFace.modX
                                    val frontZ = worldZ + frontFace.modZ

                                    val frontMat = getMat(frontX, y, frontZ)
                                    val frontMatUp = getMat(frontX, y + 1, frontZ)

                                    // Skip if the legs or torso area in front of the stair is blocked
                                    if (frontMat.isSolid || frontMatUp.isSolid) continue
                                }

                                // Staircase filter: Check Y+1 and Y-1 to differentiate a bench from an actual staircase.
                                // If adjacent stairs are detected vertically, it indicates an elevation structure.
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

                            val isSolid = material.isSolid

                            if (isStairs || isSolid) {
                                val loc = Location(world, worldX.toDouble(), y.toDouble(), worldZ.toDouble())

                                // Skip if the seat is already reserved
                                if (occupiedSeats.contains(loc)) continue

                                // Enforce personal space to prevent overlapping or intimately close NPCs
                                if (isPersonalSpaceInvaded(loc)) continue

                                // Ensure there are at least 2 empty blocks above the seat to prevent head collision
                                if (!snapshot.getBlockType(x, y + 1, z).isAir || !snapshot.getBlockType(x, y + 2, z).isAir) {
                                    continue
                                }

                                var score = 0

                                // Determine if the seat is indoor or outdoor based on the highest block Y
                                val highestY = snapshot.getHighestBlockYAt(x, z)
                                val isIndoor = highestY > y + 2
                                val actualPreference = if (isIndoor) Preference.INDOOR else Preference.OUTDOOR

                                // Add score for matching the NPC's desired preference
                                if (actualPreference == desiredPreference) score += config.scoring.preferenceMatchBonus
                                // Provide a base priority for indoor environments to encourage home usage
                                if (isIndoor) score += config.scoring.indoorBaseBonus
                                // Prioritize proper stair blocks over raw solid blocks
                                if (isStairs) score += config.scoring.stairBonus

                                // Social intelligence check: if this is a stair block with adjacent stairs, it's a bench.
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

                                // Campfire priority: If looking for an outdoor seat, prioritize locations near fire.
                                if (desiredPreference == Preference.OUTDOOR && actualPreference == Preference.OUTDOOR) {
                                    val nearCampfire = campfires.any { cf ->
                                        // Detection radius: 6 blocks horizontally, 2 blocks vertically
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

            // Select the highest-scoring seat
            val bestSeat = candidates.maxByOrNull { it.second } ?: return@Runnable

            // Return to the main thread to assign the seat and handle entities
            plugin.server.scheduler.runTask(plugin, Runnable {
                assignSeat(npc, bestSeat.first, desiredPreference)

                // If the NPC is social and found a high-value bench, invite nearby friends
                if (isSocial && bestSeat.second >= config.scoring.minScoreForSocialInvite) {
                    val friends = npc.getNearbyEntities(config.interaction.socialInviteRadiusX, config.interaction.socialInviteRadiusY, config.interaction.socialInviteRadiusZ)
                        .filterIsInstance<Villager>()
                        .filter { it.isValid && !activeSessions.containsKey(it) && it.vehicle == null }
                        .shuffled()
                        .take(Random.nextInt(1, config.interaction.maxFriendsToInvite + 1))

                    // Attempt to seat friends on valid adjacent blocks
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

        // Manage pathfinding through the Paper API
        val pathfinder: Pathfinder = npc.pathfinder
        if (!pathfinder.hasPath() || pathfinder.currentPath?.finalPoint?.distanceSquared(target)!! > config.pathing.repathDistanceSquared) {
            pathfinder.moveTo(target, config.pathing.walkSpeed)
        }
    }

    private fun handleSitting(session: LeisureSession) {
        val npc = session.villager

        // Chance per second to consume a random visual food or drink item
        if (Random.nextDouble() < config.interaction.consumptionChancePerSecond) {
            triggerRandomConsumption(npc)
        }
    }

    /**
     * Creates a visual-only consumption effect (eating or drinking) for the NPC.
     */
    private fun triggerRandomConsumption(npc: Villager) {
        val humanoid = plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(npc)

        val isDrink = Random.nextBoolean()
        val item = if (isDrink) {
            ItemStack(Material.POTION).apply {
                val meta = itemMeta as PotionMeta
                meta.color = org.bukkit.Color.fromRGB(Random.nextInt(255), Random.nextInt(255), Random.nextInt(255))
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
        freeSeat(session)
    }

    private fun freeSeat(session: LeisureSession) {
        occupiedSeats.remove(session.targetSeat)
    }

    /**
     * Evaluates seating availability for friends around a central location, enforcing a strict personal distance.
     */
    private fun getAdjacentFreeSeats(center: Location, count: Int): List<Location> {
        val seats = mutableListOf<Location>()
        val tempOccupied = occupiedSeats.toMutableSet() // Local copy to prevent friends from sitting next to each other

        // Offsets represent a minimum of 1 empty block gap (i.e., distance of 2 or 3 blocks).
        val offsets = listOf(
            Pair(2, 0), Pair(-2, 0), Pair(0, 2), Pair(0, -2), // 1 block gap straight
            Pair(2, 2), Pair(-2, -2), Pair(2, -2), Pair(-2, 2), // 1 block gap diagonally
            Pair(3, 0), Pair(-3, 0), Pair(0, 3), Pair(0, -3) // Further check for longer benches
        )
        val world = center.world

        for (offset in offsets) {
            if (seats.size >= count) break
            val checkLoc = center.clone().add(offset.first.toDouble(), 0.0, offset.second.toDouble())
            val block = checkLoc.block
            val mat = block.type
            var isSeat = mat.name.endsWith("STAIRS")

            if (isSeat) {
                val bData = block.blockData
                if (bData is org.bukkit.block.data.type.Stairs) {
                    if (bData.half == org.bukkit.block.data.Bisected.Half.TOP) {
                        isSeat = false
                    } else {
                        // Ensure the space in front of the friend's seat is not blocked
                        val frontFace = bData.facing.oppositeFace
                        val frontBlock = block.getRelative(frontFace)
                        val frontBlockUp = frontBlock.getRelative(BlockFace.UP)

                        if (frontBlock.type.isSolid || frontBlockUp.type.isSolid) {
                            isSeat = false
                        }
                    }
                }

                // Re-apply the staircase elevation filter for friend seating
                if (isSeat) {
                    var isStaircase = false
                    for (dx in -1..1) {
                        for (dz in -1..1) {
                            val up = world.getBlockAt(checkLoc.blockX + dx, checkLoc.blockY + 1, checkLoc.blockZ + dz).type
                            val down = world.getBlockAt(checkLoc.blockX + dx, checkLoc.blockY - 1, checkLoc.blockZ + dz).type
                            if (up.name.endsWith("STAIRS") || down.name.endsWith("STAIRS")) {
                                isStaircase = true
                                break
                            }
                        }
                        if (isStaircase) break
                    }
                    if (isStaircase) isSeat = false
                }
            }

            if (isSeat && !tempOccupied.contains(checkLoc)) {
                // Ensure the friend respects the personal space constraint based on the local occupied map
                if (isPersonalSpaceInvaded(checkLoc, tempOccupied)) continue

                // Check for head clearance
                if (checkLoc.clone().add(0.0, 1.0, 0.0).block.type.isAir) {
                    seats.add(checkLoc)
                    tempOccupied.add(checkLoc) // Reserve locally to maintain spacing for the next friend
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
            // Force all NPCs to stand up before the server stops or the plugin reloads.
            // This prevents their sitting attributes from being permanently stuck in their NBT data.
            val iterator = activeSessions.values.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                try {
                    standUp(session.villager, session)
                } catch (_: Exception) {
                    // Fail silently during shutdown sequence if entities are already invalidated
                }
                iterator.remove()
            }
            occupiedSeats.clear()
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        // Prevent attributes from getting stuck when the chunk they are in gets unloaded
        val iterator = activeSessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (session.villager.location.chunk == chunk) {
                standUp(session.villager, session)
                iterator.remove()
            }
        }
    }

    @EventHandler
    fun onNpcDamage(event: EntityDamageEvent) {
        val npc = event.entity as? Villager ?: return
        val session = activeSessions[npc] ?: return

        // Interrupt leisure session upon taking any damage
        standUp(npc, session)
        activeSessions.remove(npc)
    }

    @EventHandler
    fun onNpcDeath(event: EntityDeathEvent) {
        val npc = event.entity as? Villager ?: return
        activeSessions.remove(npc)?.let { session ->
            // Use standUp to ensure sitting attributes are fully cleared before the entity is destroyed
            standUp(npc, session)
        }
    }
}
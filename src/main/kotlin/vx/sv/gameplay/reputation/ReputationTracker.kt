package vx.sv.gameplay.reputation

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.IronGolem
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.personality.PersonalityManager.Companion.gender
import vx.sv.gameplay.personality.PersonalityManager.Gender
import vx.sv.gameplay.reputation.ReputationManager.Reputation
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.*
import kotlin.random.Random

class ReputationTracker : Listener {

    private val repManager = plugin.gameplayManager.reputationManager
    private val config get() = plugin.gameplayManager.config.reputation

    // Annoyance timers: NPC UUID to Player UUID -> Contact start timestamp
    private val annoyanceTimers = mutableMapOf<Pair<UUID, UUID>, Long>()

    // Phrase cooldowns: NPC UUID -> Last shout timestamp
    private val shoutCooldowns = mutableMapOf<UUID, Long>()

    // Pre-calculated squared radius for performance (5.0 * 5.0 = 25.0)
    private val personalSpaceRadiusSq = 25.0
    // Squared distance to check before calculating heavy RayTrace (hasLineOfSight) in NPC vs NPC
    private val npcWarfareRadiusSq = 225.0 // 15 blocks

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startAggressionTicker()
    }

    enum class NPCState(val translationKey: String, val color: String) {
        ANNOYED("npc-state.annoyed", "§e"),
        AGGRESSIVE("npc-state.aggressive", "§c")
    }

    /**
     * Checks if the player should be ignored by the NPC AI (Creative / Spectator)
     */
    private fun isIgnored(player: Player): Boolean {
        return player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR
    }

    fun getNPCState(npc: LivingEntity, player: Player): NPCState? {
        if (isIgnored(player)) return null

        // This uses our unified manager method that properly sums up Personal + Settlement reputation
        val finalStatus = repManager.getPlayerReputationStatus(npc, player)

        if (finalStatus.ordinal >= Reputation.HOSTILE.ordinal) {
            return NPCState.AGGRESSIVE
        }

        if (finalStatus == Reputation.UNFRIENDLY && annoyanceTimers.containsKey(npc.uniqueId to player.uniqueId)) {
            return NPCState.ANNOYED
        }

        return null
    }

    /**
     * Ticker for aggression and annoyance logic.
     * Evaluates both Player vs NPC and NPC vs NPC (Warfare).
     */
    private fun startAggressionTicker() {
        // Increased interval to 40 ticks (2 seconds) to save TPS.
        // 2 seconds is a perfectly fine reaction time for NPCs.
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()

            // 1. Time-based cleanup: remove stale annoyance timers (older than 30s)
            // This replaces the extremely heavy plugin.server.getEntity(UUID) call.
            annoyanceTimers.entries.removeIf { now - it.value > 30000L }

            // Cache to prevent redundant NPC vs NPC checks if players' view distances overlap
            val checkedNpcPairs = mutableSetOf<Pair<UUID, UUID>>()

            for (player in plugin.server.onlinePlayers) {
                if (isIgnored(player)) continue

                // Limiting bounding box entities fetching
                val npcs = player.getNearbyEntities(config.aggressionRadius, config.aggressionRadius, config.aggressionRadius)
                    .filterIsInstance<LivingEntity>()
                    .filter { !it.isDead && (it is Villager || it is IronGolem) }

                // --- 2. Player vs NPC Logic ---
                for (npc in npcs) {
                    val pair = npc.uniqueId to player.uniqueId

                    // Unified status check: ALWAYS considers the SUM of personal + town reputation!
                    val finalStatus = repManager.getPlayerReputationStatus(npc, player)

                    // PERFORMANCE: Use distanceSquared to avoid Math.sqrt()
                    val distanceSq = npc.location.distanceSquared(player.location)

                    when {
                        finalStatus == Reputation.UNFRIENDLY -> {
                            // Check distance first, ONLY then use heavy RayTrace (hasLineOfSight)
                            if (distanceSq <= personalSpaceRadiusSq && npc.hasLineOfSight(player)) {
                                val startTime = annoyanceTimers.getOrPut(pair) {
                                    shoutWithCooldown(npc, npc.race.phrases.warning.randomOrNull(), isAggressive = true)
                                    now
                                }
                                if (now - startTime > 20000L) {
                                    triggerAggression(npc, player, isFullCombat = false)
                                }
                            } else {
                                // Player backed off or hid behind a wall -> remove annoyance
                                if (annoyanceTimers.containsKey(pair)) resetAggro(npc, player)
                            }
                        }
                        finalStatus.ordinal >= Reputation.HOSTILE.ordinal -> {
                            if (npc.hasLineOfSight(player)) {
                                triggerAggression(npc, player, isFullCombat = true)
                                callForHelp(npc, player)
                            }
                        }
                        else -> {
                            // If player is Neutral or better, clear annoyance immediately
                            if (annoyanceTimers.containsKey(pair)) resetAggro(npc, player)
                        }
                    }
                }

                // --- 3. NPC vs NPC Logic (Settlement Warfare) ---
                for (i in npcs.indices) {
                    for (j in i + 1 until npcs.size) {
                        val npc1 = npcs[i]
                        val npc2 = npcs[j]

                        // Prevent reverse pair checking (A->B and B->A)
                        val pairId = if (npc1.uniqueId > npc2.uniqueId) npc1.uniqueId to npc2.uniqueId else npc2.uniqueId to npc1.uniqueId
                        if (!checkedNpcPairs.add(pairId)) continue

                        val s1 = npc1.settlement ?: continue
                        val s2 = npc2.settlement ?: continue

                        // Proceed only if they belong to different settlements
                        if (s1.data.id != s2.data.id) {
                            if (SettlementManager.getRelation(s1, s2) == Settlement.RelationLevel.WAR) {
                                // PERFORMANCE: Check distance first
                                if (npc1.location.distanceSquared(npc2.location) <= npcWarfareRadiusSq) {
                                    // PERFORMANCE: Only one Line of Sight check instead of two
                                    if (npc1.hasLineOfSight(npc2)) {
                                        triggerAggression(npc1, npc2, isFullCombat = true)
                                        triggerAggression(npc2, npc1, isFullCombat = true) // Mutual aggression
                                        callForHelp(npc1, npc2)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, 40L, 40L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNpcHit(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        val attacker = event.damager as? Player ?: return

        if (isIgnored(attacker)) return
        if (victim !is Villager && victim !is IronGolem) return

        val penalty = (event.damage * config.damageReputationMultiplier).toInt()
        if (penalty > 0) {
            val settlement = victim.settlement
            if (settlement != null) {
                // Apply penalty globally to the settlement
                repManager.addReputation(settlement, attacker, -penalty)
            } else {
                // Independent NPC fallback
                repManager.addReputation(victim, attacker, -penalty)
            }
        }

        // Properly fetch final combined sum
        val finalStatus = repManager.getPlayerReputationStatus(victim, attacker)
        if (finalStatus.ordinal >= Reputation.UNFRIENDLY.ordinal) {
            triggerAggression(victim, attacker, isFullCombat = finalStatus.ordinal >= Reputation.HOSTILE.ordinal)
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val victim = event.entity
        val killerEntity = victim.killer ?: return

        if (victim is Villager || victim is IronGolem) {
            annoyanceTimers.entries.removeIf { it.key.first == victim.uniqueId }
            shoutCooldowns.remove(victim.uniqueId)

            if (isIgnored(killerEntity)) return
            val settlement = victim.settlement ?: return

            // Let the global unified ReputationManager handle the global settlement penalty and save properly
            repManager.addReputation(settlement, killerEntity, -config.killSettlementPenalty)

            triggerSettlementAlarm(settlement)

            val witnesses = victim.getNearbyEntities(config.witnessRadius, config.witnessRadius, config.witnessRadius)
                .filterIsInstance<LivingEntity>()
                .filter { !it.isDead && (it is Villager || it is IronGolem) && it.settlement == settlement }

            witnesses.randomOrNull()?.let { screamer ->
                val phrase = screamer.race.phrases.witnessMurder.randomOrNull()?.replace("{victim}", victim.customName ?: victim.name)
                shoutWithCooldown(screamer, phrase, isAggressive = true, ignoreCooldown = true)
            }

            witnesses.forEach { triggerAggression(it, killerEntity, isFullCombat = true) }
            return
        }

        // PERFORMANCE: Fail-fast check!
        // Avoid heavy territory (AABB/Polygon) checks for neutral animals or unconfigured mobs
        val repGain = config.monsterKillReputation[victim.type.name] ?: return
        if (repGain <= 0) return

        val worldSettlements = SettlementManager.settlements[victim.world] ?: return
        // Only run territory.contains() if we are 100% sure it's a valid monster that yields reputation
        val activeSettlement = worldSettlements.find { it.territory.contains(victim.location.toVector()) } ?: return

        // Global unified ReputationManager takes care of rewarding the player!
        repManager.addReputation(activeSettlement, killerEntity, repGain)
    }

    @EventHandler
    fun onGolemTarget(event: EntityTargetLivingEntityEvent) {
        if (event.entity !is IronGolem) return
        val target = event.target ?: return

        if (target is Player && isIgnored(target)) {
            event.isCancelled = true
            return
        }

        if (target is Villager || target is IronGolem) {
            val golem = event.entity as IronGolem
            val golemSettlement = golem.settlement
            val targetSettlement = target.settlement

            // Allow Golems to attack if the targets belong to a hostile settlement
            if (golemSettlement != null && targetSettlement != null && golemSettlement.data.id != targetSettlement.data.id) {
                if (SettlementManager.getRelation(golemSettlement, targetSettlement) == Settlement.RelationLevel.WAR) {
                    return // Permit the attack
                }
            }
            event.isCancelled = true // Cancel friendly fire / neutral hits
        }
    }

    // --- AGGRESSION LOGIC ---

    /**
     * Triggers the NPC attack towards any LivingEntity (Player or rival NPC).
     * Must be executed synchronously!
     */
    private fun triggerAggression(npc: LivingEntity, target: LivingEntity, isFullCombat: Boolean) {
        if (target is Player && isIgnored(target)) return

        val phrasePool = if (isFullCombat) {
            npc.race.phrases.startFight
        } else {
            npc.race.phrases.warning
        }

        shoutWithCooldown(npc, phrasePool.randomOrNull(), isAggressive = true)

        try {
            val humanoid = plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(npc)

            if (isFullCombat) {
                humanoid.attack(target)
            } else {
                // Annoyance mode: just one punch
                if (target is Player) {
                    humanoid.attack(target, 1)
                    annoyanceTimers.remove(npc.uniqueId to target.uniqueId)
                } else {
                    humanoid.attack(target) // NPCs always full attack other NPCs if provoked
                }
            }
        } catch (_: Exception) {
            if (npc is IronGolem) {
                npc.target = target
            }
        }
    }

    private fun resetAggro(npc: LivingEntity, target: LivingEntity) {
        try {
            npc.setAI(false)
            npc.setAI(true)
        } catch (_: Exception) {
            if (npc is IronGolem && npc.target == target) npc.target = null
        }
        annoyanceTimers.remove(npc.uniqueId to target.uniqueId)
    }

    private fun callForHelp(caller: LivingEntity, enemy: LivingEntity) {
        val settlement = caller.settlement ?: return
        caller.getNearbyEntities(15.0, 10.0, 15.0)
            .filterIsInstance<LivingEntity>()
            .filter { !it.isDead && (it is Villager || it is IronGolem) && it.settlement == settlement }
            .forEach { triggerAggression(it, enemy, isFullCombat = true) }
    }

    private fun triggerSettlementAlarm(settlement: Settlement) {
        val center = settlement.data.center
        if (center.block.type == Material.BELL) {
            center.world.playSound(center, Sound.BLOCK_BELL_RESONATE, 4.0f, 1.0f)
        }
    }

    // --- PHRASE LOGIC ---

    private fun shoutWithCooldown(entity: LivingEntity, message: String?, isAggressive: Boolean = false, ignoreCooldown: Boolean = false) {
        if (message == null) return

        val now = System.currentTimeMillis()
        val lastShout = shoutCooldowns[entity.uniqueId] ?: 0L

        if (ignoreCooldown || now - lastShout > 10000L) {
            shoutCooldowns[entity.uniqueId] = now

            val name = entity.customName ?: entity.race.name
            val formatted = "§6$name§7: §c$message"

            entity.location.getNearbyPlayers(config.shoutRadius).forEach {
                it.sendMessage(formatted)
            }

            val race = entity.race
            val voices = if (entity.gender == Gender.MALE) race.maleVoices else race.femaleVoices

            if (voices.isNotEmpty()) {
                val voice = voices.random()
                val sound = voice.sound.get() ?: Sound.INTENTIONALLY_EMPTY
                var pitch = Random.nextDouble(voice.min, voice.max).toFloat()

                if (isAggressive) pitch *= 0.85f

                try {
                    entity.world.playSound(entity.location, sound, 1.0f, pitch)
                } catch (_: Exception) {}
            }
        }
    }
}
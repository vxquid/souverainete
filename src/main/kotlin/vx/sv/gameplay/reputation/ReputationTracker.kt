package vx.sv.gameplay.reputation

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
import vx.sv.gameplay.settlement.SettlementManager.Companion.currentSettlement
import vx.sv.gameplay.settlement.SettlementManager.Companion.settlements
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.*
import kotlin.random.Random

class ReputationTracker : Listener {

    private val repManager = plugin.gameplayManager.reputationManager
    private val config get() = plugin.gameplayManager.config.reputation

    // Annoyance timers: NPC UUID + Player UUID -> Contact start timestamp
    private val annoyanceTimers = mutableMapOf<Pair<UUID, UUID>, Long>()

    // Phrase cooldowns: NPC UUID -> Last shout timestamp
    private val shoutCooldowns = mutableMapOf<UUID, Long>()

    // Personal space radius for Unfriendly warnings
    private val personalSpaceRadius = 5.0

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startAggressionTicker()
    }

    enum class NPCState(val translationKey: String, val color: String) {
        ANNOYED("npc-state.annoyed", "§e"),
        AGGRESSIVE("npc-state.aggressive", "§c")
    }

    /**
     * Determines the visual state of the NPC for a specific player.
     */
    fun getNPCState(npc: LivingEntity, player: Player): NPCState? {
        val finalStatus = getFinalStatus(npc, player)

        // Full combat (Hostile/Exiled)
        if (finalStatus.ordinal >= Reputation.HOSTILE.ordinal) {
            return NPCState.AGGRESSIVE
        }

        // Warning phase (Unfriendly + Player within 5 blocks and timer active)
        if (finalStatus == Reputation.UNFRIENDLY && annoyanceTimers.containsKey(npc.uniqueId to player.uniqueId)) {
            return NPCState.ANNOYED
        }

        return null
    }

    private fun getFinalScore(entity: LivingEntity, player: Player): Int {
        val personalScore = repManager.getReputationMap(entity)[player.uniqueId] ?: 0
        val settlementScore = entity.settlement?.data?.reputation?.get(player.uniqueId) ?: 0
        return personalScore + settlementScore
    }

    private fun getFinalStatus(entity: LivingEntity, player: Player): Reputation {
        return repManager.getReputationStatusFromScore(getFinalScore(entity, player))
    }

    /**
     * Ticker for aggression and annoyance logic.
     */
    private fun startAggressionTicker() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in plugin.server.onlinePlayers) {
                val playerSettlement = player.currentSettlement
                val nearby = player.getNearbyEntities(config.aggressionRadius, config.aggressionRadius, config.aggressionRadius)

                // Cleanup timers if player is out of range or left the settlement
                annoyanceTimers.keys.removeIf { it.second == player.uniqueId &&
                        (playerSettlement == null || player.location.distance(plugin.server.getEntity(it.first)?.location ?: player.location) > personalSpaceRadius)
                }

                for (entity in nearby) {
                    if (entity !is LivingEntity || (entity !is Villager && entity !is IronGolem)) continue

                    // Reset aggro and timers if player left the settlement area
                    if (playerSettlement == null || (entity.settlement != null && entity.settlement?.data?.settlementName != playerSettlement)) {
                        resetAggro(entity, player)
                        continue
                    }

                    val finalStatus = getFinalStatus(entity, player)
                    val pair = entity.uniqueId to player.uniqueId
                    val distance = entity.location.distance(player.location)

                    when {
                        // 1. UNFRIENDLY status: Only get annoyed if player is within personal space
                        finalStatus == Reputation.UNFRIENDLY -> {
                            if (distance <= personalSpaceRadius && entity.hasLineOfSight(player)) {
                                val startTime = annoyanceTimers.getOrPut(pair) {
                                    // Shout warning when timer starts (Aggressive tone)
                                    shoutWithCooldown(entity, entity.race.phrases.warning.randomOrNull(), isAggressive = true)
                                    System.currentTimeMillis()
                                }

                                val elapsed = System.currentTimeMillis() - startTime
                                if (elapsed > 20000L) { // 20 seconds elapsed
                                    triggerAggression(entity, player, finalStatus)
                                }
                            }
                        }

                        // 2. HOSTILE and EXILED statuses: Immediate attack
                        finalStatus.ordinal >= Reputation.HOSTILE.ordinal -> {
                            if (entity.hasLineOfSight(player)) {
                                triggerAggression(entity, player, finalStatus)
                                callForHelp(entity, player)
                            }
                        }

                        // 3. Neutral or better: remove any annoyance tracking
                        else -> annoyanceTimers.remove(pair)
                    }
                }
            }
        }, 20L, 20L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNpcHit(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? LivingEntity ?: return
        val attacker = event.damager as? Player ?: return

        if (victim is IronGolem || victim !is Villager) return

        val penalty = (event.damage * config.damageReputationMultiplier).toInt()
        if (penalty > 0) repManager.addReputation(victim, attacker, -penalty)

        val finalStatus = getFinalStatus(victim, attacker)
        // If attacker is already disliked, respond immediately
        if (finalStatus.ordinal >= Reputation.UNFRIENDLY.ordinal) {
            triggerAggression(victim, attacker, finalStatus)
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val victim = event.entity
        val killerEntity = victim.killer

        // --- NPC DEATH LOGIC ---
        if (victim is Villager || victim is IronGolem) {
            // Fix: Clear internal timers immediately when the NPC dies
            annoyanceTimers.keys.removeIf { it.first == victim.uniqueId }
            shoutCooldowns.remove(victim.uniqueId)

            if (killerEntity == null) return
            val settlement = victim.settlement ?: return

            val currentSettlementRep = settlement.data.reputation.getOrDefault(killerEntity.uniqueId, 0)
            settlement.data.reputation[killerEntity.uniqueId] = currentSettlementRep - config.killSettlementPenalty

            if (config.chatNotification) {
                val msg = plugin.language.getString("settlement-reputation.decrease")!!
                    .replace("{entity}", settlement.data.settlementName)
                    .replace("{amount}", config.killSettlementPenalty.toString())
                killerEntity.sendMessage(msg)
            }

            triggerSettlementAlarm(settlement)

            val witnesses = victim.getNearbyEntities(config.witnessRadius, config.witnessRadius, config.witnessRadius)
                .filterIsInstance<LivingEntity>()
                .filter { (it is Villager || it is IronGolem) && !it.isDead && it.settlement == settlement }

            witnesses.randomOrNull()?.let { screamer ->
                val phrase = screamer.race.phrases.witnessMurder.randomOrNull()?.replace("{victim}", victim.customName ?: victim.name)
                shoutWithCooldown(screamer, phrase, isAggressive = true, ignoreCooldown = true)
            }

            witnesses.forEach { triggerAggression(it, killerEntity, Reputation.HOSTILE) }
            return
        }

        // --- MONSTER/ILLAGER DEATH LOGIC (Reputation Gain) ---
        // Fix: Only players should receive reputation to prevent data bloating
        if (killerEntity == null) return

        val worldSettlements = settlements[victim.world] ?: return
        val activeSettlement = worldSettlements.find { it.territory.contains(victim.location.toVector()) } ?: return

        val repGain = config.monsterKillReputation[victim.type.name] ?: 0
        if (repGain > 0) {
            val currentRep = activeSettlement.data.reputation.getOrDefault(killerEntity.uniqueId, 0)
            activeSettlement.data.reputation[killerEntity.uniqueId] = currentRep + repGain

            if (config.chatNotification) {
                val msg = plugin.language.getString("settlement-reputation.increase")!!
                    .replace("{entity}", activeSettlement.data.settlementName)
                    .replace("{amount}", repGain.toString())
                killerEntity.sendMessage(msg)
            }
        }
    }

    @EventHandler
    fun onGolemTarget(event: EntityTargetLivingEntityEvent) {
        if (event.entity !is IronGolem) return
        val target = event.target ?: return
        if (target is Villager || target is IronGolem) event.isCancelled = true
    }

    // --- AGGRESSION LOGIC ---

    /**
     * Triggers the NPC attack.
     * For UNFRIENDLY: Performs a single strike to scare the player.
     * For HOSTILE/EXILED: Engages in full combat until target is dead.
     */
    private fun triggerAggression(npc: LivingEntity, target: Player, status: Reputation) {
        val isFullCombat = status.ordinal >= Reputation.HOSTILE.ordinal

        // Determine which phrase pool to use
        val phrasePool = if (isFullCombat) {
            npc.race.phrases.startFight
        } else {
            npc.race.phrases.warning
        }

        // Play aggressive shout with voice
        shoutWithCooldown(npc, phrasePool.randomOrNull(), isAggressive = true)

        try {
            val humanoid = plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(npc)

            if (isFullCombat) {
                // War mode: attack until target is dead
                humanoid.attack(target)
            } else {
                // Annoyance mode: just one punch/strike to say "get out"
                humanoid.attack(target, 1)

                // Also clear the annoyance timer so the 20-second cycle can restart
                // if the player stays in personal space after being punched
                annoyanceTimers.remove(npc.uniqueId to target.uniqueId)
            }
        } catch (_: Exception) {
            // Fallback for non-humanoid entities like Golems
            if (npc is IronGolem) {
                npc.target = target
            }
        }
    }

    /**
     * Resets NPC aggro by cycling AI state and clearing timers.
     */
    private fun resetAggro(npc: LivingEntity, target: Player) {
        try {
            npc.setAI(false)
            npc.setAI(true)
        } catch (_: Exception) {
            if (npc is IronGolem && npc.target == target) npc.target = null
        }
        annoyanceTimers.remove(npc.uniqueId to target.uniqueId)
    }

    private fun callForHelp(caller: LivingEntity, enemy: Player) {
        val settlement = caller.settlement ?: return
        caller.getNearbyEntities(15.0, 10.0, 15.0)
            .filterIsInstance<LivingEntity>()
            .filter { (it is Villager || it is IronGolem) && it.settlement == settlement }
            .forEach { triggerAggression(it, enemy, Reputation.HOSTILE) }
    }

    private fun triggerSettlementAlarm(settlement: Settlement) {
        val center = settlement.data.center
        if (center.block.type == Material.BELL) {
            center.world.playSound(center, Sound.BLOCK_BELL_RESONATE, 4.0f, 1.0f)
        }
    }

    // --- PHRASE LOGIC ---

    /**
     * Sends a chat message from the NPC with a 10s cooldown per entity.
     * Plays a race-specific voice sound with pitch modification.
     *
     * @param isAggressive If true, pitch is lowered to sound more threatening.
     * @param ignoreCooldown If true, bypasses the 10s timer (used for critical events like murder).
     */
    private fun shoutWithCooldown(entity: LivingEntity, message: String?, isAggressive: Boolean = false, ignoreCooldown: Boolean = false) {
        if (message == null) return

        val now = System.currentTimeMillis()
        val lastShout = shoutCooldowns[entity.uniqueId] ?: 0L

        if (ignoreCooldown || now - lastShout > 10000L) {
            shoutCooldowns[entity.uniqueId] = now

            // 1. Send Chat Message
            val name = entity.customName ?: entity.race.name
            val formatted = "§6$name§7: §c$message"

            entity.location.getNearbyPlayers(config.shoutRadius).forEach {
                it.sendMessage(formatted)
            }

            // 2. Play Voice Sound
            val race = entity.race
            val voices = if (entity.gender == Gender.MALE) race.maleVoices else race.femaleVoices

            if (voices.isNotEmpty()) {
                val voice = voices.random()
                val sound = voice.sound.get() ?: Sound.INTENTIONALLY_EMPTY
                // Calculate random pitch within range
                var pitch = Random.nextDouble(voice.min, voice.max).toFloat()

                // Lower pitch for aggressive shouts (makes them sound angry)
                if (isAggressive) {
                    pitch *= 0.85f
                }

                try {
                    entity.world.playSound(entity.location, sound, 1.0f, pitch)
                } catch (_: Exception) {
                    // Fallback if sound is invalid
                }
            }
        }
    }
}
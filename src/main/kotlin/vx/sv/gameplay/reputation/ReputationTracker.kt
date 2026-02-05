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
import vx.sv.gameplay.reputation.ReputationManager.Reputation
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager.Companion.currentSettlement
import vx.sv.gameplay.settlement.SettlementManager.Companion.settlements
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.*

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
                                    // Shout warning when timer starts
                                    shoutWithCooldown(entity, entity.race.phrases.warning.randomOrNull())
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
        val killer = victim.killer ?: return

        // NPC Death penalty logic
        if (victim is Villager || victim is IronGolem) {
            val settlement = victim.settlement ?: return

            val currentSettlementRep = settlement.data.reputation.getOrDefault(killer.uniqueId, 0)
            settlement.data.reputation[killer.uniqueId] = currentSettlementRep - config.killSettlementPenalty

            if (config.chatNotification) {
                val msg = plugin.language.getString("settlement-reputation.decrease")!!
                    .replace("{entity}", settlement.data.settlementName)
                    .replace("{amount}", config.killSettlementPenalty.toString())
                killer.sendMessage(msg)
            }

            triggerSettlementAlarm(settlement)

            // Notify and aggro witnesses
            val witnesses = victim.getNearbyEntities(config.witnessRadius, config.witnessRadius, config.witnessRadius)
                .filterIsInstance<LivingEntity>()
                .filter { (it is Villager || it is IronGolem) && !it.isDead && it.settlement == settlement }

            witnesses.randomOrNull()?.let {
                shoutWithCooldown(it, it.race.phrases.witnessMurder.randomOrNull()?.replace("{victim}", victim.customName ?: victim.name))
            }

            witnesses.forEach { triggerAggression(it, killer, Reputation.HOSTILE) }
            return
        }

        // Monster kill reward logic
        val worldSettlements = settlements[victim.world] ?: return
        val activeSettlement = worldSettlements.find { it.territory.contains(victim.location.toVector()) } ?: return

        val repGain = config.monsterKillReputation[victim.type.name] ?: 0
        if (repGain > 0) {
            val currentRep = activeSettlement.data.reputation.getOrDefault(killer.uniqueId, 0)
            activeSettlement.data.reputation[killer.uniqueId] = currentRep + repGain

            if (config.chatNotification) {
                val msg = plugin.language.getString("settlement-reputation.increase")!!
                    .replace("{entity}", activeSettlement.data.settlementName)
                    .replace("{amount}", repGain.toString())
                killer.sendMessage(msg)
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
     * Uses warning phrases for UNFRIENDLY and startFight phrases for HOSTILE/EXILED.
     */
    private fun triggerAggression(npc: LivingEntity, target: Player, status: Reputation) {
        // Determine which phrase pool to use
        val phrasePool = if (status.ordinal >= Reputation.HOSTILE.ordinal) {
            npc.race.phrases.startFight
        } else {
            npc.race.phrases.warning
        }

        shoutWithCooldown(npc, phrasePool.randomOrNull())

        try {
            plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(npc).attack(target)
        } catch (_: Exception) {
            if (npc is IronGolem) npc.target = target
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
     */
    private fun shoutWithCooldown(entity: LivingEntity, message: String?) {
        if (message == null) return

        val now = System.currentTimeMillis()
        val lastShout = shoutCooldowns[entity.uniqueId] ?: 0L

        if (now - lastShout > 10000L) {
            shoutCooldowns[entity.uniqueId] = now

            val name = entity.customName ?: entity.race.name
            val formatted = "§6$name§r: §c$message"

            entity.location.getNearbyPlayers(config.shoutRadius).forEach {
                it.sendMessage(formatted)
            }
        }
    }
}
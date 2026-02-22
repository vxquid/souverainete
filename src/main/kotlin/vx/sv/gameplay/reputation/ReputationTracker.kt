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
import vx.sv.gameplay.settlement.SettlementManager
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

    fun getNPCState(npc: LivingEntity, player: Player): NPCState? {
        val finalStatus = getFinalStatus(npc, player)

        if (finalStatus.ordinal >= Reputation.HOSTILE.ordinal) {
            return NPCState.AGGRESSIVE
        }

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
     * Evaluates both Player vs NPC and NPC vs NPC (Warfare).
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

                // Gather nearby NPCs for internal warfare checks
                val npcs = nearby.filterIsInstance<LivingEntity>().filter { it is Villager || it is IronGolem }

                // --- 1. Player vs NPC Logic ---
                for (entity in npcs) {
                    if (playerSettlement == null || (entity.settlement != null && entity.settlement?.data?.settlementName != playerSettlement)) {
                        resetAggro(entity, player)
                        continue
                    }

                    val finalStatus = getFinalStatus(entity, player)
                    val pair = entity.uniqueId to player.uniqueId
                    val distance = entity.location.distance(player.location)

                    when {
                        finalStatus == Reputation.UNFRIENDLY -> {
                            if (distance <= personalSpaceRadius && entity.hasLineOfSight(player)) {
                                val startTime = annoyanceTimers.getOrPut(pair) {
                                    shoutWithCooldown(entity, entity.race.phrases.warning.randomOrNull(), isAggressive = true)
                                    System.currentTimeMillis()
                                }
                                if (System.currentTimeMillis() - startTime > 20000L) {
                                    triggerAggression(entity, player, isFullCombat = false)
                                }
                            }
                        }
                        finalStatus.ordinal >= Reputation.HOSTILE.ordinal -> {
                            if (entity.hasLineOfSight(player)) {
                                triggerAggression(entity, player, isFullCombat = true)
                                callForHelp(entity, player)
                            }
                        }
                        else -> annoyanceTimers.remove(pair)
                    }
                }

                // --- 2. NPC vs NPC Logic (Settlement Warfare) ---
                // O(N^2) check is lightweight here because 'npcs' list is limited to player's view distance
                for (i in npcs.indices) {
                    for (j in i + 1 until npcs.size) {
                        val npc1 = npcs[i]
                        val npc2 = npcs[j]

                        val s1 = npc1.settlement ?: continue
                        val s2 = npc2.settlement ?: continue

                        // Proceed only if they belong to different settlements
                        if (s1.data.id != s2.data.id) {
                            val relation = SettlementManager.getRelation(s1, s2)
                            if (relation == Settlement.RelationLevel.WAR) {
                                if (npc1.hasLineOfSight(npc2)) {
                                    triggerAggression(npc1, npc2, isFullCombat = true)
                                    callForHelp(npc1, npc2)
                                }
                                if (npc2.hasLineOfSight(npc1)) {
                                    triggerAggression(npc2, npc1, isFullCombat = true)
                                    callForHelp(npc2, npc1)
                                }
                            }
                        }
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
        if (finalStatus.ordinal >= Reputation.UNFRIENDLY.ordinal) {
            triggerAggression(victim, attacker, isFullCombat = finalStatus.ordinal >= Reputation.HOSTILE.ordinal)
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val victim = event.entity
        val killerEntity = victim.killer

        if (victim is Villager || victim is IronGolem) {
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

            witnesses.forEach { triggerAggression(it, killerEntity, isFullCombat = true) }
            return
        }

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
     */
    private fun triggerAggression(npc: LivingEntity, target: LivingEntity, isFullCombat: Boolean) {
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
            .filter { (it is Villager || it is IronGolem) && it.settlement == settlement }
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
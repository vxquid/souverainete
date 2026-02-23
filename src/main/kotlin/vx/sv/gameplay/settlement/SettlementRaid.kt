package vx.sv.gameplay.settlement

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.race.RaceManager
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.humanoid.race.RaceManager.Race
import vx.sv.gameplay.settlement.SettlementManager.Companion.currentSettlement
import vx.sv.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.sv.persistent.LivingEntityExtend.settlement
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SettlementRaid(val defender: Settlement, val data: Settlement.RaidData) {

    private val viewingPlayers = mutableSetOf<Player>()

    // Primary Boss Bar: Attacker vs Defender
    private var bossBar: BossBar = createBossBar()

    // Secondary Boss Bar: Alive Defenders
    private var defendersBossBar: BossBar = createDefendersBossBar()

    // Timer Logic
    private var lastWaveStartTick: Long = data.activeTicks
    private var maxDefendersTracked: Int = 1

    // Chunk Loading Flag
    private var chunksLocked: Boolean = false

    // ==========================================
    // === CONFIGURATION & STRINGS            ===
    // ==========================================

    private val raidConfig get() = plugin.gameplayManager.config.raid

    // Thresholds & Ranges
    private val glowThreshold get() = raidConfig.glowThreshold
    private val killThreshold get() = raidConfig.killThreshold
    private val broadcastRadius get() = raidConfig.broadcastRadius
    private val hornRadius get() = raidConfig.hornRadius

    // Chunk Loading Radius (8 chunks = 128 blocks radius)
    private val forceLoadRadius = 8

    // Reputation Balancing
    private val allyReputationThreshold get() = raidConfig.allyReputationThreshold
    private val repGainPerKill get() = raidConfig.repGainPerKill

    // UI Strings
    private val msgRaidInit get() = plugin.language.getString("raid.bossbar.init", "Raid Initialization...") ?: "Raid Initialization..."
    private val msgDefendersAlive get() = plugin.language.getString("raid.bossbar.defenders-alive", "Defenders Alive: {count}") ?: "Defenders Alive: {count}"
    private val msgTitlePrimary get() = plugin.language.getString("raid.bossbar.title", "{attacker} ⚔ {defender} (Wave {wave}/{total})") ?: "{attacker} ⚔ {defender} (Wave {wave}/{total})"

    // Victory/Loss End Titles
    private val msgVictoryPrimary get() = plugin.language.getString("raid.bossbar.victory-primary", "Attackers defeated") ?: "Attackers defeated"
    private val msgVictorySecondary get() = plugin.language.getString("raid.bossbar.victory-secondary", "Settlement protected") ?: "Settlement protected"

    private val msgLossPrimary get() = plugin.language.getString("raid.bossbar.loss-primary", "Settlement conquered") ?: "Settlement conquered"
    private val msgLossSecondary get() = plugin.language.getString("raid.bossbar.loss-secondary", "Defenders eliminated") ?: "Defenders eliminated"

    private val msgStoppedPrimary get() = plugin.language.getString("raid.bossbar.stopped-primary", "Raid stopped") ?: "Raid stopped"
    private val msgStoppedSecondary get() = plugin.language.getString("raid.bossbar.stopped-secondary", "Combat ceased") ?: "Combat ceased"

    // Chat Broadcasts & Entities
    private val msgRaiderName get() = plugin.language.getString("raid.entity.raider-name", "§cRaider ({attacker})") ?: "§cRaider ({attacker})"
    private val msgWaveStarted get() = plugin.language.getString("raid.chat.wave-started", "§c⚔ A raid wave has started! §6{attacker} §cis attacking §6{defender}§c!") ?: "§c⚔ A raid wave has started! §6{attacker} §cis attacking §6{defender}§c!"
    private val msgReinforcements get() = plugin.language.getString("raid.chat.reinforcements", "§c⚔ Reinforcements have arrived for §6{attacker}§c!") ?: "§c⚔ Reinforcements have arrived for §6{attacker}§c!"
    private val msgVictoryChat get() = plugin.language.getString("raid.chat.victory", "§a⚔ The raid on §6{defender} §ahas been repelled! Victory!") ?: "§a⚔ The raid on §6{defender} §ahas been repelled! Victory!"
    private val msgLossChat get() = plugin.language.getString("raid.chat.loss", "§4☠ §c{defender} has fallen! Conquered by §4{attacker}§c. It is now known as §6{newName}§c.") ?: "§4☠ §c{defender} has fallen! Conquered by §4{attacker}§c. It is now known as §6{newName}§c."
    private val msgLossWipedChat get() = plugin.language.getString("raid.chat.loss-wiped", "§4☠ §cThe settlement {defender} has been wiped out!") ?: "§4☠ §cThe settlement {defender} has been wiped out!"
    private val msgRepIncrease get() = plugin.language.getString("settlement-reputation.increase", "§a+ {amount} reputation with {entity}.") ?: "§a+ {amount} reputation with {entity}."

    // ==========================================

    val isActive: Boolean
        get() = data.status == Settlement.RaidStatus.ONGOING

    private fun createBossBar(): BossBar {
        val title = Component.text(msgRaidInit, NamedTextColor.RED)
        return BossBar.bossBar(title, 1.0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10)
    }

    private fun createDefendersBossBar(): BossBar {
        val title = Component.text(msgDefendersAlive.replace("{count}", "?"), NamedTextColor.YELLOW)
        return BossBar.bossBar(title, 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
    }

    fun tick() {
        if (!isActive) return
        data.activeTicks++

        // Ensure chunks are force-loaded for simulation
        if (!chunksLocked) {
            manageChunkLoading(true)
        }

        val attacker = SettlementManager.getById(data.attackerId)
        if (attacker == null) {
            finishRaid(Settlement.RaidStatus.STOPPED)
            return
        }

        processDeadRaiders()
        updateBossBars(attacker)
        updateBossBarViewers()
        updateRaidAI()
        checkStuckState(attacker)

        if (defender.villagers.all { it.isDead }) {
            finishRaid(Settlement.RaidStatus.LOSS)
            return
        }

        if (data.aliveRaiders.isEmpty()) {
            if (data.currentWave < data.totalWaves) {
                spawnNextWave(attacker)
            } else {
                finishRaid(Settlement.RaidStatus.VICTORY)
            }
        }
    }

    /**
     * Force loads or unloads chunks around the settlement center.
     */
    private fun manageChunkLoading(load: Boolean) {
        val centerChunk = defender.data.center.chunk
        val world = defender.world

        for (x in -forceLoadRadius..forceLoadRadius) {
            for (z in -forceLoadRadius..forceLoadRadius) {
                val cx = centerChunk.x + x
                val cz = centerChunk.z + z
                world.getChunkAt(cx, cz).isForceLoaded = load
            }
        }
        chunksLocked = load
    }

    private fun checkStuckState(attacker: Settlement) {
        val waveDuration = data.activeTicks - lastWaveStartTick

        if (waveDuration > glowThreshold && data.aliveRaiders.size <= 3) {
            data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) }.forEach {
                it.isGlowing = true
            }
        }

        if (waveDuration > killThreshold && data.aliveRaiders.isNotEmpty()) {
            val center = defender.data.center
            val world = center.world ?: return
            val stuckCount = data.aliveRaiders.size

            data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) }.forEach {
                it.remove()
            }
            data.aliveRaiders.clear()

            world.getNearbyPlayers(center, hornRadius).forEach { player ->
                player.playSound(center, Sound.EVENT_RAID_HORN, 64.0f, 1.0f)
            }

            val message = msgReinforcements.replace("{attacker}", attacker.data.settlementName)
            world.getNearbyPlayers(center, broadcastRadius).forEach { player ->
                player.sendMessage(message)
            }

            val attackerRace = RaceManager.racesRegistry.values.find { it.name == SettlementManager.getDominantRace(attacker) } ?: Race.VILLAGER_RACE
            val entityType = attackerRace.targetEntityType.get() ?: EntityType.VILLAGER

            for (i in 0 until stuckCount) {
                val angle = Random.nextDouble(0.0, 2 * Math.PI)
                val distance = Random.nextDouble(3.0, 7.0)
                val spawnX = center.x + cos(angle) * distance
                val spawnZ = center.z + sin(angle) * distance

                val highestY = world.getHighestBlockYAt(spawnX.toInt(), spawnZ.toInt()).toDouble()
                val spawnLocation = Location(world, spawnX, highestY + 1.0, spawnZ)

                world.spawnParticle(Particle.SMOKE, spawnLocation.clone().add(0.0, 1.0, 0.0), 20, 0.5, 1.0, 0.5, 0.05)
                world.spawnParticle(Particle.LAVA, spawnLocation, 5, 0.2, 0.2, 0.2)

                val raider = world.spawnEntity(spawnLocation, entityType) as? LivingEntity ?: continue

                if (raider is Villager) {
                    raider.villagerType = attackerRace.targetVillagerType
                    raider.profession = Villager.Profession.NITWIT
                }

                raider.settlement = attacker
                raider.customName = msgRaiderName.replace("{attacker}", attacker.data.settlementName)
                raider.isCustomNameVisible = true

                equipRaider(raider, attacker)

                data.aliveRaiders.add(raider.uniqueId)
            }

            lastWaveStartTick = data.activeTicks
        }
    }

    private fun updateRaidAI() {
        val aliveDefenders = defender.villagers.filter { !it.isDead && it.isValid }.mapNotNull { it as? Mob }
        val aliveRaidersList = data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) as? Mob }.filter { !it.isDead && it.isValid }

        val alliedPlayers = defender.world.players.filter { player ->
            defender.territory.contains(player.location.toVector()) &&
                    (defender.data.reputation[player.uniqueId] ?: 0) >= allyReputationThreshold &&
                    (player.gameMode == org.bukkit.GameMode.SURVIVAL || player.gameMode == org.bukkit.GameMode.ADVENTURE)
        }

        // --- Raider AI ---
        for (raider in aliveRaidersList) {
            adjustFollowRange(raider)

            var currentTarget = raider.target
            if (isInvalidTarget(currentTarget)) {
                val potentialTargets = aliveDefenders + alliedPlayers
                if (potentialTargets.isNotEmpty()) {
                    currentTarget = potentialTargets.random()
                    performAttack(raider, currentTarget)
                }
            }

            if (currentTarget != null) {
                if (raider.location.distance(currentTarget.location) > 2.5) {
                    raider.pathfinder.moveTo(currentTarget, 0.6)
                }
            } else {
                if (raider.location.distance(defender.data.center) > 5.0) {
                    raider.pathfinder.moveTo(defender.data.center, 0.6)
                }
            }
        }

        // --- Defender AI ---
        for (def in aliveDefenders) {
            adjustFollowRange(def)

            var currentTarget = def.target
            if (isInvalidTarget(currentTarget) || !aliveRaidersList.contains(currentTarget)) {
                if (aliveRaidersList.isNotEmpty()) {
                    currentTarget = aliveRaidersList.minByOrNull { it.location.distanceSquared(def.location) }
                    performAttack(def, currentTarget)
                }
            }

            if (currentTarget != null && aliveRaidersList.contains(currentTarget)) {
                if (def.location.distance(currentTarget.location) > 2.5) {
                    def.pathfinder.moveTo(currentTarget, 0.55)
                }
            }
        }
    }

    private fun adjustFollowRange(mob: Mob) {
        val followRange = mob.getAttribute(Attribute.FOLLOW_RANGE)
        if (followRange != null && followRange.baseValue < 64.0) {
            followRange.baseValue = 64.0
        }
    }

    private fun isInvalidTarget(target: LivingEntity?): Boolean {
        return target == null || target.isDead || !target.isValid
    }

    private fun performAttack(attacker: Mob, target: LivingEntity?) {
        if (target == null) return
        try {
            plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(attacker)?.attack(target)
            attacker.target = target
        } catch (e: Exception) {
            attacker.target = target
        }
    }

    private fun spawnNextWave(attacker: Settlement) {
        data.currentWave++
        lastWaveStartTick = data.activeTicks
        val waveSize = Random.nextInt(3, 6) + data.currentWave
        data.totalRaidersInWave = waveSize
        data.aliveRaiders.clear()

        val center = defender.data.center
        val world = center.world ?: return

        val message = msgWaveStarted
            .replace("{attacker}", attacker.data.settlementName)
            .replace("{defender}", defender.data.settlementName)

        // Local broadcast for wave start
        world.getNearbyPlayers(center, broadcastRadius).forEach { player ->
            player.sendMessage(message)
        }

        world.getNearbyPlayers(center, hornRadius).forEach { player ->
            player.playSound(center, Sound.EVENT_RAID_HORN, 64.0f, 1.0f)
        }

        buffDefenders()

        val attackerRace = RaceManager.racesRegistry.values.find { it.name == SettlementManager.getDominantRace(attacker) } ?: Race.VILLAGER_RACE
        val entityType = attackerRace.targetEntityType.get() ?: EntityType.VILLAGER
        var raidersSpawned = 0

        while (raidersSpawned < waveSize) {
            val squadSize = minOf(Random.nextInt(2, 4), waveSize - raidersSpawned)

            val angle = Random.nextDouble(0.0, 2 * Math.PI)
            val distance = Random.nextDouble(25.0, 35.0)
            val squadCenterX = center.x + cos(angle) * distance
            val squadCenterZ = center.z + sin(angle) * distance

            for (i in 0 until squadSize) {
                val offsetX = Random.nextDouble(-2.0, 2.0)
                val offsetZ = Random.nextDouble(-2.0, 2.0)
                val spawnX = squadCenterX + offsetX
                val spawnZ = squadCenterZ + offsetZ

                val highestY = world.getHighestBlockYAt(spawnX.toInt(), spawnZ.toInt()).toDouble()
                val spawnLocation = Location(world, spawnX, highestY + 1.0, spawnZ)

                world.spawnParticle(Particle.SMOKE, spawnLocation.clone().add(0.0, 1.0, 0.0), 20, 0.5, 1.0, 0.5, 0.05)
                world.spawnParticle(Particle.LAVA, spawnLocation, 5, 0.2, 0.2, 0.2)

                val raider = world.spawnEntity(spawnLocation, entityType) as? LivingEntity ?: continue

                if (raider is Villager) {
                    raider.villagerType = attackerRace.targetVillagerType
                    raider.profession = Villager.Profession.NITWIT
                }

                raider.settlement = attacker
                raider.customName = msgRaiderName.replace("{attacker}", attacker.data.settlementName)
                raider.isCustomNameVisible = true

                equipRaider(raider, attacker)

                data.aliveRaiders.add(raider.uniqueId)
                raidersSpawned++
            }
        }

        SettlementManager.saveSettlements(defender.world)
    }

    private fun buffDefenders() {
        val effects = listOf(
            PotionEffect(PotionEffectType.SPEED, 2400, 0),
            PotionEffect(PotionEffectType.STRENGTH, 2400, 0),
            PotionEffect(PotionEffectType.REGENERATION, 2400, 0)
        )

        defender.villagers.filter { !it.isDead }.forEach { villager ->
            villager.addPotionEffect(effects.random())
            villager.world.spawnParticle(Particle.HAPPY_VILLAGER, villager.location.add(0.0, 1.5, 0.0), 10, 0.5, 0.5, 0.5)
            villager.world.playSound(villager.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.0f)
        }
    }

    private fun equipRaider(raider: LivingEntity, attacker: Settlement) {
        val loadout = mutableMapOf<EquipmentSlot, ItemStack>()
        val roll = Random.nextInt(100)

        val isRanged = Random.nextInt(100) < 25
        val size = attacker.size()

        when (size) {
            Settlement.SettlementSize.UNDERDEVELOPED,
            Settlement.SettlementSize.EMERGING -> {
                if (roll < 60) {
                    if (Random.nextBoolean()) loadout[EquipmentSlot.HEAD] = ItemStack(Material.LEATHER_HELMET)
                    if (Random.nextBoolean()) loadout[EquipmentSlot.CHEST] = ItemStack(Material.LEATHER_CHESTPLATE)

                    if (isRanged) {
                        loadout[EquipmentSlot.HAND] = ItemStack(Material.BOW)
                    } else {
                        loadout[EquipmentSlot.HAND] = ItemStack(if (Random.nextBoolean()) Material.WOODEN_SWORD else Material.STONE_AXE)
                    }
                } else {
                    loadout[EquipmentSlot.HEAD] = ItemStack(Material.LEATHER_HELMET)
                    loadout[EquipmentSlot.CHEST] = ItemStack(Material.LEATHER_CHESTPLATE)
                    loadout[EquipmentSlot.LEGS] = ItemStack(Material.LEATHER_LEGGINGS)
                    loadout[EquipmentSlot.FEET] = ItemStack(Material.LEATHER_BOOTS)

                    if (isRanged) {
                        loadout[EquipmentSlot.HAND] = ItemStack(Material.BOW)
                    } else {
                        loadout[EquipmentSlot.HAND] = ItemStack(Material.STONE_SWORD)
                    }
                }
            }

            Settlement.SettlementSize.ESTABLISHED -> {
                if (roll < 50) {
                    loadout[EquipmentSlot.HEAD] = ItemStack(Material.LEATHER_HELMET)
                    loadout[EquipmentSlot.CHEST] = ItemStack(Material.CHAINMAIL_CHESTPLATE)
                    loadout[EquipmentSlot.LEGS] = ItemStack(Material.LEATHER_LEGGINGS)

                    if (isRanged) {
                        loadout[EquipmentSlot.HAND] = ItemStack(Material.CROSSBOW)
                    } else {
                        loadout[EquipmentSlot.HAND] = ItemStack(Material.STONE_SWORD)
                    }
                } else {
                    loadout[EquipmentSlot.HEAD] = ItemStack(Material.CHAINMAIL_HELMET)
                    loadout[EquipmentSlot.CHEST] = ItemStack(Material.IRON_CHESTPLATE)
                    loadout[EquipmentSlot.LEGS] = ItemStack(Material.CHAINMAIL_LEGGINGS)
                    loadout[EquipmentSlot.FEET] = ItemStack(Material.IRON_BOOTS)

                    if (isRanged) {
                        loadout[EquipmentSlot.HAND] = ItemStack(Material.CROSSBOW)
                    } else {
                        loadout[EquipmentSlot.HAND] = ItemStack(Material.IRON_SWORD)
                    }
                }
            }

            Settlement.SettlementSize.ADVANCED,
            Settlement.SettlementSize.METROPOLIS -> {
                loadout[EquipmentSlot.HEAD] = ItemStack(Material.IRON_HELMET)
                loadout[EquipmentSlot.CHEST] = ItemStack(Material.IRON_CHESTPLATE)
                loadout[EquipmentSlot.LEGS] = ItemStack(Material.IRON_LEGGINGS)
                loadout[EquipmentSlot.FEET] = ItemStack(Material.IRON_BOOTS)

                if (isRanged) {
                    loadout[EquipmentSlot.HAND] = ItemStack(Material.CROSSBOW)
                } else {
                    loadout[EquipmentSlot.HAND] = ItemStack(if (Random.nextBoolean()) Material.IRON_SWORD else Material.IRON_AXE)
                    if (Random.nextBoolean()) loadout[EquipmentSlot.OFF_HAND] = ItemStack(Material.SHIELD)
                }
            }
        }

        loadout.values.forEach { itemStack ->
            raider.addItemToQuillInventory(itemStack)
        }

        try {
            val humanoid = plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(raider)
            loadout.forEach { (slot, item) ->
                humanoid.equip(slot, item)
            }
        } catch (_: Exception) {
            raider.equipment?.let { eq ->
                eq.helmet = loadout[EquipmentSlot.HEAD]
                eq.chestplate = loadout[EquipmentSlot.CHEST]
                eq.leggings = loadout[EquipmentSlot.LEGS]
                eq.boots = loadout[EquipmentSlot.FEET]
                eq.setItemInMainHand(loadout[EquipmentSlot.HAND])
                eq.setItemInOffHand(loadout[EquipmentSlot.OFF_HAND])
            }
        }
    }

    private fun processDeadRaiders() {
        val iterator = data.aliveRaiders.iterator()
        while (iterator.hasNext()) {
            val uuid = iterator.next()
            val entity = Bukkit.getEntity(uuid)

            if (entity == null || !entity.isValid || entity.isDead) {

                if (entity is LivingEntity && entity.isDead) {
                    val killer = entity.killer
                    if (killer != null) {
                        val currentRep = defender.data.reputation.getOrDefault(killer.uniqueId, 0)
                        defender.data.reputation[killer.uniqueId] = currentRep + repGainPerKill

                        if (plugin.gameplayManager.config.reputation.chatNotification) {
                            val msg = msgRepIncrease
                                .replace("{entity}", defender.data.settlementName)
                                .replace("{amount}", repGainPerKill.toString())
                            killer.sendMessage(msg)
                        }
                    }
                }
                iterator.remove()
            }
        }
    }

    private fun updateBossBars(attacker: Settlement) {
        val primaryTitle = msgTitlePrimary
            .replace("{attacker}", attacker.data.settlementName)
            .replace("{defender}", defender.data.settlementName)
            .replace("{wave}", data.currentWave.toString())
            .replace("{total}", data.totalWaves.toString())

        bossBar.name(Component.text(primaryTitle, NamedTextColor.RED))

        val raiderProgress = if (data.totalRaidersInWave > 0) {
            (data.aliveRaiders.size.toFloat() / data.totalRaidersInWave.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }
        bossBar.progress(raiderProgress)

        val aliveDefendersCount = defender.villagers.count { !it.isDead && it.isValid }

        if (aliveDefendersCount > maxDefendersTracked) {
            maxDefendersTracked = aliveDefendersCount
        }

        val maxDef = maxDefendersTracked.coerceAtLeast(1)
        val defProgress = (aliveDefendersCount.toFloat() / maxDef.toFloat()).coerceIn(0.0f, 1.0f)

        val secondaryTitle = msgDefendersAlive.replace("{count}", aliveDefendersCount.toString())
        defendersBossBar.name(Component.text(secondaryTitle, NamedTextColor.YELLOW))
        defendersBossBar.progress(defProgress)
    }

    private fun updateBossBarViewers() {
        val currentPlayersInTerritory = defender.world.players.filter {
            defender.territory.contains(it.location.toVector())
        }.toSet()

        val toRemove = viewingPlayers.filter { it !in currentPlayersInTerritory }
        toRemove.forEach { player ->
            player.hideBossBar(bossBar)
            player.hideBossBar(defendersBossBar)
            viewingPlayers.remove(player)
        }

        val toAdd = currentPlayersInTerritory.filter { it !in viewingPlayers }
        toAdd.forEach { player ->
            player.showBossBar(bossBar)
            player.showBossBar(defendersBossBar)
            viewingPlayers.add(player)
        }
    }

    private fun finishRaid(finalStatus: Settlement.RaidStatus) {
        manageChunkLoading(false)

        data.status = finalStatus
        val attacker = SettlementManager.getById(data.attackerId)

        val primaryText: String
        val secondaryText: String
        val primaryColor: NamedTextColor
        val secondaryColor: NamedTextColor

        when (finalStatus) {
            Settlement.RaidStatus.VICTORY -> {
                val msg = msgVictoryChat.replace("{defender}", defender.data.settlementName)
                broadcastGlobalMessage(msg)

                primaryText = msgVictoryPrimary
                secondaryText = msgVictorySecondary
                primaryColor = NamedTextColor.GREEN
                secondaryColor = NamedTextColor.GREEN
            }

            Settlement.RaidStatus.LOSS -> {
                if (attacker != null) {

                    val oldName = defender.data.settlementName
                    val newName = (Bukkit.getEntity(this.data.aliveRaiders.random()) as Villager).race.settlementNames.random()
                    defender.data.settlementName = newName

                    // Don't forget to update current player settlement as well!
                    defender.world.getNearbyEntities(defender.territory).filterIsInstance<Player>().forEach { player ->
                        player.currentSettlement = newName
                    }

                    // --- CONQUEST CONSEQUENCES ---
                    SettlementManager.setRelation(attacker, defender, Settlement.RelationLevel.ALLIANCE)
                    defender.data.reputation.clear()
                    defender.data.reputation.putAll(attacker.data.reputation)

                    data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) as? LivingEntity }.forEach { victor ->
                        victor.settlement = defender
                        defender.villagers.add(victor as? Villager ?: return@forEach)

                        victor.customName = null
                        victor.isCustomNameVisible = false
                        victor.isGlowing = false
                        victor.profession = Villager.Profession.NONE

                        plugin.gameplayManager.personalityManager.generateCharacterName(victor)
                    }

                    val msg = msgLossChat
                        .replace("{defender}", oldName)
                        .replace("{attacker}", attacker.data.settlementName)
                        .replace("{newName}", newName)

                    broadcastGlobalMessage(msg)
                    plugin.logger.info("[Raid] $msg")

                    primaryText = msgLossPrimary
                    secondaryText = msgLossSecondary
                    primaryColor = NamedTextColor.DARK_RED
                    secondaryColor = NamedTextColor.DARK_RED
                } else {
                    val msg = msgLossWipedChat.replace("{defender}", defender.data.settlementName)
                    broadcastGlobalMessage(msg)

                    primaryText = msgLossPrimary
                    secondaryText = msgLossSecondary
                    primaryColor = NamedTextColor.DARK_RED
                    secondaryColor = NamedTextColor.DARK_RED
                }
            }

            else -> {
                primaryText = msgStoppedPrimary
                secondaryText = msgStoppedSecondary
                primaryColor = NamedTextColor.GRAY
                secondaryColor = NamedTextColor.GRAY
            }
        }

        bossBar.name(Component.text(primaryText, primaryColor))
        bossBar.progress(0.0f)

        defendersBossBar.name(Component.text(secondaryText, secondaryColor))
        defendersBossBar.progress(0.0f)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            viewingPlayers.forEach {
                it.hideBossBar(bossBar)
                it.hideBossBar(defendersBossBar)
            }
            viewingPlayers.clear()
            defender.data.activeRaid = null
            SettlementManager.saveSettlements(defender.world)
            plugin.gameplayManager.raidManager.removeActiveRaid(this)
        }, 200L)
    }

    private fun broadcastGlobalMessage(message: String) {
        plugin.gameplayManager.allowedWorlds.forEach { world ->
            world.players.forEach { player ->
                player.sendMessage(message)
            }
        }
    }

    fun destroy() {
        manageChunkLoading(false)
        viewingPlayers.forEach {
            it.hideBossBar(bossBar)
            it.hideBossBar(defendersBossBar)
        }
        viewingPlayers.clear()
    }
}
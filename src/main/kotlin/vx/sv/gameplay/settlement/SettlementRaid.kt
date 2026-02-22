package vx.sv.gameplay.settlement

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.block.data.BlockData
import org.bukkit.entity.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.sv.persistent.LivingEntityExtend.settlement
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SettlementRaid(val defender: Settlement, val data: Settlement.RaidData) {

    private val viewingPlayers = mutableSetOf<Player>()
    private var bossBar: BossBar = createBossBar()

    // Timer Logic
    private var lastWaveStartTick: Long = data.activeTicks

    // Configurable thresholds (in ticks)
    private val glowThreshold = 1800L // 1.5 minutes
    private val killThreshold = 3600L // 3.0 minutes
    private val broadcastRadius = 1000.0

    private val allyReputationThreshold = 200

    // Dummy block data for blood particles
    private val bloodBlockData: BlockData = Material.REDSTONE_BLOCK.createBlockData()

    val isActive: Boolean
        get() = data.status == Settlement.RaidStatus.ONGOING

    private fun createBossBar(): BossBar {
        val title = Component.text("Raid - Wave ${data.currentWave} / ${data.totalWaves}", NamedTextColor.RED)
        val progress = if (data.totalRaidersInWave > 0) {
            (data.aliveRaiders.size.toFloat() / data.totalRaidersInWave.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }
        return BossBar.bossBar(title, progress, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10)
    }

    fun tick() {
        if (!isActive) return
        data.activeTicks++

        val attacker = SettlementManager.getById(data.attackerId)
        if (attacker == null) {
            finishRaid(Settlement.RaidStatus.STOPPED)
            return
        }

        updateLivingRaiders()
        updateBossBarViewers()
        updateRaidAI()
        checkStuckState()

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

    private fun checkStuckState() {
        val waveDuration = data.activeTicks - lastWaveStartTick

        if (waveDuration > glowThreshold && data.aliveRaiders.size <= 3) {
            data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) }.forEach {
                it.isGlowing = true
            }
        }

        if (waveDuration > killThreshold) {
            data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) as? LivingEntity }.forEach {
                it.damage(100.0) // Force kill
            }
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
            } else {
                // If already has target, randomly play attack effects during combat ticks
                if (raider.location.distanceSquared(currentTarget!!.location) < 9.0) { // < 3 blocks
                    performCombatEffects(raider, currentTarget)
                }
            }

            // Movement Logic (Slower Speed: 0.6)
            if (currentTarget != null) {
                if (raider.location.distance(currentTarget.location) > 2.5) {
                    raider.pathfinder.moveTo(currentTarget, 0.6)
                }
            } else {
                // Storm Center
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
            } else {
                if (def.location.distanceSquared(currentTarget!!.location) < 9.0) {
                    performCombatEffects(def, currentTarget)
                }
            }

            // Movement Logic (Slower Speed: 0.55 - slightly slower than raiders to allow kiting)
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

    /**
     * Simulates "Juicy" combat sounds and particles.
     */
    private fun performCombatEffects(attacker: LivingEntity, target: LivingEntity) {
        // Limit frequency (20% chance per tick close to target)
        if (Random.nextInt(100) > 20) return

        val world = attacker.world
        val loc = target.location.add(0.0, 1.0, 0.0)
        val hasArmor = target.equipment?.chestplate?.type?.isAir == false

        // 1. Audio Layering

        // Base heavy hit (Thud)
        world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.0f)

        if (hasArmor) {
            // Metal Clank (High pitch iron door or anvil)
            world.playSound(loc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.6f, 1.5f)
            // Spark particles
            world.spawnParticle(Particle.CRIT, loc, 3, 0.2, 0.2, 0.2, 0.1)
        } else {
            // Flesh rip (Wet sound)
            world.playSound(loc, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.7f, 1.2f)
            // Blood particles
            world.spawnParticle(Particle.BLOCK_CRUMBLE, loc, 10, 0.2, 0.2, 0.2, bloodBlockData)
        }

        // Occasional Crit sound for emphasis
        if (Random.nextBoolean()) {
            world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f)
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

        // 1. Broadcast Message (No Horn Sound)
        val message = plugin.language.getString("raid.wave-started", "§c⚔ A raid wave has started! §6{attacker} §cis attacking §6{defender}§c!")
            ?.replace("{attacker}", attacker.data.settlementName)
            ?.replace("{defender}", defender.data.settlementName)

        if (message != null) {
            world.getNearbyPlayers(center, broadcastRadius).forEach { player ->
                player.sendMessage(message)
            }
        }

        // Music only on first wave
        if (data.currentWave == 1) {
            playRandomMusicDisc(center)
        }

        buffDefenders()

        val attackerRace = SettlementManager.getDominantRace(attacker)
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
                raider.customName = "§cRaider (${attacker.data.settlementName})"
                raider.isCustomNameVisible = true

                equipRaider(raider, attacker)

                data.aliveRaiders.add(raider.uniqueId)
                raidersSpawned++
            }
        }

        bossBar.name(Component.text("Raid - Wave ${data.currentWave} / ${data.totalWaves}", NamedTextColor.RED))
        bossBar.progress(1.0f)
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

    private fun playRandomMusicDisc(location: Location) {
        val discs = listOf(
            Sound.MUSIC_DISC_13, Sound.MUSIC_DISC_CAT, Sound.MUSIC_DISC_BLOCKS,
            Sound.MUSIC_DISC_CHIRP, Sound.MUSIC_DISC_FAR, Sound.MUSIC_DISC_MALL,
            Sound.MUSIC_DISC_MELLOHI, Sound.MUSIC_DISC_STAL, Sound.MUSIC_DISC_STRAD,
            Sound.MUSIC_DISC_WARD, Sound.MUSIC_DISC_11, Sound.MUSIC_DISC_WAIT,
            Sound.MUSIC_DISC_PIGSTEP
        )
        location.world.playSound(location, discs.random(), 20.0f, 1.0f)
    }

    private fun equipRaider(raider: LivingEntity, attacker: Settlement) {
        val loadout = mutableMapOf<EquipmentSlot, ItemStack>()
        val roll = Random.nextInt(100)

        val isRanged = Random.nextInt(100) < 35
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

    private fun updateLivingRaiders() {
        val iterator = data.aliveRaiders.iterator()
        while (iterator.hasNext()) {
            val uuid = iterator.next()
            val entity = Bukkit.getEntity(uuid)
            if (entity != null && entity.isDead) {
                iterator.remove()
            }
        }
        val progress = if (data.totalRaidersInWave > 0) {
            (data.aliveRaiders.size.toFloat() / data.totalRaidersInWave.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }
        bossBar.progress(progress)
    }

    private fun updateBossBarViewers() {
        val currentPlayersInTerritory = defender.world.players.filter {
            defender.territory.contains(it.location.toVector())
        }.toSet()

        val toRemove = viewingPlayers.filter { it !in currentPlayersInTerritory }
        toRemove.forEach { player ->
            player.hideBossBar(bossBar)
            viewingPlayers.remove(player)
        }

        val toAdd = currentPlayersInTerritory.filter { it !in viewingPlayers }
        toAdd.forEach { player ->
            player.showBossBar(bossBar)
            viewingPlayers.add(player)
        }
    }

    private fun finishRaid(finalStatus: Settlement.RaidStatus) {
        data.status = finalStatus
        val attacker = SettlementManager.getById(data.attackerId)

        val (titleText, color) = when (finalStatus) {
            Settlement.RaidStatus.VICTORY -> {
                // Broadcast Victory
                broadcastRaidMessage("§a⚔ The raid on §6${defender.data.settlementName} §ahas been repelled! Victory!")
                "Raid - Victory!" to NamedTextColor.GREEN
            }

            Settlement.RaidStatus.LOSS -> {
                if (attacker != null) {
                    val attackerRace = SettlementManager.getDominantRace(attacker)

                    val oldName = defender.data.settlementName
                    val newName = attackerRace.settlementNames.random()
                    defender.data.settlementName = newName

                    // --- CONQUEST LOGIC ---
                    // 1. Get ALL living raiders (even those not rendered in Bukkit yet via UUID)
                    // We iterate through the data set directly.
                    data.aliveRaiders.forEach { uuid ->
                        val entity = Bukkit.getEntity(uuid) as? LivingEntity ?: return@forEach

                        // Convert to citizen
                        entity.settlement = defender
                        defender.villagers.add(entity as? Villager ?: return@forEach)

                        // Strip Raid Metadata
                        entity.customName = null
                        entity.isCustomNameVisible = false
                        entity.isGlowing = false
                        entity.profession = Villager.Profession.NONE
                        entity.pathfinder.moveTo(defender.data.center, 0.55)

                        // Give them a personality
                        plugin.gameplayManager.personalityManager.generateCharacterName(entity)
                    }

                    // 2. Broadcast Defeat & Rename
                    val msg = "§4☠ §c${oldName} has fallen! Conquered by §4${attacker.data.settlementName}§c. It is now known as §6${newName}§c."
                    broadcastRaidMessage(msg)
                    plugin.logger.info("[Raid] $msg")

                    "Raid - Conquered!" to NamedTextColor.DARK_RED
                } else {
                    broadcastRaidMessage("§4☠ §cThe settlement ${defender.data.settlementName} has been wiped out!")
                    "Raid - Defeat!" to NamedTextColor.DARK_RED
                }
            }

            else -> "Raid - Stopped" to NamedTextColor.GRAY
        }

        bossBar.name(Component.text(titleText, color))
        bossBar.progress(0.0f)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            viewingPlayers.forEach { it.hideBossBar(bossBar) }
            viewingPlayers.clear()
            defender.data.activeRaid = null
            SettlementManager.saveSettlements(defender.world)
            plugin.gameplayManager.raidManager.removeActiveRaid(this)
        }, 200L)
    }

    private fun broadcastRaidMessage(message: String) {
        val center = defender.data.center
        center.world?.getNearbyPlayers(center, 1000.0)?.forEach { player ->
            player.sendMessage(message)
        }
    }

    fun destroy() {
        viewingPlayers.forEach { it.hideBossBar(bossBar) }
        viewingPlayers.clear()
    }
}
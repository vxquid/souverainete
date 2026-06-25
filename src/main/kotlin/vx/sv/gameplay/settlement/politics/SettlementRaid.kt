package vx.sv.gameplay.settlement.politics

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.*
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.NametagDisplayManager
import vx.sv.gameplay.humanoid.race.RaceManager
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.humanoid.race.RaceManager.Race
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.SettlementManager.Companion.currentSettlement
import vx.sv.persistent.LivingEntityExtend.settlement
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SettlementRaid(val defender: Settlement, val data: Settlement.RaidData) {

    private val viewingPlayers = mutableSetOf<Player>()

    private var bossBar: BossBar = createBossBar()
    private var defendersBossBar: BossBar = createDefendersBossBar()

    private var lastWaveStartTick: Long = data.activeTicks
    private var maxDefendersTracked: Int = 1

    private var hasReinforced: Boolean = false
    private var chunksLocked: Boolean = false
    private var isGlowingEnabled: Boolean = false

    private val teamAttackerName get() = "atk_${data.attackerId}".take(16)
    private val teamDefenderName get() = "def_${defender.data.id}".take(16)

    private fun getFakeName(uuid: UUID): String {
        return uuid.toString().substring(0, 16)
    }

    private fun getTeamInfo(color: NamedTextColor): ScoreBoardTeamInfo {
        return ScoreBoardTeamInfo(
            Component.empty(), Component.empty(), Component.empty(),
            NameTagVisibility.NEVER,
            CollisionRule.ALWAYS,
            color,
            OptionData.NONE
        )
    }

    private fun addViewerTeams(player: Player) {
        val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return

        user.sendPacket(WrapperPlayServerTeams(teamAttackerName, TeamMode.CREATE, getTeamInfo(NamedTextColor.RED), emptyList()))
        user.sendPacket(WrapperPlayServerTeams(teamDefenderName, TeamMode.CREATE, getTeamInfo(NamedTextColor.GREEN), emptyList()))

        if (isGlowingEnabled) {
            val raiders = data.aliveRaiders.map { getFakeName(it) }
            val defenders = defender.villagers.map { getFakeName(it.uniqueId) }
            val nullInfo: ScoreBoardTeamInfo? = null

            if (raiders.isNotEmpty()) {
                user.sendPacket(WrapperPlayServerTeams(teamAttackerName, TeamMode.ADD_ENTITIES, nullInfo, raiders))
            }
            if (defenders.isNotEmpty()) {
                user.sendPacket(WrapperPlayServerTeams(teamDefenderName, TeamMode.ADD_ENTITIES, nullInfo, defenders))
            }
        }
    }

    private fun removeViewerTeams(player: Player) {
        val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return
        val nullInfo: ScoreBoardTeamInfo? = null

        user.sendPacket(WrapperPlayServerTeams(teamAttackerName, TeamMode.REMOVE, nullInfo, emptyList()))
        user.sendPacket(WrapperPlayServerTeams(teamDefenderName, TeamMode.REMOVE, nullInfo, emptyList()))

        val raiders = data.aliveRaiders.map { getFakeName(it) }
        val defenders = defender.villagers.map { getFakeName(it.uniqueId) }
        val allEntities = raiders + defenders

        if (allEntities.isNotEmpty()) {
            user.sendPacket(WrapperPlayServerTeams("NamelessTeam", TeamMode.ADD_ENTITIES, nullInfo, allEntities))
        }
    }

    private fun updateEntitiesInTeams() {
        val raiders = data.aliveRaiders.map { getFakeName(it) }
        val defenders = defender.villagers.map { getFakeName(it.uniqueId) }
        val nullInfo: ScoreBoardTeamInfo? = null

        val atkPacket = WrapperPlayServerTeams(teamAttackerName, TeamMode.ADD_ENTITIES, nullInfo, raiders)
        val defPacket = WrapperPlayServerTeams(teamDefenderName, TeamMode.ADD_ENTITIES, nullInfo, defenders)

        viewingPlayers.forEach { player ->
            val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return@forEach
            if (raiders.isNotEmpty()) user.sendPacket(atkPacket)
            if (defenders.isNotEmpty()) user.sendPacket(defPacket)
        }
    }

    private fun resetGlowingState() {
        if (!isGlowingEnabled) return
        isGlowingEnabled = false

        data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) }.forEach { it.isGlowing = false }
        defender.villagers.filter { !it.isDead && it.isValid }.forEach { it.isGlowing = false }

        val raiders = data.aliveRaiders.map { getFakeName(it) }
        val defenders = defender.villagers.map { getFakeName(it.uniqueId) }
        val allEntities = raiders + defenders

        if (allEntities.isNotEmpty()) {
            val nullInfo: ScoreBoardTeamInfo? = null
            viewingPlayers.forEach { player ->
                val user = PacketEvents.getAPI().playerManager.getUser(player) ?: return@forEach
                user.sendPacket(WrapperPlayServerTeams("NamelessTeam", TeamMode.ADD_ENTITIES, nullInfo, allEntities))
            }
        }
    }

    private val raidConfig get() = plugin.gameplayManager.config.raid

    private val glowThresholdTicks get() = raidConfig.glowThreshold
    private val killThresholdTicks get() = raidConfig.killThreshold

    private val broadcastRadius get() = raidConfig.broadcastRadius
    private val hornRadius get() = raidConfig.hornRadius
    private val forceLoadRadius = 8

    private val allyReputationThreshold get() = raidConfig.allyReputationThreshold
    private val repGainPerKill get() = raidConfig.repGainPerKill

    private val msgRaidInit get() = plugin.language.getString("raid.bossbar.init", "Raid Initialization...") ?: "Raid Initialization..."
    private val msgDefendersAlive get() = plugin.language.getString("raid.bossbar.defenders-alive", "Defenders Alive: {count}") ?: "Defenders Alive: {count}"
    private val msgTitlePrimary get() = plugin.language.getString("raid.bossbar.title", "{attacker} ⚔ {defender} (Wave {wave}/{total})") ?: "{attacker} ⚔ {defender} (Wave {wave}/{total})"

    private val msgVictoryPrimary get() = plugin.language.getString("raid.bossbar.victory-primary", "Attackers defeated") ?: "Attackers defeated"
    private val msgVictorySecondary get() = plugin.language.getString("raid.bossbar.victory-secondary", "Settlement protected") ?: "Settlement protected"

    private val msgLossPrimary get() = plugin.language.getString("raid.bossbar.loss-primary", "Settlement conquered") ?: "Settlement conquered"
    private val msgLossSecondary get() = plugin.language.getString("raid.bossbar.loss-secondary", "Defenders eliminated") ?: "Defenders eliminated"

    private val msgStoppedPrimary get() = plugin.language.getString("raid.bossbar.stopped-primary", "Raid stopped") ?: "Raid stopped"
    private val msgStoppedSecondary get() = plugin.language.getString("raid.bossbar.stopped-secondary", "Combat ceased") ?: "Combat ceased"

    private val msgRaiderName get() = plugin.language.getString("raid.entity.raider-name", "§cRaider ({attacker})") ?: "§cRaider ({attacker})"
    private val msgWaveStarted get() = plugin.language.getString("raid.chat.wave-started", "§c⚔ A raid wave has started! §6{attacker} §cis attacking §6{defender}§c!") ?: "§c⚔ A raid wave has started! §6{attacker} §cis attacking §6{defender}§c!"
    private val msgReinforcements get() = plugin.language.getString("raid.chat.reinforcements", "§c⚔ Reinforcements have arrived for §6{attacker}§c!") ?: "§c⚔ Reinforcements have arrived for §6{attacker}§c!"
    private val msgRaidAbandoned get() = plugin.language.getString("raid.chat.abandoned", "§e⚔ The siege failed. The raiders decided to take their loot and retreat.") ?: "§e⚔ The siege failed. The raiders decided to take their loot and retreat."
    private val msgVictoryChat get() = plugin.language.getString("raid.chat.victory", "§a⚔ The raid on §6{defender} §ahas been repelled! Victory!") ?: "§a⚔ The raid on §6{defender} §ahas been repelled! Victory!"
    private val msgLossChat get() = plugin.language.getString("raid.chat.loss", "§4☠ §c{defender} has fallen! Conquered by §4{attacker}§c. It is now known as §6{newName}§c.") ?: "§4☠ §c{defender} has fallen! Conquered by §4{attacker}§c. It is now known as §6{newName}§c."
    private val msgLossWipedChat get() = plugin.language.getString("raid.chat.loss-wiped", "§4☠ §cThe settlement {defender} has been wiped out!") ?: "§4☠ §cThe settlement {defender} has been wiped out!"
    private val msgRepIncrease get() = plugin.language.getString("settlement-reputation.increase", "§a+ {amount} reputation with {entity}.") ?: "§a+ {amount} reputation with {entity}."

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

        if (!chunksLocked) {
            manageChunkLoading(true)
        }

        val attacker = SettlementManager.Companion.getById(data.attackerId)
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

    private fun manageChunkLoading(load: Boolean) {
        val center = defender.data.center
        val world = defender.world

        val centerChunkX = center.blockX shr 4
        val centerChunkZ = center.blockZ shr 4

        for (x in -forceLoadRadius..forceLoadRadius) {
            for (z in -forceLoadRadius..forceLoadRadius) {
                val cx = centerChunkX + x
                val cz = centerChunkZ + z

                if (load) {
                    if (world.isChunkGenerated(cx, cz)) {
                        world.addPluginChunkTicket(cx, cz, plugin)
                    }
                } else {
                    world.removePluginChunkTicket(cx, cz, plugin)
                }
            }
        }
        chunksLocked = load
    }

    private fun checkStuckState(attacker: Settlement) {
        val waveDuration = data.activeTicks - lastWaveStartTick

        if (waveDuration > glowThresholdTicks) {

            data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) }.forEach {
                it.isGlowing = true
            }
            defender.villagers.filter { !it.isDead && it.isValid }.forEach {
                it.isGlowing = true
            }

            if (!isGlowingEnabled) {
                isGlowingEnabled = true
                updateEntitiesInTeams()
            }
        }

        if (waveDuration > killThresholdTicks && data.aliveRaiders.isNotEmpty()) {

            if (!hasReinforced) {
                resetGlowingState()

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

                val attackerRace = RaceManager.racesRegistry.values.find { it.name == SettlementManager.Companion.getDominantRace(attacker) } ?: Race.VILLAGER_RACE
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

                    raider.persistentDataContainer.set(NametagDisplayManager.RAIDER_KEY, PersistentDataType.BYTE, 1.toByte())
                    raider.settlement = attacker
                    raider.customName = msgRaiderName.replace("{attacker}", attacker.data.settlementName)
                    raider.isCustomNameVisible = true

                    equipRaider(raider, attacker)
                    data.aliveRaiders.add(raider.uniqueId)
                }

                hasReinforced = true
                lastWaveStartTick = data.activeTicks

            } else {
                broadcastGlobalMessage(msgRaidAbandoned)

                data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) }.forEach {
                    it.remove()
                }
                data.aliveRaiders.clear()

                finishRaid(Settlement.RaidStatus.STOPPED)
            }
        }
    }

    private fun updateRaidAI() {
        val aliveDefenders = defender.villagers.filter { !it.isDead && it.isValid }.mapNotNull { it as? Mob }
        val aliveRaidersList = data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) as? Mob }.filter { !it.isDead && it.isValid }

        val alliedPlayers = defender.world.players.filter { player ->
            defender.territory.contains(player.location.toVector()) &&
                    (defender.data.reputation[player.uniqueId] ?: 0) >= allyReputationThreshold &&
                    (player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE)
        }

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
        resetGlowingState()

        data.currentWave++
        lastWaveStartTick = data.activeTicks
        hasReinforced = false

        val waveSize = Random.nextInt(3, 6) + data.currentWave
        data.totalRaidersInWave = waveSize
        data.aliveRaiders.clear()

        val center = defender.data.center
        val world = center.world ?: return

        val message = msgWaveStarted
            .replace("{attacker}", attacker.data.settlementName)
            .replace("{defender}", defender.data.settlementName)

        world.getNearbyPlayers(center, broadcastRadius).forEach { player ->
            player.sendMessage(message)
        }

        world.getNearbyPlayers(center, hornRadius).forEach { player ->
            player.playSound(center, Sound.EVENT_RAID_HORN, 64.0f, 1.0f)
        }

        buffDefenders()

        val attackerRace = RaceManager.racesRegistry.values.find { it.name == SettlementManager.Companion.getDominantRace(attacker) } ?: Race.VILLAGER_RACE
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

                raider.persistentDataContainer.set(NametagDisplayManager.RAIDER_KEY, PersistentDataType.BYTE, 1.toByte())
                raider.settlement = attacker
                raider.customName = msgRaiderName.replace("{attacker}", attacker.data.settlementName)
                raider.isCustomNameVisible = true

                equipRaider(raider, attacker)
                data.aliveRaiders.add(raider.uniqueId)
                raidersSpawned++
            }
        }

        SettlementManager.Companion.saveSettlements(defender.world)
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

        if (raider is Villager) {
            loadout.values.forEach { itemStack ->
                raider.inventory.addItem(itemStack)
            }
        }

        try {
            val humanoid = plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(raider)
            loadout.forEach { (slot, item) ->
                humanoid?.equip(slot, item)
            }
        } catch (_: Exception) {
            raider.equipment?.let { eq ->
                eq.setHelmet(loadout[EquipmentSlot.HEAD])
                eq.setChestplate(loadout[EquipmentSlot.CHEST])
                eq.setLeggings(loadout[EquipmentSlot.LEGS])
                eq.setBoots(loadout[EquipmentSlot.FEET])
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

            if (entity != null) {
                if (entity.isDead) {
                    if (entity is LivingEntity) {
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
            removeViewerTeams(player)
            viewingPlayers.remove(player)
        }

        val toAdd = currentPlayersInTerritory.filter { it !in viewingPlayers }
        toAdd.forEach { player ->
            player.showBossBar(bossBar)
            player.showBossBar(defendersBossBar)
            addViewerTeams(player)
            viewingPlayers.add(player)
        }
    }

    private fun finishRaid(finalStatus: Settlement.RaidStatus) {
        manageChunkLoading(false)

        data.status = finalStatus
        val attacker = SettlementManager.Companion.getById(data.attackerId)

        val primaryText: String
        val secondaryText: String
        val primaryColor: NamedTextColor
        val secondaryColor: NamedTextColor

        defender.villagers.forEach { it.isGlowing = false }

        when (finalStatus) {
            Settlement.RaidStatus.VICTORY -> {
                data.aliveRaiders.forEach { uuid ->
                    val entity = Bukkit.getEntity(uuid)
                    if (entity is LivingEntity) {
                        entity.persistentDataContainer.remove(NametagDisplayManager.RAIDER_KEY)
                        entity.isGlowing = false
                    }
                }

                val msg = msgVictoryChat.replace("{defender}", defender.data.settlementName)
                broadcastGlobalMessage(msg)

                primaryText = msgVictoryPrimary
                secondaryText = msgVictorySecondary
                primaryColor = NamedTextColor.GREEN
                secondaryColor = NamedTextColor.GREEN

                // Дополнительная проверка на случай, если последний житель погиб сразу после победы
                val aliveCount = defender.villagers.count { !it.isDead && it.isValid }
                if (aliveCount == 0) {
                    SettlementManager.destroySettlement(defender)
                }
            }

            Settlement.RaidStatus.LOSS -> {
                if (attacker != null) {

                    val oldName = defender.data.settlementName

                    val randomRaiderEntity = this.data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) as? Villager }.randomOrNull()
                    val newName = randomRaiderEntity?.race?.settlementNames?.random() ?: "Conquered Settlement"

                    defender.data.settlementName = newName

                    defender.world.getNearbyEntities(defender.territory).filterIsInstance<Player>().forEach { player ->
                        player.currentSettlement = newName
                    }

                    SettlementManager.Companion.setRelation(attacker, defender, Settlement.RelationLevel.ALLIANCE)
                    defender.data.reputation.clear()
                    defender.data.reputation.putAll(attacker.data.reputation)

                    data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) as? LivingEntity }.forEach { victor ->
                        victor.persistentDataContainer.remove(NametagDisplayManager.RAIDER_KEY)

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

                    // Ликвидируем вымершее поселение
                    SettlementManager.destroySettlement(defender)
                }
            }

            else -> {
                primaryText = msgStoppedPrimary
                secondaryText = msgStoppedSecondary
                primaryColor = NamedTextColor.GRAY
                secondaryColor = NamedTextColor.GRAY

                data.aliveRaiders.mapNotNull { Bukkit.getEntity(it) }.forEach {
                    it.remove()
                }
                data.aliveRaiders.clear()

                // Если рейд остановлен, а живых жителей не осталось, поселение уничтожается
                val aliveCount = defender.villagers.count { !it.isDead && it.isValid }
                if (aliveCount == 0) {
                    SettlementManager.destroySettlement(defender)
                }
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
                removeViewerTeams(it)
            }
            viewingPlayers.clear()
            defender.data.activeRaid = null
            SettlementManager.Companion.saveSettlements(defender.world)
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
            removeViewerTeams(it)
        }
        viewingPlayers.clear()
    }
}
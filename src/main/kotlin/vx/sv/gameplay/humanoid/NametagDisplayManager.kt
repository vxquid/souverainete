package vx.sv.gameplay.humanoid

import com.cryptomorin.xseries.XAttribute
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import vx.sv.Souverainete.Companion.plugin
import vx.sv.persistent.LivingEntityExtend.hunger
import vx.sv.persistent.LivingEntityExtend.settlement

class NametagDisplayManager : Listener {

    private val displays = mutableMapOf<Pair<LivingEntity, Player>, TextDisplay>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        val config = plugin.gameplayManager.config
        if (config.nametag.enabled) {
            startViewerUpdater()
        }
    }

    companion object {
        // Публичный ключ, чтобы использовать его в RaidManager/SettlementManager
        val RAIDER_KEY = NamespacedKey(plugin, "is_raider")
    }

    private fun createPersonalDisplay(npc: LivingEntity, player: Player): TextDisplay {
        val config = plugin.gameplayManager.config
        val location = npc.location.clone()
        val display = npc.world.spawn(location, TextDisplay::class.java) { textDisplay ->
            textDisplay.billboard = config.nametag.billboard
            textDisplay.isSeeThrough = config.nametag.seeThrough
            textDisplay.isVisibleByDefault = false
            val bgColor = config.nametag.backgroundColor
            textDisplay.backgroundColor = Color.fromARGB(bgColor[0], bgColor[1], bgColor[2], bgColor[3])

            val scale = config.nametag.displayScale
            val transformation = Transformation(
                Vector3f(0f, config.nametag.displayOffsetY, 0f),
                AxisAngle4f(),
                Vector3f(scale[0], scale[1], scale[2]),
                AxisAngle4f()
            )
            textDisplay.transformation = transformation

            // Сразу обновляем текст при создании
            updateDisplayText(textDisplay, npc, player)
        }

        npc.addPassenger(display)

        // Отложенный показ игроку (чтобы избежать визуальных гличей при спавне)
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline) player.showEntity(plugin, display)
        }, 5L)

        val pair = npc to player
        displays[pair] = display
        return display
    }

    private fun updateDisplayText(display: TextDisplay, npc: LivingEntity, player: Player) {
        val config = plugin.gameplayManager.config
        if (npc !is Villager) return

        // 1. SETTLEMENT LINE
        val npcSettlement = npc.settlement
        val settlementLine = if (npcSettlement != null) {
            "§6«${npcSettlement.data.settlementName}»\n"
        } else ""

        // 2. BASIC INFO (Name, Profession/Raider, Level)
        val name = npc.customName ?: "Unknown"
        val level = npc.villagerLevel

        // --- LOGIC CHANGE: Check for Raider Tag ---
        val isRaider = npc.persistentDataContainer.has(RAIDER_KEY, PersistentDataType.BYTE)

        val profession = if (isRaider) {
            // Берем название из конфига языка, либо дефолтное красное "Raider"
            plugin.language.getString("villager-professions.raider") ?: "§cRaider"
        } else {
            val professionKey = npc.profession.key.key.lowercase()
            (plugin.language.getString("villager-professions.$professionKey") ?: "ERR")
                .replace("_", " ").capitalizeWords()
        }
        // ------------------------------------------

        val line1 = config.nametag.nameProfessionLevelTemplate.format(name, profession, level)

        // 3. REPUTATION & HEALTH
        val reputationManager = plugin.gameplayManager.reputationManager
        val personalRep = reputationManager.getReputationMap(npc)[player.uniqueId] ?: 0
        val settlementRep = npcSettlement?.data?.reputation?.get(player.uniqueId) ?: 0
        val finalRepScore = personalRep + settlementRep
        val repStatus = reputationManager.getReputationStatusFromScore(finalRepScore)

        val health = npc.health
        val maxHealth = npc.getAttribute(XAttribute.MAX_HEALTH.get()!!)?.value ?: 20.0

        val line2 = config.nametag.reputationTemplate.format(finalRepScore, repStatus.getLocalizedName()) +
                " §7|§r " + config.nametag.healthTemplate.format(health, maxHealth)

        var text = "$settlementLine$line1\n$line2"

        // 4. HUNGER LINE
        val hungerValue = npc.hunger
        if (hungerValue <= config.hunger.eatThreshold) {
            val statusKey = if (hungerValue <= config.hunger.starvationThreshold) "starving" else "hungry"
            val status = plugin.language.getString("hunger-status.$statusKey")!!
            val line3 = config.nametag.hungerTemplate.format(status, hungerValue, config.hunger.max)
            text += "\n$line3"
        }

        // 5. PARTY STATUS LINE
        val partyManager = plugin.gameplayManager.partyManager
        val leaderUUID = partyManager.getLeaderUUID(npc)
        if (leaderUUID != null) {
            val leaderName = Bukkit.getPlayer(leaderUUID)?.name ?: Bukkit.getOfflinePlayer(leaderUUID).name ?: "Unknown"
            val partyTemplate = plugin.language.getString("party.member") ?: "§b{playerName} Party Member"
            text += "\n${partyTemplate.replace("{playerName}", leaderName)}"
        }

        // 6. NPC STATE LINE
        val tracker = plugin.gameplayManager.reputationTracker
        val state = tracker.getNPCState(npc, player) // Допускаем, что метод был добавлен в tracker, как обсуждалось

        if (state != null) {
            // Если у стейта нет ключа перевода, используем имя перечисления
            val stateDisplayName = try {
                plugin.language.getString(state.translationKey) ?: state.name
            } catch (e: Exception) { state.name }

            text += "\n${state.color}$stateDisplayName"
        }

        display.text = text
    }

    private fun startViewerUpdater() {
        val config = plugin.gameplayManager.config
        val viewDistance = config.nametag.viewDistance
        val viewDistanceSquared = viewDistance * viewDistance
        val updateIntervalTicks = 20L

        object : BukkitRunnable() {
            override fun run() {
                val cfg = plugin.gameplayManager.config
                if (!cfg.nametag.enabled || !cfg.humanoid.humanoidVillagers) {
                    displays.values.forEach { if (it.isValid) it.remove() }
                    displays.clear()
                    return
                }

                val onlinePlayers = Bukkit.getOnlinePlayers().filter {
                    cfg.worlds.allowedWorlds.contains(it.world.name)
                }.toList()

                // Обновляем/создаем дисплеи
                for (player in onlinePlayers) {
                    val nearbyEntities = player.getNearbyEntities(viewDistance, viewDistance, viewDistance)
                    val nearbyVillagers = nearbyEntities.filterIsInstance<Villager>()
                        .filter { it.location.distanceSquared(player.location) <= viewDistanceSquared }

                    for (npc in nearbyVillagers) {
                        val pair = npc to player
                        val currentDisplay = displays[pair]

                        if (currentDisplay == null || !currentDisplay.isValid) {
                            // Если дисплея нет или он сломался/деспавнулся, создаем новый
                            if (currentDisplay != null) displays.remove(pair) // чистим мусор если был
                            createPersonalDisplay(npc, player)
                        } else {
                            // Просто обновляем текст
                            updateDisplayText(currentDisplay, npc, player)
                        }
                    }
                }

                // Чистка мусора (Entity Despawn, Player Quit, Out of Range)
                val toRemove = mutableListOf<Pair<LivingEntity, Player>>()
                val iterator = displays.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val (npc, p) = entry.key
                    val display = entry.value

                    if (!npc.isValid || !display.isValid || !p.isOnline ||
                        !cfg.worlds.allowedWorlds.contains(p.world.name) ||
                        npc.world != p.world ||
                        npc.location.distanceSquared(p.location) > viewDistanceSquared
                    ) {
                        removePersonalDisplay(npc, p, display)
                        iterator.remove() // Безопасное удаление из итератора
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, updateIntervalTicks)
    }

    private fun removePersonalDisplay(npc: LivingEntity, player: Player, display: TextDisplay) {
        if (player.isOnline) player.hideEntity(plugin, display)
        if (display.isValid) {
            npc.removePassenger(display)
            display.remove()
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val npc = event.entity
        if (npc !is Villager) return

        // Быстрая чистка дисплеев при смерти
        val iterator = displays.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.first == npc) {
                removePersonalDisplay(npc, entry.key.second, entry.value)
                iterator.remove()
            }
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val config = plugin.gameplayManager.config
        if (!config.nametag.enabled) return

        // Чистка при выгрузке чанка
        val iterator = displays.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val npc = entry.key.first
            if (event.chunk == npc.location.chunk) {
                removePersonalDisplay(npc, entry.key.second, entry.value)
                iterator.remove()
            }
        }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}
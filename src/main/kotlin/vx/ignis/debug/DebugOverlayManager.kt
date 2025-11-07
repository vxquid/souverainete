package vx.ignis.debug

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.ProtocolListener.Companion.skinID
import vx.ignis.gameplay.humanoid.entity.HumanoidInfo
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import vx.ignis.gameplay.personality.PersonalityManager.Companion.gender
import vx.ignis.gameplay.personality.PersonalityManager.Companion.getPersonality
import vx.ignis.gameplay.reputation.ReputationManager
import vx.ignis.persistent.LivingEntityExtend.getVoicePitch
import vx.ignis.persistent.LivingEntityExtend.getVoiceSound
import vx.ignis.persistent.LivingEntityExtend.quests
import java.util.*

class DebugOverlayManager(private val humanoidRegistry: HashMap<LivingEntity, HumanoidInfo>) {

    private val debugDisplays: MutableMap<UUID, TextDisplay> = HashMap()
    private val debugKey = NamespacedKey(plugin, "DebugDisplay")
    private var debugTask: BukkitTask? = null
    private val updateIntervalTicks = 20L  // Каждые 1 тик (~50ms) — просто и без нагрузки

    fun startDebugOverlay() {
        if (plugin.gameplayManager.config.debug) return
        if (debugTask != null) return

        debugTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            updateAllDisplays()
        }, 0L, updateIntervalTicks)

        plugin.logger.info("Debug overlay started with interval: $updateIntervalTicks ticks")
    }

    fun stopDebugOverlay() {
        debugTask?.cancel()
        debugTask = null
        debugDisplays.values.forEach { it.remove() }
        debugDisplays.clear()
        plugin.logger.info("Debug overlay stopped")
    }

    private fun updateAllDisplays() {
        val npcs = Bukkit.getWorlds().flatMap { world ->
            world.livingEntities.filter { it in humanoidRegistry.keys && plugin.gameplayManager.allowedWorlds.contains(world) }
        }

        // Чистим инвалидные дисплеи
        val activeUuids = npcs.map { it.uniqueId }.toSet()
        debugDisplays.entries.removeIf { (uuid, display) ->
            if (!activeUuids.contains(uuid) || !display.isValid) {
                display.remove()
                true
            } else false
        }

        // Обновляем/создаём для каждого NPC
        npcs.forEach { updateOrCreateDisplay(it) }
    }

    private fun updateOrCreateDisplay(npc: LivingEntity) {
        val uuid = npc.uniqueId
        var display = debugDisplays[uuid]

        if (display == null || !display.isValid) {
            display = npc.world.spawn(getDisplayLocation(npc), TextDisplay::class.java) { configureDisplay(it) }
            display.persistentDataContainer.set(debugKey, PersistentDataType.BYTE, 1)
            debugDisplays[uuid] = display
        }

        display.let {
            updateDisplayPosition(it, npc)
            it.text = buildDebugText(npc)
        }
    }

    private fun configureDisplay(display: TextDisplay) {
        display.billboard = Display.Billboard.VERTICAL
        display.isSeeThrough = true
        display.isDefaultBackground = false
        display.backgroundColor = null
        display.textOpacity = -1
        display.isShadowed = true
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.transformation = Transformation(
            Vector3f(0f, 0f, 0f),
            AxisAngle4f(0f, 0f, 0f, 0f),
            Vector3f(0.8f, 0.8f, 0.8f),  // Компактный scale
            AxisAngle4f(0f, 0f, 0f, 0f)
        )
        display.isInvulnerable = true
        display.isPersistent = false
    }

    private fun getDisplayLocation(npc: LivingEntity): Location {
        val scale = npc.race.attributes[com.cryptomorin.xseries.XAttribute.SCALE] ?: 1.0
        return npc.eyeLocation.add(0.0, 0.3 + (scale - 1.0) * 0.5, 0.0)
    }

    private fun updateDisplayPosition(display: TextDisplay, npc: LivingEntity) {
        val targetLoc = getDisplayLocation(npc)
        if (display.location != targetLoc) display.teleport(targetLoc)
    }

    private fun buildDebugText(npc: LivingEntity): String {
        val sb = StringBuilder()
        sb.appendLine("§b§lNPC Debug")
        sb.appendLine("§eName: §f${npc.customName ?: "Unnamed"}")
        sb.appendLine("§eUUID: §f${npc.uniqueId.toString().substring(0, 8)}...")
        sb.appendLine("§eType/Loc: §f${npc.type.name} @ ${npc.location.blockX},${npc.location.blockY},${npc.location.blockZ}")

        val race = npc.race
        sb.appendLine("§eRace/Gender: §f${race.name} / ${npc.gender}")

        val personality = npc.getPersonality()
        sb.appendLine("§ePersonality: §f${personality.key}")

        sb.appendLine("§eVoice: §f${Registry.SOUNDS.getKey(npc.getVoiceSound())?.toString()?.take(15)} @ ${npc.getVoicePitch()}")

        val skinID = npc.skinID()
        sb.appendLine("§eSkinID: §f${skinID}")

        val repManager = ReputationManager()
        val repMap = repManager.getReputationMap(npc)
        sb.appendLine("§eReps:")
        Bukkit.getOnlinePlayers().take(5).forEach { player ->  // Ограничил для компактности
            val rep = repMap[player.uniqueId] ?: 0
            val status = repManager.getPlayerReputationStatus(npc, player)
            sb.appendLine("  §f${player.name.take(8)}: §e$rep (${status.name})")
        }

        val quests = npc.quests()
        sb.appendLine("§eQuests: §f${quests.size}")
        quests.take(2).forEach { q ->
            sb.appendLine("  §e${q.name.take(12)}... (ID:${q.id}, Prog:${q.progress}%)")
        }

        if (npc is Villager) {
            sb.appendLine("§eProf: §f${Registry.VILLAGER_PROFESSION.getKey(npc.profession)?.key} L${npc.villagerLevel} XP${npc.villagerExperience}")
        }

        sb.appendLine("§eAttrs:")
        race.attributes.entries.take(3).forEach { (attr, v) ->
            sb.appendLine("  §f${attr.name().take(8)}: §e$v")
        }

        return sb.toString()
    }

    private fun StringBuilder.appendLine(text: String) = append("$text\n")
}
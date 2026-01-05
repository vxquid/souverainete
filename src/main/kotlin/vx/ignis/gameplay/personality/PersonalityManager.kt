package vx.ignis.gameplay.personality

import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import java.io.File

class PersonalityManager : Listener {

    private val personalities = mutableMapOf<String, Personality>()

    // Работаем только с обычными жителями
    private val targetEntityTypes = setOf(EntityType.VILLAGER)

    // Раз в 30 секунд проверяем потеряшек (на всякий случай)
    private val generationTickDelay = 600L

    init {
        this.loadPersonalities()
        plugin.server.pluginManager.registerEvents(this, plugin)
        this.startFallbackTicker()
    }

    private fun startFallbackTicker() {
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            processFallbackTick()
        }, 0, generationTickDelay)
    }

    enum class Gender {
        MALE, FEMALE
    }

    // ============================================================================================
    // ИВЕНТЫ: Мгновенная инициализация при появлении в мире
    // ============================================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        if (!isTargetEntity(entity)) return
        if (!plugin.gameplayManager.allowedWorlds.contains(entity.world)) return

        // Если данных нет — генерируем
        if (entity.customName == null) {
            generateCharacterName(entity)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!plugin.gameplayManager.allowedWorlds.contains(event.world)) return

        // Быстрый проход по энтити в загруженном чанке
        for (entity in event.chunk.entities) {
            if (entity is LivingEntity && isTargetEntity(entity)) {
                if (entity.customName == null) {
                    generateCharacterName(entity)
                }
            }
        }
    }

    // ============================================================================================
    // ЛОГИКА
    // ============================================================================================

    private fun isTargetEntity(entity: org.bukkit.entity.Entity): Boolean {
        return targetEntityTypes.contains(entity.type) && entity is Villager
    }

    private fun processFallbackTick() {
        plugin.gameplayManager.allowedWorlds.forEach { world ->
            world.entities.filterIsInstance<Villager>()
                .filter { targetEntityTypes.contains(it.type) && it.customName == null }
                .forEach { entity ->
                    generateCharacterName(entity)
                }
        }
    }

    private fun loadPersonalities() {
        val configFile = File(plugin.dataFolder, "personalities.yml")
        if (!configFile.exists()) {
            plugin.saveResource("personalities.yml", false)
        }
        val config = YamlConfiguration.loadConfiguration(configFile)
        config.getKeys(false).forEach { key ->
            val definition = config.getString("$key.definition") ?: "Unknown vibe"
            personalities[key] = Personality(key, definition)
        }
    }

    private fun generateCharacterName(entity: LivingEntity) {
        val gender = entity.gender
        val race = entity.race
        val name = race.randomName(gender)

        if (plugin.gameplayManager.config.humanoid.humanoidVillagers)
            entity.customName = name

        if (entity.persistentDataContainer.get(personalityKey, PersistentDataType.STRING) == null) {
            entity.getPersonality()
        }
    }

    data class Personality(
        val key: String,
        val definition: String
    ) {
        override fun toString(): String = "[Personality: $key]"
    }

    companion object {
        val personalityKey   = NamespacedKey(plugin, "Personality")
        val genderKey        = NamespacedKey(plugin, "Gender")

        fun LivingEntity.getPersonality(): Personality {
            return this.persistentDataContainer.get(personalityKey, PersistentDataType.STRING)?.let {
                return gson.fromJson(it, Personality::class.java)
            } ?: plugin.gameplayManager.personalityManager.personalities.values.randomOrNull()?.let { personality ->
                this.setPersonality(personality)
                personality
            } ?: Personality("Default", "Commoner")
        }

        fun LivingEntity.setPersonality(personality: Personality) {
            this.persistentDataContainer.set(personalityKey, PersistentDataType.STRING, gson.toJson(personality, Personality::class.java))
        }

        val LivingEntity.gender: Gender
            get() = persistentDataContainer.get(genderKey, PersistentDataType.STRING)?.let {
                try { Gender.valueOf(it) } catch (e: Exception) { Gender.MALE }
            } ?: Gender.entries.random().also {
                persistentDataContainer.set(genderKey, PersistentDataType.STRING, it.toString())
            }

    }

}
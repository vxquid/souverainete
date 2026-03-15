package vx.sv.gameplay.personality

import org.bukkit.NamespacedKey
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race

class PersonalityManager : Listener {

    // Работаем только с обычными жителями
    private val targetEntityTypes = setOf(EntityType.VILLAGER)

    // Раз в 30 секунд проверяем потеряшек
    private val generationTickDelay = 600L

    init {
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

    enum class Personality {
        DEPRESSED, OPTIMISTIC, PESSIMISTIC, KIND, RUDE, MEAN, EMOTIONAL, CYNICAL, COLD, FORMAL,
        FRIENDLY, FAMILIAR, HUMOROUS, TALKATIVE, IRONIC, SARCASTIC, SERIOUS, NOSTALGIC, WITTY,
        ADVENTUROUS, MYSTERIOUS, DREAMY, IMPULSIVE, OBSESSIVE, RECKLESS, HUMBLE, FORGIVING,
        RATIONAL, ARTISTIC, ANXIOUS, PLAYFUL, RELAXED, GRUMPY, INTELLECTUAL, NAIVE, IGNORANT,
        ANGRY, MAD_SCIENTIST, DRUNKARD, SANE, ROMANTIC, REBELLIOUS, DRAMATIC, LUCKY, UNLUCKY,
        THIEF, POTHEAD, RANDOM, EVIL, SHAMAN, PHILOSOPHICAL;

        // Переопределяем toString, чтобы в промпт ИИ попадал красивый текст
        // Например: "MAD_SCIENTIST" -> "mad scientist"
        override fun toString(): String {
            return name.lowercase().replace("_", " ")
        }
    }

    // ============================================================================================
    // ИВЕНТЫ: Мгновенная инициализация при появлении в мире
    // ============================================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        if (!isTargetEntity(entity)) return
        if (!plugin.gameplayManager.allowedWorlds.contains(entity.world)) return

        if (entity.customName == null) {
            generateCharacterName(entity)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!plugin.gameplayManager.allowedWorlds.contains(event.world)) return

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

    fun generateCharacterName(entity: LivingEntity) {
        val gender = entity.gender
        val race = entity.race
        val name = race.randomName(gender)

        if (plugin.gameplayManager.config.humanoid.humanoidVillagers)
            entity.customName = name

        // Принудительно вызываем чтение (и если нужно, миграцию) характера
        entity.getPersonality()
    }

    companion object {
        val personalityKey = NamespacedKey(plugin, "Personality")
        val genderKey      = NamespacedKey(plugin, "Gender")

        fun LivingEntity.getPersonality(): Personality {
            val storedData = this.persistentDataContainer.get(personalityKey, PersistentDataType.STRING)

            if (storedData != null) {
                try {
                    // 1. Пробуем прочитать как Enum (сработает для новых/уже мигрировавших NPC)
                    return Personality.valueOf(storedData)
                } catch (e: IllegalArgumentException) {
                    // 2. Если упали с ошибкой — это старый JSON формат от Gson.
                    // Регуляркой вытаскиваем старый ключ (например из '{"key":"depressed","definition":"..."}')
                    val match = Regex("\"key\":\"([^\"]+)\"").find(storedData)
                    if (match != null) {
                        val oldKey = match.groupValues[1].uppercase()
                        try {
                            // Пытаемся найти этот ключ в нашем новом Enum'е
                            val migratedPersonality = Personality.valueOf(oldKey)

                            // Сохраняем в НОВОМ, легком формате, навсегда избавляясь от JSON у этого NPC
                            this.setPersonality(migratedPersonality)

                            return migratedPersonality
                        } catch (ex: IllegalArgumentException) {
                            // Если старый характер был удален из кода и valueOf его не нашел, идем дальше к рандому
                        }
                    }
                }
            }

            // 3. Если данных нет вообще, либо старый JSON содержал удаленный характер — выдаем рандомный
            return Personality.entries.random().also {
                this.setPersonality(it) // Сразу же сохраняем новый легкий формат
            }
        }

        fun LivingEntity.setPersonality(personality: Personality) {
            // Теперь храним просто чистую строку ("DEPRESSED", "SHAMAN", "DRUNKARD")
            this.persistentDataContainer.set(personalityKey, PersistentDataType.STRING, personality.name)
        }

        val LivingEntity.gender: Gender
            get() = persistentDataContainer.get(genderKey, PersistentDataType.STRING)?.let {
                try { Gender.valueOf(it) } catch (e: Exception) { Gender.MALE }
            } ?: Gender.entries.random().also {
                persistentDataContainer.set(genderKey, PersistentDataType.STRING, it.name)
            }
    }
}
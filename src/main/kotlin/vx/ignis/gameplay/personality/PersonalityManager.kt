package vx.ignis.gameplay.personality

import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.ai.base.AIClient
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import java.io.File

class PersonalityManager {

    private val aiClient: AIClient = plugin.providerManager.client
    private val personalities = mutableMapOf<String, Personality>()

    lateinit var genericCharacterData: GenericCharacterData

    // TODO: Must be configurable.
    private val targetEntityTypes = mutableListOf(EntityType.VILLAGER)
    private val generationTickDelay = 200L
    private val alwaysShowName = true

    init {
        this.loadPersonalities()
        this.generateGenericCharacterData()
        this.startTick()
    }

    private fun startTick() {
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            tick()
        }, 0, generationTickDelay)
    }

    enum class Gender {
        MALE, FEMALE
    }

    private fun tick() {
        plugin.gameplayManager.allowedWorlds.forEach { world ->
            world.entities.filterIsInstance<AbstractVillager>().find { targetEntityTypes.contains(it.type) && it.getCharacterData() == null }
                ?.let { entity ->
                    this.generateCharacterData(entity)
                }
        }
    }

    // Load characters from the YAML configuration
    private fun loadPersonalities() {
        val configFile = File(plugin.dataFolder, "personalities.yml")

        // Ensure the file exists, create it with default content if it doesn't
        if (!configFile.exists()) {
            plugin.saveResource("personalities.yml", false)
        }

        val config = YamlConfiguration.loadConfiguration(configFile)

        config.getKeys(false).forEach { key ->
            val definition = config.getString("$key.definition") ?: "Unknown vibe"
            personalities[key] = Personality(key, definition)
        }
    }

    private fun generateCharacterData(entity: LivingEntity) {

        val personality = entity.getPersonality()
        val gender      = entity.gender
        val race        = entity.race
        val biome       = entity.world.getBiome(entity.location).key()

        plugin.server.scheduler.runTaskAsynchronously(plugin, { _ ->
            aiClient.sendPromptWithSchema(
                prompt = "Your task is to generate NPC data & reaction phrases, taking into account the following NPC info: race (race name: ${race.name}, race description: [{${race.description}}]), personality is ($personality), gender ($gender), biome ($biome). Required JSON schema: " +
                        "‘npcNames’ (array of five strings; first and second name; must be in naming style!), " +
                        "‘sleepInterruptionPhrases’ (array of 5 strings; [reaction phrases that NPC says when a player disrupts their sleep]), " +
                        "‘damagePhrases’ (array of 5 strings; [reaction phrases that NPC says when attacked by a player]), " +
                        "‘joblessPhrases’ (array of 5 strings; [reaction phrases that NPC says when a player suggests trading, but NPC doesn't have any job]), " +
                        "‘noItemsToTradePhrases’ (array of 5 strings; [reaction phrases that NPC says when a player suggests trading, but NPC doesn't have any items to trade]), " +
                        "‘noQuestPhrases’ (array of 5 strings; [reaction phrases that NPC says when a player asks NPC about job, but NPC doesn't have any quests for the player])," +
                        "‘totemOfUndyingResurrectionPhrases’ (array of 5 strings; [reaction phrases that NPC says when totem of undying saves them from death, must be heavily depends on personality type])," +
                        "‘imprisonedOrStuckPhrases’ (array of 5 strings; [reaction phrases that NPC says when they being locked in one place for a long time (usually they should ask for help, but it depends on personality)]).",
                targetClass = CharacterData::class
            )?.let { personalityData ->
                entity.setCharacterData(personalityData)
                entity.customName = personalityData.npcNames.random()
                entity.isCustomNameVisible = alwaysShowName
            } ?: plugin.logger.warning("Failed to generate character data for entity ${entity.type}.")
        })
    }

    data class GenericCharacterData(
        val sleepInterruptionPhrases: MutableList<String>,
        val damagePhrases: MutableList<String>,
        val joblessPhrases: MutableList<String>,
        val noItemsToTradePhrases: MutableList<String>,
        val noQuestPhrases: MutableList<String>,
        val totemOfUndyingResurrectionPhrases: MutableList<String>,
        val imprisonedOrStuckPhrases: MutableList<String>
    )

    private fun generateGenericCharacterData() {
        aiClient.sendPromptWithSchema(
            prompt = "Your task is to generate generic NPC reaction phrases and put it in JSON with specified keys: " +
                    "‘sleepInterruptionPhrases’ (array of 10 strings; [phrases that NPC says when a player disrupts their sleep]), " +
                    "‘damagePhrases’ (array of 10 strings; [phrases that NPC says when attacked by a player]), " +
                    "‘joblessPhrases’ (array of 10 strings; [phrases that NPC says when a player suggests trading, but NPC doesn't have any job]), " +
                    "‘noItemsToTradePhrases’ (array of 10 strings; [phrases that NPC says when a player suggests trading, but NPC doesn't have any items to trade]), " +
                    "‘noQuestPhrases’ (array of 10 strings; [phrases that NPC says when a player asks NPC about job, but NPC doesn't have any quests for the player])," +
                    "‘totemOfUndyingResurrectionPhrases’ (array of 5 strings; [phrases that NPC says when totem of undying saves them from death])," +
                    "‘imprisonedOrStuckPhrases’ (array of 5 strings; [phrases that NPC says when they being locked in one place for a long time (usually they should ask for help)]).",
            targetClass = GenericCharacterData::class
        )?.let { genericCharacter ->
            this.genericCharacterData = genericCharacter
        } ?: plugin.logger.warning("Failed to generate generic character data.")
    }

    data class Personality(
        val key: String,
        val definition: String
    ) {
        override fun toString(): String {
            return "[Personality name is `$key`, personality definition is `$definition`]"
        }
    }

    companion object {

        val personalityKey   = NamespacedKey(plugin, "Personality")
        val characterDataKey = NamespacedKey(plugin, "CharacterData")
        val genderKey        = NamespacedKey(plugin, "Gender")

        fun LivingEntity.getPersonality(): Personality {
            return this.persistentDataContainer.get(personalityKey, PersistentDataType.STRING)?.let {
                return gson.fromJson(it, Personality::class.java)
            } ?: plugin.gameplayManager.personalityManager.personalities.values.random().let { personality ->
                this.setPersonality(personality)
                personality
            }
        }

        fun LivingEntity.setPersonality(personality: Personality) {
            this.persistentDataContainer.set(personalityKey, PersistentDataType.STRING, gson.toJson(personality, Personality::class.java))
        }

        fun LivingEntity.getCharacterData(): CharacterData? {
            return persistentDataContainer.get(characterDataKey, PersistentDataType.STRING)?.let { data ->
                gson.fromJson(data, CharacterData::class.java)
            }
        }

        fun LivingEntity.setCharacterData(characterData: CharacterData) {
            persistentDataContainer.set(characterDataKey, PersistentDataType.STRING, characterData.toString())
        }

        val LivingEntity.gender: Gender
            get() = persistentDataContainer.get(genderKey, PersistentDataType.STRING)?.let {
                Gender.valueOf(it)
            } ?: Gender.entries.random().also { persistentDataContainer.set(genderKey, PersistentDataType.STRING, it.toString()) }

        data class CharacterData(
            val npcNames: List<String>,
            val sleepInterruptionPhrases: List<String>,
            val damagePhrases: List<String>,
            val joblessPhrases: List<String>,
            val noItemsToTradePhrases: List<String>,
            val noQuestPhrases: List<String>,
            val totemOfUndyingResurrectionPhrases: MutableList<String>,
            val imprisonedOrStuckPhrases: MutableList<String>
        ) {
            override fun toString(): String {
                return gson.toJson(this).toString()
            }
        }
    }

}
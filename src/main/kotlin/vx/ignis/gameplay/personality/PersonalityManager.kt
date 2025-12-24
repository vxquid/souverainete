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
    private val generationTickDelay = 100L

    init {
        this.loadPersonalities()
        this.startGenericCharacterDataGenerationTicker()
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

        plugin.server.scheduler.runTaskAsynchronously(plugin) { _ ->
            aiClient.sendPromptWithSchema(
                prompt = """
                    You are an expert in generating immersive NPC data for a Minecraft RPG plugin. Your task is to create detailed, lore-consistent character data and reaction phrases for an NPC based on the provided details. Ensure all outputs are creative, varied, and tailored to the NPC's race, personality, gender, and biome. Phrases should be in first-person perspective, spoken as if by the NPC, and fit a fantasy/medieval style. Keep them concise (under 50 words each) and engaging.

                    NPC Details:
                    - Race: Name = ${race.name}, Description = ${race.description}
                    - Personality: Name = ${personality.key}, Definition = ${personality.definition}
                    - Gender: $gender
                    - Biome: $biome (adapt phrases to environmental themes, e.g., cold for snowy biomes, mystical for forests).

                    Output strictly as JSON matching this schema (no additional text outside the JSON):
                    {
                      "npcNames": [array of 5 unique full names as strings; each name should combine a first and last name in the stylistic conventions of the race (e.g., Elvish names sound elegant and nature-inspired); ensure diversity and cultural fit],
                      "sleepInterruptionPhrases": [array of 5 unique strings; reactions when a player wakes the NPC from sleep; reflect annoyance or surprise, influenced by personality (e.g., aggressive personalities might threaten)],
                      "damagePhrases": [array of 5 unique strings; reactions when attacked by a player; show pain, anger, or fear, tailored to personality and race (e.g., a tough orc might taunt back)],
                      "joblessPhrases": [array of 5 unique strings; responses when a player tries to trade but the NPC has no job; express confusion, refusal, or redirection, fitting the personality],
                      "noItemsToTradePhrases": [array of 5 unique strings; responses when trading is attempted but no items are available; sound apologetic or dismissive based on personality],
                      "noQuestPhrases": [array of 5 unique strings; replies when asked about quests/jobs but none are available; could hint at future possibilities or outright refuse, aligned with personality],
                      "totemOfUndyingResurrectionPhrases": [array of 5 unique strings; exclamations upon resurrection via Totem of Undying; heavily personality-dependent (e.g., grateful for kind personalities, vengeful for aggressive ones); include awe or relief],
                      "imprisonedOrStuckPhrases": [array of 5 unique strings; pleas or comments when trapped for a long time; usually beg for help, but vary by personality (e.g., proud ones might demand freedom haughtily)],
                      
                      "startFightPhrases": [array of 10 unique strings; battle cries, threats, or confident remarks when the NPC enters combat mode; highly dependent on personality (e.g., a coward might panic, a warrior will shout a challenge)],
                      "meleeKillPhrases": [array of 10 unique strings; victory lines after killing an enemy in close combat; emphasize strength, brutality, or relief],
                      "rangedKillPhrases": [array of 10 unique strings; victory lines after killing an enemy with a projectile (bow/crossbow); emphasize precision, sharp-shooting skills, or distance]
                    }
                """.trimIndent(),
                targetClass = CharacterData::class
            )?.let { personalityData ->
                entity.setCharacterData(personalityData)
                entity.customName = personalityData.npcNames.random()
            }
        }
    }

    data class GenericCharacterData(
        val sleepInterruptionPhrases: MutableList<String>,
        val damagePhrases: MutableList<String>,
        val joblessPhrases: MutableList<String>,
        val noItemsToTradePhrases: MutableList<String>,
        val noQuestPhrases: MutableList<String>,
        val totemOfUndyingResurrectionPhrases: MutableList<String>,
        val imprisonedOrStuckPhrases: MutableList<String>,

        // New generic pools
        val startFightPhrases: MutableList<String>,
        val meleeKillPhrases: MutableList<String>,
        val rangedKillPhrases: MutableList<String>
    )

    private fun startGenericCharacterDataGenerationTicker() {
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, { task ->
            if (::genericCharacterData.isInitialized) {
                task.cancel()
                return@runTaskTimerAsynchronously
            }

            aiClient.sendPromptWithSchema(
                prompt = """
                    You are an expert in generating immersive NPC reaction phrases for a Minecraft RPG plugin. Your task is to create generic, versatile reaction phrases that can apply to any NPC when specific data isn't available. Phrases should be in first-person perspective, neutral in tone but varied, fitting a fantasy/medieval style. Keep them concise (under 50 words each) and engaging. Ensure diversity across the arrays to avoid repetition.

                    Output strictly as JSON matching this schema (no additional text outside the JSON):
                    {
                      "sleepInterruptionPhrases": [array of 10 unique strings; general reactions to being woken up; mix of annoyance, confusion, and humor],
                      "damagePhrases": [array of 10 unique strings; general reactions to being attacked; include pain, pleas, or threats],
                      "joblessPhrases": [array of 10 unique strings; responses to trade attempts without a job; polite refusals or suggestions to find work],
                      "noItemsToTradePhrases": [array of 10 unique strings; responses when no items are available for trade; apologetic or explanatory],
                      "noQuestPhrases": [array of 10 unique strings; replies when no quests are available; could encourage patience or redirect],
                      "totemOfUndyingResurrectionPhrases": [array of 5 unique strings; exclamations upon resurrection; mix of relief, wonder, and determination],
                      "imprisonedOrStuckPhrases": [array of 5 unique strings; comments when trapped; generally pleas for help, with some frustration or resignation],
                      
                      "startFightPhrases": [array of 10 unique strings; general battle cries or defensive shouts when a fight starts],
                      "meleeKillPhrases": [array of 10 unique strings; general victory lines after striking down an enemy up close],
                      "rangedKillPhrases": [array of 10 unique strings; general victory lines after shooting an enemy from afar (e.g. 'Right between the eyes!', 'Got them!')]
                    }
                """.trimIndent(),
                targetClass = GenericCharacterData::class
            )?.let { genericCharacter ->
                this.genericCharacterData = genericCharacter
            }
        }, 0, generationTickDelay)
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
            val imprisonedOrStuckPhrases: MutableList<String>,

            // New specific pools
            val startFightPhrases: List<String>,
            val meleeKillPhrases: List<String>,
            val rangedKillPhrases: List<String>
        ) {
            override fun toString(): String {
                return gson.toJson(this).toString()
            }
        }
    }
}
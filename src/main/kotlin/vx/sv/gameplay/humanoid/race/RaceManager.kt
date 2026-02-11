package vx.sv.gameplay.humanoid.race

import com.cryptomorin.xseries.XAttribute
import com.cryptomorin.xseries.XEntityType
import com.cryptomorin.xseries.XMaterial
import com.cryptomorin.xseries.XSound
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.personality.PersonalityManager.Gender
import java.io.File
import kotlin.random.Random

class RaceManager {

    var races: YamlConfiguration = run {
        val file = File(plugin.dataFolder, "races.yml")
        if (!file.exists()) plugin.saveResource("races.yml", false)
        YamlConfiguration.loadConfiguration(file)
    }

    fun loadRaces() {
        racesRegistry.clear()

        races.getKeys(false).forEach { name ->
            val section = races.getConfigurationSection(name) ?: return@forEach

            // 1. --- Basic Attributes & Settings ---
            val leaderTitle = section.getString("leader-title") ?: "Mayor"
            val targetEntityType = section.getString("target-entity-type") ?: return@forEach
            val targetVillagerType = section.getString("target-villager-type")
                ?.let { Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft(it.lowercase())) }
                ?: Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft("plains"))!!

            val spawnItems = mutableListOf<SpawnItemStack>()
            section.getStringList("spawn-items").forEach { item ->
                val (material, min, max) = item.split("-")
                spawnItems.add(SpawnItemStack(XMaterial.valueOf(material), min.toInt(), max.toInt()))
            }

            val attributes = mutableMapOf<XAttribute, Double>()
            section.getStringList("basic-attributes").forEach { item ->
                val (attribute, value) = item.split("-")
                attributes[XAttribute.of(attribute).get()] = value.toDouble()
            }

            // Sounds
            val maleVoices = mutableListOf<PitchedSound>()
            section.getStringList("sound.male.voice").forEach {
                val (sound, min, max) = it.split("-")
                maleVoices.add(PitchedSound(XSound.of(sound).get(), min.toDouble(), max.toDouble()))
            }
            val maleHurtSound = section.getString("sound.male.hurt")!!.let {
                val (s, min, max) = it.split("-")
                PitchedSound(XSound.of(s).get(), min.toDouble(), max.toDouble())
            }
            val maleDeathSound = section.getString("sound.male.death")!!.let {
                val (s, min, max) = it.split("-")
                PitchedSound(XSound.of(s).get(), min.toDouble(), max.toDouble())
            }

            val femaleVoices = mutableListOf<PitchedSound>()
            section.getStringList("sound.female.voice").forEach {
                val (sound, min, max) = it.split("-")
                femaleVoices.add(PitchedSound(XSound.of(sound).get(), min.toDouble(), max.toDouble()))
            }
            val femaleHurtSound = section.getString("sound.female.hurt")!!.let {
                val (s, min, max) = it.split("-")
                PitchedSound(XSound.of(s).get(), min.toDouble(), max.toDouble())
            }
            val femaleDeathSound = section.getString("sound.female.death")!!.let {
                val (s, min, max) = it.split("-")
                PitchedSound(XSound.of(s).get(), min.toDouble(), max.toDouble())
            }

            val normalCurrency = XMaterial.valueOf(section.getString("normal-currency")!!)
            val specialCurrency = XMaterial.valueOf(section.getString("special-currency")!!)
            val description = section.getString("race-description") ?: ""

            // 2. --- External Resources Loading ---
            val raceFolder = File(plugin.dataFolder, "races/$name")

            fun loadRaceResource(fileName: String): YamlConfiguration {
                // Пытаемся найти переведенный файл в кэше
                val cachePath = "races/$name/${fileName.substringBeforeLast(".")}"
                val cacheFile = File(plugin.dataFolder, "cache/$cachePath.yml")

                if (cacheFile.exists()) {
                    return YamlConfiguration.loadConfiguration(cacheFile)
                }

                // Стандартная логика загрузки из ресурсов плагина или папки
                val file = File(raceFolder, fileName)
                val resourcePath = "races/$name/$fileName"

                if (!file.exists()) {
                    if (plugin.getResource(resourcePath) != null) {
                        plugin.saveResource(resourcePath, false)
                    } else {
                        plugin.logger.log(java.util.logging.Level.WARNING, "Resource '$resourcePath' not found. Creating empty.")
                        file.parentFile.mkdirs()
                        file.createNewFile()
                    }
                }
                return YamlConfiguration.loadConfiguration(file)
            }

            // A) Names
            val namesConfig = loadRaceResource("names.yml")
            val maleFirstNames = namesConfig.getStringList("male-first-names")
            val femaleFirstNames = namesConfig.getStringList("female-first-names")
            val lastNames = namesConfig.getStringList("last-names")

            // B) Phrases
            val phrasesConfig = loadRaceResource("phrases.yml")
            fun getPhrases(path: String) = phrasesConfig.getStringList(path)

            val racePhrases = RacePhrases(
                sleepInterruption = getPhrases("sleepInterruptionPhrases"),
                damage = getPhrases("damagePhrases"),
                jobless = getPhrases("joblessPhrases"),
                noItemsToTrade = getPhrases("noItemsToTradePhrases"),
                noQuest = getPhrases("noQuestPhrases"),
                totemResurrection = getPhrases("totemOfUndyingResurrectionPhrases"),
                imprisoned = getPhrases("imprisonedOrStuckPhrases"),
                startFight = getPhrases("startFightPhrases"),
                meleeKill = getPhrases("meleeKillPhrases"),
                rangedKill = getPhrases("rangedKillPhrases"),
                warning = getPhrases("warningPhrases"),
                witnessMurder = getPhrases("witnessMurderPhrases")
            )

            // C) Skins
            val skinsConfig = loadRaceResource("skins.yml")
            val maleSkins = mutableMapOf<Float, TextureProperty>()
            val femaleSkins = mutableMapOf<Float, TextureProperty>()

            fun loadSkinsForGender(sectionName: String, targetMap: MutableMap<Float, TextureProperty>) {
                val skinSection = skinsConfig.getConfigurationSection(sectionName) ?: return
                skinSection.getKeys(false).forEach { key ->
                    val node = skinSection.getConfigurationSection(key) ?: return@forEach
                    val texture = node.getString("texture")
                    val signature = node.getString("signature")
                    val id = key.toFloatOrNull()
                    if (texture != null && signature != null && id != null) {
                        targetMap[id] = TextureProperty("textures", texture, signature)
                    }
                }
            }
            loadSkinsForGender("male", maleSkins)
            loadSkinsForGender("female", femaleSkins)

            // D) Settlements
            val settlementsConfig = loadRaceResource("settlements.yml")
            val settlementNames = settlementsConfig.getStringList("names")

            // 3. --- Register Race ---
            racesRegistry[name] = Race(
                name,
                leaderTitle,
                XEntityType.valueOf(targetEntityType), targetVillagerType,
                maleVoices, femaleVoices,
                maleHurtSound, maleDeathSound, femaleHurtSound, femaleDeathSound,
                spawnItems, attributes,
                maleSkins, femaleSkins,
                description, normalCurrency, specialCurrency,
                maleFirstNames, femaleFirstNames, lastNames,
                racePhrases,
                settlementNames
            )
        }
    }

    data class PitchedSound(val sound: XSound, val min: Double, val max: Double)

    data class RacePhrases(
        val sleepInterruption: List<String>,
        val damage: List<String>,
        val jobless: List<String>,
        val noItemsToTrade: List<String>,
        val noQuest: List<String>,
        val totemResurrection: List<String>,
        val imprisoned: List<String>,
        val startFight: List<String>,
        val meleeKill: List<String>,
        val rangedKill: List<String>,
        val warning: List<String>,
        val witnessMurder: List<String>
    )

    data class Race(
        val name: String,
        val leaderTitle: String,
        val targetEntityType: XEntityType,
        val targetVillagerType: Villager.Type,
        val maleVoices: List<PitchedSound>,
        val femaleVoices: List<PitchedSound>,
        val maleHurtSound: PitchedSound,
        val maleDeathSound: PitchedSound,
        val femaleHurtSound: PitchedSound,
        val femaleDeathSound: PitchedSound,
        val spawnItems: MutableList<SpawnItemStack>,
        val attributes: Map<XAttribute, Double>,
        val maleSkins: MutableMap<Float, TextureProperty>,
        val femaleSkins: MutableMap<Float, TextureProperty>,
        val description: String = "",
        val normalCurrency: XMaterial,
        val specialCurrency: XMaterial,
        val maleFirstNames: List<String>,
        val femaleFirstNames: List<String>,
        val lastNames: List<String>,
        val phrases: RacePhrases,
        val settlementNames: List<String>
    ) {

        val matching: (LivingEntity) -> Boolean = { entity ->
            entity is Villager && entity.villagerType == targetVillagerType ||
                    entity !is Villager && entity.type == targetEntityType.get()
        }

        fun randomName(gender: Gender): String {

            val nameList = if (gender == Gender.MALE) maleFirstNames else femaleFirstNames
            val fallbackName = if (gender == Gender.MALE) "John" else "Jane"

            val generatedNames = List(5) {
                val first = if (nameList.isNotEmpty()) nameList.random() else fallbackName
                val last = if (lastNames.isNotEmpty()) lastNames.random() else "Doe"
                "$first${if (!last.isBlank()) " $last" else ""}"
            }

            return generatedNames.random()
        }

        companion object {
            private val voices = listOf(XSound.ENTITY_VILLAGER_YES).map { PitchedSound(it, 1.0, 1.0) }

            val VILLAGER_RACE = Race(
                "villager", "Mayor", XEntityType.VILLAGER, Villager.Type.PLAINS,
                voices, voices,
                PitchedSound(XSound.ENTITY_VILLAGER_HURT, 1.0, 1.0),
                PitchedSound(XSound.ENTITY_VILLAGER_DEATH, 1.0, 1.0),
                PitchedSound(XSound.ENTITY_VILLAGER_HURT, 1.0, 1.0),
                PitchedSound(XSound.ENTITY_VILLAGER_DEATH, 1.0, 1.0),
                mutableListOf(), mapOf(), mutableMapOf(), mutableMapOf(),
                "Villager", XMaterial.EMERALD, XMaterial.EMERALD_BLOCK,
                listOf("John"), listOf("Jane"), listOf("Doe"),
                RacePhrases(listOf(), listOf(), listOf(), listOf(), listOf(), listOf(), listOf(), listOf(), listOf(), listOf(), listOf(), listOf()),
                listOf("Village", "Town", "Hamlet", "Outpost", "Settlement")
            )
        }
    }

    data class SpawnItemStack(private val material: XMaterial, private val min: Int, private val max: Int) {
        fun build(): ItemStack = ItemStack(material.get()!!, Random.nextInt(min, max))
    }

    companion object {
        val racesRegistry = hashMapOf<String, Race>()

        val LivingEntity.race: Race get() {
            val config = plugin.gameplayManager.config.humanoid

            // One race mode
            if (config.oneRaceMode) {
                val forcedRace = racesRegistry[config.globalRace]
                if (forcedRace != null) return forcedRace
            }

            return racesRegistry.values.find { it.matching(this) } ?: Race.VILLAGER_RACE
        }
    }
}
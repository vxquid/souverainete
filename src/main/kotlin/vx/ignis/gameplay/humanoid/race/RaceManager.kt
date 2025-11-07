package vx.ignis.gameplay.humanoid.race

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
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.personality.PersonalityManager.Gender
import java.io.File
import kotlin.random.Random

class RaceManager {

    var races: YamlConfiguration = run {
        plugin.saveResource("races.yml", false)
        YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "races.yml"))
    }

    var skins: YamlConfiguration = run {
        plugin.saveResource("skins.yml", false)
        YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "skins.yml"))
    }

    fun loadRaces() {
        races.getKeys(false).forEach { name ->

            val section = races.getConfigurationSection(name) ?: return
            val targetEntityType = section.getString("target-entity-type") ?: return
            val targetVillagerType = section.getString("target-villager-type")
                ?.let { Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft(it.lowercase())) }
                ?: Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft("plains"))!!

            val spawnItems = mutableListOf<SpawnItemStack>()
            section.getStringList("spawn-items").let { items ->
                items.forEach { item ->
                    val (material, min, max) = item.split("-")
                    spawnItems.add(SpawnItemStack(XMaterial.valueOf(material), min.toInt(), max.toInt()))
                }
            }

            val attributes = mutableMapOf<XAttribute, Double>()
            section.getStringList("basic-attributes").let { items ->
                items.forEach { item ->
                    val (attribute, value) = item.split("-")
                    attributes[XAttribute.of(attribute).get()] = value.toDouble()
                }
            }

            val maleSkins = mutableMapOf<Float, TextureProperty>()
            val femaleSkins = mutableMapOf<Float, TextureProperty>()
            skins.getKeys(false).forEach { id ->
                skins.getConfigurationSection(id)?.let { data ->
                    val race = data.getString("race")
                    val texture = data.getString("texture")
                    val signature = data.getString("signature")
                    val gender = Gender.valueOf(data.getString("gender")!!)
                    if (race == name && texture != null && signature != null) {

                        when (gender) {
                            Gender.MALE -> maleSkins[id.toFloat()] = TextureProperty("textures", texture, signature)
                            Gender.FEMALE -> femaleSkins[id.toFloat()] = TextureProperty("textures", texture, signature)
                        }

                    }
                }
            }

            val maleVoices = mutableListOf<PitchedSound>()
            section.getStringList("sound.male.voice").forEach { voice ->
                val (sound, min, max) = voice.split("-")
                maleVoices.add(PitchedSound(XSound.of(sound).get(), min.toDouble(), max.toDouble()))
            }

            val maleHurtSound = section.getString("sound.male.hurt")!!.let {
                val (sound, min, max) = it.split("-")
                PitchedSound(XSound.of(sound).get(), min.toDouble(), max.toDouble())
            }

            val maleDeathSound = section.getString("sound.male.death")!!.let {
                val (sound, min, max) = it.split("-")
                PitchedSound(XSound.of(sound).get(), min.toDouble(), max.toDouble())
            }

            val femaleVoices = mutableListOf<PitchedSound>()
            section.getStringList("sound.female.voice").forEach { voice ->
                val (sound, min, max) = voice.split("-")
                femaleVoices.add(PitchedSound(XSound.of(sound).get(), min.toDouble(), max.toDouble()))
            }

            val femaleHurtSound = section.getString("sound.female.hurt")!!.let {
                val (sound, min, max) = it.split("-")
                PitchedSound(XSound.of(sound).get(), min.toDouble(), max.toDouble())
            }

            val femaleDeathSound = section.getString("sound.female.death")!!.let {
                val (sound, min, max) = it.split("-")
                PitchedSound(XSound.of(sound).get(), min.toDouble(), max.toDouble())
            }

            val normalCurrency = XMaterial.valueOf(section.getString("normal-currency")!!)
            val specialCurrency = XMaterial.valueOf(section.getString("special-currency")!!)

            val description = section.getString("race-description") ?: ""

            racesRegistry[name] = Race(
                name,
                XEntityType.valueOf(targetEntityType),
                targetVillagerType,
                maleVoices,
                femaleVoices,
                maleHurtSound,
                maleDeathSound,
                femaleHurtSound,
                femaleDeathSound,
                spawnItems,
                attributes,
                maleSkins,
                femaleSkins,
                description,
                normalCurrency,
                specialCurrency
            )

        }
    }

    data class PitchedSound(val sound: XSound, val min: Double, val max: Double)

    data class Race(
        val name: String,
        val targetEntityType: XEntityType,
        val targetVillagerType: Villager.Type,
        val maleVoices: List<PitchedSound>,
        val femaleVoices: List<PitchedSound>,
        val maleHurtSound: PitchedSound,
        val maleDeathSound: PitchedSound,
        val femaleHurtSound: PitchedSound,
        val femaleDeathSound: PitchedSound,
        val spawnItems: List<SpawnItemStack>,
        val attributes: Map<XAttribute, Double>,
        val maleSkins: MutableMap<Float, TextureProperty>,
        val femaleSkins: MutableMap<Float, TextureProperty>,
        val description: String = "",
        val normalCurrency: XMaterial,
        val specialCurrency: XMaterial
    ) {

        // A predicate that simplifies the verification of an entity that can be racially labeled.
        val matching: (LivingEntity) -> Boolean = { entity ->
            entity is Villager && entity.villagerType == targetVillagerType || entity !is Villager && entity.type == targetEntityType.get()
        }

        companion object {

            private val voices = listOf(
                XSound.ENTITY_WANDERING_TRADER_YES,
                XSound.ENTITY_WANDERING_TRADER_NO,
                XSound.ENTITY_VILLAGER_YES,
                XSound.ENTITY_VILLAGER_NO,
                XSound.ENTITY_VINDICATOR_AMBIENT,
                XSound.ENTITY_VINDICATOR_CELEBRATE,
                XSound.ENTITY_VILLAGER_TRADE,
                XSound.ENTITY_PILLAGER_AMBIENT,
                XSound.ENTITY_WITCH_AMBIENT
            ).map { PitchedSound(it, 0.9, 1.05) }

            // Default villager race will be used if humanoid-villagers in config.yml is false.
            val VILLAGER_RACE = Race(
                "villager",
                XEntityType.VILLAGER,
                Villager.Type.PLAINS,
                voices, voices,
                PitchedSound(XSound.ENTITY_VILLAGER_HURT, 0.95, 1.05),
                PitchedSound(XSound.ENTITY_VILLAGER_DEATH, 0.95, 1.05),
                PitchedSound(XSound.ENTITY_VILLAGER_HURT, 0.95, 1.05),
                PitchedSound(XSound.ENTITY_VILLAGER_DEATH, 0.95, 1.05),
                listOf(
                    SpawnItemStack(XMaterial.EMERALD, 32, 64),
                    SpawnItemStack(XMaterial.IRON_INGOT, 32, 64),
                    SpawnItemStack(XMaterial.LEATHER, 32, 64),
                    SpawnItemStack(XMaterial.DIAMOND, 2, 4),
                    SpawnItemStack(XMaterial.BREAD, 32, 64),
                    SpawnItemStack(XMaterial.STICK, 16, 32),
                    SpawnItemStack(XMaterial.APPLE, 32, 64)
                ), mapOf(), mutableMapOf(), mutableMapOf(), "Minecraft villagers who love emeralds and hate zombies. They are recognized for their “humming” accent and prominent noses.", XMaterial.EMERALD, XMaterial.EMERALD_BLOCK
            )
        }

    }

    data class SpawnItemStack(private val material: XMaterial, private val min: Int, private val max: Int) {
        fun build(): ItemStack = ItemStack(material.get() ?: throw NullPointerException("Error during parsing spawn item stack!"), Random.nextInt(min, max))
    }

    companion object {

        val racesRegistry = hashMapOf<String, Race>()

        val LivingEntity.race: Race
            get() {
                return racesRegistry.values.find { race ->
                    race.matching(this)
                } ?: Race.VILLAGER_RACE
            }
    }

}
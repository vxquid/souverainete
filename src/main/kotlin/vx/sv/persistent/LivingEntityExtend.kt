package vx.sv.persistent

import com.google.common.reflect.TypeToken
import com.google.gson.JsonSyntaxException
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.personality.PersonalityManager.Companion.gender
import vx.sv.gameplay.personality.PersonalityManager.Gender
import vx.sv.gameplay.quest.QuestManager.Quest
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import java.util.*
import kotlin.random.Random

object LivingEntityExtend {

    val voiceKey      = NamespacedKey(plugin, "VoiceSound")
    val pitchKey      = NamespacedKey(plugin, "VoicePitch")
    val questDataKey  = NamespacedKey(plugin, "NPCQuestData")
    val settlementKey = NamespacedKey(plugin, "NPCSettlement")
    val hungerKey     = NamespacedKey(plugin, "Hunger")

    val Villager.professionLevelName get() = when (villagerLevel) { 1 -> "NOVICE"; 2 -> "APPRENTICE"; 3 -> "JOURNEYMAN"; 4 -> "EXPERT"; else -> "MASTER" }

    fun LivingEntity.quests(): MutableList<Quest> =
        persistentDataContainer.get(questDataKey, PersistentDataType.STRING)?.let {
            try {
                val type = object : TypeToken<MutableList<Quest>>() {}.type
                gson.fromJson(it, type)
            } catch (_: JsonSyntaxException) {
                persistentDataContainer.remove(questDataKey)
                mutableListOf()
            }
        } ?: mutableListOf()

    fun LivingEntity.getVoiceSound(): Sound =
        persistentDataContainer.get(voiceKey, PersistentDataType.STRING)?.let { name ->
            // Находим ключ и запрашиваем звук нативно через SOUNDS реестр
            val key = if (name.contains(":")) NamespacedKey.fromString(name) else NamespacedKey.minecraft(name.lowercase())
            if (key != null) Registry.SOUNDS.get(key) else null
        } ?: race.let {
            val voices = if (gender == Gender.MALE) it.maleVoices else it.femaleVoices
            val sound = voices.random().sound.get() ?: throw NullPointerException()

            // Нативно получаем ключ для сохранения через Registry.SOUNDS
            val key = Registry.SOUNDS.getKey(sound) ?: throw NullPointerException("Could not resolve key for sound!")
            persistentDataContainer.set(voiceKey, PersistentDataType.STRING, key.toString())
            sound
        }

    fun LivingEntity.getVoicePitch() =
        persistentDataContainer.get(pitchKey, PersistentDataType.FLOAT) ?: race.let {
            Random.nextDouble(it.maleVoices.random().min, it.maleVoices.random().max).toFloat().also { pitch ->
                persistentDataContainer.set(pitchKey, PersistentDataType.FLOAT, pitch)
            }
        }

    fun LivingEntity.hasEdibleItem(): Boolean {
        val inv = (this as? Villager)?.inventory ?: return false
        return inv.filterNotNull().any { it.type.isEdible }
    }

    var LivingEntity.hunger: Double
        get() = persistentDataContainer.get(hungerKey, PersistentDataType.DOUBLE) ?: plugin.gameplayManager.config.hunger.max.also {
            persistentDataContainer.set(hungerKey, PersistentDataType.DOUBLE, it)
        }
        set(value) { persistentDataContainer.set(hungerKey, PersistentDataType.DOUBLE, value.coerceIn(0.0, plugin.gameplayManager.config.hunger.max)) }

    var LivingEntity.settlement: Settlement?
        get() {
            val storedData = persistentDataContainer.get(settlementKey, PersistentDataType.STRING) ?: return null

            // 1. Try to parse as UUID (New system)
            val uuid = try { UUID.fromString(storedData) } catch (_: Exception) { null }
            if (uuid != null) {
                return SettlementManager.getById(uuid)
            }

            // 2. Fallback: Search by name (Legacy system)
            val legacySettlement = SettlementManager.getByName(storedData)

            // 3. Optional Migration: Update PDC to UUID format for faster future lookups
            if (legacySettlement != null) {
                this.settlement = legacySettlement
            }

            return legacySettlement
        }
        set(value) {
            if (value != null) {
                // We always save the ID now
                persistentDataContainer.set(settlementKey, PersistentDataType.STRING, value.data.id.toString())
            } else {
                persistentDataContainer.remove(settlementKey)
            }
        }
}
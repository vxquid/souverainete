package vx.ignis.persistent

import com.cryptomorin.xseries.XSound
import com.google.common.reflect.TypeToken
import com.google.gson.JsonSyntaxException
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import vx.ignis.gameplay.personality.PersonalityManager.Companion.gender
import vx.ignis.gameplay.personality.PersonalityManager.Gender
import vx.ignis.gameplay.quest.QuestManager.Quest
import vx.ignis.gameplay.settlement.Settlement
import vx.ignis.gameplay.settlement.SettlementManager.Companion.settlements
import vx.ignis.util.InventorySerializer
import kotlin.jvm.optionals.getOrNull
import kotlin.random.Random

object LivingEntityExtend {

    val voiceKey      = NamespacedKey(plugin, "VoiceSound")
    val pitchKey      = NamespacedKey(plugin, "VoicePitch")
    val questDataKey  = NamespacedKey(plugin, "NPCQuestData")
    val inventoryKey  = NamespacedKey(plugin, "Inventory")
    val settlementKey = NamespacedKey(plugin, "NPCSettlement")
    val hungerKey     = NamespacedKey(plugin, "Hunger")

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
        persistentDataContainer.get(voiceKey, PersistentDataType.STRING)?.let {
            XSound.of(it).getOrNull()?.get()
        } ?: race.let {
            val voices = if (gender == Gender.MALE) it.maleVoices else it.femaleVoices
            (voices.random().sound.get() ?: throw NullPointerException()).also { sound -> persistentDataContainer.set(voiceKey, PersistentDataType.STRING, sound.toString()) }
        }

    fun LivingEntity.getVoicePitch() =
        persistentDataContainer.get(pitchKey, PersistentDataType.FLOAT) ?: race.let {
            Random.nextDouble(it.maleVoices.random().min, it.maleVoices.random().max).toFloat().also { pitch ->
                persistentDataContainer.set(pitchKey, PersistentDataType.FLOAT, pitch)
            }
        }

    val LivingEntity.subInventory: Inventory
        get() = persistentDataContainer.get(inventoryKey, PersistentDataType.STRING)?.let {
            InventorySerializer.inventoryFromJSON(it)
        } ?: Bukkit.createInventory(null, 54).also { inv ->
            race.spawnItems.forEach { item -> inv.addItem(item.build()) }
            persistentDataContainer.set(inventoryKey, PersistentDataType.STRING, InventorySerializer.inventoryToJSON(inv).toString())
        }

    fun LivingEntity.addItemToQuillInventory(vararg items: ItemStack) = subInventory.let { inv ->
        items.forEach { it.amount = it.amount.coerceAtMost(it.maxStackSize); inv.addItem(it) }
        persistentDataContainer.set(inventoryKey, PersistentDataType.STRING, InventorySerializer.inventoryToJSON(inv).toString())
    }

    fun LivingEntity.takeItemFromQuillInventory(item: ItemStack, amountToTake: Int) = subInventory.let { inventory: Inventory ->
        inventory.filterNotNull().find {
            it.isSimilar(item)
        }?.also {
            it.amount -= amountToTake
            persistentDataContainer.set(inventoryKey, PersistentDataType.STRING, InventorySerializer.inventoryToJSON(inventory).toString())
        }
    }

    fun LivingEntity.hasEdibleItem(): Boolean {
        return this.subInventory.filterNotNull().any { it.type.isEdible }
    }

    var LivingEntity.hunger: Double
        get() = persistentDataContainer.get(hungerKey, PersistentDataType.DOUBLE) ?: plugin.gameplayManager.config.hunger.max.also {
            persistentDataContainer.set(hungerKey, PersistentDataType.DOUBLE, it)
        }
        set(value) { persistentDataContainer.set(hungerKey, PersistentDataType.DOUBLE, value.coerceIn(0.0, plugin.gameplayManager.config.hunger.max)) }

    var LivingEntity.settlement: Settlement?
        get() = persistentDataContainer.get(settlementKey, PersistentDataType.STRING)?.let { name -> settlements[world]?.find { it.data.settlementName == name } }
        set(value) { value?.let { persistentDataContainer.set(settlementKey, PersistentDataType.STRING, it.data.settlementName) } }

}
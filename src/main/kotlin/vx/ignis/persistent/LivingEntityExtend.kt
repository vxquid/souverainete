package vx.ignis.persistent

import com.cryptomorin.xseries.XSound
import com.google.common.reflect.TypeToken
import com.google.gson.JsonSyntaxException
import org.bukkit.Bukkit
import org.bukkit.Material
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
            // 1. Сначала добавляем расовые предметы (как и было)
            race.spawnItems.forEach { item -> inv.addItem(item.build()) }

            // --- НАСТРОЙКА ВЕСОВ (Чем больше число, тем выше шанс) ---

            // Пул только для гарантированного оружия
            val weaponWeights = mapOf(
                Material.STONE_SWORD to 60,
                Material.BOW to 60,
                Material.CROSSBOW to 40,
                Material.IRON_SWORD to 25,
                Material.DIAMOND_SWORD to 5,
                Material.NETHERITE_SWORD to 1
            )

            // Общий пул (Оружие + Броня + Щиты) для дополнительных роллов
            val armorAndMiscWeights = mapOf(
                Material.LEATHER_HELMET to 80, Material.LEATHER_CHESTPLATE to 80,
                Material.LEATHER_LEGGINGS to 80, Material.LEATHER_BOOTS to 80,

                Material.CHAINMAIL_HELMET to 50, Material.CHAINMAIL_CHESTPLATE to 50,
                Material.CHAINMAIL_LEGGINGS to 50, Material.CHAINMAIL_BOOTS to 50,

                Material.SHIELD to 40,

                Material.IRON_HELMET to 20, Material.IRON_CHESTPLATE to 20,
                Material.IRON_LEGGINGS to 20, Material.IRON_BOOTS to 20,

                Material.DIAMOND_CHESTPLATE to 3, Material.NETHERITE_CHESTPLATE to 1
            )

            // Объединяем пулы для доп. предметов
            val globalPool = weaponWeights + armorAndMiscWeights

            // --- ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ ВЫБОРА ---
            fun pickWeighted(weights: Map<Material, Int>): ItemStack {
                val totalWeight = weights.values.sum()
                var random = (0 until totalWeight).random()

                for ((material, weight) in weights) {
                    random -= weight
                    if (random < 0) return ItemStack(material)
                }
                return ItemStack(weights.keys.last()) // На всякий случай
            }

            // 2. Гарантированное оружие (Меч, Лук или Арбалет)
            inv.addItem(pickWeighted(weaponWeights))

            // 3. Ролл количества дополнительных предметов (например, от 0 до 4 предметов)
            // Можно сделать тоже взвешенным: чаще выпадает 1-2 предмета, реже 4.
            val rollCountWeights = mapOf(
                0 to 10, // 10% шанс, что больше ничего не будет
                1 to 30, // 30% шанс на 1 доп. предмет
                2 to 40, // 40% шанс на 2 доп. предмета
                3 to 15, // 15% шанс на 3 доп. предмета
                4 to 5   // 5% шанс на фулл закуп
            )

            val totalRollsWeight = rollCountWeights.values.sum()
            var randomRoll = (0 until totalRollsWeight).random()
            var itemsCount = 0
            for ((count, weight) in rollCountWeights) {
                randomRoll -= weight
                if (randomRoll < 0) {
                    itemsCount = count
                    break
                }
            }

            // 4. Заполнение слотов
            repeat(itemsCount) {
                inv.addItem(pickWeighted(globalPool))
            }

            // Сохранение
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
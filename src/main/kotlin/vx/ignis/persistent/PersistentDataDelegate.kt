package vx.ignis.persistent

import com.google.gson.Gson
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import java.lang.reflect.Type

// Делегат для ленивой инициализации PDC ключей
class PersistentDataDelegate<T>(
    private val key: NamespacedKey,
    private val defaultValue: T? = null,
    private val serializer: (T) -> String,
    private val deserializer: (String) -> T
) {
    private var cachedValue: T? = null
    private var isInitialized = false

    operator fun getValue(thisRef: PersistentDataManager, property: kotlin.reflect.KProperty<*>): T {
        if (!isInitialized) {
            cachedValue = thisRef.getData(key, defaultValue, deserializer)
            isInitialized = true
        }
        return cachedValue ?: defaultValue ?: throw IllegalStateException("Value not found for key $key")
    }

    operator fun setValue(thisRef: PersistentDataManager, property: kotlin.reflect.KProperty<*>, value: T) {
        cachedValue = value
        isInitialized = true
        thisRef.setData(key, value, serializer)
    }

}

// Адаптер для GSON в PDC
class GsonPersistentDataType<T : Any>(
    private val gson: Gson,
    private val type: Type
) : PersistentDataType<String, T> {
    override fun getPrimitiveType(): Class<String> = String::class.java

    override fun getComplexType(): Class<T> = type as Class<T>

    override fun toPrimitive(complex: T, context: PersistentDataAdapterContext): String {
        return gson.toJson(complex)
    }

    override fun fromPrimitive(primitive: String, context: PersistentDataAdapterContext): T {
        return gson.fromJson(primitive, type)
    }
}

// Основной менеджер персистентных данных
open class PersistentDataManager(
    private val entity: LivingEntity
) {
    private val pdc: PersistentDataContainer
        get() = entity.persistentDataContainer

    // Базовые методы для работы с данными
    fun <T> setData(key: NamespacedKey, value: T, serializer: (T) -> String = { it.toString() }) {
        pdc.set(key, PersistentDataType.STRING, serializer(value))
    }

    fun <T> getData(key: NamespacedKey, defaultValue: T? = null, deserializer: (String) -> T): T {
        return pdc.get(key, PersistentDataType.STRING)?.let(deserializer) ?: defaultValue
            ?: throw IllegalArgumentException("Data not found for key $key")
    }

    fun <T> getDataOrNull(key: NamespacedKey, deserializer: (String) -> T): T? {
        return pdc.get(key, PersistentDataType.STRING)?.let(deserializer)
    }

    // Методы для работы с GSON
    fun <T : Any> setGsonData(key: NamespacedKey, value: T, type: Type) {
        pdc.set(key, GsonPersistentDataType(gson, type), value)
    }

    fun <T : Any> getGsonData(key: NamespacedKey, type: Type): T? {
        return pdc.get(key, GsonPersistentDataType(gson, type))
    }

    fun <T : Any> getGsonData(key: NamespacedKey, type: Type, defaultValue: T): T {
        return getGsonData(key, type) ?: defaultValue
    }

    // Удаление данных
    fun removeData(key: NamespacedKey) {
        pdc.remove(key)
    }

    // Проверка наличия данных
    fun hasData(key: NamespacedKey): Boolean {
        return pdc.has(key)
    }

    // Очистка всех данных
    fun clearAllData() {
        pdc.keys.forEach { key ->
            if (key.namespace == plugin.name.lowercase()) {
                pdc.remove(key)
            }
        }
    }

    // Создание ключа с автоматическим неймспейсом плагина
    fun createKey(key: String): NamespacedKey {
        return NamespacedKey(plugin, key)
    }

    companion object {
        // Extension function для LivingEntity
        fun LivingEntity.getDataManager(): PersistentDataManager {
            return PersistentDataManager(this)
        }
    }
}

// Extension functions для удобной работы с PDC
fun <T> PersistentDataManager.persistentProperty(
    key: String,
    defaultValue: T? = null,
    serializer: (T) -> String = { it.toString() },
    deserializer: (String) -> T
): PersistentDataDelegate<T> {
    return PersistentDataDelegate(createKey(key), defaultValue, serializer, deserializer)
}

// Специализированные делегаты для распространенных типов
fun PersistentDataManager.intProperty(key: String, defaultValue: Int = 0) =
    persistentProperty(key, defaultValue, { it.toString() }, { it.toInt() })

fun PersistentDataManager.doubleProperty(key: String, defaultValue: Double = 0.0) =
    persistentProperty(key, defaultValue, { it.toString() }, { it.toDouble() })

fun PersistentDataManager.booleanProperty(key: String, defaultValue: Boolean = false) =
    persistentProperty(key, defaultValue, { it.toString() }, { it.toBoolean() })

fun PersistentDataManager.stringProperty(key: String, defaultValue: String = "") =
    persistentProperty(key, defaultValue, { it }, { it })

fun PersistentDataManager.longProperty(key: String, defaultValue: Long = 0L) =
    persistentProperty(key, defaultValue, { it.toString() }, { it.toLong() })

fun PersistentDataManager.floatProperty(key: String, defaultValue: Float = 0f) =
    persistentProperty(key, defaultValue, { it.toString() }, { it.toFloat() })

// Делегат для GSON объектов
class GsonPersistentDataDelegate<T : Any>(
    private val key: NamespacedKey,
    private val type: Type,
    private val defaultValue: T? = null
) {
    private var cachedValue: T? = null
    private var isInitialized = false

    operator fun getValue(thisRef: PersistentDataManager, property: kotlin.reflect.KProperty<*>): T {
        if (!isInitialized) {
            cachedValue = thisRef.getGsonData(key, type, defaultValue ?: throw IllegalStateException("Value not found for key $key"))
            isInitialized = true
        }
        return cachedValue!!
    }

    operator fun setValue(thisRef: PersistentDataManager, property: kotlin.reflect.KProperty<*>, value: T) {
        cachedValue = value
        isInitialized = true
        thisRef.setGsonData(key, value, type)
    }
}

// Extension для GSON делегатов
fun <T : Any> PersistentDataManager.gsonProperty(key: String, type: Type, defaultValue: T? = null) =
    GsonPersistentDataDelegate(createKey(key), type, defaultValue)

// Пример использования
data class PlayerStats(
    val kills: Int = 0,
    val deaths: Int = 0,
    val level: Int = 1,
    val lastPlayed: Long = System.currentTimeMillis()
)

// Пример класса, использующего PersistentDataManager
class EntityDataManager(entity: LivingEntity) : PersistentDataManager(entity) {

    // Ленивые свойства с делегатами
    var healthMultiplier by this.doubleProperty("health_multiplier", 1.0)
    var isBoss by this.booleanProperty("is_boss")
    var customName by this.stringProperty("custom_name", "Unknown")
    var spawnTime by this.longProperty("spawn_time", System.currentTimeMillis())

    // Сложный объект через GSON
    var playerStats by this.gsonProperty("player_stats", PlayerStats::class.java, PlayerStats())

    // Кастомная сериализация
    var stringList by this.persistentProperty(
        key = "string_list",
        defaultValue = emptyList(),
        serializer = { it.joinToString(";;") },
        deserializer = { if (it.isEmpty()) emptyList() else it.split(";;") }
    )
}

// Пример использования в плагине
fun exampleUsage(entity: LivingEntity, plugin: Plugin) {

    val entityData = EntityDataManager(entity)
    
    // Работа с простыми данными
    entityData.healthMultiplier = 2.5
    entityData.isBoss = true
    entityData.customName = "Могущественный Босс"
    
    // Работа с GSON объектами
    val stats = entityData.playerStats
    entityData.playerStats = stats.copy(kills = stats.kills + 1, level = stats.level + 1)
    
    // Работа со списками
    entityData.stringList = listOf("item1", "item2", "item3")
    
    // Проверка наличия данных
    if (entityData.hasData(entityData.createKey("custom_name"))) {
        println("Custom name exists: ${entityData.customName}")
    }

}
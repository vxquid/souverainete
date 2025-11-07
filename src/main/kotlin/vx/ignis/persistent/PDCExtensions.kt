package vx.ignis.persistent

import com.google.gson.reflect.TypeToken
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.gson

class PDCExtensions(val pdc: PersistentDataContainer) {
    
    // Для списков
    inline fun <reified T> getList(key: NamespacedKey, default: List<T> = emptyList()): List<T> {
        return pdc.get(key, PersistentDataType.STRING)?.let { json ->
            gson.fromJson<List<T>>(json, object : TypeToken<List<T>>() {}.type)
        } ?: default.also { setList(key, it) }
    }
    
    inline fun <reified T> setList(key: NamespacedKey, list: List<T>) {
        pdc.set(key, PersistentDataType.STRING, gson.toJson(list))
    }
    
    // Для мап
    inline fun <reified K, reified V> getMap(key: NamespacedKey, default: Map<K, V> = emptyMap()): Map<K, V> {
        return pdc.get(key, PersistentDataType.STRING)?.let { json ->
            gson.fromJson<Map<K, V>>(json, object : TypeToken<Map<K, V>>() {}.type)
        } ?: default.also { setMap(key, it) }
    }
    
    inline fun <reified K, reified V> setMap(key: NamespacedKey, map: Map<K, V>) {
        pdc.set(key, PersistentDataType.STRING, gson.toJson(map))
    }
    
    // Для любых объектов
    inline fun <reified T> getObject(key: NamespacedKey, default: T? = null): T? {
        return pdc.get(key, PersistentDataType.STRING)?.let { json ->
            gson.fromJson(json, T::class.java)
        } ?: default
    }
    
    inline fun <reified T> setObject(key: NamespacedKey, obj: T) {
        pdc.set(key, PersistentDataType.STRING, gson.toJson(obj))
    }
    
    // Удобные операции со списками
    inline fun <reified T> addToList(key: NamespacedKey, item: T) {
        val list = getList<T>(key).toMutableList()
        list.add(item)
        setList(key, list)
    }
    
    inline fun <reified T> removeFromList(key: NamespacedKey, item: T) {
        val list = getList<T>(key).toMutableList()
        list.remove(item)
        setList(key, list)
    }
    
    inline fun <reified T> containsInList(key: NamespacedKey, item: T): Boolean {
        return getList<T>(key).contains(item)
    }
}

// Или ещё короче:
val Player.pdc get() = PDCExtensions(this.persistentDataContainer)
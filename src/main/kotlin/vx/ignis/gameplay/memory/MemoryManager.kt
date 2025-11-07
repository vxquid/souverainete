package vx.ignis.gameplay.memory

import org.bukkit.NamespacedKey
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import java.util.*

class MemoryManager {

    data class Memory(
        val opinions: MutableMap<UUID, String> = mutableMapOf(),
        val shortMemory: MutableList<String> = mutableListOf()
    ) {

        fun save(villager: Villager) {
            villager.persistentDataContainer.set(memoryKey, PersistentDataType.STRING, this.toJson())
        }

        fun toJson(): String {
            return gson.toJson(this)
        }

        companion object {

            fun fromJson(json: String): Memory {
                return gson.fromJson(json, Memory::class.java)
            }

        }

    }

    companion object {

        val memoryKey = NamespacedKey(plugin, "Memory")

        fun Villager.getEmotionalMemory() : Memory {
            return this.persistentDataContainer.get(memoryKey, PersistentDataType.STRING)?.let {
                Memory.fromJson(it)
            } ?: Memory().also {
                this.persistentDataContainer.set(memoryKey, PersistentDataType.STRING, it.toJson())
            }
        }
    }

}
package vx.ignis.gameplay.dictionary

import de.exlll.configlib.YamlConfigurations
import org.bukkit.Material
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.properties
import vx.ignis.config.DictionaryConfiguration
import java.io.File
import java.nio.file.Path

class CustomItemDictionary : Listener {

    private val dictionary: DictionaryConfiguration = run {
        YamlConfigurations.update(path, DictionaryConfiguration::class.java, properties)
        YamlConfigurations.load(path, DictionaryConfiguration::class.java, properties)
    }

    private fun saveDictionary(): Boolean {
        return try {
            YamlConfigurations.save(path, DictionaryConfiguration::class.java, dictionary, properties)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Либо вытаскиваем предмет из словаря с кастомными предметами, либо используем дефолтный материал (цена в таком случае берётся из prices.yml!)
    fun getItem(key: String): CustomItem {
        dictionary.dictionary[key]?.let { return gson.fromJson(it, CustomItem::class.java) }
        return try {
            val material = Material.valueOf(key)
            val score = plugin.prices.getLong(key)
            if (score == 0L) {
                run {
                    plugin.logger.warning("Error retrieving cost! The item {item} is missing from dictionary.yml, meaning it is not a custom item, and its price is not specified in prices.yml. Please fix this!".replace("{item}", key))
                    throw IllegalStateException()
                }
            }
            CustomItem(key = key, score = score, item = ItemStack(material))
        } catch (e: IllegalArgumentException) {
            plugin.logger.severe("Invalid item key: $key. No custom item or Material found.")
            throw IllegalArgumentException("Invalid item key: $key")
        }
    }

    fun addItem(key: String, score: Long, item: ItemStack): Boolean {
        if (dictionary.dictionary.containsKey(key)) {
            return false
        }
        dictionary.dictionary[key] = gson.toJson(CustomItem(key, score, item.clone()))
        return saveDictionary()
    }

    fun removeItemByKey(key: String): Boolean {
        if (!dictionary.dictionary.containsKey(key)) {
            return false
        }
        dictionary.dictionary.remove(key)
        return saveDictionary()
    }

    fun getItemList(): List<String> {
        return dictionary.dictionary.keys.toList()
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    companion object {

        private val path: Path = File(plugin.dataFolder, "dictionary.yml").toPath()

        fun createRedDiamond(): CustomItem {
            val item = ItemStack(Material.DIAMOND)
            item.itemMeta = item.itemMeta?.apply {
                setDisplayName("§cRed Diamond")
                lore = listOf("§7An incredibly rare red diamond.")
            }
            return CustomItem("RED_DIAMOND", 2000, item)
        }

        fun createSpecialCoin(): CustomItem {
            val item = ItemStack(Material.SUNFLOWER)
            item.itemMeta = item.itemMeta?.apply {
                setDisplayName("§eSpecial Coin")
                lore = listOf("§7Use this item to obtain 200 chimes.")
            }
            return CustomItem("SPECIAL_COIN", 200, item)
        }

    }

}

package vx.ignis

import co.aikar.commands.PaperCommandManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import de.exlll.configlib.ConfigLib
import de.exlll.configlib.YamlConfigurationProperties
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import vx.ignis.ai.ProviderManager
import vx.ignis.command.DictionaryCommand
import vx.ignis.command.QuestCommand
import vx.ignis.gameplay.GameplayManager
import vx.ignis.gameplay.settlement.SettlementManager.Companion.settlements
import vx.ignis.gameplay.settlement.SettlementManager.Companion.settlementsWorldKey
import vx.ignis.serialization.ItemStackSerializer
import vx.ignis.serialization.LocationSerializer
import vx.ignis.serialization.UUIDSerializer
import vx.ignis.util.RainbowColorTicker
import java.io.File
import java.util.*

class Ignis : JavaPlugin(), Listener {

    lateinit var providerManager: ProviderManager
    lateinit var gameplayManager: GameplayManager
    lateinit var commandManager:  PaperCommandManager

    var language: YamlConfiguration = run {
        super.saveResource("language.yml", false)
        YamlConfiguration.loadConfiguration(File(super.getDataFolder(), "language.yml"))
    }

    var prompts: YamlConfiguration = run {
        super.saveResource("prompts.yml", false)
        YamlConfiguration.loadConfiguration(File(super.getDataFolder(), "prompts.yml"))
    }

    var prices: YamlConfiguration = run {
        super.saveResource("prices.yml", false)
        YamlConfiguration.loadConfiguration(File(super.getDataFolder(), "prices.yml"))
    }

    var professions: YamlConfiguration = run {
        super.saveResource("professions.yml", false)
        YamlConfiguration.loadConfiguration(File(super.getDataFolder(), "professions.yml"))
    }

    override fun onEnable() {
        RainbowColorTicker.init()
        this.providerManager = ProviderManager()
        this.server.pluginManager.registerEvents(this, this)
    }

    override fun onDisable() {
        gameplayManager.allowedWorlds.forEach { world ->
            world.persistentDataContainer.set(settlementsWorldKey, PersistentDataType.STRING, gson.toJson(settlements[world]?.map { it.data }))
        }
        Bukkit.getWorlds().getOrNull(0)?.persistentDataContainer?.set(
            NamespacedKey(this, "ActualQuests"),
            PersistentDataType.LONG_ARRAY,
            gameplayManager.actualQuests.toLongArray()
        )
    }

    /* Gameplay-related stuff must be initialized post-world. */
    @EventHandler
    private fun onFirstWorldLoad(event: WorldLoadEvent) {
        if (server.worlds.indexOf(event.world) == 0) {
            this.gameplayManager = GameplayManager(event.world)
            this.commandManager = PaperCommandManager(this)

            // Command registration
            commandManager.registerCommand(QuestCommand())
            commandManager.registerCommand(DictionaryCommand())
        }
    }

    init {
        plugin = this
        gson = GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Location::class.java, LocationSerializer())
            .registerTypeAdapter(ItemStack::class.java, ItemStackSerializer())
            .registerTypeAdapter(UUID::class.java, UUIDSerializer())
            .create()
    }

    companion object {
        lateinit var plugin: Ignis
        lateinit var gson: Gson

        val properties: YamlConfigurationProperties = ConfigLib
            .BUKKIT_DEFAULT_PROPERTIES
            .toBuilder()
            .build()

        fun Player.sendFormattedMessage(message: String) {
            this.sendMessage(plugin.gameplayManager.config.messagePrefix + " " + message)
        }
    }

}
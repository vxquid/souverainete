package vx.ignis

import co.aikar.commands.PaperCommandManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import vx.ignis.ai.ProviderManager
import vx.ignis.command.DictionaryCommand
import vx.ignis.command.QuestCommand
import vx.ignis.config.lib.TranslationManager
import vx.ignis.gameplay.GameplayManager
import vx.ignis.gameplay.settlement.SettlementManager.Companion.settlements
import vx.ignis.gameplay.settlement.SettlementManager.Companion.settlementsWorldKey
import vx.ignis.serialization.ItemStackSerializer
import vx.ignis.serialization.LocationSerializer
import vx.ignis.serialization.UUIDSerializer
import vx.ignis.util.GeyserSupportProvider
import vx.ignis.util.Metrics
import vx.ignis.util.RainbowColorTicker
import vx.ignis.util.UpdateChecker
import java.io.File
import java.util.*

class Ignis : JavaPlugin(), Listener {

    lateinit var providerManager: ProviderManager
    lateinit var gameplayManager: GameplayManager
    lateinit var commandManager:  PaperCommandManager
    lateinit var translationManager: TranslationManager

    var geyserProvider: GeyserSupportProvider? = null
        get() = if (server.pluginManager.isPluginEnabled("Geyser-Spigot")) {
            if (field == null) GeyserSupportProvider().also { field = it } else field
        } else null

    var language: YamlConfiguration = run {
        val file = File(super.getDataFolder(), "language.yml")
        if (!file.exists()) super.saveResource("language.yml", false)
        YamlConfiguration.loadConfiguration(file)
    }

    var prompts: YamlConfiguration = run {
        val file = File(super.getDataFolder(), "prompts.yml")
        if (!file.exists()) super.saveResource("prompts.yml", false)
        YamlConfiguration.loadConfiguration(file)
    }

    var prices: YamlConfiguration = run {
        val file = File(super.getDataFolder(), "prices.yml")
        if (!file.exists()) super.saveResource("prices.yml", false)
        YamlConfiguration.loadConfiguration(file)
    }

    var professions: YamlConfiguration = run {
        val file = File(super.getDataFolder(), "professions.yml")
        if (!file.exists()) super.saveResource("professions.yml", false)
        YamlConfiguration.loadConfiguration(file)
    }

    private var updateAvailable: Boolean = false
    private var latestVersion: String? = null

    override fun onEnable() {

        RainbowColorTicker.init()
        this.providerManager = ProviderManager()
        this.translationManager = TranslationManager(this, providerManager.client, providerManager.config.language)
        this.server.pluginManager.registerEvents(this, this)

        // Translate language.yml using cache. Must be async.
        this.server.scheduler.runTaskAsynchronously(this) { _ ->
            val languageFile = File(dataFolder, "language.yml")
            language = translationManager.getTranslated(languageFile)
        }

        // Metrics!
        Metrics(this, 27976)

        // Update checking.
        UpdateChecker(121059).getVersion { remoteVersion ->
            @Suppress("DEPRECATION") val currentVersion = description.version
            val comparison = UpdateChecker.compareVersions(currentVersion, remoteVersion)
            if (comparison >= 0) {
                logger.info("You are running the latest release of Ignis ($currentVersion).")
                updateAvailable = false
            } else {
                logger.info("New version ($remoteVersion) is available. Please, consider updating.")
                updateAvailable = true
                latestVersion = remoteVersion
            }
        }
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

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (event.player.hasPermission("ignis.update")) {
            if (latestVersion != null) {
                if (updateAvailable) {
                    val updateMsg = language.getString("update.new-version-available", "§cA new version of §6Ignis §cis available: §e{newVersion}§c! Please update.")
                        ?.replace("{newVersion}", latestVersion!!)
                    event.player.sendFormattedMessage(updateMsg ?: "New version available: $latestVersion")
                }
            }
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

        val premium: Boolean = false
        lateinit var plugin: Ignis
        lateinit var gson: Gson

        fun Player.sendFormattedMessage(message: String) {
            this.sendMessage(plugin.gameplayManager.config.general.messagePrefix + " " + message)
        }
    }

}
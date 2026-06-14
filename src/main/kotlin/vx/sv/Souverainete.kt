package vx.sv

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
import vx.sv.ai.ProviderManager
import vx.sv.command.*
import vx.sv.config.lib.TranslationManager
import vx.sv.gameplay.GameplayManager
import vx.sv.gameplay.settlement.SettlementManager.Companion.settlements
import vx.sv.gameplay.settlement.SettlementManager.Companion.settlementsWorldKey
import vx.sv.gameplay.trade.ScoreCalculator
import vx.sv.nms.v1_21_R7.entity.ai.construct.*
import vx.sv.serialization.ItemStackSerializer
import vx.sv.serialization.LocationSerializer
import vx.sv.serialization.UUIDSerializer
import vx.sv.util.GeyserSupportProvider
import vx.sv.util.Metrics
import vx.sv.util.RainbowColorTicker
import vx.sv.util.UpdateChecker
import java.io.File
import java.util.*

class Souverainete : JavaPlugin(), Listener {

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
        ScoreCalculator.init()
        RainbowColorTicker.init()
        this.providerManager = ProviderManager()
        this.translationManager = TranslationManager(this, providerManager.client, providerManager.config.language)
        this.server.pluginManager.registerEvents(this, this)

        // Translate language.yml using cache. Must be async.
        this.server.scheduler.runTaskAsynchronously(this) { _ ->
            val languageFile = File(dataFolder, "language.yml")
            language = translationManager.getTranslated(languageFile)
        }

        // --- УСТАНОВКА МЕТРИК BSTATS ---
        val metrics = Metrics(this, 27976)

        // 1. Наиболее используемый ИИ провайдер
        metrics.addCustomChart(Metrics.SimplePie("ai_provider") {
            providerManager.config.providerType.name
        })

        // 2. Используемый язык генерации контента
        metrics.addCustomChart(Metrics.SimplePie("ai_language") {
            providerManager.config.language
        })

        // 3. Формат диалогов
        metrics.addCustomChart(Metrics.SimplePie("dialogue_format") {
            if (::gameplayManager.isInitialized) gameplayManager.config.dialogue.dialogueFormat.name else "Unknown"
        })

        // 4. Стратегия смерти компаньонов
        metrics.addCustomChart(Metrics.SimplePie("death_strategy") {
            if (::gameplayManager.isInitialized) gameplayManager.config.party.deathHandleStrategy else "Unknown"
        })

        // 5. Статус кастомных скинов
        metrics.addCustomChart(Metrics.SimplePie("humanoid_npcs") {
            if (::gameplayManager.isInitialized) {
                if (gameplayManager.config.humanoid.humanoidVillagers) "Enabled" else "Disabled"
            } else "Unknown"
        })

        // 6. Статус ванильной торговли
        metrics.addCustomChart(Metrics.SimplePie("vanilla_trading") {
            if (::gameplayManager.isInitialized) {
                if (gameplayManager.config.general.vanillaTrading) "Enabled" else "Disabled"
            } else "Unknown"
        })

        // 7. Общее количество поселений на сервере
        metrics.addCustomChart(Metrics.SingleLineChart("total_settlements") {
            if (::gameplayManager.isInitialized) {
                settlements.values.sumOf { it.size }
            } else 0
        })
        // --- КОНЕЦ БЛОКА МЕТРИК ---

        // Update checking.
        UpdateChecker(121059).getVersion { remoteVersion ->
            @Suppress("DEPRECATION") val currentVersion = description.version
            val comparison = UpdateChecker.compareVersions(currentVersion, remoteVersion)
            if (comparison >= 0) {
                logger.info("You are running the latest release of Souverainete ($currentVersion).")
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

            // ИСПРАВЛЕНО: Безопасно сохраняем всю разметку, очереди и активные сессии планировщика для каждого мира
            SettlementPlanner.saveBuildingsToWorld(world)
        }
        Bukkit.getWorlds().getOrNull(0)?.persistentDataContainer?.set(
            NamespacedKey(this, "ActualQuests"),
            PersistentDataType.LONG_ARRAY,
            gameplayManager.actualQuests.toLongArray()
        )
        gameplayManager.raidManager.disable()
    }

    @EventHandler
    private fun onFirstWorldLoad(event: WorldLoadEvent) {
        if (server.worlds.indexOf(event.world) == 0) {
            this.gameplayManager = GameplayManager(event.world)
            this.commandManager = PaperCommandManager(this)

            // Command registration
            commandManager.registerCommand(QuestCommand())
            commandManager.registerCommand(TranslateCommand())
            commandManager.registerCommand(SettlementCommand())
            commandManager.registerCommand(SettingsCommand())

            // Register GUI listener
            server.pluginManager.registerEvents(SettingsGUIListener(), this)

            // build test
            server.pluginManager.registerEvents(BuildTestListener(), this)
            server.pluginManager.registerEvents(BuildSaveListener(), this)
            server.pluginManager.registerEvents(VillageGenerationListener(), this)
            server.pluginManager.registerEvents(WoodFarmManager(), this)
            server.pluginManager.registerEvents(BuilderSafetyListener(), this)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        // Handle admin updates alert
        if (event.player.hasPermission("sv.update")) {
            if (latestVersion != null && updateAvailable) {
                val updateMsg = language.getString("update.new-version-available", "§cA new version of §6Souverainete §cis available: §e{newVersion}§c! Please update.")
                    ?.replace("{newVersion}", latestVersion!!)
                event.player.sendFormattedMessage(updateMsg ?: "New version available: $latestVersion")
            }
        }

        // --- NEW: Settings Welcome Hint ---
        val welcomeMsg = language.getString(
            "info-messages.welcome-settings",
            "§8[Souverainete] §7Server is running Souverainete. Customize your experience using §e/s settings§7."
        )
        welcomeMsg?.let { event.player.sendMessage(it) }
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

        val premium: Boolean = BuildConfig.IS_PREMIUM
        lateinit var plugin: Souverainete
        lateinit var gson: Gson

        fun Player.sendFormattedMessage(message: String) {
            this.sendMessage(plugin.gameplayManager.config.general.messagePrefix + " " + message)
        }
    }

}
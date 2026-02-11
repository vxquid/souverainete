package vx.sv.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.config.ProviderConfiguration.ProviderType
import vx.sv.config.lib.ConfigurationManager
import vx.sv.config.lib.TranslationManager
import vx.sv.config.lib.TranslationManager.TranslationResult
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@CommandAlias("s|sv")
class TranslateCommand : BaseCommand(), Listener {

    private val isRunning = AtomicBoolean(false)
    private val queueFile = File(plugin.dataFolder, "cache/queue.yml")
    private var bossBar: BossBar? = null

    private enum class SetupStep { KEY, LANGUAGE, SETTING, NAMING }
    private data class Session(val type: ProviderType, var step: SetupStep)

    private val setupSessions = mutableMapOf<UUID, Session>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.commandManager.commandCompletions.registerCompletion("providers") {
            ProviderType.entries.map { it.name }
        }
    }

    private companion object {
        const val SETUP_PREFIX = "§6[AI Setup] "
        const val STEP_PREFIX = "§e[Step {n}/4] "

        const val MSG_START = "$SETUP_PREFIX§7Select your provider: §e/s provider <type>"
        const val MSG_LIST = "§7Available: §fGROQ (recommended), GEMINI, OPENROUTER, DEEPSEEK, CHATGPT, ANYTHINGLLM"
        const val MSG_INVALID = "§cInvalid provider type!"
        const val MSG_CANCELLED = "§c[AI Setup] Setup cancelled."

        const val STEP_1_PROMPT = "${STEP_PREFIX}§fPaste your §6API Key §fin chat. §7(It will be hidden)."
        const val STEP_2_PROMPT = "${STEP_PREFIX}§fType the §6Language §ffor generation (e.g., English, Russian, Japanese, Elvish, Gibberish, Anything):"
        const val STEP_3_PROMPT = "${STEP_PREFIX}§fType the §6Thematic Setting §f(e.g., Medieval Fantasy, Sci-Fi):"
        const val STEP_4_PROMPT = "${STEP_PREFIX}§fType the §6Naming Style §f(e.g., Fantasy Names, Nordic):"

        const val MSG_LOCALIZING = "$SETUP_PREFIX§7Setup complete! Localizing §elanguage.yml §7to §b{lang}§7. Please wait..."
        const val MSG_SUCCESS = "§a[AI Setup] Configuration complete! Provider: §6{provider} §a| Model: §e{model}"
        const val MSG_SUGGESTION = "$SETUP_PREFIX§7It's recommended to translate the plugin content using §e/s translate§7."

        // Новые сообщения для режима отключения
        const val MSG_DISABLED_HEADER = "$SETUP_PREFIX§cAI features have been disabled."
        const val MSG_DISABLED_MODE = "§7Souveraineté is now running in §eDeterministic Mode§7."
        const val MSG_DISABLED_INFO_1 = "§7- Generative translation: §cOFF"
        const val MSG_DISABLED_INFO_2 = "§7- Dynamic Quests: §cOFF"
        const val MSG_DISABLED_INFO_3 = "§7- Content will be loaded strictly from local configuration files."

        // Сообщения с просьбой о поддержке
        const val MSG_DONATE_DIVIDER = "§8§m--------------------------------------------------"
        const val MSG_DONATE_1 = "§7I have been developing §6Souveraineté §7solo for over §e1.5 years§7,"
        const val MSG_DONATE_2 = "§7pouring hundreds of hours into creating this experience."
        const val MSG_DONATE_3 = "§7If you enjoy the plugin, please consider §abuying the full version"
        const val MSG_DONATE_4 = "§7or supporting the development via Ko-fi:"
        const val MSG_DONATE_LINK = "§b➤ https://ko-fi.com/vxquid"
    }

    @Subcommand("setup")
    @CommandPermission("sv.admin.setup")
    fun onSetup(player: Player) {
        player.sendFormattedMessage(MSG_START)
        player.sendFormattedMessage(MSG_LIST)
    }

    // --- Новая команда для отключения ИИ ---
    @Subcommand("disable ai")
    @CommandPermission("sv.admin.setup")
    fun onDisableAI(player: Player) {
        // Если игрок был в процессе настройки, отменяем её
        if (setupSessions.containsKey(player.uniqueId)) {
            setupSessions.remove(player.uniqueId)
        }

        val config = plugin.providerManager.config

        // Устанавливаем маркер отключения в API Key
        config.apiKey = "DISABLED"

        // Сохраняем конфигурацию
        ConfigurationManager.save(plugin, config)

        // Информируем игрока о режиме
        player.sendFormattedMessage(MSG_DISABLED_HEADER)
        player.sendFormattedMessage(MSG_DISABLED_MODE)
        player.sendFormattedMessage(MSG_DISABLED_INFO_1)
        player.sendFormattedMessage(MSG_DISABLED_INFO_2)
        player.sendFormattedMessage(MSG_DISABLED_INFO_3)

        // Просьба о поддержке
        sendSupportMessage(player)
    }
    // ---------------------------------------

    @Subcommand("provider")
    @CommandPermission("sv.admin.setup")
    @CommandCompletion("@providers")
    fun onSelectProvider(player: Player, @Values("@providers") type: String) {
        val providerType = try { ProviderType.valueOf(type.uppercase()) } catch (e: Exception) {
            player.sendFormattedMessage(MSG_INVALID)
            return
        }

        // Configuration and URL mapping
        val (url, defaultModel) = when (providerType) {
            ProviderType.GEMINI -> "https://aistudio.google.com/app/apikey" to "google/gemini-2.5-flash-lite"
            ProviderType.GROQ -> "https://console.groq.com/keys" to "openai/gpt-oss-120b"
            ProviderType.OPENROUTER -> "https://openrouter.ai/keys" to "deepseek/deepseek-r1-0528:free"
            ProviderType.DEEPSEEK -> "https://platform.deepseek.com/api_keys" to "deepseek-chat"
            ProviderType.CHATGPT -> "https://platform.openai.com/api-keys" to "gpt-4o-mini"
            ProviderType.ANYTHINGLLM -> "Workspace API settings" to "gpt-3.5-turbo"
        }

        // Automatically set the recommended model for the provider
        plugin.providerManager.config.model = defaultModel
        plugin.providerManager.config.providerType = providerType

        setupSessions[player.uniqueId] = Session(providerType, SetupStep.KEY)

        player.sendFormattedMessage("$SETUP_PREFIX§7Selected §e${providerType.name}§7. URL: §b$url")
        player.sendFormattedMessage("$SETUP_PREFIX§7Recommended model §a$defaultModel §7has been automatically selected.")
        player.sendFormattedMessage(STEP_1_PROMPT.replace("{n}", "1"))
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChatInterceptor(event: AsyncPlayerChatEvent) {
        val player = event.player
        val session = setupSessions[player.uniqueId] ?: return

        event.isCancelled = true
        val input = event.message.trim()

        if (input.equals("cancel", true)) {
            setupSessions.remove(player.uniqueId)
            player.sendFormattedMessage(MSG_CANCELLED)
            return
        }

        val config = plugin.providerManager.config

        when (session.step) {
            SetupStep.KEY -> {
                config.apiKey = input
                session.step = SetupStep.LANGUAGE
                player.sendFormattedMessage(STEP_2_PROMPT.replace("{n}", "2"))
            }
            SetupStep.LANGUAGE -> {
                config.language = input
                session.step = SetupStep.SETTING
                player.sendFormattedMessage(STEP_3_PROMPT.replace("{n}", "3"))
            }
            SetupStep.SETTING -> {
                config.setting = input
                session.step = SetupStep.NAMING
                player.sendFormattedMessage(STEP_4_PROMPT.replace("{n}", "4"))
            }
            SetupStep.NAMING -> {
                config.namingStyle = input

                // Final save
                ConfigurationManager.save(plugin, config)
                setupSessions.remove(player.uniqueId)

                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.providerManager.load()
                    plugin.translationManager = TranslationManager(
                        plugin,
                        plugin.providerManager.client,
                        plugin.providerManager.config.language
                    )

                    player.sendFormattedMessage(MSG_LOCALIZING.replace("{lang}", config.language))

                    plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                        val languageFile = File(plugin.dataFolder, "language.yml")
                        plugin.language = plugin.translationManager.getTranslated(languageFile)

                        plugin.server.scheduler.runTask(plugin, Runnable {
                            player.sendFormattedMessage(MSG_SUCCESS
                                .replace("{provider}", config.providerType.name)
                                .replace("{model}", config.model))
                            player.sendFormattedMessage(MSG_SUGGESTION)

                            // Сообщение о поддержке после успешной настройки
                            sendSupportMessage(player)
                        })
                    })
                })
            }
        }
    }

    // Вспомогательная функция для вывода сообщения о донате
    private fun sendSupportMessage(player: Player) {
        player.sendFormattedMessage(" ")
        player.sendFormattedMessage(MSG_DONATE_DIVIDER)
        player.sendFormattedMessage(MSG_DONATE_1)
        player.sendFormattedMessage(MSG_DONATE_2)
        player.sendFormattedMessage(MSG_DONATE_3)
        player.sendFormattedMessage(MSG_DONATE_4)
        player.sendFormattedMessage(MSG_DONATE_LINK)
        player.sendFormattedMessage(MSG_DONATE_DIVIDER)
    }

    // --- Bulk Translation Logic ---

    @Subcommand("translate")
    @CommandPermission("sv.admin.translate")
    fun onTranslateInfo(player: Player) {
        if (isRunning.get()) {
            player.sendFormattedMessage(plugin.language.getString("command-message.translate.already-running")!!)
            return
        }

        if (queueFile.exists()) {
            player.sendFormattedMessage(plugin.language.getString("command-message.translate.resume-info")!!)
        }

        val races = plugin.gameplayManager.humanoidManager.raceManager.races.getKeys(false)
        val fileCount = races.size * 2

        player.sendFormattedMessage(plugin.language.getString("command-message.translate.warning")!!
            .replace("{count}", fileCount.toString()))
        player.sendFormattedMessage(plugin.language.getString("command-message.translate.accept-prompt")!!)
    }

    @Subcommand("translate accept|resume")
    @CommandPermission("sv.admin.translate")
    fun onAccept(player: Player) {
        if (isRunning.get()) return

        if (!queueFile.exists()) {
            val config = YamlConfiguration()
            val races = plugin.gameplayManager.humanoidManager.raceManager.races.getKeys(false)
            val tasks = mutableListOf<String>()
            races.forEach {
                tasks.add("$it:names.yml")
                tasks.add("$it:phrases.yml")
            }
            config.set("pending", tasks)
            config.set("total", tasks.size)
            config.save(queueFile)
        }

        isRunning.set(true)
        startTranslationWorker(player)
    }

    private fun startTranslationWorker(admin: Player) {
        val config = YamlConfiguration.loadConfiguration(queueFile)
        val total = config.getInt("total", 1)

        bossBar = Bukkit.createBossBar(
            plugin.language.getString("command-message.translate.bossbar.initializing", "§dAI: Initializing...")!!,
            BarColor.PURPLE, BarStyle.SOLID
        ).apply { addPlayer(admin) }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                while (true) {
                    val currentQueue = YamlConfiguration.loadConfiguration(queueFile)
                    val pending = currentQueue.getStringList("pending").toMutableList()

                    if (pending.isEmpty()) break

                    val task = pending[0]
                    val (race, file) = task.split(":")
                    val progress = ((total - pending.size).toDouble() / total * 100).toInt()

                    updateBar(
                        plugin.language.getString("command-message.translate.bossbar.processing")!!
                            .replace("{race}", race).replace("{file}", file).replace("{progress}", progress.toString()),
                        progress.toDouble() / 100
                    )

                    val sourceFile = File(plugin.dataFolder, "races/$race/$file")
                    val result = plugin.translationManager.translateFileWithState(sourceFile, "races/$race/${file.substringBeforeLast(".")}")

                    when (result) {
                        TranslationResult.SUCCESS -> {
                            pending.removeAt(0)
                            saveQueue(pending)
                            handleCooldown(10, progress)
                        }
                        TranslationResult.SKIPPED -> {
                            pending.removeAt(0)
                            saveQueue(pending)
                        }
                        TranslationResult.QUOTA_LIMIT -> {
                            for (i in 60 downTo 1) {
                                updateBar(plugin.language.getString("command-message.translate.bossbar.quota-wait")!!
                                    .replace("{time}", i.toString()), progress.toDouble() / 100)
                                Thread.sleep(1000)
                            }
                        }
                        TranslationResult.ERROR -> {
                            pending.removeAt(0)
                            saveQueue(pending)
                            Thread.sleep(2000)
                        }
                    }
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    bossBar?.removeAll()
                    queueFile.delete()
                    isRunning.set(false)
                    admin.sendFormattedMessage(plugin.language.getString("command-message.translate.success")!!)
                    plugin.gameplayManager.humanoidManager.raceManager.loadRaces()
                })

            } catch (e: Exception) {
                isRunning.set(false)
                plugin.logger.severe("Worker failure: ${e.message}")
            }
        })
    }

    private fun handleCooldown(seconds: Int, progress: Int) {
        for (i in seconds downTo 1) {
            updateBar(plugin.language.getString("command-message.translate.bossbar.cooldown")!!
                .replace("{time}", i.toString()), progress.toDouble() / 100)
            Thread.sleep(1000)
        }
    }

    private fun saveQueue(list: List<String>) {
        val config = YamlConfiguration.loadConfiguration(queueFile)
        config.set("pending", list)
        config.save(queueFile)
    }

    private fun updateBar(title: String, progress: Double) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            bossBar?.setTitle(title)
            bossBar?.progress = progress.coerceIn(0.0, 1.0)
        })
    }
}
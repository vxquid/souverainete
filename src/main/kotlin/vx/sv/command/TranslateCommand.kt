package vx.sv.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Subcommand
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.config.lib.TranslationManager.TranslationResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@CommandAlias("s|sv")
class TranslateCommand : BaseCommand() {

    private val isRunning = AtomicBoolean(false)
    private val queueFile = File(plugin.dataFolder, "cache/queue.yml")
    private var bossBar: BossBar? = null

    @Subcommand("translate")
    @CommandPermission("sv.admin.translate")
    fun onTranslate(player: Player) {
        if (isRunning.get()) {
            player.sendFormattedMessage(plugin.language.getString("command-message.translate.already-running")!!)
            return
        }

        if (queueFile.exists()) {
            player.sendFormattedMessage(plugin.language.getString("command-message.translate.resume-info")!!)
        }

        val races = plugin.gameplayManager.humanoidManager.raceManager.races.getKeys(false)
        val fileCount = races.size * 2
        val estTime = (fileCount * 12) / 60 // ~12 сек на файл

        player.sendFormattedMessage(plugin.language.getString("command-message.translate.warning")!!.replace("{count}", fileCount.toString()))
        player.sendFormattedMessage(plugin.language.getString("command-message.translate.cooldown-info")!!.replace("{time}", estTime.toString()))
        player.sendFormattedMessage(plugin.language.getString("command-message.translate.accept-prompt")!!)
    }

    @Subcommand("translate accept|resume")
    @CommandPermission("sv.admin.translate")
    fun onAccept(player: Player) {
        if (isRunning.get()) return

        // Создаем очередь, если её нет
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
        startWorker(player)
    }

    private fun startWorker(admin: Player) {
        val config = YamlConfiguration.loadConfiguration(queueFile)
        val total = config.getInt("total", 1)

        bossBar = Bukkit.createBossBar(
            plugin.language.getString("command-message.translate.bossbar.initializing")!!,
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
                            doCooldown(10, progress)
                        }
                        TranslationResult.SKIPPED -> {
                            pending.removeAt(0)
                            saveQueue(pending)
                        }
                        TranslationResult.QUOTA_LIMIT -> {
                            for (i in 60 downTo 1) {
                                updateBar(plugin.language.getString("command-message.translate.bossbar.quota-wait")!!.replace("{time}", i.toString()), progress.toDouble() / 100)
                                Thread.sleep(1000)
                            }
                        }
                        TranslationResult.ERROR -> {
                            // Пропускаем проблемный файл после лога или пробуем позже
                            pending.removeAt(0)
                            saveQueue(pending)
                            Thread.sleep(2000)
                        }
                    }
                }

                // Завершение
                plugin.server.scheduler.runTask(plugin, Runnable {
                    bossBar?.removeAll()
                    queueFile.delete()
                    isRunning.set(false)
                    admin.sendFormattedMessage(plugin.language.getString("command-message.translate.success")!!)
                    plugin.gameplayManager.humanoidManager.raceManager.loadRaces()
                })

            } catch (e: Exception) {
                isRunning.set(false)
                plugin.logger.severe("Worker crashed: ${e.message}")
            }
        })
    }

    private fun doCooldown(seconds: Int, progress: Int) {
        for (i in seconds downTo 1) {
            updateBar(plugin.language.getString("command-message.translate.bossbar.cooldown")!!.replace("{time}", i.toString()), progress.toDouble() / 100)
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
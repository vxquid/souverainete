package vx.ignis.gameplay.dialogue

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.config.lib.GameplayConfiguration.DialogueConfig.DialogueFormat
import vx.ignis.persistent.LivingEntityExtend.getVoicePitch
import vx.ignis.persistent.LivingEntityExtend.getVoiceSound
import java.util.concurrent.ConcurrentHashMap

class DialogueManager {

    init {
        dialogueManager = this
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            dialogues.values.forEach(DialogueWindow::relocate)
        }, 0L, 1L)
    }

    fun startDialogue(pair: Pair<Player, LivingEntity>, text: String, follow: Boolean = true, size: Float = 0.35F, interrupt: Boolean = false, onFinish: () -> Unit = {}) {

        val (player, villager) = pair
        val formattedText = dialogueBoxTextBaseColor + text.replace(Regex("\\*\\*(.*?)\\*\\*")) { matchResult ->
            "$dialogueBoxTextImportantColor${matchResult.groupValues[1]}$dialogueBoxTextBaseColor"
        }.replace(Regex("\\*(.*?)\\*")) { matchResult ->
            "$dialogueBoxTextInterestingColor${matchResult.groupValues[1]}$dialogueBoxTextBaseColor"
        }.replace("\\\"", "\"")

        when (player.dialogueFormat) {
            DialogueFormat.IMMERSIVE -> {
                if (!interrupt && dialogues.containsKey(pair)) return
                DialogueWindow(player, villager, size, formattedText.split(" "), follow, interrupt, false, onFinish).schedule()
            }
            DialogueFormat.HOLOGRAM -> {
                if (!interrupt && dialogues.containsKey(pair)) return
                // ИЗМЕНЕНИЕ: Размер увеличен до 0.9F. Это делает текст крупным и очень разборчивым.
                DialogueWindow(player, villager, 0.9F, formattedText.split(" "), follow, interrupt, true, onFinish).schedule()
            }
            DialogueFormat.CHAT -> {
                this.sendDialogueInChat(player, villager, formattedText)
            }
            DialogueFormat.BOTH -> {
                if (!interrupt && dialogues.containsKey(pair)) return
                DialogueWindow(player, villager, size, formattedText.split(" "), follow, interrupt, false, onFinish).schedule()
                this.sendDialogueInChat(player, villager, formattedText)
            }
        }
    }

    private val cooldownPlayers = mutableListOf<Player>()
    fun sendDialogueInChat(player: Player, entity: LivingEntity, message: String) {

        if (cooldownPlayers.contains(player)) {
            return
        } else {
            cooldownPlayers.add(player)
            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                cooldownPlayers.remove(player)
            }, 20)
        }

        val formattedMessage = plugin.language.getString("villager-message-chat-format")!!
            .replace("{villagerName}", entity.customName ?: "")
            .replace("{message}", message)

        player.sendMessage(formattedMessage)

        if (player.dialogueFormat != DialogueFormat.BOTH)
            player.playSound(entity.location, entity.getVoiceSound(), 1F, entity.getVoicePitch())
    }

    companion object {

        private lateinit var dialogueManager: DialogueManager

        val dialogueFormatKey = NamespacedKey(plugin, "DialogueFormat")
        private val dialogueBoxSize = 0.28F
        private val dialogueBoxTextBaseColor = "§f"
        private val dialogueBoxTextImportantColor = "§5"
        private val dialogueBoxTextInterestingColor = "§6"
        val dialogueBackgroundAlpha = 185
        val dialogueBackgroundRed = 0
        val dialogueBackgroundGreen = 0
        val dialogueBackgroundBlue = 0

        val dialogues: ConcurrentHashMap<Pair<Player, LivingEntity>, DialogueWindow> = ConcurrentHashMap()

        fun LivingEntity.talk(player: Player?, text: String?, displaySize: Float = dialogueBoxSize, followDuringDialogue: Boolean = true, interruptPreviousDialogue: Boolean = false, onFinish: () -> Unit = {}) {
            if (player == null) return
            text?.let {
                dialogueManager.startDialogue(player to this, it, size = displaySize, follow = followDuringDialogue, interrupt = interruptPreviousDialogue, onFinish = onFinish)
            }
        }

        fun LivingEntity.shout(text: String?, radius: Double = 32.0) {
            text?.let { msg ->
                val formattedText = dialogueBoxTextBaseColor + msg.replace(Regex("\\*\\*(.*?)\\*\\*")) { matchResult ->
                    "$dialogueBoxTextImportantColor${matchResult.groupValues[1]}$dialogueBoxTextBaseColor"
                }.replace(Regex("\\*(.*?)\\*")) { matchResult ->
                    "$dialogueBoxTextInterestingColor${matchResult.groupValues[1]}$dialogueBoxTextBaseColor"
                }.replace("\\\"", "\"")

                this.location.getNearbyPlayers(radius).forEach { player ->
                    dialogueManager.sendDialogueInChat(player, this, formattedText)
                }
            }
        }

        val Player.dialogueFormat: DialogueFormat
            get() {
                this.persistentDataContainer.get(dialogueFormatKey, PersistentDataType.STRING)?.let { type ->
                    return try { DialogueFormat.valueOf(type) } catch (e: Exception) { DialogueFormat.IMMERSIVE }
                }
                return plugin.gameplayManager.config.dialogue.dialogueFormat.also { type ->
                    this.persistentDataContainer.set(dialogueFormatKey, PersistentDataType.STRING, type.toString())
                }
            }
    }

    class DialogueWindow(
        private val player: Player,
        val entity: LivingEntity,
        private val size: Float,
        private val words: List<String>,
        private val follow: Boolean,
        cancelPrevious: Boolean,
        private val isHologram: Boolean,
        private val onFinish: () -> Unit = {}
    ) {

        private val display: TextDisplay
        private val displayBackgroundColor = if (isHologram) {
            Color.fromARGB(0, 0, 0, 0)
        } else {
            Color.fromARGB(dialogueBackgroundAlpha, dialogueBackgroundRed, dialogueBackgroundGreen, dialogueBackgroundBlue)
        }

        private val voice: Sound = entity.getVoiceSound()
        private val pitch: Float = entity.getVoicePitch()

        private val height = if (entity is Ageable && !entity.isAdult) 0.75 else 1.25
        private val maxDistance = 12.0 // Еще увеличил дистанцию, текст большой, видно далеко

        private val pauseDurationBetweenSentences = 3000L
        private val pauseDurationBetweenWords = 175L
        private val fastPauseDurationBetweenSentences = 1250L
        private val fastPauseDurationBetweenWords = 100L

        private var isCancelled = false
        private var isDestroyed = false

        init {
            if (cancelPrevious) dialogues[player to entity]?.let {
                it.display.remove()
                it.isCancelled = true
            }
            display = entity.world.spawnEntity(entity.location, EntityType.TEXT_DISPLAY) as TextDisplay
            dialogues[player to entity] = this
        }

        fun schedule() {
            display.billboard = Display.Billboard.CENTER
            display.isSeeThrough = false
            display.isVisibleByDefault = false

            if (isHologram) {
                display.isShadowed = true
                // ИЗМЕНЕНИЕ: Увеличил ширину строки до 350, чтобы крупный текст не переносился каждые два слова
                display.lineWidth = 350
            }

            player.showEntity(plugin, display)
            display.backgroundColor = displayBackgroundColor

            if (isHologram) {
                entity.addPassenger(display)

                // ИЗМЕНЕНИЕ: Подняли еще выше (0.95f), чтобы компенсировать крупный шрифт
                display.transformation = Transformation(
                    Vector3f(0f, 0.65f, 0f),
                    AxisAngle4f(),
                    Vector3f(size, size, size),
                    AxisAngle4f()
                )
            } else {
                display.transformation = Transformation(
                    Vector3f(0f, 0f, 0f),
                    AxisAngle4f(),
                    Vector3f(size, size, size),
                    AxisAngle4f()
                )
            }

            val task = object : BukkitRunnable() {
                override fun run() {
                    var wordAmount = 0
                    for (word in words) {
                        if (!plugin.isEnabled || word.isEmpty() || isCancelled || isDestroyed) break

                        val sentence = word.last() == '.' || word.last() == '!' || word.last() == '?' || word.last() == ','
                        val lastWord = words.indexOf(word) == words.lastIndex
                        val clear    = ++wordAmount > 10 && sentence && !lastWord

                        plugin.server.scheduler.runTask(plugin) { _ ->
                            display.text += "$word "
                            player.playSound(entity.location, voice, 1F, pitch)
                            if (follow && !isHologram) {
                                plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(entity).talkingPlayer = null
                            }
                        }

                        val pauseDuration = when {
                            player.isSneaking && sentence -> fastPauseDurationBetweenSentences
                            player.isSneaking -> fastPauseDurationBetweenWords
                            sentence || lastWord -> if (word.last() != ',' || clear) pauseDurationBetweenSentences else pauseDurationBetweenWords * 3
                            else -> pauseDurationBetweenWords
                        }

                        Thread.sleep(pauseDuration)

                        if (clear) {
                            display.text = dialogueBoxTextBaseColor
                            wordAmount = 0
                        }
                    }

                    if (plugin.isEnabled && !isDestroyed) {
                        plugin.server.scheduler.runTask(plugin) { _ ->
                            destroy()
                            onFinish.invoke()
                        }
                    }
                }
            }

            task.runTaskAsynchronously(plugin)
        }

        fun relocate() {
            if (checkDistance()) {
                this.destroy()
                return
            }
            if (!isHologram) {
                display.teleport(this.calculatePosition())
            }
        }

        fun destroy() {
            display.remove()
            plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(entity).talkingPlayer = null
            dialogues.remove(player to entity, this@DialogueWindow)
            isDestroyed = true
        }

        private fun checkDistance(): Boolean = player.location.distance(entity.location) > maxDistance

        private fun calculatePosition(): Location {
            return player.eyeLocation.add(entity.location.add(0.0, if (entity.pose != Pose.SLEEPING) height else height - 0.4, 0.0)).multiply(0.5)
        }
    }
}
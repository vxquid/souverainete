package vx.sv.gameplay.dialogue

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import vx.sv.Souverainete.Companion.plugin
import vx.sv.config.GameplayConfiguration.DialogueConfig.DialogueFormat
import vx.sv.persistent.LivingEntityExtend.getVoicePitch
import vx.sv.persistent.LivingEntityExtend.getVoiceSound
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
                DialogueWindow(player, villager, 0.9F, formattedText.split(" "), follow, interrupt, true, onFinish).schedule()
            }
            DialogueFormat.CHAT -> {
                this.sendDialogueInChat(player, villager, formattedText)
                onFinish.invoke()
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
        private const val dialogueBoxSize = 0.28F
        private const val dialogueBoxTextBaseColor = "§f"
        private const val dialogueBoxTextImportantColor = "§5"
        private const val dialogueBoxTextInterestingColor = "§6"
        const val dialogueBackgroundAlpha = 185
        const val dialogueBackgroundRed = 0
        const val dialogueBackgroundGreen = 0
        const val dialogueBackgroundBlue = 0

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
        val size: Float, // ИЗМЕНЕНИЕ: Теперь это public val, чтобы мы могли читать его размер
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
        private val maxDistance = 12.0

        private var isCancelled = false
        private var isDestroyed = false
        private var tickTask: BukkitTask? = null

        private var currentWordIndex = 0
        private var wordsInCurrentLine = 0
        private var currentTextString = dialogueBoxTextBaseColor
        private var ticksToWait = 0

        init {
            if (cancelPrevious) dialogues[player to entity]?.let {
                it.destroy()
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
                display.lineWidth = 350
            }

            player.showEntity(plugin, display)
            display.backgroundColor = displayBackgroundColor

            if (isHologram) {
                entity.addPassenger(display)
                display.transformation = Transformation(Vector3f(0f, 0.65f, 0f), AxisAngle4f(), Vector3f(size, size, size), AxisAngle4f())
            } else {
                display.transformation = Transformation(Vector3f(0f, 0f, 0f), AxisAngle4f(), Vector3f(size, size, size), AxisAngle4f())
            }

            tickTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { processTick() }, 0L, 1L)
        }

        private fun processTick() {
            if (!plugin.isEnabled || isDestroyed) {
                tickTask?.cancel()
                return
            }

            if (isCancelled) {
                tickTask?.cancel()
                destroy()
                onFinish.invoke()
                return
            }

            if (ticksToWait > 0) {
                ticksToWait--
                return
            }

            if (currentWordIndex >= words.size) {
                tickTask?.cancel()

                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (!isDestroyed) {
                        destroy()
                        onFinish.invoke()
                    }
                }, 40L)
                return
            }

            val word = words[currentWordIndex]
            val sentence = word.isNotEmpty() && word.last() in charArrayOf('.', '!', '?', ',')
            val lastWord = currentWordIndex == words.lastIndex

            wordsInCurrentLine++
            val clear = wordsInCurrentLine > 10 && sentence && !lastWord

            currentTextString += "$word "
            display.text = currentTextString
            player.playSound(entity.location, voice, 1F, pitch)

            if (follow && !isHologram) {
                plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(entity)?.talkingPlayer = player
            }

            ticksToWait = when {
                player.isSneaking && sentence -> 20
                player.isSneaking -> 2
                sentence || lastWord -> if (word.last() != ',' || clear) 50 else 10
                else -> 3
            }

            if (clear) {
                currentTextString = dialogueBoxTextBaseColor
                wordsInCurrentLine = 0
            }

            currentWordIndex++
        }

        fun relocate() {
            if (checkDistance()) {
                this.isCancelled = true
                return
            }
            if (!isHologram && !isDestroyed) {
                display.teleport(this.calculatePosition())
            }
        }

        fun skip() {
            if (!isDestroyed && !isCancelled) {
                isCancelled = true
            }
        }

        fun destroy() {
            if (isDestroyed) return
            isDestroyed = true
            tickTask?.cancel()
            display.remove()
            plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(entity)?.talkingPlayer = null
            dialogues.remove(player to entity, this@DialogueWindow)
        }

        private fun checkDistance(): Boolean = player.location.distanceSquared(entity.location) > maxDistance * maxDistance

        private fun calculatePosition(): Location {
            return player.eyeLocation.add(entity.location.add(0.0, if (entity.pose != Pose.SLEEPING) height else height - 0.4, 0.0)).multiply(0.5)
        }
    }
}
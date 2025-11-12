package vx.ignis.gameplay.reputation

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Vector3f
import vx.ignis.Ignis.Companion.plugin
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReputationDisplayManager(private val reputationManager: ReputationManager) : Listener {

    private val config by lazy { plugin.gameplayManager.config }
    private val playerDisplays: MutableMap<UUID, MutableMap<UUID, TextDisplay>> = HashMap() // Player UUID -> (NPC UUID -> TextDisplay)
    private val executor = Executors.newSingleThreadScheduledExecutor()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Асинхронный таймер для обновлений каждые 25 мс
        executor.scheduleAtFixedRate({
            // Все обновления entities в sync
            Bukkit.getScheduler().runTask(plugin) { _ ->
                updateNearbyDisplays()
            }
        }, 0, 25, TimeUnit.MILLISECONDS)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        playerDisplays[event.player.uniqueId] = HashMap()
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val displays = playerDisplays.remove(event.player.uniqueId) ?: return
        displays.values.forEach { it.remove() }
    }

    /**
     * Обновляет дисплеи для nearby NPC всех игроков.
     */
    private fun updateNearbyDisplays() {
        Bukkit.getOnlinePlayers().forEach { player ->
            val playerDisplaysMap = playerDisplays[player.uniqueId] ?: return@forEach

            // Получаем nearby LivingEntities с репутацией, сортируем по расстоянию (closest first)
            val nearbyNPCs = player.getNearbyEntities(config.reputation.displayCloseDistance, config.reputation.displayCloseDistance, config.reputation.displayCloseDistance)
                .filterIsInstance<LivingEntity>()
                .filter { it.persistentDataContainer.has(ReputationManager.REP_KEY, org.bukkit.persistence.PersistentDataType.STRING) }
                .sortedBy { player.location.distanceSquared(it.location) }
                .associateBy { it.uniqueId }

            // Удаляем старые дисплеи для NPC, которые больше не близко
            val toRemove = playerDisplaysMap.keys - nearbyNPCs.keys
            toRemove.forEach { npcUUID ->
                val display = playerDisplaysMap.remove(npcUUID)!!
                player.hideEntity(plugin, display)
                display.remove()
            }

            // Создаём/обновляем/телепортируем дисплеи для близких NPC
            nearbyNPCs.entries.forEachIndexed { index, (npcUUID, npc) ->
                var display = playerDisplaysMap[npcUUID]
                val npcName = npc.customName ?: npc.type.name.lowercase().capitalize()
                var repMap = reputationManager.getReputationMap(npc)
                if (!repMap.containsKey(player.uniqueId)) {
                    // Ленивая инициализация репутации на 0 напрямую в PDC без уведомлений
                    repMap = repMap.toMutableMap()
                    repMap[player.uniqueId] = 0
                    reputationManager.setReputationMap(npc, repMap)
                }
                val repValue = repMap[player.uniqueId] ?: 0
                val status = reputationManager.getPlayerReputationStatus(npc, player)
                val text = toSmallCaps(config.reputation.displayTextTemplate
                    .replace("{npcName}", npcName)
                    .replace("{repValue}", repValue.toString())
                    .replace("{status}", status.localizedName))

                // Рассчитываем позицию в правой части экрана относительно взгляда игрока
                val forward = player.eyeLocation.direction.normalize().multiply(config.reputation.displayHudDistance)
                val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0)).normalize().multiply(config.reputation.displayHudRightOffset)
                val verticalOffset = Vector(0.0, config.reputation.displayHudVerticalSpacing * index, 0.0) // Стек для нескольких NPC
                val newLocation = player.eyeLocation.add(forward).add(right).add(verticalOffset)

                if (display == null || !display.isValid) {
                    // Спавн нового дисплея
                    display = player.world.spawnEntity(newLocation, EntityType.TEXT_DISPLAY) as TextDisplay
                    configureDisplay(display, text)
                    player.showEntity(plugin, display)
                    playerDisplaysMap[npcUUID] = display
                } else {
                    // Обновляем текст, если изменился
                    if ((display.text() as? TextComponent)?.content() != text) {
                        display.text(Component.text(text).color(resolveTextColor()))
                    }
                    // Телепорт к новой позиции
                    display.teleport(newLocation)
                }
            }
        }
    }

    /**
     * Конфигурирует новый TextDisplay.
     */
    private fun configureDisplay(display: TextDisplay, text: String) {
        display.isVisibleByDefault = false // Не visible по умолчанию
        display.text(Component.text(text).color(resolveTextColor()))
        display.billboard = config.reputation.displayBillboard // Поворот к игроку
        val scale = config.reputation.displayScale
        display.transformation = Transformation(
            Vector3f(0f, 0f, 0f), // Translation
            org.joml.Quaternionf(0f, 0f, 0f, 1f), // Left rotation
            Vector3f(scale[0], scale[1], scale[2]), // Scale
            org.joml.Quaternionf(0f, 0f, 0f, 1f) // Right rotation
        )
        val bgColor = config.reputation.displayBackgroundColor
        display.backgroundColor = org.bukkit.Color.fromARGB(bgColor[0], bgColor[1], bgColor[2], bgColor[3]) // Фон из конфига
        display.isSeeThrough = config.reputation.displaySeeThrough // See-through
        display.isDefaultBackground = false
    }

    /**
     * Разрешает цвет текста из строки конфига.
     * Поддерживает именованные цвета и HEX (#RRGGBB).
     */
    private fun resolveTextColor(): net.kyori.adventure.text.format.TextColor {
        val colorStr = config.reputation.displayTextColor.trim()
        return if (colorStr.startsWith("#") && colorStr.length == 7) {
            net.kyori.adventure.text.format.TextColor.fromHexString(colorStr)
                ?: NamedTextColor.WHITE
        } else {
            NamedTextColor.NAMES.value(colorStr.lowercase()) ?: NamedTextColor.WHITE
        }
    }

    /**
     * Конвертирует текст в small caps Unicode.
     */
    private fun toSmallCaps(text: String): String {
        val mapping = mapOf(
            'A' to 'ᴀ', 'B' to 'ʙ', 'C' to 'ᴄ', 'D' to 'ᴅ', 'E' to 'ᴇ', 'F' to 'ғ', 'G' to 'ɢ',
            'H' to 'ʜ', 'I' to 'ɪ', 'J' to 'ᴊ', 'K' to 'ᴋ', 'L' to 'ʟ', 'M' to 'ᴍ', 'N' to 'ɴ',
            'O' to 'ᴏ', 'P' to 'ᴘ', 'Q' to 'Q', 'R' to 'ʀ', 'S' to 'ꜱ', 'T' to 'ᴛ', 'U' to 'ᴜ',
            'V' to 'ᴠ', 'W' to 'ᴡ', 'X' to 'x', 'Y' to 'ʏ', 'Z' to 'ᴢ',
            '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅',
            '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
            ':' to ':', ' ' to ' ', '(' to '(', ')' to ')', '\'' to '\''
        )
        return text.uppercase().map { mapping[it] ?: it }.joinToString("")
    }

    /**
     * Обновляет для конкретного NPC и игрока (вызывать при изменении репутации).
     */
    fun updateForPlayer(player: Player, entity: LivingEntity) {
        val playerDisplaysMap = playerDisplays[player.uniqueId] ?: return
        if (player.location.distance(entity.location) > config.reputation.displayCloseDistance) return

        val npcName = entity.customName ?: entity.type.name.lowercase().capitalize()
        var repMap = reputationManager.getReputationMap(entity)
        if (!repMap.containsKey(player.uniqueId)) {
            // Ленивая инициализация репутации на 0 напрямую в PDC без уведомлений
            repMap = repMap.toMutableMap()
            repMap[player.uniqueId] = 0
            reputationManager.setReputationMap(entity, repMap)
        }
        val repValue = repMap[player.uniqueId] ?: 0
        val status = reputationManager.getPlayerReputationStatus(entity, player)
        val text = toSmallCaps(config.reputation.displayTextTemplate
            .replace("{npcName}", npcName)
            .replace("{repValue}", repValue.toString())
            .replace("{status}", status.localizedName))

        val display = playerDisplaysMap[entity.uniqueId] ?: return
        display.text(Component.text(text).color(resolveTextColor()))

        // Обновляем позицию в правой части экрана
        val forward = player.eyeLocation.direction.normalize().multiply(config.reputation.displayHudDistance)
        val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0)).normalize().multiply(config.reputation.displayHudRightOffset)
        // Для updateForPlayer не стекуем, так как это одиночное обновление; полный стек в таймере
        val newLocation = player.eyeLocation.add(forward).add(right)
        display.teleport(newLocation)
    }

    /**
     * Отключение: Удаляем все дисплеи и останавливаем executor.
     */
    fun disable() {
        executor.shutdownNow()
        playerDisplays.forEach { (_, displays) ->
            displays.values.forEach { it.remove() }
        }
        playerDisplays.clear()
    }

}

package vx.ignis.gameplay.dialogue.menu

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.*
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.dialogue.menu.InteractionManager.Companion.defaultButtonColor
import vx.ignis.gameplay.dialogue.menu.InteractionManager.Companion.openedMenuList
import vx.ignis.util.RainbowColorTicker.rainbowColor

class Menu(
    val villager: Villager,
    val viewer: Player,
    var lastScrollTime: Long = 0
) {

    data class Button(val display: TextDisplay, val defaultColor: Color = defaultButtonColor, val isRainbow: Boolean = false)

    // Позиция для отображения текста
    private val pivot: Location = calculatePosition()
    private val buttons: MutableMap<Button, () -> Unit> = mutableMapOf()

    private val height = 1.4
    private val maxDistance = 5.5
    private val size = 0.4F
    private val step = 0.125

    private val selectedColor = Color.fromARGB(150, 200, 200, 0)

    init {
        openedMenuList.add(this)
    }

    /**
     * Вычисляет позицию отображения текста относительно игрока и жителя.
     */
    private fun calculatePosition(): Location {
        return viewer.eyeLocation.add(villager.location.add(0.0, height, 0.0)).multiply(0.5)
    }

    /**
     * Перемещает GUI, если игрок находится в пределах допустимого расстояния.
     */
    fun relocate() {
        if (viewer.location.distance(villager.location.add(0.0, height, 0.0)) > maxDistance) {
            destroy()
            return
        }
        updatePosition()
        updateSelection()
        plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(villager).talkingPlayer = viewer
    }

    private fun updatePosition() {
        val newLocation = calculatePosition()
        buttons.keys.forEachIndexed { index, button ->
            button.display.teleport(newLocation.clone().add(0.0, -index * step, 0.0))
        }
    }

    var index: Int = 0
        set(value) {
            field = cyclicIndex(value)
            updateSelection()
        }

    /**
     * Добавляет текстовую строку и связанную с ней функцию в GUI.
     *
     * @param text Текст для отображения.
     * @param function Функция, вызываемая при выборе этой строки.
     */
    fun addLine(text: String, buttonColor: Color = defaultButtonColor, rainbow: Boolean = false, function: () -> Unit) {
        val button = Button(this.createButtonDisplay(text, buttonColor), isRainbow = rainbow)
        buttons[button] = function
        updateSelection()
    }

    private fun createButtonDisplay(text: String, buttonColor: Color = defaultButtonColor, rainbow: Boolean = false): TextDisplay {
        val display = viewer.world.spawnEntity(
            pivot.clone().add(0.0, -buttons.size * step, 0.0),
            EntityType.TEXT_DISPLAY
        ) as TextDisplay
        display.isVisibleByDefault = false
        viewer.showEntity(plugin, display)
        display.transformation = Transformation(Vector3f(0f, 0f, 0f), AxisAngle4f(), Vector3f(size, size, size), AxisAngle4f())
        display.text = text
        display.billboard = Display.Billboard.CENTER
        display.backgroundColor = buttonColor
        return display
    }

    private fun updateSelection() {
        buttons.keys.forEach { button -> button.display.backgroundColor  = if (button.isRainbow) rainbowColor else button.defaultColor }
        buttons.keys.toList().getOrNull(index)?.display?.backgroundColor = selectedColor
    }

    /**
     * Вызывает функцию, связанную с текущим выбранным элементом.
     */
    fun invokeSelected() {
        buttons.keys.toList().getOrNull(index)?.let { display ->
            buttons[display]?.invoke()
        }
    }

    /**
     * Уничтожает GUI и освобождает ресурсы.
     */
    fun destroy() {
        openedMenuList.remove(this)
        buttons.keys.forEach { it.display.remove() }
        buttons.clear()
    }

    /**
     * Циклический индекс для навигации по доступным строкам.
     */
    private fun cyclicIndex(value: Int): Int {
        return when {
            value >= buttons.size -> 0
            value < 0 -> buttons.size - 1
            else -> value
        }
    }

}

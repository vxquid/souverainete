package vx.sv.gameplay.dialogue.menu

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.*
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.dialogue.menu.InteractionHandler.Companion.defaultButtonColor
import vx.sv.gameplay.dialogue.menu.InteractionHandler.Companion.openedMenuList
import vx.sv.util.RainbowColorTicker.rainbowColor

class Menu(
    val villager: Villager,
    val viewer: Player
) {

    data class MenuRow(
        val display: TextDisplay,
        val defaultColor: Color,
        val isRainbow: Boolean,
        val action: () -> Unit
    )

    // Изменено на var, так как центр должен обновляться при движении
    private var pivot: Location = calculatePosition()
    private val rows: MutableList<MenuRow> = mutableListOf()

    private val height = 1.4
    private val maxDistanceSq = 5.5 * 5.5
    private val size = 0.4F
    private val step = 0.125

    private val selectedColor = Color.fromARGB(150, 200, 200, 0)

    var isDestroyed = false
        private set

    init {
        openedMenuList.add(this)
    }

    private fun calculatePosition(): Location {
        val vLoc = villager.location.clone().add(0.0, height, 0.0)
        val pLoc = viewer.eyeLocation.clone()

        if (vLoc.world != pLoc.world) {
            return pLoc
        }

        return pLoc.add(vLoc).multiply(0.5)
    }

    fun relocate() {
        if (isDestroyed) return

        val targetLoc = villager.location.clone().add(0.0, height, 0.0)

        // Safety check: different worlds OR too far away
        if (viewer.world != targetLoc.world || viewer.location.distanceSquared(targetLoc) > maxDistanceSq) {
            destroy()
            return
        }

        updatePosition()
        updateLookSelection()
        updateSelection() // Обновляем цвета каждый тик для плавности rainbow кнопок

        plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(villager).talkingPlayer = viewer
    }

    private fun updatePosition() {
        pivot = calculatePosition() // Обновляем pivot
        rows.forEachIndexed { index, row ->
            row.display.teleport(pivot.clone().add(0.0, -index * step, 0.0))
        }
    }

    /**
     * Вычисляет кнопку, на которую смотрит игрок, находя наименьший угол
     * между направлением взгляда и вектором от глаз игрока к кнопке.
     */
    private fun updateLookSelection() {
        if (rows.isEmpty()) return

        val eyeLoc = viewer.eyeLocation.toVector()
        val lookDir = viewer.eyeLocation.direction

        var bestIndex = 0
        var minAngle = Double.MAX_VALUE

        rows.forEachIndexed { i, _ ->
            val buttonLoc = pivot.clone().add(0.0, -i * step, 0.0).toVector()
            val vecToButton = buttonLoc.subtract(eyeLoc)

            if (vecToButton.lengthSquared() > 0.0001) { // Защита от деления на ноль
                val angle = vecToButton.normalize().angle(lookDir).toDouble()
                if (angle < minAngle) {
                    minAngle = angle
                    bestIndex = i
                }
            }
        }

        // Обновляем индекс только если он изменился, и воспроизводим звук "клика"
        if (this.index != bestIndex) {
            this.index = bestIndex
            viewer.playSound(viewer.location, Sound.UI_BUTTON_CLICK, 1F, 2F)
        }
    }

    var index: Int = 0
        set(value) {
            field = cyclicIndex(value)
            updateSelection()
        }

    fun addLine(text: String, buttonColor: Color = defaultButtonColor, rainbow: Boolean = false, function: () -> Unit) {
        val display = createButtonDisplay(text, buttonColor)
        rows.add(MenuRow(display, buttonColor, rainbow, function))
        updateSelection()
    }

    private fun createButtonDisplay(text: String, buttonColor: Color): TextDisplay {
        val display = viewer.world.spawnEntity(
            pivot.clone().add(0.0, -rows.size * step, 0.0),
            EntityType.TEXT_DISPLAY
        ) as TextDisplay

        display.isPersistent = false
        display.isVisibleByDefault = false
        viewer.showEntity(plugin, display)
        display.transformation = Transformation(Vector3f(0f, 0f, 0f), AxisAngle4f(), Vector3f(size, size, size), AxisAngle4f())
        display.text = text
        display.billboard = Display.Billboard.CENTER
        display.backgroundColor = buttonColor
        return display
    }

    private fun updateSelection() {
        if (rows.isEmpty()) return

        rows.forEachIndexed { i, row ->
            row.display.backgroundColor = when {
                i == index -> selectedColor
                row.isRainbow -> rainbowColor
                else -> row.defaultColor
            }
        }
    }

    fun invokeSelected() {
        rows.getOrNull(index)?.action?.invoke()
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        openedMenuList.remove(this)

        rows.forEach { it.display.remove() }
        rows.clear()
    }

    private fun cyclicIndex(value: Int): Int {
        if (rows.isEmpty()) return 0
        return when {
            value >= rows.size -> 0
            value < 0 -> rows.size - 1
            else -> value
        }
    }
}
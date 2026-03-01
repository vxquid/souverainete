package vx.sv.gameplay.dialogue.menu

import org.bukkit.Color
import org.bukkit.Location
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
    val viewer: Player,
    var lastScrollTime: Long = 0
) {

    // Switched from Map to a List of MenuRows.
    // This removes the need for heavy .toList() conversions every tick.
    data class MenuRow(
        val display: TextDisplay,
        val defaultColor: Color,
        val isRainbow: Boolean,
        val action: () -> Unit
    )

    // The pivot point for text display
    private val pivot: Location = calculatePosition()
    private val rows: MutableList<MenuRow> = mutableListOf()

    private val height = 1.4
    // Using squared distance avoids heavy Math.sqrt() calculations every tick
    private val maxDistanceSq = 5.5 * 5.5
    private val size = 0.4F
    private val step = 0.125

    private val selectedColor = Color.fromARGB(150, 200, 200, 0)

    var isDestroyed = false
        private set

    init {
        openedMenuList.add(this)
    }

    /**
     * Calculates the position to display text between the player and the villager.
     */
    private fun calculatePosition(): Location {
        val vLoc = villager.location.clone().add(0.0, height, 0.0)
        val pLoc = viewer.eyeLocation.clone()

        // CRITICAL: Prevent IllegalArgumentException if entities are in different worlds
        if (vLoc.world != pLoc.world) {
            return pLoc // Fallback to avoid crash before destroy() handles it
        }

        return pLoc.add(vLoc).multiply(0.5)
    }

    /**
     * Moves the GUI or destroys it if the player is out of range/in another world.
     */
    fun relocate() {
        if (isDestroyed) return

        val targetLoc = villager.location.clone().add(0.0, height, 0.0)

        // Safety check: different worlds OR too far away
        if (viewer.world != targetLoc.world || viewer.location.distanceSquared(targetLoc) > maxDistanceSq) {
            destroy()
            return
        }

        updatePosition()
        updateSelection()
        plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(villager).talkingPlayer = viewer
    }

    private fun updatePosition() {
        val newLocation = calculatePosition()
        rows.forEachIndexed { index, row ->
            row.display.teleport(newLocation.clone().add(0.0, -index * step, 0.0))
        }
    }

    var index: Int = 0
        set(value) {
            field = cyclicIndex(value)
            updateSelection()
        }

    /**
     * Adds a text line and its associated function to the GUI.
     *
     * @param text Text to display.
     * @param function Function invoked when this line is selected.
     */
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

        // CRITICAL MEMORY LEAK FIX: Ensure displays don't remain as ghosts if the server stops/crashes
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

        // Completely removed heavy Map.keys.toList() allocations. O(n) now instead of memory spam.
        rows.forEachIndexed { i, row ->
            row.display.backgroundColor = when {
                i == index -> selectedColor
                row.isRainbow -> rainbowColor
                else -> row.defaultColor
            }
        }
    }

    /**
     * Invokes the function associated with the currently selected item.
     */
    fun invokeSelected() {
        rows.getOrNull(index)?.action?.invoke()
    }

    /**
     * Destroys the GUI and frees resources.
     */
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        // NOTE: If this destroy() is called while looping through `openedMenuList`
        // in your handler, it may throw ConcurrentModificationException.
        // Using CopyOnWriteArrayList for `openedMenuList` inside InteractionHandler is highly recommended.
        openedMenuList.remove(this)

        rows.forEach { it.display.remove() }
        rows.clear()
    }

    /**
     * Cyclic index for navigating available lines.
     */
    private fun cyclicIndex(value: Int): Int {
        if (rows.isEmpty()) return 0
        return when {
            value >= rows.size -> 0
            value < 0 -> rows.size - 1
            else -> value
        }
    }

}
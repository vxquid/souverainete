package vx.sv.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Subcommand
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import vx.sv.Souverainete.Companion.plugin
import vx.sv.persistent.MenuControlMode
import vx.sv.persistent.PlayerPreferencesManager.preferences

/**
 * Custom InventoryHolder to easily identify our GUI in events.
 */
class SettingsGUI(val player: Player) : InventoryHolder {

    private val title = plugin.language.getString("settings.gui.title") ?: "Personal Settings"
    private val inv = Bukkit.createInventory(this, 27, title)

    init {
        render()
    }

    override fun getInventory(): Inventory = inv

    fun open() {
        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.2f)
    }

    /**
     * Dynamically updates the items in the GUI.
     * Called on initialization and whenever a player clicks an option.
     */
    fun render() {
        inv.clear()
        val prefs = player.preferences

        // Background filler glass
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val fillerMeta = filler.itemMeta
        fillerMeta?.setDisplayName(plugin.language.getString("settings.gui.filler-name") ?: " ")
        filler.itemMeta = fillerMeta

        for (i in 0 until inv.size) {
            inv.setItem(i, filler)
        }

        // === Menu Control Toggle Button (Slot 10 - Row 2, Column 2) ===
        val isCursor = prefs.menuControl == MenuControlMode.CURSOR
        val btnMat = if (isCursor) Material.TARGET else Material.PAPER

        val btnItem = ItemStack(btnMat)
        val btnMeta = btnItem.itemMeta

        btnMeta?.setDisplayName(plugin.language.getString("settings.gui.menu-control.name") ?: "§eInteraction Menu Control")

        // Fetch current mode display names
        val cursorStr = plugin.language.getString("settings.gui.menu-control.mode-cursor") ?: "§aCursor (Aiming)"
        val scrollStr = plugin.language.getString("settings.gui.menu-control.mode-scroll") ?: "§bScroll (Hotbar)"
        val currentModeStr = if (isCursor) cursorStr else scrollStr

        // Build lore dynamically
        val rawLore = plugin.language.getStringList("settings.gui.menu-control.lore").takeIf { it.isNotEmpty() }
            ?: listOf("§7Current mode: {current}", "", "§eClick to toggle!")

        btnMeta?.lore = rawLore.map { it.replace("{current}", currentModeStr) }
        btnMeta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)

        btnItem.itemMeta = btnMeta

        // Place the setting button in Slot 10
        // You have slots 10, 11, 12, 13, 14, 15, 16 free for future settings in this row.
        inv.setItem(10, btnItem)
    }
}

/**
 * Event listener exclusively for handling Settings GUI clicks.
 */
class SettingsGUIListener : Listener {
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val inventory = event.inventory
        val gui = inventory.holder as? SettingsGUI ?: return

        // Prevent taking items from the menu
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val prefs = player.preferences

        // Check which slot was clicked
        when (event.rawSlot) {
            10 -> { // Menu Control Toggle Button (Row 2, Column 2)
                prefs.menuControl = if (prefs.menuControl == MenuControlMode.CURSOR) {
                    MenuControlMode.SCROLL
                } else {
                    MenuControlMode.CURSOR
                }

                // Save automatically via PDC property setter
                player.preferences = prefs

                // Play feedback sound
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)

                // Render update without closing the inventory
                gui.render()
            }
        }
    }
}

/**
 * Command handling for /s settings
 */
@CommandAlias("settlement|s")
class SettingsCommand : BaseCommand() {

    @Subcommand("settings")
    @CommandPermission("sv.player.settings")
    fun onSettings(player: Player) {
        val gui = SettingsGUI(player)
        gui.open()
    }
}
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
import vx.sv.persistent.NametagMode
import vx.sv.persistent.PlayerPreferencesManager.preferences
import vx.sv.persistent.QuestDialogueLength

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

        // === Menu Control Toggle Button (Slot 10) ===
        val isCursor = prefs.menuControl == MenuControlMode.CURSOR
        val btnMat = if (isCursor) Material.TARGET else Material.PAPER

        val btnItem = ItemStack(btnMat)
        val btnMeta = btnItem.itemMeta

        btnMeta?.setDisplayName(plugin.language.getString("settings.gui.menu-control.name") ?: "§eInteraction Menu Control")

        val cursorStr = plugin.language.getString("settings.gui.menu-control.mode-cursor") ?: "§aCursor (Aiming)"
        val scrollStr = plugin.language.getString("settings.gui.menu-control.mode-scroll") ?: "§bScroll (Hotbar)"
        val currentModeStr = if (isCursor) cursorStr else scrollStr

        val rawLore = plugin.language.getStringList("settings.gui.menu-control.lore").takeIf { it.isNotEmpty() }
            ?: listOf("§7Current mode: {current}", "", "§eClick to toggle!")

        btnMeta?.lore = rawLore.map { it.replace("{current}", currentModeStr) }
        btnMeta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)

        btnItem.itemMeta = btnMeta
        inv.setItem(10, btnItem)

        // === Nametag Mode Toggle Button (Slot 11) ===
        val isNametagAdvanced = prefs.nametagMode == NametagMode.ADVANCED
        val nameBtnMat = if (isNametagAdvanced) Material.NAME_TAG else Material.OAK_SIGN

        val nameBtnItem = ItemStack(nameBtnMat)
        val nameBtnMeta = nameBtnItem.itemMeta

        nameBtnMeta?.setDisplayName(plugin.language.getString("settings.gui.nametag-mode.name") ?: "§eNPC Name Display Mode")

        val advancedStr = plugin.language.getString("settings.gui.nametag-mode.mode-advanced") ?: "§dAdvanced (Detailed text)"
        val vanillaStr = plugin.language.getString("settings.gui.nametag-mode.mode-vanilla") ?: "§aVanilla (Standard)"
        val currentNametagStr = if (isNametagAdvanced) advancedStr else vanillaStr

        val nameLore = plugin.language.getStringList("settings.gui.nametag-mode.lore").takeIf { it.isNotEmpty() }
            ?: listOf("§7Current mode: {current}", "", "§eClick to toggle!")

        nameBtnMeta?.lore = nameLore.map { it.replace("{current}", currentNametagStr) }
        nameBtnMeta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)

        nameBtnItem.itemMeta = nameBtnMeta
        inv.setItem(11, nameBtnItem)

        // === Quest Dialogue Length Button (Slot 12) ===
        val isQuestShort = prefs.questDialogueLength == QuestDialogueLength.SHORT
        val questBtnMat = if (isQuestShort) Material.FEATHER else Material.WRITTEN_BOOK

        val questBtnItem = ItemStack(questBtnMat)
        val questBtnMeta = questBtnItem.itemMeta

        questBtnMeta?.setDisplayName(plugin.language.getString("settings.gui.quest-dialogue.name") ?: "§eQuest Dialogue Length")

        val shortStr = plugin.language.getString("settings.gui.quest-dialogue.mode-short") ?: "§bShort & Concise"
        val longStr = plugin.language.getString("settings.gui.quest-dialogue.mode-long") ?: "§aImmersive (Long)"
        val currentQuestStr = if (isQuestShort) shortStr else longStr

        val questLore = plugin.language.getStringList("settings.gui.quest-dialogue.lore").takeIf { it.isNotEmpty() }
            ?: listOf("§7Current mode: {current}", "", "§eClick to toggle!")

        questBtnMeta?.lore = questLore.map { it.replace("{current}", currentQuestStr) }
        questBtnMeta?.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)

        questBtnItem.itemMeta = questBtnMeta
        inv.setItem(12, questBtnItem)
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

        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val prefs = player.preferences

        when (event.rawSlot) {
            10 -> { // Menu Control
                prefs.menuControl = if (prefs.menuControl == MenuControlMode.CURSOR) MenuControlMode.SCROLL else MenuControlMode.CURSOR
                player.preferences = prefs
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                gui.render()
            }
            11 -> { // Nametag Mode
                prefs.nametagMode = if (prefs.nametagMode == NametagMode.ADVANCED) NametagMode.VANILLA else NametagMode.ADVANCED
                player.preferences = prefs
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                gui.render()
            }
            12 -> { // Quest Dialogue Length
                prefs.questDialogueLength = if (prefs.questDialogueLength == QuestDialogueLength.LONG) QuestDialogueLength.SHORT else QuestDialogueLength.LONG
                player.preferences = prefs
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
                gui.render()
            }
        }
    }
}

@CommandAlias("settlement|s")
class SettingsCommand : BaseCommand() {

    @Subcommand("settings")
    @CommandPermission("sv.player.settings")
    fun onSettings(player: Player) {
        val gui = SettingsGUI(player)
        gui.open()
    }
}
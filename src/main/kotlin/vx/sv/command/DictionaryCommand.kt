package vx.sv.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import vx.sv.Souverainete.Companion.plugin

@CommandAlias("dictionary|dq")
@Description("Manage custom items in the dictionary.")
class DictionaryCommand : BaseCommand() {

    init {
        plugin.commandManager.commandCompletions.registerCompletion("customitems") { plugin.gameplayManager.itemDictionary.getItemList() }
    }

    @Subcommand("add")
    @CommandPermission("acai.customitem.add")
    @Description("Add a new custom item to the dictionary")
    @Syntax("<key> <min> <max> <score> <material>")
    fun onAddItem(sender: CommandSender, key: String, score: Long, @Optional material: String?) {
        if (material == null && sender !is Player) {
            sender.sendMessage("§cYou must specify a material or execute this command as a player!")
            return
        }

        val itemStack = if (material != null) {
            try {
                ItemStack(Material.valueOf(material.uppercase()))
            } catch (e: IllegalArgumentException) {
                sender.sendMessage("§cInvalid material: $material")
                return
            }
        } else {
            (sender as Player).inventory.itemInMainHand.clone().apply {
                amount = 1
                if (type == Material.AIR) {
                    sender.sendMessage("§cYou must hold an item or specify a material!")
                    return
                }
            }
        }

        if (plugin.gameplayManager.itemDictionary.addItem(key, score, itemStack)) {
            sender.sendMessage("§aSuccessfully added item '$key' to the dictionary.")
        } else {
            sender.sendMessage("§cItem '$key' already exists in the dictionary!")
        }
    }

    @Subcommand("remove")
    @CommandPermission("acai.customitem.remove")
    @Description("Remove a custom item from the dictionary")
    @Syntax("<key>")
    @CommandCompletion("@customitems")
    fun onRemoveItem(sender: CommandSender, key: String) {
        if (plugin.gameplayManager.itemDictionary.removeItemByKey(key)) {
            sender.sendMessage("§aSuccessfully removed item '$key' from the dictionary.")
        } else {
            sender.sendMessage("§cItem '$key' does not exist in the dictionary!")
        }
    }

    @Subcommand("list")
    @CommandPermission("acai.customitem.list")
    @Description("List all custom items in the dictionary")
    fun onListItems(sender: CommandSender) {
        val items = plugin.gameplayManager.itemDictionary.getItemList()
        if (items.isEmpty()) {
            sender.sendMessage("§cThe custom item dictionary is empty.")
            return
        }
        sender.sendMessage("§aCustom items: ${items.joinToString(", ")}")
    }

    @Subcommand("give")
    @CommandPermission("acai.customitem.give")
    @Description("Give a custom item to a player")
    @Syntax("<player> <key>")
    @CommandCompletion("@players @customitems")
    fun onGiveItem(sender: CommandSender, target: Player, key: String) {
        try {
            val customItem = plugin.gameplayManager.itemDictionary.getItem(key)
            target.inventory.addItem(customItem.item)
            sender.sendMessage("§aSuccessfully gave '$key' to ${target.name}.")
        } catch (e: IllegalArgumentException) {
            sender.sendMessage("§cError: ${e.message}")
        }
    }

}

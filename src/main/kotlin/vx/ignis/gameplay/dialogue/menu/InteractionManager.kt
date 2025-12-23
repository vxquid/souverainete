package vx.ignis.gameplay.dialogue.menu

import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.data.type.Bed
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.premium
import vx.ignis.Ignis.Companion.sendFormattedMessage
import vx.ignis.event.VillagerKillTargetEvent
import vx.ignis.event.VillagerStartFightEvent
import vx.ignis.gameplay.dialogue.DialogueManager
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundAlpha
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundBlue
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundGreen
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogueBackgroundRed
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.dialogues
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.shout
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.talk
import vx.ignis.gameplay.dialogue.DialogueSession
import vx.ignis.gameplay.dialogue.DialogueSession.Companion.getActiveDialogueSession
import vx.ignis.gameplay.event.PlayerAcceptQuestEvent
import vx.ignis.gameplay.personality.PersonalityManager.Companion.getCharacterData
import vx.ignis.gameplay.quest.QuestManager.Quest
import vx.ignis.gameplay.reputation.ReputationManager.Companion.reputationOf
import vx.ignis.gameplay.reputation.ReputationManager.Reputation
import vx.ignis.gameplay.trade.TradeManager.Companion.openTradeMenu
import vx.ignis.persistent.LivingEntityExtend.quests

class InteractionManager: Listener {

    private val genericReactionMessages by lazy { plugin.gameplayManager.personalityManager.genericCharacterData }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            openedMenuList.toList().forEach(Menu::relocate)
        }, 0L, 1L)
    }

    companion object {
        val openedMenuList: MutableList<Menu> = mutableListOf()
        val defaultButtonColor = Color.fromARGB(dialogueBackgroundAlpha, dialogueBackgroundRed, dialogueBackgroundGreen, dialogueBackgroundBlue)
    }

    // --- НОВЫЕ БОЕВЫЕ ЛИСЕНЕРЫ ---

    @EventHandler
    private fun onVillagerStartFight(event: VillagerStartFightEvent) {
        val villager = event.villager

        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return

        // Получаем фразу из характера или берем дефолтную
        val phrases = villager.getCharacterData()?.startFightPhrases ?: genericReactionMessages.startFightPhrases

        if (phrases.isNotEmpty()) {
            val message = phrases.random()
            villager.shout(message)
        }
    }

    @EventHandler
    private fun onVillagerKillTarget(event: VillagerKillTargetEvent) {
        val villager = event.villager

        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return

        // Выбираем пул фраз в зависимости от типа убийства
        val phrases = when (event.killType) {
            VillagerKillTargetEvent.KillType.RANGED ->
                villager.getCharacterData()?.rangedKillPhrases ?: genericReactionMessages.rangedKillPhrases
            else -> // MELEE or OTHER
                villager.getCharacterData()?.meleeKillPhrases ?: genericReactionMessages.meleeKillPhrases
        }

        if (phrases.isNotEmpty()) {
            val message = phrases.random()
            villager.shout(message)
        }
    }

    // -----------------------------

    @EventHandler
    private fun whenVillagerDies(event: EntityDeathEvent) {
        (event.entity as? Villager)?.let { villager ->
            openedMenuList.filter { it.villager == villager }.forEach(Menu::destroy)
            dialogues.values.filter { it.entity == villager }.forEach(DialogueManager.DialogueWindow::destroy)
        }
    }

    @EventHandler
    private fun onPlayerJoin(event: PlayerJoinEvent) {
        lastInteraction[event.player] = System.currentTimeMillis()
    }

    private val lastInteraction = mutableMapOf<Player, Long>()
    @EventHandler(priority = EventPriority.HIGHEST)
    private fun handleVillagerInteraction(event: PlayerInteractEntityEvent) {
        (event.rightClicked as? Villager)?.let { villager ->

            // Проверка мира при клике. Меню не должно открываться в мирах, где выключен плагин.
            if (!plugin.gameplayManager.allowedWorlds.contains(villager.world))
                return

            val player: Player = event.player
            val time = System.currentTimeMillis()
            val last = lastInteraction.computeIfAbsent(player) {
                System.currentTimeMillis()
            }

            if (time - last <= 200) {
                return
            } else lastInteraction[player] = time

            // Отмена стандартного события
            event.isCancelled = true

            // Обработка повторного нажатия в случае наличия уже открытого меню
            openedMenuList.find { it.viewer == player }?.let { menu ->
                menu.invokeSelected()
                menu.destroy()
                (villager as CraftVillager).handle.tradingPlayer = null
                return
            }

            // Меню не должно открываться, если житель уже что-то говорит
            if (dialogues.containsKey(player to villager)) {
                return
            }

            // Обработка состояния спящего жителя
            if (villager.pose == Pose.SLEEPING) {
                val message = villager.getCharacterData()?.sleepInterruptionPhrases?.random() ?: genericReactionMessages.sleepInterruptionPhrases.random()
                villager.talk(player, message, followDuringDialogue = false)
                return
            }

            /* Далее происходит активация чата. Все проверки должны быть выше. */

            // Поддержка Гейзера.
            if (plugin.geyserProvider?.checkGeyserPlayer(player) == true) {
                plugin.geyserProvider?.openInteractionMenu(player, villager)
                return
            }

            val dialogueSession = player.getActiveDialogueSession()
            // Если у игрока есть активная диалоговая сессия, то
            if (dialogueSession != null) {
                if (dialogueSession.entity == villager) this.showDialogueMenu(player, villager)
                return
            }

            player.inventory.heldItemSlot = 4
            this.showDefaultMenu(player, villager)
        }
    }

    @EventHandler
    private fun handlePlayerQuit(event: PlayerQuitEvent) {
        openedMenuList.removeIf { it.viewer == event.player}
    }

    private fun showDialogueMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)

        // Quest button.
        builder.button(plugin.language.getString("interaction-menu.quests-button")!!) {

            // Когда игрок спрашивает о квестах у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getCharacterData()?.joblessPhrases?.random() ?: genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            // Когда игрок спрашивает о квестах у жителя с работой, но без квестов
            if (villager.quests().isEmpty()) {
                val message = villager.getCharacterData()?.noQuestPhrases?.random() ?: genericReactionMessages.noQuestPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            if (villager.quests().isNotEmpty()) {
                this.showQuestListMenu(player, villager)
            }
        }

        // Trading should be possible during conversation sessions.
        builder.button(plugin.language.getString("interaction-menu.trade-button")!!) {

            // Когда игрок спрашивает о торговле у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getCharacterData()?.joblessPhrases?.random() ?: genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                if (!villager.openTradeMenu(player)) {
                    val message = villager.getCharacterData()?.noItemsToTradePhrases?.random() ?: genericReactionMessages.noItemsToTradePhrases.random()
                    villager.talk(player, message, followDuringDialogue = true)
                }
            }, 1L)
        }

        // Gift button.
        builder.button(plugin.language.getString("interaction-menu.gift-button")!!) {
            if (player.getActiveDialogueSession()?.giftAwaiting == false) player.getActiveDialogueSession()?.giftAwaiting = true
        }

        // Dialogue interruption button.
        builder.button(plugin.language.getString("interaction-menu.interrupt-button")!!) {
            player.getActiveDialogueSession()?.cancelled = true
        }

        // Cancel button.
        builder.button(plugin.language.getString("interaction-menu.close-button")!!) { menu ->
            menu.destroy()
        }

        builder.build()
    }

    private fun showDefaultMenu(player: Player, villager: Villager) {

        val builder = Builder(villager, player)

        builder.button(plugin.language.getString("interaction-menu.quests-button")!! + " §8[${villager.quests().count()}]") { // debug

            // Когда игрок спрашивает о квестах у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getCharacterData()?.joblessPhrases?.random() ?: genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            // Когда игрок спрашивает о квестах у жителя с работой, но без квестов
            if (villager.quests().isEmpty()) {
                val message = villager.getCharacterData()?.noQuestPhrases?.random() ?: genericReactionMessages.noQuestPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            if (villager.quests().isNotEmpty()) {
                this.showQuestListMenu(player, villager)
            }
        }

        builder.button(plugin.language.getString("interaction-menu.trade-button")!!) {

            // Когда игрок спрашивает о торговле у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getCharacterData()?.joblessPhrases?.random() ?: genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                if (!villager.openTradeMenu(player)) {
                    val message = villager.getCharacterData()?.noItemsToTradePhrases?.random() ?: genericReactionMessages.noItemsToTradePhrases.random()
                    villager.talk(player, message, followDuringDialogue = true)
                }
            }, 1L)
        }

        builder.button(plugin.language.getString("interaction-menu.talk-button")!!) {
            if (premium) {
                if (player.getActiveDialogueSession() == null) DialogueSession(player, villager)
            } else player.sendFormattedMessage("This feature is only available in the premium version of the plugin. Please support the development by purchasing the plugin on Spigot.")
        }

        builder.button(plugin.language.getString("interaction-menu.close-button")!!) { menu ->
            menu.destroy()
        }

        builder.build()
    }

    private fun showQuestSuggestionMenu(player: Player, villager: Villager, quest: Quest) {

        val builder = Builder(villager, player)
        builder.button(plugin.language.getString("interaction-menu.accept-button")!!) {
            plugin.server.pluginManager.callEvent(PlayerAcceptQuestEvent(player, villager, quest))
        }

        builder.button(plugin.language.getString("interaction-menu.decline-button")!!) { menu ->
            menu.destroy()
        }

        builder.button(plugin.language.getString("interaction-menu.close-button")!!) { menu ->
            menu.destroy()
        }

        builder.build()

    }

    private fun showQuestListMenu(player: Player, villager: Villager) {

        val builder = Builder(villager, player)
        val quests  = villager.quests().toMutableList()

        quests.forEach { quest ->
            val useRainbow = false // TODO; Shall we use a special quest? quest.type == QuestManager.QuestType.ITEM_GATHERING
            builder.button(quest.name, isRainbow = useRainbow) {
                val description = (villager.let { villager ->
                    return@let when (villager.reputationOf(player)) {
                        Reputation.EXALTED -> quest.data.reputationBasedQuestDescriptions[7]
                        Reputation.REVERED -> quest.data.reputationBasedQuestDescriptions[6]
                        Reputation.HONORED -> quest.data.reputationBasedQuestDescriptions[5]
                        Reputation.FRIENDLY -> quest.data.reputationBasedQuestDescriptions[4]
                        Reputation.NEUTRAL -> quest.data.reputationBasedQuestDescriptions[3]
                        Reputation.UNFRIENDLY -> quest.data.reputationBasedQuestDescriptions[2]
                        Reputation.HOSTILE -> quest.data.reputationBasedQuestDescriptions[1]
                        Reputation.EXILED -> quest.data.reputationBasedQuestDescriptions[0]
                    }
                }).replace("%playerName%", player.name) // 4 — это френдли. Логично, что жители будут относиться к игрокам дружелюбно, если они окажутся наедине.

                villager.talk(player, description) {
                    this.showQuestSuggestionMenu(player, villager, quest)
                }
            }
        }

        builder.button(plugin.language.getString("interaction-menu.return-button")!!) { menu ->
            menu.destroy()
            this.showDefaultMenu(player, villager)
        }

        builder.build()
    }

    @EventHandler
    private fun onPlayerItemHeld(event: PlayerItemHeldEvent) {

        val player = event.player
        val menu   = openedMenuList.find { it.viewer == player } ?: return

        event.isCancelled = true

        // Double scroll fix.
        if (System.currentTimeMillis() - menu.lastScrollTime > 250) {
            menu.lastScrollTime = System.currentTimeMillis()
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1F, 2F)
            if (event.previousSlot < event.newSlot) {
                menu.index += 1
            } else menu.index -= 1
        }

    }

    @EventHandler
    private fun onPlayerInteract(event: PlayerInteractEvent) {
        event.clickedBlock?.let { block ->
            (block.blockData as? Bed)?.let { bed ->
                if (bed.isOccupied) event.isCancelled = true
            }
        }
    }

    @EventHandler
    private fun onPlayerDamageEntity(event: EntityDamageByEntityEvent) {
        (event.damager as? Player)?.let { player ->
            (event.entity as? Villager)?.let { entity ->

                // Dialogue skip
                if (dialogues.contains(player to entity)) {
                    dialogues[player to entity]?.destroy()
                    event.isCancelled = true
                    return
                }

                // Lethal damage check
                if (event.finalDamage >= entity.health) {
                    if (entity.equipment?.getItem(EquipmentSlot.OFF_HAND)?.type == Material.TOTEM_OF_UNDYING) {
                        val message = entity.getCharacterData()?.totemOfUndyingResurrectionPhrases?.random() ?: genericReactionMessages.totemOfUndyingResurrectionPhrases.random()
                        entity.talk(player, message, displaySize = 0.55F, followDuringDialogue = false, interruptPreviousDialogue = true)
                    }
                    return
                }

                // Hurt message
                val message = entity.getCharacterData()?.damagePhrases?.random() ?: genericReactionMessages.damagePhrases.random()
                entity.talk(player, message, displaySize = 0.55F, followDuringDialogue = false, interruptPreviousDialogue = true)

            }
        }
    }

    class Builder(villager: Villager, viewer: Player) {

        private val menu: Menu = Menu(villager, viewer)

        fun button(name: String, buttonColor: Color = defaultButtonColor, isRainbow: Boolean = false, action: (Menu) -> Unit): Builder {
            menu.addLine(name, buttonColor, isRainbow, {
                action(menu)
            })
            return this
        }

        fun build(): Menu {
            return menu
        }

    }

}
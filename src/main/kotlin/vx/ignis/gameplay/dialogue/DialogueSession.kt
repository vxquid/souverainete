@file:Suppress("DEPRECATION")

package vx.ignis.gameplay.dialogue

import com.cryptomorin.xseries.XSound
import io.papermc.paper.event.player.AsyncChatEvent
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.sendFormattedMessage
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import vx.ignis.persistent.VillagerExtend.professionLevelName
import vx.ignis.gameplay.memory.MemoryManager.Companion.getEmotionalMemory
import vx.ignis.gameplay.personality.PersonalityManager.Companion.gender
import vx.ignis.gameplay.personality.PersonalityManager.Companion.getPersonality
import vx.ignis.gameplay.trade.TradeHack.Companion.openCustomTradeMenu
import vx.ignis.persistent.LivingEntityExtend.getVoicePitch
import vx.ignis.persistent.LivingEntityExtend.getVoiceSound
import vx.ignis.util.Daytime

class DialogueSession(val player: Player, val entity: Villager) : Listener {

    var readyToSend = true
    var cancelled = false
        set(value) {
            if (value) {
                player.sendFormattedMessage(plugin.language.getString("info-messages.npc-conversation.ended")!!.replace("{npcName}", entity.customName ?: "NPC"))
                HandlerList.unregisterAll(this)
                activeDialogueSessions.remove(this)
                (entity as CraftVillager).handle.tradingPlayer = null
            }
            field = value
        }

    var giftAwaiting = false
        set(value) {
            if (value) {
                player.sendFormattedMessage(plugin.language.getString("info-messages.npc-conversation.waiting-for-gift")!!.replace("{npcName}", entity.customName ?: "NPC"))
            }
            field = value
        }

    init {
        player.sendFormattedMessage(plugin.language.getString("info-messages.npc-conversation.started")!!.replace("{npcName}", entity.customName ?: "NPC"))
        plugin.server.pluginManager.registerEvents(this, plugin)
        activeDialogueSessions.add(this)
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            this.keepAlive(task)
        }, 0L, 20L)
    }

    var lastMessageTime = System.currentTimeMillis()
    val dialogueHistory = mutableListOf<String>()

    private fun keepAlive(task: BukkitTask) {

        if (cancelled) {
            task.cancel()
            return
        }

        val timeout        = (System.currentTimeMillis() - lastMessageTime) / 1000 > 120
        val tooFar         = if (player.world != entity.world) true else player.location.distance(entity.location) > 8
        val differentWorld = player.world != entity.world
        val someoneIsDead  = player.isDead || entity.isDead
        if (timeout || tooFar || differentWorld || someoneIsDead) {
            this.cancelled = true
        } else {
            (entity as CraftVillager).handle.tradingPlayer = (player as CraftPlayer).handle // (player as CraftPlayer).handle
        }

    }

    @EventHandler
    private fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (giftAwaiting && event.player == player && !cancelled) {
            if (readyToSend) {
                this.cooldown()
                this.generateGiftReaction(player, entity, event.itemDrop.itemStack.clone(), dialogueHistory)
                // TODO: (entity as CraftVillager).handle.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(event.itemDrop.itemStack))
                giftAwaiting = false
                readyToSend = false
                lastMessageTime = System.currentTimeMillis()
                event.itemDrop.remove()
                player.playSound(entity.eyeLocation, entity.getVoiceSound(), 1F, entity.getVoicePitch())
            } else player.sendFormattedMessage(plugin.language.getString("info-messages.npc-conversation.cooldown")!!)
        }
    }

    @EventHandler
    private fun onPlayerChat(event: AsyncChatEvent) {
        val message = (event.message() as net.kyori.adventure.text.TextComponent).content()
        if (event.player == player && !cancelled) {
            event.isCancelled = true
            if (readyToSend) {
                plugin.server.scheduler.runTask(plugin) { _ ->
                    this.handleMessage(message)
                }
                dialogueHistory.add("${player.name}: \"${message}\" ->")
                lastMessageTime = System.currentTimeMillis()
                player.sendFormattedMessage(playerToNPCMessage.replace("{playerName}", player.name).replace("{message}", message).replace("&", "§"))
            } else player.sendFormattedMessage(plugin.language.getString("info-messages.npc-conversation.cooldown")!!)
        }
    }

    data class NPCChatResponseData(val npcResponse: List<String>, val memoryNode: String, val impression: String, val updatedOpinionOnPlayer: String, val directive: String)
    fun generateChatReply(player: Player, villager: Villager, playerMessage: String, dialogue: MutableList<String>) {

        val npcName = villager.customName ?: "unknown"
        val npcRace = villager.race.name

        val opinionOnPlayer  = villager.getEmotionalMemory().opinions[player.uniqueId] ?: "Unknown. It is their first meeting."
        val shortMemory      = villager.getEmotionalMemory().shortMemory.toString()
        val playerReputation = plugin.gameplayManager.reputationManager.getPlayerReputationStatus(villager, player)
        val tradeReadiness   = when {
            villager.profession == Villager.Profession.NONE -> "NPC can't trade due to lack of the profession. Help them find any! NPC must point it in the response."
            !villager.openCustomTradeMenu(player, false) -> "NPC has a profession, but cannot trade due to poorness: the NPC simply does not have items to trade."
            Daytime.fromWorldTime(villager.world.time) == Daytime.NIGHT -> "NPC must be annoyed by the player's attempt to trade during a time when all normal people are sleeping and dreaming!"
            else -> "NPC is ready to trade with the player. If the player suggests to trade, NPC will respond positively."
        }

        val biome = villager.world.getBiome(villager.location).toString().replace("_", " ").lowercase()
        val currentBiome   = biome.split(":").getOrNull(1) ?: biome
        val currentDaytime = Daytime.fromWorldTime(villager.world.time).toString().lowercase()
        val currentWeather = villager.world.let { if (it.isThundering) return@let "thunder" else if (it.isClearWeather) "clear" else "raining" }
        val activeEffects  = villager.activePotionEffects.map { it.type.toString() }.toString()

        val placeholders = mapOf(
            "playerName"         to player.name,
            "opinionOnPlayer"    to opinionOnPlayer,
            "npcName"            to npcName,
            "npcRace"            to npcRace,
            "npcGender"          to villager.gender.toString(),
            "npcPersonality"     to villager.getPersonality().toString(),
            "npcProfession"      to "${villager.profession}",
            "npcProfessionLevel" to villager.professionLevelName,
            "playerReputation"   to playerReputation.toString(),
            "currentBiome"       to currentBiome,
            "currentTime"        to currentDaytime,
            "currentWeather"     to currentWeather,
            "activeEffects"      to activeEffects,
            "playerMessage"      to playerMessage,
            "dialogueHistory"    to if (dialogue.isEmpty()) "[NO PREVIOUS MESSAGES. IT IS THE START OF THE DIALOGUE. GREET THE PLAYER IF NEEDED.]" else dialogue.toString(),
            "shortMemory"        to shortMemory,
            "tradeReadiness"     to tradeReadiness,
        )

        val prompt = placeholders.entries.fold(plugin.prompts.getString("npc-chat")!!) { acc, entry ->
            acc.replace("{${entry.key}}", entry.value)
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, { _ ->
            plugin.providerManager.client.sendPromptWithSchema(prompt, NPCChatResponseData::class)?.let { response ->
                this.handleChatResponse(response)
            } ?: run {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(plugin.language.getString("info-messages.npc-conversation.ai-overloaded")!!))
            }
        })

    }

    data class NPCGiftReaction(val npcResponse: List<String>, val memoryNode: String, val impression: String, val updatedOpinionOnPlayer: String, val keepTheGift: Boolean)
    fun generateGiftReaction(player: Player, villager: Villager, gift: ItemStack, dialogue: MutableList<String>) {

        val npcName = villager.customName ?: "unknown"
        val npcRace = villager.race.name

        val opinionOnPlayer  = villager.getEmotionalMemory().opinions[player.uniqueId] ?: "Unknown. It is their first meeting."
        val shortMemory      = villager.getEmotionalMemory().shortMemory.toString()
        val playerReputation = plugin.gameplayManager.reputationManager.getPlayerReputationStatus(villager, player)

        val biome          = villager.world.getBiome(villager.location).toString().replace("_", " ").lowercase()
        val currentBiome   = biome.split(":").getOrNull(1) ?: biome
        val currentDaytime = Daytime.fromWorldTime(villager.world.time).toString().lowercase()
        val currentWeather = villager.world.let { if (it.isThundering) return@let "thunder" else if (it.isClearWeather) "clear" else "raining" }
        val activeEffects  = villager.activePotionEffects.map { it.type.toString() }.toString()

        val placeholders = mapOf(
            "playerName"         to player.name,
            "opinionOnPlayer"    to opinionOnPlayer,
            "npcName"            to npcName,
            "npcRace"            to npcRace,
            "npcGender"          to villager.gender.toString(),
            "npcPersonality"     to villager.getPersonality().toString(),
            "npcProfession"      to "${villager.profession}",
            "npcProfessionLevel" to villager.professionLevelName,
            "playerReputation"   to playerReputation.toString(),
            "currentBiome"       to currentBiome,
            "currentTime"        to currentDaytime,
            "currentWeather"     to currentWeather,
            "activeEffects"      to activeEffects,
            "itemType"           to gift.type.toString().lowercase().replace("_", " "),
            "itemAmount"         to gift.amount.toString(),
            "dialogueHistory"    to if (dialogue.isEmpty()) "[NO PREVIOUS MESSAGES.]" else dialogue.toString(),
            "shortMemory"        to shortMemory,
        )

        val prompt = placeholders.entries.fold(plugin.prompts.getString("npc-gift-reaction")!!) { acc, entry ->
            acc.replace("{${entry.key}}", entry.value)
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, { _ ->
            plugin.providerManager.client.sendPromptWithSchema(prompt, NPCGiftReaction::class)?.let { reaction ->
                this.handleGiftReaction(player, villager, gift, reaction)
            } ?: run {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(plugin.language.getString("info-messages.npc-conversation.ai-overloaded")!!))
            }
        })

    }

    private enum class Impression(val score: Int) {
        DISASTROUS(-100), TERRIBLE(-25), BAD(-10), POOR(-5), MEDIOCRE(-1), NEUTRAL(0), GOOD(5), GREAT(10), EXCELLENT(25), AMAZING(50), PERFECT(100);
    }

    private enum class Directive {
        NONE, OPEN_TRADE_MENU, INTERRUPT_CONVERSATION;
    }

    private fun handleGiftReaction(player: Player, entity: Villager, gift: ItemStack, reaction: NPCGiftReaction) {

        if (!cancelled) {
            reaction.npcResponse.forEach { dialogueHistory.add("${entity.customName}: \"$it\" ->") }

            // NPC memory modification.
            entity.getEmotionalMemory().let { memory ->
                memory.shortMemory.add(reaction.memoryNode)
                memory.opinions[player.uniqueId] = reaction.updatedOpinionOnPlayer
                memory.save(entity)
            }

            val impression = Impression.valueOf(reaction.impression)

            // Sending messages.
            var delay = 0L
            for (message in reaction.npcResponse) {
                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                    player.sendFormattedMessage(npcResponseMessage.replace("{npcName}", entity.customName ?: "NPC").replace("{message}", message).replace("&", "§"))
                    player.playSound(player.eyeLocation, XSound.UI_TOAST_IN.get() ?: throw NullPointerException(), 1F, 1.25F)
                    // Handling directive only on last message of the response.
                    if (reaction.npcResponse.last() == message) {
                        if (!reaction.keepTheGift) {
                            entity.world.dropItem(entity.location, gift)
                        }
                        readyToSend = true

                        // TODO; Необходимо добавлять репутацию за нормальный базар. Наверное. Не забудь про подарки.
                    }
                }, delay)
                delay += 60L
            }

        }
    }

    private fun handleChatResponse(responseData: NPCChatResponseData) {
        if (!cancelled) {
            responseData.npcResponse.forEach { dialogueHistory.add("${entity.customName}: \"$it\" ->") }

            // NPC memory modification.
            entity.getEmotionalMemory().let { memory ->
                memory.shortMemory.add(responseData.memoryNode)
                memory.opinions[player.uniqueId] = responseData.updatedOpinionOnPlayer
                memory.save(entity)
            }

            val impression = Impression.valueOf(responseData.impression)
            val directive  = Directive.valueOf(responseData.directive)

            // Sending messages.
            var delay = 0L
            for (message in responseData.npcResponse) {
                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                    player.sendFormattedMessage(npcResponseMessage.replace("{npcName}", entity.customName ?: "NPC").replace("{message}", message).replace("&", "§"))
                    player.playSound(player.eyeLocation, XSound.UI_TOAST_IN.get() ?: throw NullPointerException(), 1F, 1.25F)
                    // Handling directive only on last message of the response.
                    if (responseData.npcResponse.last() == message) {

                        // TODO; Необходимо добавлять репутацию за нормальный базар. Наверное. Не забудь про подарки.

                        readyToSend = true
                        when (directive) {
                            Directive.OPEN_TRADE_MENU -> entity.openCustomTradeMenu(player)
                            Directive.INTERRUPT_CONVERSATION -> this.cancelled = true
                            Directive.NONE -> { /* I have nothing to do. */}
                        }
                    }
                }, delay)
                delay += 60L
            }

        }
    }

    @EventHandler
    private fun onEntityDeath(event: EntityDeathEvent) {
        if (event.entity == entity) {
            cancelled = true
        }
    }

    private fun handleMessage(message: String) {
        this.cooldown()
        this.generateChatReply(player, entity, message, dialogueHistory)
    }

    private fun cooldown() {
        readyToSend = false
        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            readyToSend = true
        }, 200L)
    }

    companion object {

        private val npcResponseMessage = "&7{npcName}&6ᵃⁱ&7: &f{message}"
        private val playerToNPCMessage = "&7{playerName}&6ᵃⁱ&7: &f{message}"

        val activeDialogueSessions = mutableListOf<DialogueSession>()
        fun Player.getActiveDialogueSession() : DialogueSession? {
            return activeDialogueSessions.find { it.player == this }
        }

    }

}
package vx.sv.gameplay.dialogue

import com.cryptomorin.xseries.XSound
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.ai.base.DummyClient
import vx.sv.gameplay.dialogue.DialogueSession.Companion.getActiveDialogueSession
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.party.PartyManager.Companion.partyLeaderUUID
import vx.sv.gameplay.personality.PersonalityManager.Companion.gender
import vx.sv.gameplay.personality.PersonalityManager.Companion.getPersonality
import vx.sv.gameplay.settlement.isSettlementLeader
import vx.sv.persistent.LivingEntityExtend.getVoicePitch
import vx.sv.persistent.LivingEntityExtend.getVoiceSound
import vx.sv.util.Daytime
import vx.sv.util.VivaldiHook
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Global listener for party communication via '@'
class PartyChatManager : Listener, PacketListenerAbstract(PacketListenerPriority.HIGHEST) {

    private val MAX_PARTY_MEMORY_SIZE = 20

    // Store dialogue history per player UUID globally in memory
    private val partyHistories = ConcurrentHashMap<UUID, MutableList<String>>()

    // Prevent spamming
    private val processingPlayers = ConcurrentHashMap<UUID, Boolean>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        PacketEvents.getAPI().eventManager.registerListener(this)
    }

    // Clean up memory when player leaves
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        partyHistories.remove(event.player.uniqueId)
        processingPlayers.remove(event.player.uniqueId)
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType != PacketType.Play.Client.CHAT_MESSAGE) return

        val wrapper = WrapperPlayClientChatMessage(event)
        val message = wrapper.message

        // Only intercept messages starting with '@'
        if (!message.startsWith("@")) return

        event.isCancelled = true
        val player = plugin.server.getPlayer(event.user.uuid) ?: return
        val partyMessage = message.substring(1).trim()

        if (partyMessage.isEmpty()) return

        if (processingPlayers[player.uniqueId] == true) {
            player.sendFormattedMessage(plugin.language.getString("info-messages.npc-conversation.cooldown") ?: "§cWait for the party to respond!")
            return
        }

        processingPlayers[player.uniqueId] = true

        val history = partyHistories.computeIfAbsent(player.uniqueId) { mutableListOf() }
        history.add("${player.name}: \"${partyMessage}\" ->")
        if (history.size > MAX_PARTY_MEMORY_SIZE) history.removeAt(0)

        // Send confirmation message to player
        player.sendFormattedMessage("§8[Party] §7${player.name}§6ᵃⁱ§8: §f$partyMessage".replace("&", "§"))

        plugin.server.scheduler.runTask(plugin) { _ ->
            generatePartyChatReply(player, partyMessage, history)
        }
    }

    data class PartyDialogueLine(val speakerId: String, val speakerName: String, val message: String)
    data class PartyChatResponseData(val conversation: List<PartyDialogueLine>)

    private fun generatePartyChatReply(player: Player, playerMessage: String, dialogue: MutableList<String>) {
        if (plugin.providerManager.client is DummyClient) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§cAI is not configured..."))
            processingPlayers[player.uniqueId] = false
            return
        }

        // Search for party members around the player
        val partyMembers = player.getNearbyEntities(16.0, 16.0, 16.0)
            .filterIsInstance<Villager>()
            .filter { it.partyLeaderUUID == player.uniqueId }

        if (partyMembers.isEmpty()) {
            player.sendFormattedMessage("§cNo party members nearby to hear you.")
            processingPlayers[player.uniqueId] = false
            return
        }

        // World context
        val biome          = player.world.getBiome(player.location).key.key.replace("_", " ").lowercase()
        val currentBiome   = biome.split(":").getOrNull(1) ?: biome
        val currentDaytime = Daytime.fromWorldTime(player.world.time).toString().lowercase()
        val currentWeather = player.world.let { if (it.isThundering) return@let "thunder" else if (it.isClearWeather) "clear" else "raining" }

        // CHANGED: Безопасное получение сезона и динамическое формирование строки
        val currentSeason  = VivaldiHook.getCurrentSeasonName()
        val seasonContext  = if (currentSeason != null) "\n            - Season: $currentSeason" else ""

        // Dialogue context (If the player is currently talking to an NPC)
        val activeSession = player.getActiveDialogueSession()
        val interactionContext = if (activeSession != null) {
            val npcName = activeSession.entity.customName ?: "Unknown"
            val actualProfession = if (activeSession.entity.isSettlementLeader()) activeSession.entity.race.leaderTitle else activeSession.entity.profession.key.key
            "The player is currently talking to an NPC named $npcName (${activeSession.entity.race.name}, $actualProfession)."
        } else {
            "The player is not talking to anyone else. The party is just traveling, exploring, or resting."
        }

        // Build party members profiles for the AI
        val partyMembersContext = partyMembers.joinToString("\n") { member ->
            val profName = if (member.isSettlementLeader()) member.race.leaderTitle else member.profession.key.key
            "- Name: ${member.customName ?: "Unknown"}, UUID: ${member.uniqueId}, Race: ${member.race.name}, Gender: ${member.gender}, Personality: ${member.getPersonality()}, Profession: $profName"
        }

        // CHANGED: seasonContext вставлен безопасно (если null, он просто исчезнет)
        val partyPrompt = """
            You are an AI generating a natural, grounded group conversation between a player and their party members in a medieval fantasy world.
            
            ### CURRENT ENVIRONMENT (STRICT FACTS):
            - Biome: $currentBiome
            - Time: $currentDaytime$seasonContext
            - Weather: $currentWeather
            - Interaction Status: $interactionContext
            
            ### PARTY MEMBERS:
            $partyMembersContext
            
            ### CHAT HISTORY:
            ${if (dialogue.isEmpty()) "[NO PREVIOUS PARTY MESSAGES]" else dialogue.joinToString("\n")}
            
            ### PLAYER'S MESSAGE:
            ${player.name}: "$playerMessage"
            
            ### INSTRUCTIONS:
            1. Keep the conversation natural, casual, and grounded. Act like normal traveling companions. Respect the CURRENT ENVIRONMENT strictly.
            2. Messages MUST be short (1-2 sentences). Generate a chain of 3 to 6 distinct messages to simulate a real back-and-forth chat.
            3. Address the player using exactly "%playerName%".
            4. You CAN mix actions and dialogue in the same message. Enclose actions in asterisks (e.g., *looks around* We should keep moving.).
            5. Return ONLY a JSON object with a single array "conversation". Each element: "speakerId" (the exact UUID), "speakerName", and "message".
        """.trimIndent()

        plugin.server.scheduler.runTaskAsynchronously(plugin) { _ ->
            plugin.providerManager.client.sendPromptWithSchema(partyPrompt, PartyChatResponseData::class)?.let { response ->
                handlePartyChatResponse(player, response, partyMembers, dialogue)
            } ?: run {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacy(plugin.language.getString("info-messages.npc-conversation.ai-overloaded") ?: "§cAI overloaded"))
                processingPlayers[player.uniqueId] = false
            }
        }
    }

    private fun handlePartyChatResponse(player: Player, responseData: PartyChatResponseData, partyMembers: List<Villager>, history: MutableList<String>) {
        if (responseData.conversation.isEmpty()) {
            processingPlayers[player.uniqueId] = false
            return
        }

        // Store raw generated replies into memory
        responseData.conversation.forEach { line ->
            history.add("${line.speakerName}: \"${line.message}\" ->")
        }
        if (history.size > MAX_PARTY_MEMORY_SIZE) {
            history.subList(0, history.size - MAX_PARTY_MEMORY_SIZE).clear()
        }

        var delay = 0L
        for (line in responseData.conversation) {
            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                val speaker = partyMembers.find { it.uniqueId.toString() == line.speakerId }
                val actualName = speaker?.customName ?: line.speakerName

                // Replace player placeholder properly
                val rawMessage = line.message.replace("%playerName%", player.name)

                // Smart Regex parser: colors *actions* gray italic, and leaves normal text white
                val formattedText = formatMixedMessage(rawMessage)

                val formattedMessage = "§8[Party] §b$actualName§6ᵃⁱ§8: $formattedText"

                player.sendFormattedMessage(formattedMessage)
                player.playSound(player.eyeLocation, XSound.UI_TOAST_IN.get() ?: throw NullPointerException(), 1F, 1.25F)

                speaker?.let {
                    player.playSound(it.eyeLocation, it.getVoiceSound(), 1F, it.getVoicePitch())
                }

                if (responseData.conversation.last() === line) {
                    processingPlayers[player.uniqueId] = false
                }
            }, delay)

            delay += 50L
        }
    }

    companion object {
        /**
         * Parses text to properly color actions wrapped in asterisks.
         * Default color is White (§f). Text inside *asterisks* becomes Gray Italic (§7§o).
         */
        fun formatMixedMessage(text: String): String {
            // Set base color for the message to white
            var formatted = "§f$text"

            // Regex finds any text between asterisks (e.g. *smiles*)
            formatted = formatted.replace(Regex("\\*([^*]+)\\*")) { match ->
                // Apply gray italic, re-add asterisks, then reset to white after the action
                "§7§o*${match.groupValues[1]}*§f"
            }

            return formatted
        }
    }
}
package vx.sv.gameplay

import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.plugin
import vx.sv.config.lib.GameplayConfiguration
import vx.sv.gameplay.death.DeathManager
import vx.sv.gameplay.dialogue.DialogueManager
import vx.sv.gameplay.dialogue.PartyChatManager
import vx.sv.gameplay.dialogue.menu.InteractionHandler
import vx.sv.gameplay.humanoid.HumanoidManager
import vx.sv.gameplay.humanoid.HungerManager
import vx.sv.gameplay.party.PartyManager
import vx.sv.gameplay.personality.PersonalityManager
import vx.sv.gameplay.profession.ProfessionManager
import vx.sv.gameplay.quest.QuestManager
import vx.sv.gameplay.reputation.ReputationManager
import vx.sv.gameplay.reputation.ReputationTracker
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.settlement.SettlementManager.Companion.settlements
import vx.sv.gameplay.settlement.politics.PoliticsManager
import vx.sv.gameplay.settlement.politics.RaidManager
import vx.sv.gameplay.trade.TradeManager
import vx.sv.nms.VersionBridge

class GameplayManager(val firstWorld: World) : Listener {

    val config: GameplayConfiguration    = plugin.gameplayConfig
    val allowedWorlds: MutableSet<World> = mutableSetOf()

    val actualQuests = firstWorld.persistentDataContainer.get(NamespacedKey(plugin, "ActualQuests"), PersistentDataType.LONG_ARRAY)?.toMutableList() ?: mutableListOf<Long>().toLongArray().also {
        firstWorld.persistentDataContainer.set(NamespacedKey(plugin, "ActualQuests"), PersistentDataType.LONG_ARRAY, it)
    }.toMutableList()

    init {
        plugin.gameplayManager = this
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (plugin.gameplayConfig.worlds.allowedWorlds.contains(firstWorld.name)) {
            this.allowedWorlds.add(firstWorld) // Add the first world only if it is allowed in cfg.
        }
    }

    val personalityManager = PersonalityManager()
    val reputationManager  = ReputationManager()
    val reputationTracker  = ReputationTracker()
    val dialogueManager    = DialogueManager()
    val questManager       = QuestManager()
    val interactionManager = InteractionHandler()
    val tradeManager       = TradeManager()
    val humanoidManager    = HumanoidManager()
    val professionManager  = ProfessionManager()
    val settlementManager  = SettlementManager()
    val politicsManager    = PoliticsManager()
    val raidManager        = RaidManager()
    val hungerManager      = HungerManager.also { it.startTicker() }
    val partyManager       = PartyManager(plugin, plugin.gameplayConfig)
    val partChatManager    = PartyChatManager()
    val deathManager       = DeathManager()
    val versionBridge      = VersionBridge(plugin)

    @EventHandler
    private fun onWorldLoad(event: WorldLoadEvent) {
        if (config.worlds.allowedWorlds.contains(event.world.name)) {
            this.allowedWorlds.add(event.world)
            this.settlementManager.handleWorldLoad(event.world).also {
                if (!settlements.keys.contains(firstWorld)) settlementManager.handleWorldLoad(firstWorld)
                plugin.gameplayManager.raidManager.restoreRaidsFromData(settlements[event.world] ?: emptyList())
            }
        }
    }
}
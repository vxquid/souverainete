package vx.ignis.gameplay

import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.config.GameplayConfiguration
import vx.ignis.config.lib.ConfigurationManager
import vx.ignis.gameplay.death.DeathManager
import vx.ignis.gameplay.dialogue.DialogueManager
import vx.ignis.gameplay.dialogue.menu.InteractionManager
import vx.ignis.gameplay.dictionary.CustomItemDictionary
import vx.ignis.gameplay.humanoid.HumanoidManager
import vx.ignis.gameplay.humanoid.HungerManager
import vx.ignis.gameplay.party.PartyManager
import vx.ignis.gameplay.personality.PersonalityManager
import vx.ignis.gameplay.profession.ProfessionManager
import vx.ignis.gameplay.quest.QuestManager
import vx.ignis.gameplay.reputation.ReputationManager
import vx.ignis.gameplay.settlement.SettlementManager
import vx.ignis.gameplay.settlement.SettlementManager.Companion.settlements
import vx.ignis.gameplay.trade.TradeManager
import vx.ignis.nms.VersionBridge

class GameplayManager(val firstWorld: World) : Listener {

    val config: GameplayConfiguration    = ConfigurationManager.load(GameplayConfiguration::class.java)
    val allowedWorlds: MutableSet<World> = mutableSetOf(firstWorld)

    val actualQuests = firstWorld.persistentDataContainer.get(NamespacedKey(plugin, "ActualQuests"), PersistentDataType.LONG_ARRAY)?.toMutableList() ?: mutableListOf<Long>().toLongArray().also {
        firstWorld.persistentDataContainer.set(NamespacedKey(plugin, "ActualQuests"), PersistentDataType.LONG_ARRAY, it)
    }.toMutableList()

    init {
        plugin.gameplayManager = this
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    val itemDictionary     = CustomItemDictionary()
    val personalityManager = PersonalityManager()
    val reputationManager  = ReputationManager()
    val dialogueManager    = DialogueManager()
    val questManager       = QuestManager()
    val interactionManager = InteractionManager()
    val tradeManager       = TradeManager()
    val humanoidManager    = HumanoidManager()
    val professionManager  = ProfessionManager()
    val settlementManager  = SettlementManager()
    val hungerManager      = HungerManager.also { it.startTicker() }
    val partyManager       = PartyManager(plugin)
    val deathManager       = DeathManager()
    val versionBridge      = VersionBridge(plugin)

    @EventHandler
    private fun onWorldLoad(event: WorldLoadEvent) {
        if (config.worlds.allowedWorlds.contains(event.world.name)) {
            this.allowedWorlds.add(event.world)
            this.settlementManager.handleWorldLoad(event.world).also {
                if (!settlements.keys.contains(firstWorld)) settlementManager.handleWorldLoad(firstWorld)
            }
        }
    }

}
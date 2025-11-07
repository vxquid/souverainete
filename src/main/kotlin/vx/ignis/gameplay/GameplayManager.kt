package vx.ignis.gameplay

import de.exlll.configlib.YamlConfigurations
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.properties
import vx.ignis.config.GameplayConfiguration
import vx.ignis.gameplay.dialogue.DialogueManager
import vx.ignis.gameplay.dialogue.menu.InteractionManager
import vx.ignis.gameplay.dictionary.CustomItemDictionary
import vx.ignis.gameplay.humanoid.HumanoidManager
import vx.ignis.gameplay.personality.PersonalityManager
import vx.ignis.gameplay.quest.QuestManager
import vx.ignis.gameplay.reputation.ReputationManager
import vx.ignis.gameplay.trade.TradeHack
import java.io.File

class GameplayManager(val firstWorld: World) : Listener {

    val config: GameplayConfiguration = run {
        YamlConfigurations.update(File(plugin.dataFolder, "gameplay.yml").toPath(), GameplayConfiguration::class.java, properties)
        YamlConfigurations.load(File(plugin.dataFolder, "gameplay.yml").toPath(), GameplayConfiguration::class.java, properties)
    }

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
    val tradeHack          = TradeHack()
    val humanoidManager    = HumanoidManager()

    @EventHandler
    private fun onWorldLoad(event: WorldLoadEvent) {
        if (config.allowedWorlds.contains(event.world.name)) {
            this.allowedWorlds.add(event.world)
        }
    }

}
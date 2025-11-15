package vx.ignis.gameplay.quest

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.event.QuestInvalidationEvent
import vx.ignis.gameplay.quest.QuestManager.Companion.addQuest
import vx.ignis.gameplay.quest.QuestManager.Companion.removeQuest
import vx.ignis.persistent.LivingEntityExtend.quests

class ProgressTracker : Listener {

    private val progressTickerPauseDuration = 20L

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            this.tick()
        }, 0L, progressTickerPauseDuration)
    }

    /* Обновление боссбаров происходит тут. Логика обновления зависит от типа квеста. */
    private fun tick() {
        questTracker.forEach { player, (quest, bar) ->
            when (quest.family) {
                QuestManager.QuestFamily.GATHERING -> {
                    val questItem = quest.questItem.getItemStack()
                    val requiredAmount = questItem.amount
                    val currentAmount  = player.inventory.contents.filterNotNull().filter { it.isSimilar(questItem) }.sumOf { it.amount }
                    val step = 1.0 / requiredAmount
                    bar.progress = (currentAmount * step).coerceAtMost(1.0)
                    quest.progress = bar.progress
                    if (bar.progress == 1.0) {
                        if (bar.color != BarColor.GREEN) player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F)
                        bar.color = BarColor.GREEN
                    } else bar.color = BarColor.RED
                }
            }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        player.quests().forEach { quest ->
            if (!plugin.gameplayManager.actualQuests.contains(quest.id)) {
                plugin.gameplayManager.questManager.invalidateQuest(quest, QuestInvalidationEvent.Reason.NOT_ACTUAL)
                return@forEach
            }
            if (plugin.gameplayManager.actualQuests.contains(quest.id) && quest.tracking) {
                questTracker[player] = quest to this.startTracking(player, quest)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        questTracker[player]?.let { (quest) ->
            player.removeQuest(quest)
            player.addQuest(quest)
        }
        questTracker.remove(player)
    }

    fun startTracking(player: Player, quest: QuestManager.Quest): BossBar {
        val progressBar = Bukkit.createBossBar("§6${quest.name}§f: ${quest.data.extraShortTaskDescription.replace("%playerName%", player.name)}", BarColor.RED, BarStyle.SEGMENTED_20)
        progressBar.progress = quest.progress
        progressBar.addPlayer(player)
        questTracker[player] = quest to progressBar
        quest.tracking = true
        return progressBar
    }

    fun stopTracking(player: Player, questData: QuestManager.Quest) {
        player.getTrackedQuest()?.let { (trackedQuest, progressBar) ->
            if (trackedQuest.id == questData.id) {
                progressBar.removePlayer(player)
                questTracker.remove(player)
            }
        }
    }

    companion object {

        val questTracker = mutableMapOf<Player, Pair<QuestManager.Quest, BossBar>>()
        fun Player.getTrackedQuest() : Pair<QuestManager.Quest, BossBar>? = questTracker[this]

        var Player.questsCompleted : Long
            get() = this.persistentDataContainer.get(questCompletedKey, PersistentDataType.LONG) ?: 0L.also{this.persistentDataContainer.set(questCompletedKey, PersistentDataType.LONG, it)}
            set(value) = this.persistentDataContainer.set(questCompletedKey, PersistentDataType.LONG, value)

        var Player.questsFailed : Long
            get() = this.persistentDataContainer.get(questFailedKey, PersistentDataType.LONG) ?: 0L.also{this.persistentDataContainer.set(questFailedKey, PersistentDataType.LONG, it)}
            set(value) = this.persistentDataContainer.set(questFailedKey, PersistentDataType.LONG, value)

        var Player.experienceEarnedByQuests : Long
            get() = this.persistentDataContainer.get(experienceEarnedKey, PersistentDataType.LONG) ?: 0L.also{this.persistentDataContainer.set(experienceEarnedKey, PersistentDataType.LONG, it)}
            set(value) = this.persistentDataContainer.set(experienceEarnedKey, PersistentDataType.LONG, value)

        private val questCompletedKey = NamespacedKey(plugin, "QuestsCompleted")
        private val questFailedKey = NamespacedKey(plugin, "QuestsFailed")
        private val experienceEarnedKey = NamespacedKey(plugin, "ExperienceEarned")

    }

}
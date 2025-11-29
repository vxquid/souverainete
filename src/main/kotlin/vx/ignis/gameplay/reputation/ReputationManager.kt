package vx.ignis.gameplay.reputation

import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import vx.ignis.Ignis.Companion.plugin
import java.util.*

class ReputationManager {

    fun getReputationMap(entity: LivingEntity): MutableMap<UUID, Int> {
        val pdc = entity.persistentDataContainer
        if (pdc.has(REP_KEY, PersistentDataType.STRING)) {
            val str = pdc.get(REP_KEY, PersistentDataType.STRING)!!
            return str.split(",").mapNotNull { part ->
                val parts = part.split(":")
                if (parts.size == 2) UUID.fromString(parts[0]) to parts[1].toInt() else null
            }.toMap().toMutableMap()
        }
        return HashMap()
    }

    fun setReputationMap(entity: LivingEntity, map: Map<UUID, Int>) {
        val pdc = entity.persistentDataContainer
        if (map.isEmpty()) {
            pdc.remove(REP_KEY)
        } else {
            val str = map.map { "${it.key}:${it.value}" }.joinToString(",")
            pdc.set(REP_KEY, PersistentDataType.STRING, str)
        }
    }

    fun addReputation(entity: LivingEntity, player: Player, value: Int) {

        val statusUpdateMessage = plugin.language.getString("settlement-reputation.status-update")!!

        val map = getReputationMap(entity)
        val previousRep = map[player.uniqueId] ?: 0
        val previousStatus = getPlayerReputationStatus(entity, player)
        map[player.uniqueId] = previousRep + value
        setReputationMap(entity, map)
        val newStatus = getPlayerReputationStatus(entity, player)

        // Уведомление об изменении репутации
        if (plugin.gameplayManager.config.reputation.chatNotification && value != 0) {
            val entityDescription = entity.customName ?: "nearby inhabitants"
            notifyReputationChange(player, value, entityDescription)
        }

        if (previousStatus != newStatus && plugin.gameplayManager.config.reputation.chatNotification) {
            val entityName = entity.customName ?: "nearby inhabitants"
            val statusChangeMessage = statusUpdateMessage.replace("{entity}", entityName).replace("{status}", newStatus.getLocalizedName())
            player.sendMessage(statusChangeMessage)
            player.playSound(player.eyeLocation, plugin.gameplayManager.config.reputation.statusUpdateSound, 1F, 1F)
        }

    }

    private fun notifyReputationChange(player: Player, aggregatedValue: Int, entityDescription: String) {
        val increaseMessage = plugin.language.getString("settlement-reputation.increase")!!
        val decreaseMessage = plugin.language.getString("settlement-reputation.decrease")!!

        val reputationChangeMessage = (if (aggregatedValue > 0) increaseMessage else decreaseMessage)
            .replace("{entity}", entityDescription)
            .replace("{amount}", Math.abs(aggregatedValue).toString())
        player.sendMessage(reputationChangeMessage)
    }

    fun setReputation(entity: LivingEntity, player: Player, value: Int) {
        val statusUpdateMessage = plugin.language.getString("settlement-reputation.status-update")!!

        val previousStatus = getPlayerReputationStatus(entity, player)
        val map = getReputationMap(entity)
        val previousRep = map[player.uniqueId] ?: 0
        map[player.uniqueId] = value
        setReputationMap(entity, map)
        val newStatus = getPlayerReputationStatus(entity, player)

        // Уведомление об изменении репутации (аналогично addReputation, если значение изменилось)
        if (plugin.gameplayManager.config.reputation.chatNotification && value != previousRep) {
            val entityDescription = entity.customName ?: entity.type.name.lowercase().capitalize()
            val changeValue = value - previousRep
            notifyReputationChange(player, changeValue, entityDescription)
        }

        if (previousStatus != newStatus && plugin.gameplayManager.config.reputation.chatNotification) {
            val entityName = entity.customName ?: entity.type.name.lowercase().capitalize()
            val statusChangeMessage = statusUpdateMessage.replace("{entity}", entityName).replace("{status}", newStatus.getLocalizedName())
            player.sendMessage(statusChangeMessage)
            player.playSound(player.eyeLocation, plugin.gameplayManager.config.reputation.statusUpdateSound, 1F, 1F)
        }

    }

    enum class Reputation(val priceMultiplier: Double) {
        EXALTED(0.6),
        REVERED(0.7),
        HONORED(0.9),
        FRIENDLY(0.9),
        NEUTRAL(1.0),
        UNFRIENDLY(1.25),
        HOSTILE(1.5),
        EXILED(2.0);

        fun getLocalizedName(): String {
            return plugin.language.getString("reputation.status.${this.name.lowercase()}")!!
        }

    }

    fun getPlayerReputationStatus(entity: LivingEntity, player: Player): Reputation {
        val reputation = getReputationMap(entity)[player.uniqueId] ?: 0
        val config = plugin.gameplayManager.config

        return when {
            reputation >= config.reputation.exaltedRequired -> Reputation.EXALTED
            reputation >= config.reputation.reveredRequired -> Reputation.REVERED
            reputation >= config.reputation.honoredRequired -> Reputation.HONORED
            reputation >= config.reputation.friendlyRequired -> Reputation.FRIENDLY
            reputation >= config.reputation.neutralRequired -> Reputation.NEUTRAL
            reputation >= config.reputation.unfriendlyRequired -> Reputation.UNFRIENDLY
            reputation >= config.reputation.hostileRequired -> Reputation.HOSTILE
            reputation >= config.reputation.exiledRequired -> Reputation.EXILED
            else -> Reputation.EXILED
        }
    }

    companion object {

        val REP_KEY = NamespacedKey(plugin, "ReputationData")

        fun LivingEntity.reputationOf(player: Player): Reputation {
            return plugin.gameplayManager.reputationManager.getPlayerReputationStatus(this, player)
        }

    }

}

package vx.sv.gameplay.party

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import vx.sv.config.GameplayConfiguration

import java.util.*

class PartyManager(
    private val plugin: JavaPlugin,
    private val config: GameplayConfiguration
) : Listener {

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    enum class PartyState {
        FOLLOW, // Житель следует за игроком (по умолчанию)
        STAY    // Житель стоит на месте
    }

    enum class CombatTactic {
        AUTO,
        MELEE,
        RANGED
    }

    fun setPartyState(villager: Villager, state: PartyState) {
        villager.partyState = state
    }

    fun getPartyState(villager: Villager): PartyState {
        return villager.partyState
    }

    fun togglePartyState(villager: Villager): PartyState {
        val newState = if (villager.partyState == PartyState.FOLLOW) PartyState.STAY else PartyState.FOLLOW
        villager.partyState = newState
        return newState
    }

    fun setCombatTactic(villager: Villager, tactic: CombatTactic) {
        villager.combatTactic = tactic
    }

    fun getCombatTactic(villager: Villager): CombatTactic {
        return villager.combatTactic
    }

    fun cycleCombatTactic(villager: Villager): CombatTactic {
        val current = villager.combatTactic
        val next = when (current) {
            CombatTactic.AUTO -> CombatTactic.MELEE
            CombatTactic.MELEE -> CombatTactic.RANGED
            CombatTactic.RANGED -> CombatTactic.AUTO
        }
        villager.combatTactic = next
        return next
    }

    fun addMember(leader: Player, villager: Villager): Boolean {
        if (hasParty(villager)) return false

        // Используем extension для получения списка
        val currentMembers = leader.partyMemberUUIDs.toMutableList()

        // Проверка лимита из конфига
        if (currentMembers.size >= config.party.maxPartySize) return false

        // Установка лидера через extension
        villager.partyLeaderUUID = leader.uniqueId

        // При вступлении сбрасываем настройки на дефолт из конфига
        val defaultState = try {
            PartyState.valueOf(config.party.defaultPartyState)
        } catch (_: IllegalArgumentException) {
            PartyState.FOLLOW
        }

        val defaultTactic = try {
            CombatTactic.valueOf(config.party.defaultCombatTactic)
        } catch (_: IllegalArgumentException) {
            CombatTactic.AUTO
        }

        villager.partyState = defaultState
        villager.combatTactic = defaultTactic

        currentMembers.add(villager.uniqueId)
        leader.partyMemberUUIDs = currentMembers // Сохранение через extension
        return true
    }

    fun removeMember(leader: Player, villager: Villager) {
        // Очистка данных жителя
        villager.persistentDataContainer.remove(leaderKey)
        villager.persistentDataContainer.remove(stateKey)
        villager.persistentDataContainer.remove(tacticKey)

        // Обновление списка игрока
        val currentMembers = leader.partyMemberUUIDs.toMutableList()
        if (currentMembers.remove(villager.uniqueId)) {
            leader.partyMemberUUIDs = currentMembers
        }
    }

    fun isMember(leader: Player, villager: Villager): Boolean {
        return villager.partyLeaderUUID == leader.uniqueId
    }

    fun hasParty(villager: Villager): Boolean {
        return villager.persistentDataContainer.has(leaderKey, PersistentDataType.STRING)
    }

    fun getLeaderUUID(villager: Villager): UUID? {
        return villager.partyLeaderUUID
    }

    fun getMemberUUIDs(player: Player): List<UUID> {
        return player.partyMemberUUIDs
    }

    @EventHandler
    private fun onVillagerDeath(event: EntityDeathEvent) {
        // Проверяем настройку конфига
        if (!config.party.removeMemberOnDeath) return

        val villager = event.entity as? Villager ?: return
        val leaderUUID = villager.partyLeaderUUID ?: return

        val leaderPlayer = plugin.server.getPlayer(leaderUUID)
        if (leaderPlayer != null) {
            val currentMembers = leaderPlayer.partyMemberUUIDs.toMutableList()
            if (currentMembers.remove(villager.uniqueId)) {
                leaderPlayer.partyMemberUUIDs = currentMembers
            }
        }
    }

    // --- COMPANION OBJECT (Global Access) ---

    companion object {
        // Ключи публичные и статические
        val leaderKey = NamespacedKey("sv", "party_leader")
        val membersKey = NamespacedKey("sv", "party_members")
        val stateKey = NamespacedKey("sv", "party_state")
        val tacticKey = NamespacedKey("sv", "party_tactic")

        private val gson = GsonBuilder().create()

        // --- Extension Properties для удобного доступа из любого места ---

        /**
         * Состояние поведения жителя (FOLLOW / STAY).
         */
        var LivingEntity.partyState: PartyState
            get() {
                val stateName = this.persistentDataContainer.get(stateKey, PersistentDataType.STRING)
                    ?: return PartyState.FOLLOW
                return try { PartyState.valueOf(stateName) } catch (_: Exception) { PartyState.FOLLOW }
            }
            set(value) {
                this.persistentDataContainer.set(stateKey, PersistentDataType.STRING, value.name)
            }

        /**
         * Боевая тактика жителя (AUTO / MELEE / RANGED).
         */
        var LivingEntity.combatTactic: CombatTactic
            get() {
                val tacticName = this.persistentDataContainer.get(tacticKey, PersistentDataType.STRING)
                    ?: return CombatTactic.AUTO
                return try { CombatTactic.valueOf(tacticName) } catch (_: Exception) { CombatTactic.AUTO }
            }
            set(value) {
                this.persistentDataContainer.set(tacticKey, PersistentDataType.STRING, value.name)
            }

        /**
         * UUID лидера пати (если есть).
         */
        var LivingEntity.partyLeaderUUID: UUID?
            get() {
                val uuidStr = this.persistentDataContainer.get(leaderKey, PersistentDataType.STRING) ?: return null
                return try { UUID.fromString(uuidStr) } catch (_: IllegalArgumentException) { null }
            }
            set(value) {
                if (value == null) {
                    this.persistentDataContainer.remove(leaderKey)
                } else {
                    this.persistentDataContainer.set(leaderKey, PersistentDataType.STRING, value.toString())
                }
            }

        /**
         * Список UUID членов пати игрока.
         */
        var Player.partyMemberUUIDs: List<UUID>
            get() {
                val json = this.persistentDataContainer.get(membersKey, PersistentDataType.STRING) ?: return emptyList()
                return try {
                    val type = object : TypeToken<List<UUID>>() {}.type
                    gson.fromJson(json, type) ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }
            set(value) {
                if (value.isEmpty()) {
                    this.persistentDataContainer.remove(membersKey)
                } else {
                    val json = gson.toJson(value)
                    this.persistentDataContainer.set(membersKey, PersistentDataType.STRING, json)
                }
            }
    }
}
package vx.sv.persistent

import com.google.gson.annotations.SerializedName
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin

/**
 * Available control schemes for interactions.
 */
enum class MenuControlMode {
    CURSOR, SCROLL
}

/**
 * Enum for determining the preferred nametag style.
 */
enum class NametagMode {
    ADVANCED, VANILLA
}

/**
 * Enum for determining the preferred length of quest dialogues.
 */
enum class QuestDialogueLength {
    LONG, SHORT
}

/**
 * Data class representing player-specific preferences.
 * By using default parameters, GSON will naturally handle adding new configurations
 * in future updates without breaking existing stored JSONs.
 */
data class PlayerPreferences(
    @SerializedName("menu_control")
    var menuControl: MenuControlMode = MenuControlMode.SCROLL,

    @SerializedName("nametag_mode")
    var nametagMode: NametagMode = NametagMode.ADVANCED,

    @SerializedName("quest_dialogue_length")
    var questDialogueLength: QuestDialogueLength = QuestDialogueLength.LONG

    // Feel free to add more preferences here later.
    // Example: var enableHolograms: Boolean = true
)

object PlayerPreferencesManager {
    private val PREFERENCE_KEY = NamespacedKey(plugin, "player_preferences")

    /**
     * Extension property for easy read/write access to player preferences.
     * Example: val mode = player.preferences.menuControl
     * To save: player.preferences = prefs
     */
    var Player.preferences: PlayerPreferences
        get() {
            val json = this.persistentDataContainer.get(PREFERENCE_KEY, PersistentDataType.STRING)
            return if (json != null) {
                try {
                    gson.fromJson(json, PlayerPreferences::class.java)
                } catch (e: Exception) {
                    // Fallback if the stored JSON is somehow corrupted
                    PlayerPreferences()
                }
            } else {
                PlayerPreferences()
            }
        }
        set(value) {
            val json = gson.toJson(value)
            this.persistentDataContainer.set(PREFERENCE_KEY, PersistentDataType.STRING, json)
        }
}
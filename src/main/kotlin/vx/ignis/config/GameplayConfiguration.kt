package vx.ignis.config

import de.exlll.configlib.Comment
import de.exlll.configlib.Configuration
import org.bukkit.entity.Display.Billboard

@Configuration
class GameplayConfiguration {

    @Comment("Don't touch this, or you'll see a lot weird things.")
    val debug: Boolean = false

    @Comment("Message prefix.")
    val messagePrefix: String = "§e🔥 §8|"

    @Comment("Specify the names of the worlds where you want Ignis to work.")
    var allowedWorlds: List<String> = listOf("world", "world_nether", "world_the_end")

    @Comment("Default dialogue format.", "IMMERSIVE, CHAT, BOTH.")
    var dialogueFormat: DialogueFormat = DialogueFormat.IMMERSIVE

    @Comment("Enable humanoid villagers with skins and player models.")
    var humanoidVillagers: Boolean = true

    @Comment("Enable chat notifications for reputation changes.")
    var reputationChatNotification: Boolean = true

    @Comment("Sound played on reputation status update.")
    var reputationStatusUpdateSound: String = "ui.hud.bubble_pop"

    @Comment("Required reputation for EXILED status.")
    var reputationExiledRequired: Int = -10000

    @Comment("Required reputation for HOSTILE status.")
    var reputationHostileRequired: Int = -5000

    @Comment("Required reputation for UNFRIENDLY status.")
    var reputationUnfriendlyRequired: Int = -2500

    @Comment("Required reputation for NEUTRAL status.")
    var reputationNeutralRequired: Int = 0

    @Comment("Required reputation for FRIENDLY status.")
    var reputationFriendlyRequired: Int = 1000

    @Comment("Required reputation for HONORED status.")
    var reputationHonoredRequired: Int = 5000

    @Comment("Required reputation for REVERED status.")
    var reputationReveredRequired: Int = 10000

    @Comment("Required reputation for EXALTED status.")
    var reputationExaltedRequired: Int = 20000

    @Comment("Distance within which to show reputation HUD for nearby NPCs.")
    var reputationDisplayCloseDistance: Double = 5.0

    @Comment("Forward distance from player for HUD position.")
    var reputationDisplayHudDistance: Double = 2.0

    @Comment("Right offset from player's view direction for HUD position.")
    var reputationDisplayHudRightOffset: Double = 1.0

    @Comment("Vertical spacing between multiple reputation displays in HUD.")
    var reputationDisplayHudVerticalSpacing: Double = -0.3

    @Comment("Scale factor for the reputation text display (X, Y, Z).")
    var reputationDisplayScale: List<Float> = listOf(0.5f, 0.5f, 0.5f)

    @Comment("Background color for the reputation display in ARGB format (A, R, G, B).")
    var reputationDisplayBackgroundColor: List<Int> = listOf(128, 0, 0, 0)

    @Comment("Whether the reputation display should be see-through.")
    var reputationDisplaySeeThrough: Boolean = true

    @Comment("Billboard mode for the reputation display.", "FIXED, VERTICAL, HORIZONTAL, CENTER.")
    var reputationDisplayBillboard: Billboard = Billboard.CENTER

    @Comment("Text color for the reputation display (NamedTextColor name, e.g., WHITE, GREEN).")
    var reputationDisplayTextColor: String = "WHITE"

    @Comment("Template for the reputation text. Placeholders: {npcName}, {repValue}, {status}")
    var reputationDisplayTextTemplate: String = "{npcName}'s Reputation: {repValue} ({status})"

    @Comment("Show entry message on settlement entry.")
    var trackPlayerSettlementEntry: Boolean = true

    enum class DialogueFormat {
        IMMERSIVE, CHAT, BOTH
    }

}

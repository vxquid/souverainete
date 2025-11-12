package vx.ignis.config

import org.bukkit.entity.Display.Billboard
import vx.ignis.config.lib.annotations.Comment
import vx.ignis.config.lib.annotations.Configuration
import vx.ignis.config.lib.annotations.Header

@Configuration("gameplay.yml")
@Header("Pretty gameplay configuration.")
class GameplayConfiguration {

    val general    = GeneralConfig()
    val worlds     = WorldsConfig()
    val dialogue   = DialogueConfig()
    val humanoid   = HumanoidConfig()
    val reputation = ReputationConfig()
    val settlement = SettlementConfig()
    val hunger     = HungerConfig()

    class GeneralConfig {
        @Comment("Don't touch this, or you'll see a lot weird things.")
        val debug: Boolean = false

        @Comment("Message prefix.")
        val messagePrefix: String = "§e🔥 §8|§7"

    }

    class WorldsConfig {
        @Comment("Specify the names of the worlds where you want Ignis to work.")
        var allowedWorlds: List<String> = listOf("world", "world_nether", "world_the_end")
    }

    class DialogueConfig {

        @Comment("Default dialogue format.", "IMMERSIVE, CHAT, BOTH.")
        var dialogueFormat: DialogueFormat = DialogueFormat.IMMERSIVE

        enum class DialogueFormat {
            IMMERSIVE, CHAT, BOTH
        }

    }

    class HumanoidConfig {
        @Comment("Enable humanoid villagers with skins and player models.")
        var humanoidVillagers: Boolean = true
    }

    class ReputationConfig {
        @Comment("Enable chat notifications for reputation changes.")
        var chatNotification: Boolean = true

        @Comment("Sound played on reputation status update.")
        var statusUpdateSound: String = "ui.hud.bubble_pop"

        @Comment("Required reputation for EXILED status.")
        var exiledRequired: Int = -10000

        @Comment("Required reputation for HOSTILE status.")
        var hostileRequired: Int = -5000

        @Comment("Required reputation for UNFRIENDLY status.")
        var unfriendlyRequired: Int = -2500

        @Comment("Required reputation for NEUTRAL status.")
        var neutralRequired: Int = 0

        @Comment("Required reputation for FRIENDLY status.")
        var friendlyRequired: Int = 1000

        @Comment("Required reputation for HONORED status.")
        var honoredRequired: Int = 5000

        @Comment("Required reputation for REVERED status.")
        var reveredRequired: Int = 10000

        @Comment("Required reputation for EXALTED status.")
        var exaltedRequired: Int = 20000

        @Comment("Distance within which to show reputation HUD for nearby NPCs.")
        var displayCloseDistance: Double = 5.0

        @Comment("Forward distance from player for HUD position.")
        var displayHudDistance: Double = 2.0

        @Comment("Right offset from player's view direction for HUD position.")
        var displayHudRightOffset: Double = 1.0

        @Comment("Vertical spacing between multiple reputation displays in HUD.")
        var displayHudVerticalSpacing: Double = -0.3

        @Comment("Scale factor for the reputation text display (X, Y, Z).")
        var displayScale: List<Float> = listOf(0.5f, 0.5f, 0.5f)

        @Comment("Background color for the reputation display in ARGB format (A, R, G, B).")
        var displayBackgroundColor: List<Int> = listOf(128, 0, 0, 0)

        @Comment("Whether the reputation display should be see-through.")
        var displaySeeThrough: Boolean = true

        @Comment("Billboard mode for the reputation display.", "FIXED, VERTICAL, HORIZONTAL, CENTER.")
        var displayBillboard: Billboard = Billboard.CENTER

        @Comment("Text color for the reputation display (NamedTextColor name, e.g., WHITE, GREEN).")
        var displayTextColor: String = "WHITE"

        @Comment("Template for the reputation text. Placeholders: {npcName}, {repValue}, {status}")
        var displayTextTemplate: String = "{npcName}'s Reputation: {repValue} ({status})"
    }

    class SettlementConfig {
        @Comment("Show entry message on settlement entry.")
        var trackPlayerSettlementEntry: Boolean = true
    }

    class HungerConfig {
        @Comment("Maximum hunger level for NPCs.")
        var max: Double = 20.0

        @Comment("Amount of hunger decreased per interval.")
        var decrease: Double = 0.1

        @Comment("Interval in ticks for hunger decrease.")
        var decreaseInterval: Long = 200

        @Comment("Hunger threshold to trigger eating from inventory.")
        var eatThreshold: Double = 14.0

        @Comment("Hunger threshold to force food quest generation.")
        var questThreshold: Double = 10.0

        @Comment("Hunger threshold for regeneration effect.")
        var regenThreshold: Double = 18.0

        @Comment("Hunger threshold for starvation damage and debuffs.")
        var starvationThreshold: Double = 5.0

        @Comment("Damage amount per tick when starving.")
        var starvationDamage: Double = 1.0
    }

}
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
    val nametag    = NametagDisplayConfig()

    class GeneralConfig {
        @Comment("Don't touch this, or you'll see a lot weird things.")
        val debug: Boolean = false
        @Comment("Message prefix.")
        val messagePrefix: String = "§6ɪɢɴɪꜱ §8|§7"
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

        @Comment("Whether NPCs can die from starvation.")
        var canDieFromStarvation: Boolean = true
    }

    class NametagDisplayConfig {
        @Comment("Enable NPC info displays above humanoids.")
        var enabled: Boolean = true

        @Comment("Maximum view distance in blocks to show NPC info displays to players.")
        var viewDistance: Double = 5.5

        @Comment("Y-axis offset for positioning the display above the NPC's head.")
        var displayOffsetY: Float = 0.195f

        @Comment("Scale factors for the display entity (X, Y, Z).")
        var displayScale: List<Float> = listOf(0.65f, 0.65f, 0.65f)

        @Comment("Interval in server ticks between display updates (text refresh and viewer checks).")
        var updateIntervalTicks: Long = 10L

        @Comment("Text template for the NPC name, profession, and level line. Use %s for name and profession, %d for level.")
        var nameProfessionLevelTemplate: String = "§a%s§f, %s §6(⭐%d)"

        @Comment("Text template for the reputation line. Use %d for value, %s for status.")
        var reputationTemplate: String = "§e🏆 %d §f(%s)"

        @Comment("Text template for the health line. Use %.1f placeholders for current and max health.")
        var healthTemplate: String = "§с❤%.1f§f/§a%.1f"

        @Comment("Text template for the hunger status line when NPC is starving or hungry. Use %s for status, %.1f for hunger.")
        var hungerTemplate: String = "§c%s (%.1f / %.1f)"

        @Comment("Billboard rendering mode for the display. FIXED, VERTICAL, HORIZONTAL, CENTER.")
        var billboard: Billboard = Billboard.CENTER

        @Comment("Whether the display entity renders as see-through (ignores blocks).")
        var seeThrough: Boolean = false

        @Comment("Background color for the display in ARGB format (A, R, G, B). Higher alpha for more opacity.")
        var backgroundColor: List<Int> = listOf(175, 0, 0, 0)
    }

}
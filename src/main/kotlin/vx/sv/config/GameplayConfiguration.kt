package vx.sv.config

import org.bukkit.entity.Display
import vx.sv.config.lib.annotations.Comment
import vx.sv.config.lib.annotations.Configuration
import vx.sv.config.lib.annotations.Header

@Configuration("gameplay.yml")
@Header("Pretty gameplay configuration.")
class GameplayConfiguration {

    val general    = GeneralConfig()
    val worlds     = WorldsConfig()
    val dialogue   = DialogueConfig()
    val humanoid   = HumanoidConfig()
    val reputation = ReputationConfig()
    val settlement = SettlementConfig()
    val raid       = RaidConfig()
    val hunger     = HungerConfig()
    val nametag    = NametagDisplayConfig()
    val quest      = QuestConfig()
    val profession = ProfessionConfig()
    val uniqueItem = UniqueItemConfig()
    val party      = PartyConfig()
    val leisure    = LeisureConfig()

    class GeneralConfig {
        @Comment("Message prefix.")
        val messagePrefix: String = "§6♣ §8| §7"

        @Comment(
            "If enabled, the plugin will not modify vanilla trades (original mechanics), only adding quest items on top.",
            "This also affects profession overhaul: villagers won't craft items for sale, including items with AI-generated descriptions.",
            "Enabling this means that all content coming from professions.yml will be disabled. Think twice."
        )
        val vanillaTrading: Boolean = false

        @Comment(
            "Whether custom villager construction is enabled.",
            "If set to false, villagers will not construct new buildings or roads, and vanilla villages will generate normally."
        )
        val enableConstruction: Boolean = true
    }

    class WorldsConfig {
        @Comment("Specify the names of the worlds where you want Souverainete to work.")
        var allowedWorlds: List<String> = listOf("world", "world_nether", "world_the_end")
    }

    class DialogueConfig {
        @Comment("Default dialogue format.", "IMMERSIVE, CHAT, HOLOGRAM, BOTH.")
        var dialogueFormat: DialogueFormat = DialogueFormat.BOTH

        enum class DialogueFormat {
            IMMERSIVE, CHAT, HOLOGRAM, BOTH
        }
    }

    class HumanoidConfig {
        @Comment("Enable humanoid villagers with skins and player models.")
        var humanoidVillagers: Boolean = true

        @Comment("If you have plugins such as entity-culler or others that interfere with the display of creatures, enable this.")
        var adaptivePacketManipulator: Boolean = false

        @Comment("If enabled, all villagers will be assigned the race defined in 'globalRace', ignoring individual race matching rules.")
        var oneRaceMode: Boolean = false

        @Comment("The ID of the race to use when oneRaceMode is enabled (default is 'human'). Make sure this race exists in races.yml.")
        var globalRace: String = "human"

        @Comment("Whether humanoid villagers have collisions with other entities (can push/be pushed). Default is true.")
        var villagerCollisions: Boolean = true
    }

    class ReputationConfig {
        @Comment("Enable chat notifications for reputation changes.")
        var chatNotification: Boolean = true

        @Comment("Sound played on reputation status update.")
        var statusUpdateSound: String = "ui.hud.bubble_pop"

        @Comment("Reputation loss per 1 point of damage dealt to an NPC.")
        var damageReputationMultiplier: Double = 2.0

        @Comment("Reputation loss with the entire settlement when a member is killed.")
        var killSettlementPenalty: Int = 500

        @Comment("Radius in blocks to check for witnesses upon murder.")
        var witnessRadius: Double = 48.0

        @Comment("Radius in blocks for hostile NPCs to detect and aggro on the player.")
        var aggressionRadius: Double = 15.0

        @Comment("Radius in blocks where NPC shouts (warnings/witness cries) can be heard.")
        var shoutRadius: Double = 20.0

        @Comment("Required reputation for EXILED status.")
        var exiledRequired: Int = -1000

        @Comment("Required reputation for HOSTILE status.")
        var hostileRequired: Int = -500

        @Comment("Required reputation for UNFRIENDLY status.")
        var unfriendlyRequired: Int = -250

        @Comment("Required reputation for NEUTRAL status.")
        var neutralRequired: Int = 0

        @Comment("Required reputation for FRIENDLY status.")
        var friendlyRequired: Int = 250

        @Comment("Required reputation for HONORED status.")
        var honoredRequired: Int = 750

        @Comment("Required reputation for REVERED status.")
        var reveredRequired: Int = 1500

        @Comment("Required reputation for EXALTED status.")
        var exaltedRequired: Int = 2500

        @Comment("Reputation gain for killing specific monsters within settlement territory.")
        val monsterKillReputation: Map<String, Int> = hashMapOf(
            "ZOMBIE" to 2,
            "SKELETON" to 2,
            "CREEPER" to 5,
            "SPIDER" to 2,
            "ZOMBIE_VILLAGER" to 10,
            "HUSK" to 3,
            "PILLAGER" to 10,
            "VINDICATOR" to 20,
            "EVOKER" to 30,
            "RAVAGER" to 50,
            "WITCH" to 10,
            "ILLUSIONER" to 30
        )
    }

    class SettlementConfig {
        @Comment("Show entry message on settlement entry.")
        val defaultName: String = "Unknown Settlement"
        var trackEntry: Boolean = true
        val titleColor: String = "§6"
        val detectionDistance: Double = 128.0
        val villagersRequired: Int = 5
        val broadcastCreation: Boolean = true
        val movementTickInterval: Long = 40L
        val detectionInterval: Long = 200L
        val titleFadeIn: Int = 20
        val titleStay: Int = 40
        val titleFadeOut: Int = 20

        @Comment(
            "Maximum number of settlements allowed to build simultaneously in a world.",
            "Set to 0 for unlimited simultaneous construction."
        )
        var maxActiveBuildingSettlements: Int = 3

        @Comment("Maximum number of rent foundation plots allowed per settlement.")
        var maxRentFoundations: Int = 3

        @Comment("Default size (width and length) of the rent foundation plot. Default is 10 (10x10).")
        var rentFoundationSize: Int = 10

        @Comment("Rent duration in Minecraft in-game days (1 day = 24000 ticks). Default 30 days = 720000 ticks.")
        var rentDurationDays: Long = 30L

        @Comment("Amount of racial currency required to pay for rent.")
        var rentCurrencyCost: Int = 64

        @Comment("Maximum number of builder villagers that can work on the exact same building job simultaneously.")
        var maxBuildersPerProject: Int = 4

        @Comment("Base ticks required for a builder to break an obstacle block (multiplied for harder blocks).")
        var baseBlockBreakDuration: Int = 15

        @Comment("Ticks delay between individual block placements by a builder.")
        var blockPlacementDelayTicks: Long = 10L

        @Comment("Cooldown ticks before a builder can pick up a new block task after finishing the previous one.")
        var taskSwitchCooldownTicks: Long = 2L

        @Comment("How many ticks a builder can be idle or stuck before abandoning the current block and resetting pathfinding.")
        var builderStuckTimeoutTicks: Long = 240L

        @Comment("Enable debug visuals (particles/lines) when players hold a spyglass and look at active builders.")
        var enableBuilderDebugVisuals: Boolean = true
    }

    class RaidConfig {
        @Comment("Time in seconds before stuck raiders start glowing to help players find them.")
        var glowThreshold: Long = 45L

        @Comment("Time in seconds before stuck raiders are forcefully removed and reinforcements spawn at the center.")
        var killThreshold: Long = 120L

        @Comment("Minimum reputation score for a player to be considered an ally and targeted by raiders.")
        var allyReputationThreshold: Int = 200

        @Comment("Reputation gained with the defending settlement for killing a raider.")
        var repGainPerKill: Int = 150

        @Comment("Radius in blocks to broadcast raid chat messages.")
        var broadcastRadius: Double = 1000.0

        @Comment("Radius in blocks to play the ominous raid horn sound.")
        var hornRadius: Double = 250.0
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

        @Comment("Text template for the NPC name, profession, and level line. Use %s for name and profession, %d for level.")
        var nameProfessionLevelTemplate: String = "§a%s§f, %s §6(⭐%d)"

        @Comment("Text template for the reputation line. Use %d for value, %s for status.")
        var reputationTemplate: String = "§e🏆 %d §f(%s)"

        @Comment("Text template for the health line. Use %.1f placeholders for current and max health.")
        var healthTemplate: String = "§4❤§c%.1f§7/§c%.1f"

        @Comment("Text template for the hunger status line when NPC is starving or hungry. Use %s for status, %.1f for hunger.")
        var hungerTemplate: String = "§c%s (%.1f/%.1f)"

        @Comment("Billboard rendering mode for the display. FIXED, VERTICAL, HORIZONTAL, CENTER.")
        var billboard: Display.Billboard = Display.Billboard.CENTER

        @Comment("Whether the display entity renders as see-through (ignores blocks).")
        var seeThrough: Boolean = false

        @Comment("Background color for the display in ARGB format (A, R, G, B). Higher alpha for more opacity.")
        var backgroundColor: List<Int> = listOf(175, 0, 0, 0)
    }

    class QuestConfig {
        @Comment("Quest lifetime duration in ticks.")
        var lifetimeDuration: Long = 192000L

        @Comment("Interval in ticks for quest generation.")
        var intervalTicks: Long = 2400L

        @Comment("Reputation score multiplier for quest completion.")
        var reputationMultiplier: Double = 0.005

        @Comment("Experience multiplier for players.")
        var playerExperienceMultiplier: Double = 0.05

        @Comment("Experience multiplier for NPCs.")
        var npcExperienceMultiplier: Double = 0.0025

        @Comment("Maximum number of quests a player can have.")
        var playerQuestLimit: Int = 4

        @Comment("Base number of quests an NPC can have (added to villager profession level).")
        var npcQuestBase: Int = 1
    }

    class ProfessionConfig {
        @Comment("Interval in ticks for villager production cycles.")
        var workIntervalTicks: Long = 240L

        @Comment("Interval in ticks for processing unique item queue.")
        var uniqueProcessingIntervalTicks: Long = 400L

        @Comment("Base potions per level for clerics (multiplied by level + 1).")
        var clericMaxPotionsBase: Int = 1

        @Comment("Minimum amount of brewing ingredient to consume for clerics.")
        var clericBrewingIngredientMin: Int = 1

        @Comment("Maximum additional random amount of brewing ingredient to consume for clerics.")
        var clericBrewingIngredientMaxRandom: Int = 5
    }

    class RarityColor {
        var common: String = "#a9a9a9"
        var uncommon: String = "#00ff00"
        var rare: String = "#fae100"
        var epic: String = "#00ffff"
        var legendary: String = "#fa8100"
        var mythical: String = "#ff0000"
        var divine: String = "#ff50b4"
    }

    class UniqueItemConfig {
        @Comment("Base chance divisor for determining unique item rarity rolls.")
        var rollsBaseChanceDivisor: Int = 50

        @Comment("Maximum line length for lore wrapping.")
        var loreLineMaxLength: Int = 60

        @Comment("Hex color definitions for different unique item rarity tiers.")
        var rarityColor = RarityColor()
    }

    class PartyConfig {
        @Comment("Maximum number of members allowed in a player's party.")
        var maxPartySize: Int = 2

        @Comment("Whether party members are automatically removed from the party list when they die.")
        var removeMemberOnDeath: Boolean = true

        @Comment("Default combat tactic for new party members.", "AUTO, MELEE, RANGED")
        var defaultCombatTactic: String = "AUTO"

        @Comment("Default state for new party members.", "FOLLOW, STAY")
        var defaultPartyState: String = "FOLLOW"

        @Comment(
            "FATALISM: Vanilla behavior. Villagers die permanently.",
            "RESPAWN: Villagers escape to their settlement on lethal damage (dies if homeless).",
            "KNOCKOUT: Villagers fall down and bleed out. Can be revived with GAP or carried. Bleeding out triggers RESPAWN logic."
        )
        var deathHandleStrategy: String = "KNOCKOUT"
    }

    class LeisureConfig {
        @Comment("Maximum number of active leisure sessions allowed globally.")
        var maxActiveSessions: Int = 20

        @Comment("Global ticker interval in ticks for searching idle NPCs (default: 1200 = 1 minute).")
        var globalTickerInterval: Long = 1200L

        @Comment("Session ticker interval in ticks for managing ongoing leisure sessions (default: 20 = 1 second).")
        var sessionTickerInterval: Long = 20L

        val time        = TimeConfig()
        val duration    = DurationConfig()
        val pathing     = PathingConfig()
        val interaction = InteractionConfig()
        val scoring     = ScoringConfig()

        class TimeConfig {
            @Comment("Start of the night (world time) when NPCs should stop leisure activities and go to sleep.")
            var nightStart: Long = 13000L

            @Comment("End of the night (world time) when NPCs can resume leisure activities.")
            var nightEnd: Long = 23500L
        }

        class DurationConfig {
            @Comment("Minimum duration of a leisure session in milliseconds.")
            var minDurationMs: Long = 30000L

            @Comment("Maximum duration of a leisure session in milliseconds.")
            var maxDurationMs: Long = 120000L
        }

        class PathingConfig {
            @Comment("Walking speed multiplier when an NPC is pathing to a seat.")
            var walkSpeed: Double = 0.6

            @Comment("Distance squared to the seat before the NPC transitions into the sitting state.")
            var sitDistanceSquared: Double = 2.5

            @Comment("Distance squared threshold to trigger pathfinder repathing if NPC moves away.")
            var repathDistanceSquared: Double = 2.0
        }

        class InteractionConfig {
            @Comment("Probability (0.0 to 1.0) of an NPC preferring an indoor seat.")
            var indoorPreferenceChance: Double = 0.8

            @Comment("Probability (0.0 to 1.0) per second of an NPC consuming food or drink while sitting.")
            var consumptionChancePerSecond: Double = 0.05

            @Comment("Radius (X, Y, Z axis) to look for friends when a social NPC finds an open bench.")
            var socialInviteRadiusX: Double = 10.0
            var socialInviteRadiusY: Double = 5.0
            var socialInviteRadiusZ: Double = 10.0

            @Comment("Maximum number of friends to invite to a seating area.")
            var maxFriendsToInvite: Int = 3
        }

        class ScoringConfig {
            @Comment("Score bonus if the seat matches the NPC's environment preference (indoor/outdoor).")
            var preferenceMatchBonus: Int = 50

            @Comment("Base score bonus for indoor seats to encourage home usage.")
            var indoorBaseBonus: Int = 30

            @Comment("Score bonus for proper stair blocks (benches) over raw solid blocks.")
            var stairBonus: Int = 100

            @Comment("Score bonus if adjacent blocks are also stairs, indicating an actual bench/sofa.")
            var benchBonus: Int = 150

            @Comment("Score bonus for outdoor seats near a campfire.")
            var campfireBonus: Int = 350

            @Comment("Minimum seat score required for an NPC to invite friends.")
            var minScoreForSocialInvite: Int = 250
        }
    }

}
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
    val quest      = QuestConfig()
    val profession = ProfessionConfig()
    val uniqueItem = UniqueItemConfig()

    class GeneralConfig {
        @Comment("Message prefix.")
        val messagePrefix: String = "§x§F§F§0§0§7§2ɪ§x§B§F§1§1§9§5ɢ§x§8§0§2§3§B§9ɴ§x§4§0§3§4§D§Cɪ§x§0§0§4§5§F§Fꜱ §8|§7"
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

    class QuestConfig {
        @Comment("Quest lifetime duration in ticks.")
        var lifetimeDuration: Long = 192000L

        @Comment("Interval in ticks for quest generation.")
        var intervalTicks: Long = 400L

        @Comment("Reputation score multiplier for quest completion.")
        var reputationMultiplier: Double = 0.005

        @Comment("Experience multiplier for players.")
        var playerExperienceMultiplier: Double = 0.05

        @Comment("Experience multiplier for NPCs.")
        var npcExperienceMultiplier: Double = 0.0025

        @Comment("Maximum number of quests a player can have.")
        var playerQuestLimit: Int = 3

        @Comment("Base number of quests an NPC can have (added to villager level).")
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

    class UniqueItemConfig {
        @Comment("Base chance divisor for determining unique item rarity rolls.")
        var rollsBaseChanceDivisor: Int = 50

        @Comment("Maximum line length for lore wrapping.")
        var loreLineMaxLength: Int = 60

        @Comment("Increment for attack speed attribute per roll.")
        var attackSpeedIncrement: Double = 0.3

        @Comment("Increment for attack damage attribute per roll.")
        var attackDamageIncrement: Double = 1.5

        @Comment("Increment for block break speed attribute per roll.")
        var blockBreakSpeedIncrement: Double = 0.4

        @Comment("Increment for block interaction range attribute per roll.")
        var blockInteractionRangeIncrement: Double = 0.5

        @Comment("Increment for entity interaction range attribute per roll.")
        var entityInteractionRangeIncrement: Double = 0.5

        @Comment("Increment for max health attribute per roll.")
        var maxHealthIncrement: Double = 2.0

        @Comment("Increment for armor attribute per roll.")
        var armorIncrement: Double = 1.0

        @Comment("Increment for armor toughness attribute per roll.")
        var armorToughnessIncrement: Double = 1.0

        @Comment("Increment for scale attribute per roll.")
        var scaleIncrement: Double = 0.1

        @Comment("Base attack speed for swords.")
        var baseAttackSpeedSwords: Double = 1.6

        @Comment("Base attack speed for pickaxes.")
        var baseAttackSpeedPickaxes: Double = 1.2

        @Comment("Base attack speed for iron axes.")
        var baseAttackSpeedIronAxe: Double = 0.9

        @Comment("Base attack speed for diamond/netherite axes.")
        var baseAttackSpeedDiamondNetheriteAxe: Double = 1.0

        @Comment("Base attack speed default.")
        var baseAttackSpeedDefault: Double = 4.0

        @Comment("Base attack damage for copper sword.")
        var baseAttackDamageCopperSword: Double = 5.0

        @Comment("Base attack damage for iron sword.")
        var baseAttackDamageIronSword: Double = 6.0

        @Comment("Base attack damage for diamond sword.")
        var baseAttackDamageDiamondSword: Double = 7.0

        @Comment("Base attack damage for netherite sword.")
        var baseAttackDamageNetheriteSword: Double = 8.0

        @Comment("Base attack damage for copper pickaxe.")
        var baseAttackDamageCopperPickaxe: Double = 3.0

        @Comment("Base attack damage for iron pickaxe.")
        var baseAttackDamageIronPickaxe: Double = 4.0

        @Comment("Base attack damage for diamond pickaxe.")
        var baseAttackDamageDiamondPickaxe: Double = 5.0

        @Comment("Base attack damage for netherite pickaxe.")
        var baseAttackDamageNetheritePickaxe: Double = 6.0

        @Comment("Base attack damage for copper/iron/diamond axe.")
        var baseAttackDamageCopperIronDiamondAxe: Double = 9.0

        @Comment("Base attack damage for netherite axe.")
        var baseAttackDamageNetheriteAxe: Double = 10.0

        @Comment("Base armor for leather helmet.")
        var baseArmorLeatherHelmet: Double = 1.0

        @Comment("Base armor for leather chestplate.")
        var baseArmorLeatherChestplate: Double = 3.0

        @Comment("Base armor for leather leggings.")
        var baseArmorLeatherLeggings: Double = 2.0

        @Comment("Base armor for leather boots.")
        var baseArmorLeatherBoots: Double = 1.0

        @Comment("Base armor for copper helmet.")
        var baseArmorCopperHelmet: Double = 2.0

        @Comment("Base armor for copper chestplate.")
        var baseArmorCopperChestplate: Double = 4.0

        @Comment("Base armor for copper leggings.")
        var baseArmorCopperLeggings: Double = 3.0

        @Comment("Base armor for copper boots.")
        var baseArmorCopperBoots: Double = 1.0

        @Comment("Base armor for iron helmet.")
        var baseArmorIronHelmet: Double = 2.0

        @Comment("Base armor for iron chestplate.")
        var baseArmorIronChestplate: Double = 6.0

        @Comment("Base armor for iron leggings.")
        var baseArmorIronLeggings: Double = 5.0

        @Comment("Base armor for iron boots.")
        var baseArmorIronBoots: Double = 2.0

        @Comment("Base armor for diamond/netherite helmet.")
        var baseArmorDiamondNetheriteHelmet: Double = 3.0

        @Comment("Base armor for diamond/netherite chestplate.")
        var baseArmorDiamondNetheriteChestplate: Double = 8.0

        @Comment("Base armor for diamond/netherite leggings.")
        var baseArmorDiamondNetheriteLeggings: Double = 6.0

        @Comment("Base armor for diamond/netherite boots.")
        var baseArmorDiamondNetheriteBoots: Double = 3.0

        @Comment("Base armor toughness for diamond armor.")
        var baseArmorToughnessDiamond: Double = 2.0

        @Comment("Base armor toughness for netherite armor.")
        var baseArmorToughnessNetherite: Double = 3.0
    }

}
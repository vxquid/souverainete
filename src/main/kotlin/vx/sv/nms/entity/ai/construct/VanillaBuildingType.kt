package vx.sv.nms.entity.ai.construct

import org.bukkit.Material
import vx.sv.Souverainete.Companion.plugin

enum class VanillaBuildingType(
    val typeName: String,
    val relativeSubPath: String,
    val width: Int,
    val length: Int,
    val height: Int,
    val workstation: Material,
    val displayName: String
) {
    TOWN_HALL("TOWN_HALL", "houses/{style}_temple_1", 11, 11, 12, Material.BREWING_STAND, "Town Hall"),
    BLACKSMITH("BLACKSMITH", "houses/{style}_weaponsmith_1", 10, 10, 6, Material.BLAST_FURNACE, "Blacksmith Shop"),
    BAKERY("BAKERY", "houses/{style}_butcher_shop_1", 9, 9, 6, Material.SMOKER, "Bakery"),
    FARM("FARM", "houses/{style}_large_farm_1", 13, 9, 5, Material.COMPOSTER, "Crop Farm"),
    LIBRARY("LIBRARY", "houses/{style}_library_1", 9, 9, 7, Material.LECTERN, "Library"),
    ARMORY("ARMORY", "houses/{style}_armorer_1", 10, 10, 7, Material.GRINDSTONE, "Armory"),
    SHEPHERD("SHEPHERD", "houses/{style}_shepherd_1", 9, 13, 6, Material.LOOM, "Shepherd's Cot"),
    TEMPLE("TEMPLE", "houses/{style}_temple_1", 11, 11, 12, Material.BREWING_STAND, "Temple"),
    HOUSE_SMALL("HOUSE_SMALL", "houses/{style}_small_house_1", 7, 7, 5, Material.CRAFTING_TABLE, "Small House"),
    HOUSE_MEDIUM("HOUSE_MEDIUM", "houses/{style}_medium_house_1", 9, 9, 6, Material.CRAFTING_TABLE, "Medium House"),
    HOUSE_LARGE("HOUSE_LARGE", "houses/{style}_medium_house_1", 11, 11, 7, Material.CRAFTING_TABLE, "Large House"),
    WOOD_FARM("WOOD_FARM", "houses/{style}_small_house_2", 9, 9, 5, Material.OAK_SAPLING, "Wood Farm"),
    STABLE("STABLE", "houses/{style}_stable_1", 7, 7, 5, Material.HAY_BLOCK, "Stables"),
    ANIMAL_PEN("ANIMAL_PEN", "houses/{style}_animal_pen_1", 9, 9, 4, Material.OAK_FENCE, "Animal Pen"),
    LAMP("LAMP", "{style}_lamp_1", 1, 1, 4, Material.OAK_FENCE, "Street Lamp"),
    MEETING_POINT("MEETING_POINT", "town_centers/{style}_meeting_point_1", 6, 6, 5, Material.BELL, "Gathering Place"),
    CARTOGRAPHER("CARTOGRAPHER", "houses/{style}_cartographer_1", 9, 9, 6, Material.CARTOGRAPHY_TABLE, "Cartographer House"),
    MINE("MINE", "custom/mine", 7, 7, 6, Material.SMITHING_TABLE, "Stone Quarry"),
    IRON_GOLEM("IRON_GOLEM", "custom/iron_golem", 3, 3, 3, Material.IRON_BLOCK, "Iron Golem Blueprint"),
    RENT_FOUNDATION("RENT_FOUNDATION", "custom/rent_foundation",
        try { plugin.gameplayConfig.settlement.rentFoundationSize } catch (_: Exception) { 10 },
        try { plugin.gameplayConfig.settlement.rentFoundationSize } catch (_: Exception) { 10 },
        4, Material.STONE_BRICKS, "Rent Foundation");

    fun getStructurePath(style: String = "plains"): String {
        if (relativeSubPath.startsWith("custom/")) {
            return relativeSubPath
        }

        val validStyle = when (style.lowercase()) {
            "desert" -> "desert"
            "savanna" -> "savanna"
            "taiga" -> "taiga"
            "snow", "snowy" -> "snowy"
            else -> "plains"
        }

        val formattedSubPath = relativeSubPath.replace("{style}", validStyle)
        return "village/$validStyle/$formattedSubPath"
    }

    val vanillaPath: String
        get() = getStructurePath("plains")

    companion object {
        fun byTypeName(name: String): VanillaBuildingType? {
            return entries.find { it.typeName.equals(name, ignoreCase = true) }
        }
    }
}
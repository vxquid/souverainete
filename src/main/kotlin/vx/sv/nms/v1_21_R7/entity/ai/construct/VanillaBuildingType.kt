package vx.sv.nms.v1_21_R7.entity.ai.construct

import org.bukkit.Material

enum class VanillaBuildingType(
    val typeName: String,
    val vanillaPath: String,
    val width: Int,
    val length: Int,
    val height: Int,
    val workstation: Material,
    val displayName: String
) {
    TOWN_HALL("TOWN_HALL", "village/plains/houses/plains_temple_3", 11, 11, 12, Material.BREWING_STAND, "Town Hall"),
    BLACKSMITH("BLACKSMITH", "village/plains/houses/plains_weaponsmith_1", 10, 10, 6, Material.BLAST_FURNACE, "Blacksmith Shop"),
    BAKERY("BAKERY", "village/plains/houses/plains_butcher_shop_1", 9, 9, 6, Material.SMOKER, "Bakery"),
    FARM("FARM", "village/plains/houses/plains_large_farm_1", 13, 9, 5, Material.COMPOSTER, "Crop Farm"),
    LIBRARY("LIBRARY", "village/plains/houses/plains_library_1", 9, 9, 7, Material.LECTERN, "Library"),
    ARMORY("ARMORY", "village/plains/houses/plains_armorer_house_1", 10, 10, 7, Material.GRINDSTONE, "Armory"),
    SHEPHERD("SHEPHERD", "village/plains/houses/plains_shepherds_house_1", 10, 10, 6, Material.LOOM, "Shepherd's Cot"),
    TEMPLE("TEMPLE", "village/plains/houses/plains_temple_4", 11, 11, 12, Material.BREWING_STAND, "Temple"),
    HOUSE_SMALL("HOUSE_SMALL", "village/plains/houses/plains_small_house_1", 7, 7, 5, Material.CRAFTING_TABLE, "Small House"),
    HOUSE_MEDIUM("HOUSE_MEDIUM", "village/plains/houses/plains_medium_house_1", 9, 9, 6, Material.CRAFTING_TABLE, "Medium House"),
    STABLE("STABLE", "village/plains/houses/plains_stable_1", 7, 7, 5, Material.HAY_BLOCK, "Stables"),
    ANIMAL_PEN("ANIMAL_PEN", "village/plains/houses/plains_animal_pen_1", 9, 9, 4, Material.OAK_FENCE, "Animal Pen"),
    LAMP("LAMP", "village/plains/plains_lamp_1", 1, 1, 4, Material.OAK_FENCE, "Street Lamp"),
    MEETING_POINT("MEETING_POINT", "village/plains/town_centers/plains_meeting_point_2", 6, 6, 5, Material.BELL, "Gathering Place"),
    CARTOGRAPHER("CARTOGRAPHER", "village/plains/houses/plains_cartographer_1", 9, 9, 6, Material.CARTOGRAPHY_TABLE, "Cartographer House"),
    MINE("MINE", "custom/mine", 7, 7, 6, Material.SMITHING_TABLE, "Stone Quarry");

    companion object {
        fun byTypeName(name: String): VanillaBuildingType? {
            return entries.find { it.typeName.equals(name, ignoreCase = true) }
        }
    }
}
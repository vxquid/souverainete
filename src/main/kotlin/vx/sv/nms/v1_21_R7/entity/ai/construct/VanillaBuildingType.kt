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
    TOWN_HALL("TOWN_HALL", "village/plains/houses/plains_meeting_point_1", 11, 11, 5, Material.BELL, "Town Hall"),
    BLACKSMITH("BLACKSMITH", "village/plains/houses/plains_weaponsmith_1", 10, 10, 6, Material.BLAST_FURNACE, "Blacksmith Shop"),
    BAKERY("BAKERY", "village/plains/houses/plains_butcher_shop_1", 9, 9, 6, Material.SMOKER, "Bakery"),
    FARM("FARM", "village/plains/houses/plains_large_farm_1", 13, 13, 6, Material.COMPOSTER, "Crop Farm"),
    LIBRARY("LIBRARY", "village/plains/houses/plains_library_1", 9, 9, 7, Material.LECTERN, "Library"),
    ARMORY("ARMORY", "village/plains/houses/plains_armorer_house_1", 10, 10, 7, Material.GRINDSTONE, "Armory"),
    SHEPHERD("SHEPHERD", "village/plains/houses/plains_shepherds_house_1", 10, 10, 6, Material.LOOM, "Shepherd's Cot"),
    TEMPLE("TEMPLE", "village/plains/houses/plains_temple_3", 11, 11, 12, Material.BREWING_STAND, "Temple"),

    HOUSE_SMALL("HOUSE_SMALL", "village/plains/houses/plains_small_house_1", 7, 7, 5, Material.CRAFTING_TABLE, "Small House"),
    HOUSE_MEDIUM("HOUSE_MEDIUM", "village/plains/houses/plains_medium_house_1", 9, 9, 6, Material.CRAFTING_TABLE, "Medium House");

    companion object {
        fun byTypeName(name: String): VanillaBuildingType? {
            return entries.find { it.typeName.equals(name, ignoreCase = true) }
        }
    }
}
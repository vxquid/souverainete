package vx.sv.nms.entity.ai.construct

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import vx.sv.Souverainete.Companion.plugin

enum class VanillaBuildingType(
    val typeName: String,
    val width: Int,
    val length: Int,
    val height: Int,
    val workstation: Material,
    val displayName: String
) {
    TOWN_HALL("TOWN_HALL", 11, 11, 12, Material.BREWING_STAND, "Town Hall") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_temple_1",
            "houses/${style}_temple_2",
            "houses/${style}_temple_3",
            "houses/${style}_medium_house_1"
        )
    },
    BLACKSMITH("BLACKSMITH", 10, 10, 6, Material.BLAST_FURNACE, "Blacksmith Shop") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_weaponsmith_1",
            "houses/${style}_armorer_1",
            "houses/${style}_mason_1",
            "houses/${style}_medium_house_1"
        )
    },
    BAKERY("BAKERY", 9, 9, 6, Material.SMOKER, "Bakery") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_butcher_shop_1",
            "houses/${style}_butcher_shop_2",
            "houses/${style}_small_house_2"
        )
    },
    FARM("FARM", 13, 9, 5, Material.COMPOSTER, "Crop Farm") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_large_farm_1",
            "houses/${style}_farm_1",
            "houses/${style}_farm_2",
            "houses/${style}_small_farm_1"
        )
    },
    LIBRARY("LIBRARY", 9, 9, 7, Material.LECTERN, "Library") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_library_1",
            "houses/${style}_library_2",
            "houses/${style}_medium_house_2"
        )
    },
    ARMORY("ARMORY", 10, 10, 7, Material.GRINDSTONE, "Armory") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_armorer_house_1",
            "houses/${style}_armorer_1",
            "houses/${style}_weaponsmith_1",
            "houses/${style}_medium_house_1"
        )
    },
    SHEPHERD("SHEPHERD", 9, 13, 6, Material.LOOM, "Shepherd's Cot") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_shepherds_house_1",
            "houses/${style}_shepherd_house_1",
            "houses/${style}_small_house_4",
            "houses/${style}_small_house_1"
        )
    },
    TEMPLE("TEMPLE", 11, 11, 12, Material.BREWING_STAND, "Temple") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_temple_4",
            "houses/${style}_temple_1",
            "houses/${style}_medium_house_1"
        )
    },
    HOUSE_SMALL("HOUSE_SMALL", 7, 7, 5, Material.CRAFTING_TABLE, "Small House") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_small_house_1",
            "houses/${style}_small_house_2",
            "houses/${style}_small_house_3",
            "houses/${style}_small_house_4",
            "houses/${style}_small_house_5"
        )
    },
    HOUSE_MEDIUM("HOUSE_MEDIUM", 9, 9, 6, Material.CRAFTING_TABLE, "Medium House") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_medium_house_1",
            "houses/${style}_medium_house_2",
            "houses/${style}_small_house_1"
        )
    },
    HOUSE_LARGE("HOUSE_LARGE", 11, 11, 7, Material.CRAFTING_TABLE, "Large House") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_big_house_1",
            "houses/${style}_medium_house_1",
            "houses/${style}_medium_house_2"
        )
    },
    WOOD_FARM("WOOD_FARM", 9, 9, 5, Material.OAK_SAPLING, "Wood Farm") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_small_house_5",
            "houses/${style}_small_house_6",
            "houses/${style}_small_house_2"
        )
    },
    STABLE("STABLE", 7, 7, 5, Material.HAY_BLOCK, "Stables") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_stable_1",
            "houses/${style}_animal_pen_1",
            "houses/${style}_small_house_1"
        )
    },
    ANIMAL_PEN("ANIMAL_PEN", 9, 9, 4, Material.OAK_FENCE, "Animal Pen") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_animal_pen_1",
            "houses/${style}_animal_pen_2",
            "houses/${style}_animal_pen_3",
            "houses/${style}_small_house_3"
        )
    },
    LAMP("LAMP", 1, 1, 4, Material.OAK_FENCE, "Street Lamp") {
        override fun getPaths(style: String) = listOf(
            "${style}_lamp_1"
        )
    },
    MEETING_POINT("MEETING_POINT", 6, 6, 5, Material.BELL, "Gathering Place") {
        override fun getPaths(style: String) = listOf(
            "town_centers/${style}_meeting_point_1",
            "town_centers/${style}_meeting_point_2",
            "town_centers/${style}_meeting_point_3"
        )
    },
    CARTOGRAPHER("CARTOGRAPHER", 9, 9, 6, Material.CARTOGRAPHY_TABLE, "Cartographer House") {
        override fun getPaths(style: String) = listOf(
            "houses/${style}_cartographer_1",
            "houses/${style}_cartographer_house_1",
            "houses/${style}_medium_house_1"
        )
    },
    MINE("MINE", 7, 7, 6, Material.SMITHING_TABLE, "Stone Quarry") {
        override fun getPaths(style: String) = listOf("custom/mine")
    },
    IRON_GOLEM("IRON_GOLEM", 3, 3, 3, Material.IRON_BLOCK, "Iron Golem Blueprint") {
        override fun getPaths(style: String) = listOf("custom/iron_golem")
    },
    RENT_FOUNDATION("RENT_FOUNDATION",
        try { plugin.gameplayConfig.settlement.rentFoundationSize } catch (_: Exception) { 10 },
        try { plugin.gameplayConfig.settlement.rentFoundationSize } catch (_: Exception) { 10 },
        4, Material.STONE_BRICKS, "Rent Foundation") {
        override fun getPaths(style: String) = listOf("custom/rent_foundation")
    };

    abstract fun getPaths(style: String): List<String>

    fun getValidStructurePath(style: String): String {
        val validStyle = when (style.lowercase()) {
            "desert" -> "desert"
            "savanna" -> "savanna"
            "taiga" -> "taiga"
            "snow", "snowy" -> "snowy"
            else -> "plains"
        }

        val paths = getPaths(validStyle)
        val structureManager = Bukkit.getStructureManager()

        // Ищем первый существующий файл в ресурсах сервера для этого биома
        for (subPath in paths) {
            if (subPath.startsWith("custom/")) return subPath

            val fullPath = "village/$validStyle/$subPath"
            val key = NamespacedKey.minecraft(fullPath)

            if (structureManager.getStructure(key) != null) return fullPath
            try {
                if (structureManager.loadStructure(key) != null) return fullPath
            } catch (_: Exception) {}
        }

        // Если для биома не нашлось вообще ничего (невозможно, но для защиты от крашей), возвращаем дефолт
        val fallbackPath = getPaths("plains").first()
        return "village/plains/$fallbackPath"
    }

    companion object {
        fun byTypeName(name: String): VanillaBuildingType? {
            return entries.find { it.typeName.equals(name, ignoreCase = true) }
        }
    }
}
package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager

data class BlockToPlace(
    val pos: BlockPos,
    val blockData: BlockData,
    var claimedBy: HumanoidVillager? = null,
    var isPlaced: Boolean = false,
    val isRoad: Boolean = false,
    val priority: Int = 0 // Очередь застройки: чем меньше число, тем раньше строится
) {
    val material: Material get() = blockData.material.toBaseIngredient()
}

fun Block.isIgnorableObstacle(): Boolean {
    val type = this.type
    if (type.isAir || this.isLiquid) return true
    if (!type.isSolid && type != Material.WITHER_ROSE) return true
    return false
}

fun Material.isShovelable(): Boolean {
    val name = this.name
    return name.contains("DIRT") ||
            name.contains("GRASS") ||
            name.contains("SAND") ||
            name.contains("GRAVEL") ||
            name.contains("CLAY") ||
            name.contains("MUD") ||
            name.contains("PODZOL") ||
            name.contains("MYCELIUM")
}

fun Material.toBaseIngredient(): Material {
    val name = this.name

    val base = when {
        name.contains("OAK") && !name.contains("DARK_OAK") -> Material.OAK_PLANKS
        name.contains("DARK_OAK") -> Material.DARK_OAK_PLANKS
        name.contains("SPRUCE") -> Material.SPRUCE_PLANKS
        name.contains("BIRCH") -> Material.BIRCH_PLANKS
        name.contains("JUNGLE") -> Material.JUNGLE_PLANKS
        name.contains("ACACIA") -> Material.ACACIA_PLANKS
        name.contains("MANGROVE") -> Material.MANGROVE_PLANKS
        name.contains("CHERRY") -> Material.CHERRY_PLANKS
        name.contains("BAMBOO") -> Material.BAMBOO_PLANKS
        name.contains("CRIMSON") -> Material.CRIMSON_PLANKS
        name.contains("WARPED") -> Material.WARPED_PLANKS
        name.contains("PALE_OAK") -> Material.OAK_PLANKS

        name.contains("STONE") ||
                name.contains("COBBLE") ||
                name.contains("GRANITE") ||
                name.contains("DIORITE") ||
                name.contains("ANDESITE") ||
                name.contains("DEEPSLATE") ||
                name.contains("BLACKSTONE") ||
                name.contains("TUFF") ||
                name.contains("BASALT") -> Material.COBBLESTONE

        name.contains("RED_SANDSTONE") -> Material.RED_SANDSTONE
        name.contains("SANDSTONE") -> Material.SANDSTONE

        name.contains("MUD_BRICK") -> Material.MUD_BRICKS
        name.contains("NETHER_BRICK") -> Material.NETHER_BRICKS
        name.contains("PRISMARINE") -> Material.PRISMARINE
        name.contains("QUARTZ") -> Material.QUARTZ_BLOCK
        name.contains("BRICK") -> Material.BRICKS

        name.contains("GLASS") -> Material.GLASS
        name.contains("COPPER") -> Material.COPPER_BLOCK

        name.contains("WALL_TORCH") -> if (name.contains("SOUL")) Material.SOUL_TORCH else if (name.contains("REDSTONE")) Material.REDSTONE_TORCH else Material.TORCH
        name.contains("TORCH") -> if (name.contains("SOUL")) Material.SOUL_TORCH else if (name.contains("REDSTONE")) Material.REDSTONE_TORCH else Material.TORCH
        name.contains("WALL_SIGN") -> Material.OAK_SIGN
        name.contains("WALL_BANNER") -> Material.WHITE_BANNER
        name.contains("WALL_FAN") -> Material.OAK_PLANKS
        name.contains("LANTERN") -> if (name.contains("SOUL")) Material.SOUL_LANTERN else Material.LANTERN
        name.contains("CAMPFIRE") -> if (name.contains("SOUL")) Material.SOUL_CAMPFIRE else Material.CAMPFIRE
        name.contains("POTTED") -> Material.FLOWER_POT
        name.contains("DIRT_PATH") || name.contains("FARMLAND") || name.contains("PODZOL") || name.contains("MYCELIUM") -> Material.DIRT
        name.contains("FIRE") -> Material.FLINT_AND_STEEL
        name.contains("WATER") -> Material.WATER_BUCKET
        name.contains("LAVA") -> Material.LAVA_BUCKET
        name.contains("KELP") -> Material.KELP
        name.contains("SEAGRASS") -> Material.SEAGRASS
        name.contains("WHEAT") -> Material.WHEAT_SEEDS
        name.contains("CARROTS") -> Material.CARROT
        name.contains("POTATOES") -> Material.POTATO
        name.contains("BEETROOTS") -> Material.BEETROOT_SEEDS
        name.contains("MELON_STEM") -> Material.MELON_SEEDS
        name.contains("PUMPKIN_STEM") -> Material.PUMPKIN_SEEDS
        name.contains("COCOA") -> Material.COCOA_BEANS

        else -> this
    }

    return if (base.isItem) base else Material.COBBLESTONE
}
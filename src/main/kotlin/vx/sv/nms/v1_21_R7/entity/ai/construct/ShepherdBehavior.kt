package vx.sv.nms.v1_21_R7.entity.ai.construct

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Sheep
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import vx.sv.nms.v1_21_R7.VersionSpecificHumanoidEntityProvider.Companion.plugin
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager

class ShepherdBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ),
    200
) {
    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        return bukkitVillager.profession == org.bukkit.entity.Villager.Profession.SHEPHERD && villager.settlement != null
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val center = settlement.data.center
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location

        // 1. Проверяем, ведём ли мы уже какую-то овцу на поводке
        val leashedSheep = bukkitWorld.getEntitiesByClass(Sheep::class.java).find {
            it.isLeashed && it.leashHolder == villager.bukkitEntity
        }

        if (leashedSheep != null) {
            // Если довели овцу близко к центру — отпускаем её
            if (leashedSheep.location.distanceSquared(center) <= 36.0) {
                leashedSheep.setLeashHolder(null)
                
                // Помечаем её домашней деревенской
                leashedSheep.persistentDataContainer.set(
                    NamespacedKey(plugin, "village_animal"),
                    PersistentDataType.BYTE,
                    1.toByte()
                )
                
                villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            } else {
                // Иначе продолжаем вести её к ратуше
                villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.LEAD)))
                val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 2))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        }

        // Собираем всех овец в радиусе 45 блоков
        val allSheep = bukkitWorld.getEntitiesByClass(Sheep::class.java).filter {
            it.location.distanceSquared(npcLoc) <= 2025.0
        }

        // 2. Ищем сбежавшую домашнюю овцу или дикую овцу неподалеку от деревни
        val sheepToHerd = allSheep.find { sheep ->
            val isVillageAnimal = sheep.persistentDataContainer.has(NamespacedKey(plugin, "village_animal"), PersistentDataType.BYTE)
            if (isVillageAnimal) {
                sheep.location.distanceSquared(center) > 900.0 && !sheep.isLeashed
            } else {
                sheep.location.distanceSquared(center) <= 1600.0 && !sheep.isLeashed
            }
        }

        if (sheepToHerd != null) {
            val distSq = npcLoc.distanceSquared(sheepToHerd.location)
            if (distSq <= 9.0) {
                // Подходим и привязываем
                sheepToHerd.setLeashHolder(villager.bukkitEntity)
                villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.LEAD)))
            } else {
                // Идем ловить
                val targetPos = BlockPos(sheepToHerd.location.blockX, sheepToHerd.location.blockY, sheepToHerd.location.blockZ)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        }

        // 3. Стрижка шерсти
        val sheepToShear = allSheep.find { sheep ->
            sheep.isAdult && !sheep.isSheared && sheep.location.distanceSquared(center) <= 900.0
        }

        if (sheepToShear != null) {
            val distSq = npcLoc.distanceSquared(sheepToShear.location)
            if (distSq <= 4.0) {
                // Стрижем овцу
                sheepToShear.isSheared = true
                bukkitWorld.playSound(sheepToShear.location, Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f)
                
                // Дропаем шерсть цвета овцы
                val woolMat = try {
                    Material.valueOf((sheepToShear.color?.name ?: "WHITE") + "_WOOL")
                } catch (e: Exception) {
                    Material.WHITE_WOOL
                }
                bukkitWorld.dropItemNaturally(sheepToShear.location, ItemStack(woolMat, 1 + world.random.nextInt(3)))
                
                // Анимация ножниц
                villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.SHEARS)))
                villager.swing(InteractionHand.MAIN_HAND)
            } else {
                // Идем стричь
                val targetPos = BlockPos(sheepToShear.location.blockX, sheepToShear.location.blockY, sheepToShear.location.blockZ)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        }

        // 4. Кормление и разведение (при численности овец в деревне < 12)
        val villageSheep = allSheep.filter { sheep ->
            sheep.location.distanceSquared(center) <= 900.0 && sheep.isAdult
        }

        if (villageSheep.size < 12) {
            val breedingSheep = villageSheep.find { sheep ->
                !sheep.isLoveMode && sheep.canBreed()
            }

            if (breedingSheep != null) {
                val distSq = npcLoc.distanceSquared(breedingSheep.location)
                if (distSq <= 4.0) {
                    // Кормим пшеницей
                    breedingSheep.loveModeTicks = 600
                    bukkitWorld.playSound(breedingSheep.location, Sound.ENTITY_COW_MILK, 1.0f, 1.0f)
                    
                    villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.WHEAT)))
                    villager.swing(InteractionHand.MAIN_HAND)
                } else {
                    val targetPos = BlockPos(breedingSheep.location.blockX, breedingSheep.location.blockY, breedingSheep.location.blockZ)
                    villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                    villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
                }
                return
            }
        }

        // Сбрасываем вещи из рук, если работы нет
        villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
    }
}
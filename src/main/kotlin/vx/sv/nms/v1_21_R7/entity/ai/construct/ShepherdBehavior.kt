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
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class ShepherdBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
    ),
    200
) {
    companion object {
        // Глобальная потокобезопасная карта резервирования овец
        val reservedSheep = ConcurrentHashMap<UUID, UUID>()

        fun isSheepFree(sheep: Sheep, villager: HumanoidVillager): Boolean {
            val res = reservedSheep[sheep.uniqueId]
            return res == null || res == villager.uuid
        }

        fun claimSheep(sheep: Sheep, villager: HumanoidVillager) {
            releaseReservations(villager.uuid)
            reservedSheep[sheep.uniqueId] = villager.uuid
        }

        fun releaseReservations(villagerUuid: UUID) {
            val iterator = reservedSheep.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value == villagerUuid) {
                    iterator.remove()
                }
            }
        }
    }

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.SHEPHERD || villager.settlement == null) {
            return false
        }

        // ИСПРАВЛЕНО: Пастух работает строго в рабочее время (с 8:00 до 15:00)
        val timeOfDay = world.world.time
        if (timeOfDay < 2000 || timeOfDay > 9000) {
            return false
        }

        return true
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.SHEPHERD || villager.settlement == null) {
            return false
        }

        val timeOfDay = world.world.time
        return timeOfDay in 2000..9000
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val center = settlement.data.center
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location

        // === АНТИ-ЗАСТРЕВАНИЕ (Stuck Prevention) ===
        val nmsPos = villager.position()
        val lastPos = villager.lastPosition
        if (lastPos != null && lastPos.distanceToSqr(nmsPos) < 0.01) {
            villager.stuckTicks++
        } else {
            villager.stuckTicks = 0
            villager.lastPosition = nmsPos
        }

        // 1. ПРОВЕРЯЕМ, ВЕДЕМ ЛИ МЫ УЖЕ ОВЦУ НА ПОВОДКЕ
        val leashedSheep = bukkitWorld.getEntitiesByClass(Sheep::class.java).find {
            it.isLeashed && it.leashHolder == villager.bukkitEntity
        }

        if (leashedSheep != null) {
            claimSheep(leashedSheep, villager)

            // Если пастух завис на месте с овцой больше 5 секунд — веревка обрывается
            if (villager.stuckTicks > 100) {
                leashedSheep.setLeashHolder(null)
                releaseReservations(villager.uuid)
                villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.stuckTicks = 0
                return
            }

            if (leashedSheep.location.distanceSquared(center) <= 36.0) {
                leashedSheep.setLeashHolder(null)

                leashedSheep.persistentDataContainer.set(
                    NamespacedKey(plugin, "village_animal"),
                    PersistentDataType.BYTE,
                    1.toByte()
                )

                releaseReservations(villager.uuid)
                villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            } else {
                villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.LEAD)))
                val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 2))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        }

        val allSheep = bukkitWorld.getEntitiesByClass(Sheep::class.java).filter {
            it.location.distanceSquared(npcLoc) <= 2025.0
        }

        // 2. СТРИЖКА ОВЕЦ (Приоритет 2)
        val sheepToShear = allSheep.find { sheep ->
            sheep.isAdult && !sheep.isSheared && sheep.location.distanceSquared(center) <= 900.0 && isSheepFree(sheep, villager)
        }

        if (sheepToShear != null) {
            claimSheep(sheepToShear, villager)

            // Защита от зависания при попытке дойти до овцы
            if (villager.stuckTicks > 100) {
                releaseReservations(villager.uuid)
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.stuckTicks = 0
                return
            }

            val distSq = npcLoc.distanceSquared(sheepToShear.location)
            if (distSq <= 4.0) {
                sheepToShear.isSheared = true
                bukkitWorld.playSound(sheepToShear.location, Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f)

                val woolMat = try {
                    Material.valueOf(sheepToShear.color?.name + "_WOOL")
                } catch (e: Exception) {
                    Material.WHITE_WOOL
                }
                bukkitWorld.dropItemNaturally(sheepToShear.location, ItemStack(woolMat, 1 + world.random.nextInt(3)))

                villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.SHEARS)))
                villager.swing(InteractionHand.MAIN_HAND)

                releaseReservations(villager.uuid)
            } else {
                val targetPos = BlockPos(sheepToShear.location.blockX, sheepToShear.location.blockY, sheepToShear.location.blockZ)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        }

        // 3. ИЩЕМ СБЕЖАВШУЮ ИЛИ ДИКУЮ ОВЦУ (Приоритет 3)
        val sheepToHerd = allSheep.find { sheep ->
            val isVillageAnimal = sheep.persistentDataContainer.has(NamespacedKey(plugin, "village_animal"), PersistentDataType.BYTE)
            val needsHerding = if (isVillageAnimal) {
                sheep.location.distanceSquared(center) > 900.0 && !sheep.isLeashed
            } else {
                sheep.location.distanceSquared(center) <= 1600.0 && !sheep.isLeashed
            }
            needsHerding && isSheepFree(sheep, villager)
        }

        if (sheepToHerd != null) {
            claimSheep(sheepToHerd, villager)

            if (villager.stuckTicks > 100) {
                releaseReservations(villager.uuid)
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.stuckTicks = 0
                return
            }

            val distSq = npcLoc.distanceSquared(sheepToHerd.location)
            if (distSq <= 9.0) {
                sheepToHerd.setLeashHolder(villager.bukkitEntity)
                villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.LEAD)))
            } else {
                val targetPos = BlockPos(sheepToHerd.location.blockX, sheepToHerd.location.blockY, sheepToHerd.location.blockZ)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        }

        // 4. КОРМЛЕНИЕ И РАЗВЕДЕНИЕ (Приоритет 4)
        val villageSheep = allSheep.filter { sheep ->
            sheep.location.distanceSquared(center) <= 900.0 && sheep.isAdult
        }

        if (villageSheep.size < 12) {
            val breedingSheep = villageSheep.find { sheep ->
                !sheep.isLoveMode && sheep.canBreed() && isSheepFree(sheep, villager)
            }

            if (breedingSheep != null) {
                claimSheep(breedingSheep, villager)

                if (villager.stuckTicks > 100) {
                    releaseReservations(villager.uuid)
                    villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                    villager.stuckTicks = 0
                    return
                }

                val distSq = npcLoc.distanceSquared(breedingSheep.location)
                if (distSq <= 4.0) {
                    breedingSheep.loveModeTicks = 600
                    bukkitWorld.playSound(breedingSheep.location, Sound.ENTITY_COW_MILK, 1.0f, 1.0f)

                    villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.WHEAT)))
                    villager.swing(InteractionHand.MAIN_HAND)

                    releaseReservations(villager.uuid)
                } else {
                    val targetPos = BlockPos(breedingSheep.location.blockX, breedingSheep.location.blockY, breedingSheep.location.blockZ)
                    villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                    villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
                }
                return
            }
        }

        releaseReservations(villager.uuid)
        villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        releaseReservations(villager.uuid)
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
        villager.stuckTicks = 0
        villager.lastPosition = null
    }
}
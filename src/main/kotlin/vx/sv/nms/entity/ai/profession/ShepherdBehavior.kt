package vx.sv.nms.entity.ai.profession

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import net.minecraft.world.item.ItemStack
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Sheep
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import vx.sv.nms.VersionSpecificHumanoidEntityProvider
import vx.sv.nms.entity.HumanoidVillager
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
    private var lastSheepSearchTime = 0L
    private var cachedTargetSheep: Sheep? = null
    private var isHerding = false
    private var noWorkUntil = 0L

    companion object {
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
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        if (bukkitVillager.profession != Villager.Profession.SHEPHERD || villager.settlement == null) {
            return false
        }
        val gameTime = world.gameTime
        if (gameTime < noWorkUntil) return false

        return world.world.time in 0..12000
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        if (bukkitVillager.profession != Villager.Profession.SHEPHERD || villager.settlement == null) {
            return false
        }
        val gameTime = world.gameTime
        if (gameTime < noWorkUntil) return false

        return world.world.time in 0..12000
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val center = settlement.data.center
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location
        val gameTime = world.gameTime

        val nmsPos = villager.position()
        val lastPos = villager.lastPosition
        if (lastPos != null && lastPos.distanceToSqr(nmsPos) < 0.01) {
            villager.stuckTicks++
        } else {
            villager.stuckTicks = 0
            villager.lastPosition = nmsPos
        }

        val leashedSheep = cachedTargetSheep?.takeIf { it.isLeashed && it.leashHolder == villager.bukkitEntity }
        if (leashedSheep != null) {
            claimSheep(leashedSheep, villager)

            if (villager.stuckTicks > 100) {
                leashedSheep.setLeashHolder(null)
                releaseReservations(villager.uuid)
                villager.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                cachedTargetSheep = null
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.stuckTicks = 0
                return
            }

            if (leashedSheep.location.distanceSquared(center) <= 100.0) {
                leashedSheep.setLeashHolder(null)
                leashedSheep.persistentDataContainer.set(
                    NamespacedKey(
                        VersionSpecificHumanoidEntityProvider.Companion.plugin,
                        "village_animal"
                    ), PersistentDataType.BYTE, 1.toByte())
                releaseReservations(villager.uuid)
                villager.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
                cachedTargetSheep = null
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            } else {
                villager.setItemInHand(
                    InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(
                        org.bukkit.inventory.ItemStack(
                            Material.LEAD
                        )
                    ))
                val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
                villager.brain.setMemory(
                    MemoryModuleType.WALK_TARGET,
                    WalkTarget(BlockPosTracker(targetPos), speedModifier, 2)
                )
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        }

        if (cachedTargetSheep == null && gameTime - lastSheepSearchTime > 20L) {
            lastSheepSearchTime = gameTime
            val allSheep = npcLoc.getNearbyEntities(45.0, 20.0, 45.0).filterIsInstance<Sheep>()

            cachedTargetSheep = allSheep.find { sheep ->
                sheep.isAdult && !sheep.isSheared && sheep.location.distanceSquared(center) <= 900.0 && isSheepFree(sheep, villager)
            }
            isHerding = false

            if (cachedTargetSheep == null) {
                cachedTargetSheep = allSheep.find { sheep ->
                    val isVillageAnimal = sheep.persistentDataContainer.has(
                        NamespacedKey(
                            VersionSpecificHumanoidEntityProvider.Companion.plugin,
                            "village_animal"
                        ), PersistentDataType.BYTE)
                    val needsHerding = if (isVillageAnimal) sheep.location.distanceSquared(center) > 900.0 else sheep.location.distanceSquared(center) <= 1600.0
                    needsHerding && !sheep.isLeashed && isSheepFree(sheep, villager)
                }
                if (cachedTargetSheep != null) isHerding = true
            }
        }

        val sheep = cachedTargetSheep
        if (sheep != null && sheep.isValid && !sheep.isDead) {
            claimSheep(sheep, villager)

            if (villager.stuckTicks > 100) {
                releaseReservations(villager.uuid)
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.stuckTicks = 0
                cachedTargetSheep = null
                return
            }

            val distSq = npcLoc.distanceSquared(sheep.location)

            if (isHerding) {
                if (distSq <= 9.0) {
                    sheep.setLeashHolder(villager.bukkitEntity)
                    villager.setItemInHand(
                        InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(
                            org.bukkit.inventory.ItemStack(
                                Material.LEAD
                            )
                        ))
                } else {
                    val targetPos = BlockPos(sheep.location.blockX, sheep.location.blockY, sheep.location.blockZ)
                    villager.brain.setMemory(
                        MemoryModuleType.WALK_TARGET,
                        WalkTarget(BlockPosTracker(targetPos), speedModifier, 1)
                    )
                    villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
                }
            } else {
                if (distSq <= 4.0) {
                    sheep.isSheared = true
                    bukkitWorld.playSound(sheep.location, Sound.ENTITY_SHEEP_SHEAR, 1.0f, 1.0f)
                    val woolMat = try { Material.valueOf(sheep.color?.name + "_WOOL") } catch (e: Exception) { Material.WHITE_WOOL }
                    bukkitWorld.dropItemNaturally(sheep.location,
                        org.bukkit.inventory.ItemStack(woolMat, 1 + world.random.nextInt(3))
                    )

                    villager.setItemInHand(
                        InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(
                            org.bukkit.inventory.ItemStack(
                                Material.SHEARS
                            )
                        ))
                    villager.swing(InteractionHand.MAIN_HAND)

                    releaseReservations(villager.uuid)
                    cachedTargetSheep = null
                } else {
                    val targetPos = BlockPos(sheep.location.blockX, sheep.location.blockY, sheep.location.blockZ)
                    villager.brain.setMemory(
                        MemoryModuleType.WALK_TARGET,
                        WalkTarget(BlockPosTracker(targetPos), speedModifier, 1)
                    )
                    villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
                }
            }
        } else {
            releaseReservations(villager.uuid)
            villager.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
            cachedTargetSheep = null

            // ИСПРАВЛЕНО: Если овец нет — ИИ засыпает на 10 секунд и даёт жителю пойти поработать строителем
            noWorkUntil = gameTime + 200L
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        releaseReservations(villager.uuid)
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
        villager.stuckTicks = 0
        villager.lastPosition = null
        cachedTargetSheep = null
    }
}
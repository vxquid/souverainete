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
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Sheep
import org.bukkit.inventory.ItemStack
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager

class ButcherBehavior(
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

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.BUTCHER || villager.settlement == null) {
            return false
        }
        return world.world.time in 0..12000
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        if (bukkitVillager.profession != org.bukkit.entity.Villager.Profession.BUTCHER || villager.settlement == null) {
            return false
        }
        return world.world.time in 0..12000
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location
        val gameTime = world.gameTime

        // Троттлинг поиска овец для забоя (каждые 40 тиков)
        if (cachedTargetSheep == null && gameTime - lastSheepSearchTime > 40L) {
            lastSheepSearchTime = gameTime
            val localSheep = npcLoc.getNearbyEntities(30.0, 15.0, 30.0).filterIsInstance<Sheep>()

            // Если овец слишком много, находим цель
            if (localSheep.size > 8) {
                cachedTargetSheep = localSheep.find { it.isAdult && ShepherdBehavior.isSheepFree(it, villager) }
            }
        }

        val targetSheep = cachedTargetSheep
        if (targetSheep != null && targetSheep.isValid && !targetSheep.isDead) {
            ShepherdBehavior.claimSheep(targetSheep, villager)

            val distSq = npcLoc.distanceSquared(targetSheep.location)
            if (distSq <= 4.0) {
                targetSheep.damage(20.0, villager.bukkitEntity)
                bukkitWorld.playSound(targetSheep.location, Sound.ENTITY_SHEEP_DEATH, 1.0f, 1.0f)

                villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.IRON_AXE)))
                villager.swing(InteractionHand.MAIN_HAND)

                ShepherdBehavior.releaseReservations(villager.uuid)
                cachedTargetSheep = null
            } else {
                val targetPos = BlockPos(targetSheep.location.blockX, targetSheep.location.blockY, targetSheep.location.blockZ)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        } else {
            cachedTargetSheep = null
        }

        // Если овец для забоя нет, идем к коптильне
        val smokerPos = SettlementPlanner.getWorkstationFor(villager)
        if (smokerPos != null) {
            val distSq = npcLoc.distanceSquared(Location(bukkitWorld, smokerPos.x + 0.5, smokerPos.y + 0.5, smokerPos.z + 0.5))
            val targetPos = BlockPos(smokerPos.x, smokerPos.y, smokerPos.z)
            if (distSq <= 6.0) {
                villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
                if (world.random.nextInt(60) == 0) {
                    villager.swing(InteractionHand.MAIN_HAND)
                    bukkitWorld.playSound(Location(bukkitWorld, smokerPos.x + 0.5, smokerPos.y + 0.5, smokerPos.z + 0.5), Sound.BLOCK_SMOKER_SMOKE, 1.0f, 1.0f)
                }
            } else {
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
        } else {
            ShepherdBehavior.releaseReservations(villager.uuid)
            villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
            val center = settlement.data.center
            val targetPos = BlockPos(center.blockX, center.blockY, center.blockZ)
            villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 3))
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        ShepherdBehavior.releaseReservations(villager.uuid)
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
        cachedTargetSheep = null
    }
}
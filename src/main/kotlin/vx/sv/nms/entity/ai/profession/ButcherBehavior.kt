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
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Sheep
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import vx.sv.nms.entity.HumanoidVillager

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
    private var noWorkUntil = 0L

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        if (bukkitVillager.profession != Villager.Profession.BUTCHER || villager.settlement == null) {
            return false
        }
        val gameTime = world.gameTime
        if (gameTime < noWorkUntil) return false

        return world.world.time in 0..12000
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val bukkitVillager = villager.bukkitEntity as? Villager ?: return false
        if (bukkitVillager.profession != Villager.Profession.BUTCHER || villager.settlement == null) {
            return false
        }
        val gameTime = world.gameTime
        if (gameTime < noWorkUntil) return false

        return world.world.time in 0..12000
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bukkitWorld = world.world
        val npcLoc = villager.bukkitEntity.location
        val gameTime = world.gameTime

        if (cachedTargetSheep == null && gameTime - lastSheepSearchTime > 40L) {
            lastSheepSearchTime = gameTime
            val localSheep = npcLoc.getNearbyEntities(30.0, 15.0, 30.0).filterIsInstance<Sheep>()

            if (localSheep.size > 8) {
                cachedTargetSheep = localSheep.find { it.isAdult && ShepherdBehavior.Companion.isSheepFree(it, villager) }
            }
        }

        val targetSheep = cachedTargetSheep
        if (targetSheep != null && targetSheep.isValid && !targetSheep.isDead) {
            ShepherdBehavior.Companion.claimSheep(targetSheep, villager)

            val distSq = npcLoc.distanceSquared(targetSheep.location)
            if (distSq <= 4.0) {
                targetSheep.damage(20.0, villager.bukkitEntity)
                bukkitWorld.playSound(targetSheep.location, Sound.ENTITY_SHEEP_DEATH, 1.0f, 1.0f)

                villager.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(ItemStack(Material.IRON_AXE)))
                villager.swing(InteractionHand.MAIN_HAND)

                ShepherdBehavior.Companion.releaseReservations(villager.uuid)
                cachedTargetSheep = null
            } else {
                val targetPos = BlockPos(targetSheep.location.blockX, targetSheep.location.blockY, targetSheep.location.blockZ)
                villager.brain.setMemory(MemoryModuleType.WALK_TARGET, WalkTarget(BlockPosTracker(targetPos), speedModifier, 1))
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(targetPos))
            }
            return
        } else {
            ShepherdBehavior.Companion.releaseReservations(villager.uuid)
            villager.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
            cachedTargetSheep = null

            // ИСПРАВЛЕНО: Если овец для забоя нет — ИИ засыпает на 10 секунд и дает жителю пойти строить дома
            noWorkUntil = gameTime + 200L
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        ShepherdBehavior.Companion.releaseReservations(villager.uuid)
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
        cachedTargetSheep = null
    }
}
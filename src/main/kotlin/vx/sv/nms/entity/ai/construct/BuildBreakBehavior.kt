package vx.sv.nms.entity.ai.construct

import com.google.common.collect.ImmutableMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BlockPosTracker
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.memory.WalkTarget
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.nms.entity.HumanoidVillager
import org.bukkit.entity.Villager as BukkitVillager

class BuildBreakBehavior(
    private val speedModifier: Float
) : Behavior<HumanoidVillager>(
    ImmutableMap.of(
        MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
        MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT
    ),
    1200
) {

    override fun checkExtraStartConditions(world: ServerLevel, villager: HumanoidVillager): Boolean {
        val timeOfDay = world.world.time
        if (timeOfDay >= 13000 && timeOfDay <= 23000) {
            return false
        }

        val bukkitVillager = villager.bukkitEntity as? org.bukkit.entity.Villager ?: return false
        val prof = bukkitVillager.profession

        if (prof == org.bukkit.entity.Villager.Profession.FARMER ||
            prof == org.bukkit.entity.Villager.Profession.SHEPHERD ||
            prof == org.bukkit.entity.Villager.Profession.BUTCHER ||
            prof == org.bukkit.entity.Villager.Profession.TOOLSMITH) {
            return false
        }

        return world.gameTime < villager.buildBreakUntilTime && villager.settlement != null
    }

    override fun canStillUse(world: ServerLevel, villager: HumanoidVillager, time: Long): Boolean {
        val timeOfDay = world.world.time
        if (timeOfDay >= 13000 && timeOfDay <= 23000) {
            return false
        }
        return world.gameTime < villager.buildBreakUntilTime && villager.settlement != null
    }

    override fun start(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bellLoc = settlement.data.center
        val bellPos = BlockPos(bellLoc.blockX, bellLoc.blockY, bellLoc.blockZ)

        villager.brain.setMemory(
            MemoryModuleType.WALK_TARGET,
            WalkTarget(BlockPosTracker(bellPos), speedModifier, 3)
        )
        villager.brain.setMemory(
            MemoryModuleType.LOOK_TARGET,
            BlockPosTracker(bellPos)
        )
    }

    override fun tick(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        val settlement = villager.settlement ?: return
        val bellLoc = settlement.data.center
        val bellPos = BlockPos(bellLoc.blockX, bellLoc.blockY, bellLoc.blockZ)

        val npcPos = villager.blockPosition()
        val distSqr = npcPos.distSqr(bellPos)

        if (distSqr <= 16.0) {
            villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)

            if (world.random.nextInt(40) == 0) {
                val randomOffset = BlockPos(
                    bellPos.x + world.random.nextInt(7) - 3,
                    bellPos.y,
                    bellPos.z + world.random.nextInt(7) - 3
                )
                villager.brain.setMemory(MemoryModuleType.LOOK_TARGET, BlockPosTracker(randomOffset))
            }
        } else {
            if (!villager.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                villager.brain.setMemory(
                    MemoryModuleType.WALK_TARGET,
                    WalkTarget(BlockPosTracker(bellPos), speedModifier, 3)
                )
            }
        }
    }

    override fun stop(world: ServerLevel, villager: HumanoidVillager, time: Long) {
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
        villager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
    }
}

class BuilderSafetyListener : Listener {

    @EventHandler
    fun onNpcDamage(event: EntityDamageEvent) {
        val villager = event.entity as? BukkitVillager ?: return
        val nmsVillager = (villager as? CraftVillager)?.handle as? HumanoidVillager ?: return

        val cause = event.cause
        val isSuffocation = cause == EntityDamageEvent.DamageCause.SUFFOCATION
        val isDrowning = cause == EntityDamageEvent.DamageCause.DROWNING

        if (isSuffocation || isDrowning) {
            event.isCancelled = true

            if (isDrowning) {
                villager.remainingAir = villager.maximumAir
            }

            val settlement = nmsVillager.settlement
            val safeLoc = if (settlement != null) {
                val center = settlement.data.center

                var targetY = center.blockY
                var targetX = center.blockX + 2
                var targetZ = center.blockZ + 2

                val world = center.world!!
                var foundSafeSpot = false
                for (ox in listOf(2, -2, 3, -3, 1, -1)) {
                    for (oz in listOf(2, -2, 3, -3, 1, -1)) {
                        val tx = center.blockX + ox
                        val tz = center.blockZ + oz
                        val gy = world.getHighestBlockYAt(tx, tz)

                        if (gy <= center.blockY + 1) {
                            val feetBlock = world.getBlockAt(tx, gy + 1, tz)
                            val headBlock = world.getBlockAt(tx, gy + 2, tz)
                            if (feetBlock.type.isAir && headBlock.type.isAir) {
                                targetX = tx
                                targetY = gy
                                targetZ = tz
                                foundSafeSpot = true
                                break
                            }
                        }
                    }
                    if (foundSafeSpot) break
                }
                Location(world, targetX + 0.5, targetY + 1.0, targetZ + 0.5)
            } else {
                val loc = villager.location
                val highestY = loc.world.getHighestBlockYAt(loc.blockX, loc.blockZ)
                Location(loc.world, loc.x, highestY + 1.0, loc.z, loc.yaw, loc.pitch)
            }

            val job = nmsVillager.activeBuildJob
            val assigned = nmsVillager.assignedBlock
            if (assigned != null && job != null) {
                job.unclaimBlock(assigned)
            }

            nmsVillager.assignedBlock = null
            nmsVillager.digTicks = 0
            nmsVillager.buildTicks = 0
            nmsVillager.isBuildDistanceHackActive = false
            nmsVillager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            nmsVillager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)

            villager.teleport(safeLoc)

            villager.world.playSound(safeLoc, Sound.ENTITY_VILLAGER_HURT, 1.0f, 1.0f)
            villager.world.spawnParticle(Particle.ANGRY_VILLAGER, safeLoc.clone().add(0.0, 1.5, 0.0), 5, 0.2, 0.2, 0.2)
            return
        }

        if (nmsVillager.activeBuildJob != null) {
            if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK ||
                cause == EntityDamageEvent.DamageCause.PROJECTILE) {

                val job = nmsVillager.activeBuildJob
                val assigned = nmsVillager.assignedBlock

                if (assigned != null && job != null) {
                    job.unclaimBlock(assigned)
                }

                nmsVillager.assignedBlock = null
                nmsVillager.digTicks = 0
                nmsVillager.buildTicks = 0
                nmsVillager.isBuildDistanceHackActive = false
                nmsVillager.lastBuildActionTime = nmsVillager.level().gameTime

                nmsVillager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                nmsVillager.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
                nmsVillager.refreshBrain(nmsVillager.level() as ServerLevel)

                val loc = villager.location
                loc.world.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0.0, 1.5, 0.0), 5, 0.2, 0.2, 0.2)
            }
        }
    }
}
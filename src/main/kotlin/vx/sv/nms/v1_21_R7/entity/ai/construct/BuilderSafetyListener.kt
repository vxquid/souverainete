package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.world.entity.ai.memory.MemoryModuleType
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import org.bukkit.entity.Villager as BukkitVillager

class BuilderSafetyListener : Listener {

    @EventHandler
    fun onNpcDamage(event: EntityDamageEvent) {
        val villager = event.entity as? BukkitVillager ?: return

        // Реагируем только на урон от удушья (застревание в блоках)
        if (event.cause == EntityDamageEvent.DamageCause.SUFFOCATION) {
            val nmsVillager = (villager as? CraftVillager)?.handle as? HumanoidVillager ?: return

            // Спасаем жителя только если он сейчас находится в процессе строительства
            if (nmsVillager.activeBuildJob != null) {
                event.isCancelled = true // Полностью отменяем урон

                val settlement = nmsVillager.settlement
                val safeLoc = if (settlement != null) {
                    val center = settlement.data.center
                    // Находим самую верхнюю точку над центром деревни, чтобы не заспавнить жителя внутри текстур ратуши/беседки
                    val highestY = center.world.getHighestBlockYAt(center.blockX, center.blockZ)
                    Location(center.world, center.x + 0.5, highestY + 1.0, center.z + 0.5)
                } else {
                    val loc = villager.location
                    val highestY = loc.world.getHighestBlockYAt(loc.blockX, loc.blockZ)
                    Location(loc.world, loc.x, highestY + 1.0, loc.z, loc.yaw, loc.pitch)
                }

                // Полностью сбрасываем текущую микро-задачу ИИ, чтобы он пересчитал путь и не бежал обратно в баганную стену
                nmsVillager.assignedBlock = null
                nmsVillager.digTicks = 0
                nmsVillager.buildTicks = 0
                nmsVillager.isBuildDistanceHackActive = false
                nmsVillager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)

                // Телепортируем в безопасность
                villager.teleport(safeLoc)

                // Проигрываем эффекты паники и звуки спасения
                villager.world.playSound(safeLoc, Sound.ENTITY_VILLAGER_HURT, 1.0f, 1.0f)
                villager.world.spawnParticle(Particle.ANGRY_VILLAGER, safeLoc.clone().add(0.0, 1.5, 0.0), 5, 0.2, 0.2, 0.2)
            }
        }
    }
}
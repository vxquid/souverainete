package vx.sv.gameplay.humanoid.combat

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.BlockData
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.personality.PersonalityManager.Companion.gender
import vx.sv.gameplay.personality.PersonalityManager.Gender
import kotlin.random.Random

class HumanoidCombatHandler : Listener {

    private val bloodBlockData: BlockData = Material.REDSTONE_BLOCK.createBlockData()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    // 1. Звуки ударов, звон брони, партиклы крови и искр
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityAttack(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? LivingEntity ?: return
        val target = event.entity as? LivingEntity ?: return

        // Применяем эффекты, если атакующий — наш кастомный NPC
        if (damager is Villager) {
            val world = target.world
            val loc = target.location.add(0.0, 1.0, 0.0)
            val hasArmor = target.equipment?.chestplate?.type?.isAir == false

            // Базовый звук тяжелого удара по плоти
            world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.8f, 1.0f)

            if (hasArmor) {
                // Металлический звон об броню
                world.playSound(loc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.6f, 1.5f)
                world.spawnParticle(Particle.CRIT, loc, 3, 0.2, 0.2, 0.2, 0.1)
            } else {
                // Влажный звук разрыва плоти
                world.playSound(loc, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.7f, 1.2f)
                world.spawnParticle(Particle.BLOCK_CRUMBLE, loc, 10, 0.2, 0.2, 0.2, bloodBlockData)
            }

            // Шанс на крит или размашистый удар
            if (Random.nextBoolean()) {
                world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f)
            } else {
                world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.8f)
            }
        }
    }

    // 2. Расовые звуки получения урона (Hurt)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityHurt(event: EntityDamageEvent) {
        val entity = event.entity as? LivingEntity ?: return

        // Если это наш NPC-гуманоид (житель)
        if (entity is Villager) {
            val race = entity.race
            val gender = entity.gender

            val soundData = if (gender == Gender.MALE) race.maleHurtSound else race.femaleHurtSound
            val sound = soundData.sound.get() ?: return

            val pitch = Random.nextDouble(soundData.min, soundData.max).toFloat()

            entity.world.playSound(entity.location, sound, 1.0f, pitch)
        }
    }

    // 3. Расовые звуки смерти (Death)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity

        if (entity is Villager) {
            val race = entity.race
            val gender = entity.gender

            val soundData = if (gender == Gender.MALE) race.maleDeathSound else race.femaleDeathSound
            val sound = soundData.sound.get() ?: return

            val pitch = Random.nextDouble(soundData.min, soundData.max).toFloat()

            entity.world.playSound(entity.location, sound, 1.0f, pitch)
        }
    }
}
package vx.ignis.gameplay.humanoid

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SuspiciousStewMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.HumanoidManager.Companion.asHumanoid
import vx.ignis.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.ignis.persistent.LivingEntityExtend.getVoicePitch
import vx.ignis.persistent.LivingEntityExtend.getVoiceSound
import vx.ignis.persistent.LivingEntityExtend.hasEdibleItem
import vx.ignis.persistent.LivingEntityExtend.hunger
import vx.ignis.persistent.LivingEntityExtend.subInventory
import vx.ignis.persistent.LivingEntityExtend.takeItemFromQuillInventory
import kotlin.random.Random

object HungerManager {

    private val hungerDecreaseInterval = plugin.gameplayManager.config.hunger.decreaseInterval
    private val hungerDecreaseAmount = plugin.gameplayManager.config.hunger.decrease
    private val hungerEatThreshold = plugin.gameplayManager.config.hunger.eatThreshold
    private val hungerQuestThreshold = plugin.gameplayManager.config.hunger.questThreshold
    private val hungerMax = plugin.gameplayManager.config.hunger.max
    private val hungerRegenThreshold = plugin.gameplayManager.config.hunger.regenThreshold
    private val hungerStarvationThreshold = plugin.gameplayManager.config.hunger.starvationThreshold
    private val hungerStarvationDamage = plugin.gameplayManager.config.hunger.starvationDamage

    fun startTicker() {
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            tickHunger()
        }, 0, hungerDecreaseInterval)
    }

    private fun tickHunger() {
        plugin.gameplayManager.allowedWorlds.flatMap { it.entities.filterIsInstance<Villager>() }.shuffled().forEachIndexed { index, villager ->
            if (villager.pose != org.bukkit.entity.Pose.SLEEPING) {
                // Decrease hunger gradually
                villager.hunger = (villager.hunger - hungerDecreaseAmount).coerceAtLeast(0.0)

                // Apply effects based on hunger level
                applyHungerEffects(villager)

                // If below eat threshold and has edible item, schedule eat
                if (villager.hunger <= hungerEatThreshold && villager.hasEdibleItem()) {
                    plugin.server.scheduler.runTaskLater(plugin, { _ -> villager.eat() }, 5 + (index * 2L).coerceAtMost(40) + Random.nextInt(250))
                }
            }
        }
    }

    private fun applyHungerEffects(entity: LivingEntity) {
        if (entity.hunger >= hungerRegenThreshold) {
            entity.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 200, 2))
        } else if (entity.hunger <= hungerStarvationThreshold) {
            // Apply starvation damage and weakness
            entity.damage(hungerStarvationDamage)
            entity.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 200, 1))
            entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 200, 0))
        } else if (entity.hunger <= hungerEatThreshold) {
            // Minor debuffs when hungry but not starving
            entity.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 200, 0))
        }
    }

    fun LivingEntity.eat() {
        subInventory.filterNotNull().find { it.type.isEdible }?.let { food ->
            val sound = when (food.type) {
                Material.HONEY_BOTTLE -> Sound.ITEM_HONEY_BOTTLE_DRINK
                Material.MUSHROOM_STEW, Material.RABBIT_STEW, Material.SUSPICIOUS_STEW, Material.BEETROOT_SOUP -> Sound.ENTITY_GENERIC_DRINK
                else -> Sound.ENTITY_GENERIC_EAT
            }

            // Use the Humanoid consume method
            val humanoid = this.asHumanoid() ?: return
            humanoid.consume(world, food, sound, 7, location) {
                takeItemFromQuillInventory(food, 1)
                if (food.type.toString().contains("STEW") || food.type == Material.BEETROOT_SOUP) {
                    addItemToQuillInventory(ItemStack(Material.BOWL))
                    (food.itemMeta as? SuspiciousStewMeta)?.customEffects?.forEach { addPotionEffect(it) }
                }
                if (food.type == Material.HONEY_BOTTLE) addItemToQuillInventory(ItemStack(Material.GLASS_BOTTLE))
                world.playSound(location, getVoiceSound(), 1F, getVoicePitch())
                world.playSound(location, Sound.ENTITY_PLAYER_BURP, 1F, 1F)
                hunger = (hunger + calculateFoodRestoration(food)).coerceAtMost(hungerMax)
                applyHungerEffects(this)
            }
        }
    }

    // Helper to calculate how much hunger food restores (customizable via config if needed)
    private fun calculateFoodRestoration(food: ItemStack): Double {
        // Base on Minecraft food values, but can be extended
        return when (food.type) {
            Material.APPLE -> 4.0
            Material.BREAD -> 5.0
            Material.COOKED_BEEF -> 8.0
            // Add more as needed
            else -> 2.0 // Default
        } * food.amount.coerceAtLeast(1)
    }

}
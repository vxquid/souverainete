package vx.ignis.gameplay.death

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Pose
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.shout
import vx.ignis.gameplay.party.PartyManager.Companion.partyLeaderUUID
import kotlin.random.Random

class DeathManager : Listener {

    private val downedKey = NamespacedKey(plugin, "is_downed")
    private val downedTimerKey = NamespacedKey(plugin, "downed_death_timer")

    var deathMode: DeathMode = DeathMode.KNOCKOUT
    private val bleedOutTime = 1200L

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startBleedOutTicker()
    }

    enum class DeathMode {
        KNOCKOUT, FATALISM, RESPAWN
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        val villager = event.entity as? Villager ?: return

        if (!plugin.gameplayManager.partyManager.hasParty(villager) && deathMode != DeathMode.FATALISM) return

        if (villager.health - event.finalDamage <= 0) {
            when (deathMode) {
                DeathMode.FATALISM -> { /* Ванильная смерть */ }
                DeathMode.RESPAWN -> {
                    event.isCancelled = true
                    handleRespawn(villager)
                }
                DeathMode.KNOCKOUT -> {
                    event.isCancelled = true
                    // Если уже в нокауте и бьют — можно отнимать время таймера или добить
                    if (!isDowned(villager)) {
                        knockoutVillager(villager)
                    }
                }
            }
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEntityEvent) {
        val villager = event.rightClicked as? Villager ?: return
        val player = event.player

        if (isDowned(villager)) {
            event.isCancelled = true

            // 1. Взять на плечи / Снять с плеч
            if (player.isSneaking) {
                if (villager.vehicle == null) {
                    // --- ВЗЯТЬ ---
                    // ВАЖНО: Выключаем режим лежания, иначе хитбокс жителя
                    // застрянет в голове игрока и его сбросит сервер.
                    villager.isGliding = false
                    villager.isSwimming = false

                    if (player.addPassenger(villager)) {
                        player.sendActionBar(Component.text("You are carrying ${villager.customName}", NamedTextColor.YELLOW))
                    }
                } else {
                    // --- БРОСИТЬ ---
                    villager.leaveVehicle()

                    // Возвращаем позу лежания с небольшой задержкой,
                    // чтобы он успел "отлипнуть" от игрока
                    plugin.server.scheduler.runTaskLater(plugin, { _ ->
                        if (isDowned(villager)) {
                            villager.isGliding = true
                        }
                    }, 5L)
                }
                return
            }

            // 2. Лечение
            val item = player.inventory.itemInMainHand
            if (isReviveItem(item)) {
                item.amount -= 1
                reviveVillager(villager)
                player.sendMessage(Component.text("You revived ${villager.customName}!", NamedTextColor.GREEN))
                player.playSound(player.location, Sound.ENTITY_VILLAGER_YES, 1f, 1f)
            } else {
                player.sendActionBar(Component.text("Needs a Golden Apple or Potion to revive!", NamedTextColor.RED))
            }
        }
    }

    // --- LOGIC: KNOCKOUT ---

    private fun knockoutVillager(villager: Villager) {
        villager.persistentDataContainer.set(downedKey, PersistentDataType.BYTE, 1)
        villager.persistentDataContainer.set(downedTimerKey, PersistentDataType.LONG, System.currentTimeMillis() + (bleedOutTime * 50))

        villager.health = 1.0
        villager.isAware = false // Отключает AI
        villager.pose = if (Random.nextBoolean()) Pose.SLEEPING else Pose.SWIMMING

        val helpPhrases = listOf("I'm down!", "Help me!", "Too much blood...", "Don't let me die!")
        villager.shout(helpPhrases.random())
    }

    private fun reviveVillager(villager: Villager) {
        villager.persistentDataContainer.remove(downedKey)
        villager.persistentDataContainer.remove(downedTimerKey)

        villager.isAware = true
        villager.isGlowing = false
        villager.pose = Pose.STANDING
        villager.leaveVehicle()

        val maxHealth = villager.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        villager.health = maxHealth * 0.2

        villager.shout("Thanks! I owe you one.")
    }

    private fun isDowned(villager: Villager): Boolean {
        return villager.persistentDataContainer.has(downedKey, PersistentDataType.BYTE)
    }

    private fun isReviveItem(item: ItemStack): Boolean {
        if (!item.hasItemMeta()) return item.type == Material.GOLDEN_APPLE || item.type == Material.ENCHANTED_GOLDEN_APPLE

        return item.type == Material.GOLDEN_APPLE ||
                item.type == Material.ENCHANTED_GOLDEN_APPLE ||
                (item.type == Material.POTION) // Можно добавить проверку на тип зелья
    }

    private fun startBleedOutTicker() {
        object : BukkitRunnable() {
            override fun run() {
                for (world in Bukkit.getWorlds()) {
                    // Оптимизация: перебираем только живых сущностей
                    for (entity in world.livingEntities) {
                        if (entity is Villager && isDowned(entity)) {
                            checkBleedOut(entity)
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun checkBleedOut(villager: Villager) {
        val deathTime = villager.persistentDataContainer.get(downedTimerKey, PersistentDataType.LONG) ?: return

        if (System.currentTimeMillis() > deathTime) {
            handleRespawn(villager)
            villager.shout("I couldn't hold on... Meeting you at home.")
        } else {
            // Частицы крови
            villager.world.spawnParticle(org.bukkit.Particle.DAMAGE_INDICATOR, villager.location.add(0.0, 0.5, 0.0), 1)
        }
    }

    // --- LOGIC: RESPAWN ---

    private fun handleRespawn(villager: Villager) {
        if (isDowned(villager)) {
            villager.persistentDataContainer.remove(downedKey)
            villager.persistentDataContainer.remove(downedTimerKey)
            villager.isAware = true
            villager.isGliding = false
            villager.isGlowing = false
            villager.leaveVehicle()
        }

        val leaderUUID = villager.partyLeaderUUID
        val targetLocation: Location = if (leaderUUID != null) {
            val player = Bukkit.getPlayer(leaderUUID)
            player?.respawnLocation
                ?: Bukkit.getOfflinePlayer(leaderUUID).respawnLocation
                ?: villager.world.spawnLocation
        } else {
            villager.world.spawnLocation
        }

        villager.teleport(targetLocation)
        villager.health = villager.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        villager.world.playSound(targetLocation, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f)
    }
}
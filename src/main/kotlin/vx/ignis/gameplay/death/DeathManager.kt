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
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.dialogue.DialogueManager.Companion.shout
import vx.ignis.gameplay.party.PartyManager.Companion.partyLeaderUUID
import vx.ignis.persistent.LivingEntityExtend.settlement
import kotlin.random.Random

class DeathManager : Listener {

    private val downedKey = NamespacedKey(plugin, "is_downed")
    private val downedTimerKey = NamespacedKey(plugin, "downed_death_timer")

    // Режим можно менять через конфиг или команды, здесь по умолчанию KNOCKOUT
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

        // Если житель не в пати и режим не фатализм — игнорируем (пусть умирает как обычно, если не настроено иначе)
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
                    // Если уже в нокауте и бьют — добиваем или ускоряем смерть.
                    // Здесь логика: если в нокауте и получил летал — отправляем домой/на смерть.
                    if (isDowned(villager)) {
                        handleRespawn(villager)
                    } else {
                        knockoutVillager(villager)
                    }
                }
            }
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEntityEvent) {
        // Исправление бага с мгновенным сбросом: обрабатываем только главную руку
        if (event.hand != EquipmentSlot.HAND) return

        val villager = event.rightClicked as? Villager ?: return
        val player = event.player

        if (isDowned(villager)) {
            event.isCancelled = true

            // 1. Взять на плечи / Снять с плеч
            if (player.isSneaking) {
                if (villager.vehicle == null) {
                    if (player.addPassenger(villager)) {
                        villager.pose = Pose.SITTING
                        val carryMsg = plugin.language.getString("death-messages.carry-start")
                            ?.replace("{villagerName}", villager.customName ?: "Villager")
                            ?: "Carrying villager"
                        player.sendActionBar(Component.text(carryMsg, NamedTextColor.YELLOW))
                    }
                } else {
                    villager.leaveVehicle()
                    // Возвращаем позу с задержкой
                    plugin.server.scheduler.runTaskLater(plugin, { _ ->
                        if (isDowned(villager)) {
                            villager.pose = if (Random.nextBoolean()) Pose.SLEEPING else Pose.SWIMMING
                        }
                    }, 5L)
                }
                return
            }

            // 2. Лечение
            val item = player.inventory.itemInMainHand
            if (isReviveItem(item)) {
                item.amount -= 1
                reviveVillager(villager, player)
            } else {
                val neededMsg = plugin.language.getString("death-messages.needs-revive-item")
                    ?: "Needs a Golden Apple or Potion!"
                player.sendActionBar(Component.text(neededMsg, NamedTextColor.RED))
            }
        }
    }

    // --- LOGIC: KNOCKOUT ---

    private fun knockoutVillager(villager: Villager) {
        villager.persistentDataContainer.set(downedKey, PersistentDataType.BYTE, 1)
        villager.persistentDataContainer.set(downedTimerKey, PersistentDataType.LONG, System.currentTimeMillis() + (bleedOutTime * 50))

        villager.health = 1.0
        villager.isAware = false
        villager.pose = if (Random.nextBoolean()) Pose.SLEEPING else Pose.SWIMMING

        // Фразы можно вынести в конфиг, но пока оставим хардкод или добавим в lang
        val helpPhrases = listOf("I'm down!", "Help me!", "Too much blood...", "Don't let me die!")
        villager.shout(helpPhrases.random())
    }

    private fun reviveVillager(villager: Villager, savior: org.bukkit.entity.Player) {
        cleanDownedState(villager)

        val maxHealth = villager.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        villager.health = maxHealth * 0.2

        val reviveMsg = plugin.language.getString("death-messages.revived")
            ?.replace("{villagerName}", villager.customName ?: "Villager")
            ?: "You revived the villager!"

        savior.sendMessage(Component.text(reviveMsg, NamedTextColor.GREEN))
        savior.playSound(savior.location, Sound.ENTITY_VILLAGER_YES, 1f, 1f)
        villager.shout("Thanks! I owe you one.")
    }

    private fun isDowned(villager: Villager): Boolean {
        return villager.persistentDataContainer.has(downedKey, PersistentDataType.BYTE)
    }

    private fun isReviveItem(item: ItemStack): Boolean {
        if (!item.hasItemMeta()) return item.type == Material.GOLDEN_APPLE || item.type == Material.ENCHANTED_GOLDEN_APPLE
        return item.type == Material.GOLDEN_APPLE || item.type == Material.ENCHANTED_GOLDEN_APPLE || item.type == Material.POTION
    }

    private fun startBleedOutTicker() {
        object : BukkitRunnable() {
            override fun run() {
                for (world in Bukkit.getWorlds()) {
                    world.livingEntities.forEach { entity ->
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
            villager.shout("I couldn't hold on...")
            handleRespawn(villager)
        } else {
            villager.world.spawnParticle(Particle.DAMAGE_INDICATOR, villager.location.add(0.0, 0.5, 0.0), 1)
        }
    }

    // --- LOGIC: RESPAWN / DEATH ---

    private fun handleRespawn(villager: Villager) {
        // Сначала очищаем состояние нокаута
        cleanDownedState(villager)

        val homeSettlement = villager.settlement

        // 1. Если нет поселения — смерть
        if (homeSettlement == null) {
            villager.health = 0.0 // Это вызовет анимацию смерти и дроп

            // Сообщение о смерти
            val deathMsg = plugin.language.getString("death-messages.permanent-death")
                ?.replace("{villagerName}", villager.customName ?: "A villager")
                ?: "${villager.customName} has perished."

            // Отправляем сообщение лидеру пати, если он есть
            villager.partyLeaderUUID?.let { uuid ->
                Bukkit.getPlayer(uuid)?.sendMessage(Component.text(deathMsg, NamedTextColor.RED))
            }
            return
        }

        // 2. Если есть поселение — эвакуация
        val targetLoc = homeSettlement.data.center

        // Обязательно загружаем чанк перед телепортацией
        if (!targetLoc.chunk.isLoaded) {
            targetLoc.chunk.load()
        }

        villager.teleport(targetLoc)
        villager.health = villager.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        villager.world.playSound(targetLoc, Sound.BLOCK_BELL_RESONATE, 1f, 1f) // Звук колокола при возвращении

        // Сообщение об эвакуации всем игрокам в мире (или только лидеру, по желанию. Сделаем лидеру).
        val escapedMsg = plugin.language.getString("death-messages.returned-home")
            ?.replace("{villagerName}", villager.customName ?: "Villager")
            ?.replace("{settlementName}", homeSettlement.data.settlementName)
            ?: "${villager.customName} was critically injured and returned to ${homeSettlement.data.settlementName}."

        // Оповещаем бывших сопартийцев (или просто игроков в мире, если хотите глобально)
        homeSettlement.world.players.forEach { player ->
            player.sendMessage(Component.text(escapedMsg, NamedTextColor.GOLD))
        }
    }

    private fun cleanDownedState(villager: Villager) {
        if (isDowned(villager)) {
            villager.persistentDataContainer.remove(downedKey)
            villager.persistentDataContainer.remove(downedTimerKey)
            villager.isAware = true
            villager.isGliding = false
            villager.isGlowing = false
            villager.leaveVehicle()
            villager.pose = Pose.STANDING
        }
    }
}
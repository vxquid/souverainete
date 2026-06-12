package vx.sv.gameplay.humanoid

import org.bukkit.Material.*
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import vx.sv.Souverainete.Companion.plugin
import vx.sv.nms.VersionBridge.Companion.asHumanoid
import vx.sv.persistent.LivingEntityExtend.subInventory
import vx.sv.persistent.LivingEntityExtend.takeItemFromQuillInventory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

class EquipmentManager : Listener {

    companion object {
        private const val SWEEP_INTERVAL_TICKS = 100L // Раз в 5 секунд собираем всех
        private const val PROCESS_INTERVAL_TICKS = 1L // Каждую 1 тику (50мс) обрабатываем пачку
        private const val BATCH_SIZE = 10 // Сколько NPC обрабатывать за 1 тику
    }

    // Потокобезопасная очередь для распределения нагрузки (FAWE style)
    private val evaluationQueue = ConcurrentLinkedQueue<Villager>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        startQueueFiller()
        startQueueProcessor()
    }

    /**
     * MAIN THREAD: Собираем NPC по мирам и закидываем в очередь.
     * Запускается редко (раз в 5 секунд).
     */
    private fun startQueueFiller() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            plugin.gameplayManager.allowedWorlds.forEach { world ->
                val villagers = world.getEntitiesByClass(Villager::class.java)
                for (villager in villagers) {
                    if (villager.isValid && !evaluationQueue.contains(villager)) {
                        evaluationQueue.offer(villager)
                    }
                }
            }
        }, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS)
    }

    /**
     * ASYNC THREAD: Воркер, который "откусывает" от очереди по BATCH_SIZE штук каждый тик.
     * Оценка инвентаря и предметов по-прежнему происходит асинхронно!
     */
    private fun startQueueProcessor() {
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable {
            if (evaluationQueue.isEmpty()) return@Runnable

            for (i in 0 until BATCH_SIZE) {
                val villager = evaluationQueue.poll() ?: break

                if (villager.isValid && !villager.isDead) {
                    equipBestEquipmentFor(villager)
                }
            }
        }, PROCESS_INTERVAL_TICKS, PROCESS_INTERVAL_TICKS)
    }

    @EventHandler
    private fun onWorldLoad(event: WorldLoadEvent) {
        if (!plugin.gameplayManager.allowedWorlds.contains(event.world)) return

        val villagers = event.world.getEntitiesByClass(Villager::class.java)
        villagers.forEach {
            if (it.isValid && !evaluationQueue.contains(it)) {
                // Очистку экипировки принудительно делаем на ГЛАВНОМ потоке
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (it.isValid) removeEquipment(it)
                })
                evaluationQueue.offer(it)
            }
        }
    }

    @EventHandler
    private fun onChunkLoad(event: ChunkLoadEvent) {
        if (!plugin.gameplayManager.allowedWorlds.contains(event.world)) return

        val villagers = event.chunk.entities.filterIsInstance<Villager>()
        villagers.forEach {
            if (it.isValid && !evaluationQueue.contains(it)) {
                // Очистку экипировки принудительно делаем на ГЛАВНОМ потоке
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (it.isValid) removeEquipment(it)
                })
                evaluationQueue.offer(it)
            }
        }
    }

    @EventHandler
    private fun onEntityResurrect(event: EntityResurrectEvent) {
        val villager = event.entity as? Villager ?: return
        if (!plugin.gameplayManager.allowedWorlds.contains(villager.world)) return

        val slot = event.hand ?: return

        // Тотем срабатывает мгновенно, выполняем операцию строго на ГЛАВНОМ потоке на следующем тике
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (villager.isValid) {
                villager.asHumanoid()?.equip(slot, ItemStack(AIR))
                villager.takeItemFromQuillInventory(ItemStack(TOTEM_OF_UNDYING), 1)
            }
        })
    }

    private fun removeEquipment(villager: Villager) {
        EquipmentSlot.entries.forEach { slot ->
            villager.asHumanoid()?.equip(slot, ItemStack(AIR))
        }
    }

    fun equipBestEquipmentFor(villager: Villager) {
        val availableItems = villager.subInventory.filterNotNull()
        if (availableItems.isEmpty()) return

        val bestItems = mutableMapOf<EquipmentSlot, ItemStack>()
        val bestScores = mutableMapOf<EquipmentSlot, Double>()

        for (item in availableItems) {
            val slot = getSlotForItem(item) ?: continue
            val score = evaluateItem(item, slot)

            val currentBestScore = bestScores.getOrDefault(slot, -1.0)
            if (score > currentBestScore) {
                bestScores[slot] = score
                bestItems[slot] = item
            }
        }

        // КРИТИЧЕСКИЙ ФИКС: Если лучшая экипировка найдена,
        // перенаправляем ее применение на ГЛАВНЫЙ ПОТОК (Main Thread)
        if (bestItems.isNotEmpty()) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (villager.isValid && !villager.isDead) {
                    applyEquipmentChanges(villager, bestItems)
                }
            })
        }
    }

    private fun getSlotForItem(item: ItemStack): EquipmentSlot? {
        return when (item.type) {
            WOODEN_SWORD, STONE_SWORD, IRON_SWORD,
            GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD,
            WOODEN_AXE, STONE_AXE, IRON_AXE,
            DIAMOND_AXE, NETHERITE_AXE, BOW, CROSSBOW -> EquipmentSlot.HAND

            SHIELD, TOTEM_OF_UNDYING -> EquipmentSlot.OFF_HAND

            LEATHER_HELMET, CHAINMAIL_HELMET, IRON_HELMET,
            GOLDEN_HELMET, DIAMOND_HELMET, NETHERITE_HELMET,
            TURTLE_HELMET -> EquipmentSlot.HEAD

            LEATHER_CHESTPLATE, CHAINMAIL_CHESTPLATE, IRON_CHESTPLATE,
            GOLDEN_CHESTPLATE, DIAMOND_CHESTPLATE, NETHERITE_CHESTPLATE -> EquipmentSlot.CHEST

            LEATHER_LEGGINGS, CHAINMAIL_LEGGINGS, IRON_LEGGINGS,
            GOLDEN_LEGGINGS, DIAMOND_LEGGINGS, NETHERITE_LEGGINGS -> EquipmentSlot.LEGS

            LEATHER_BOOTS, CHAINMAIL_BOOTS, IRON_BOOTS,
            GOLDEN_BOOTS, DIAMOND_BOOTS, NETHERITE_BOOTS -> EquipmentSlot.FEET

            else -> null
        }
    }

    private fun evaluateItem(item: ItemStack, slot: EquipmentSlot): Double {
        var score = 0.0

        score += when (item.type) {
            WOODEN_SWORD, WOODEN_AXE -> 1.0
            STONE_SWORD, STONE_AXE -> 2.0
            IRON_SWORD, IRON_AXE, IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS -> 3.0
            GOLDEN_SWORD, GOLDEN_AXE, GOLDEN_HELMET, GOLDEN_CHESTPLATE, GOLDEN_LEGGINGS, GOLDEN_BOOTS -> 2.5
            DIAMOND_SWORD, DIAMOND_AXE, DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS -> 4.0
            NETHERITE_SWORD, NETHERITE_AXE, NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS -> 5.0
            SHIELD -> 2.0
            TOTEM_OF_UNDYING -> 3.0
            TURTLE_HELMET -> 3.5
            LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS -> 1.5
            CHAINMAIL_HELMET, CHAINMAIL_CHESTPLATE, CHAINMAIL_LEGGINGS, CHAINMAIL_BOOTS -> 2.5
            else -> 0.0
        }

        item.enchantments.forEach { (enchant, level) ->
            score += when (enchant) {
                Enchantment.SHARPNESS, Enchantment.PROTECTION -> level * 1.5
                Enchantment.UNBREAKING -> level * 0.5
                else -> level * 0.3
            }
        }

        (item.itemMeta as? Damageable)?.let { meta ->
            if (item.type.maxDurability > 0) {
                val wearFraction = meta.damage.toDouble() / item.type.maxDurability
                score -= wearFraction * 0.5
            }
        }

        return score
    }

    private fun applyEquipmentChanges(villager: Villager, changes: Map<EquipmentSlot, ItemStack>) {
        val loc = villager.location

        // Метод выполняется на главном потоке, сбор игроков безопасен и быстр
        val nearbyPlayers = plugin.server.onlinePlayers.filter {
            it.world == loc.world && it.location.distanceSquared(loc) <= 256.0
        }

        for ((slot, item) in changes) {
            val equipped = villager.equipment?.getItem(slot)

            if (equipped != null && equipped.isSimilar(item))
                continue

            villager.asHumanoid()?.equip(slot, item)

            val sound = when (item.type) {
                IRON_BOOTS, IRON_LEGGINGS, IRON_CHESTPLATE, IRON_HELMET -> Sound.ITEM_ARMOR_EQUIP_IRON
                CHAINMAIL_BOOTS, CHAINMAIL_LEGGINGS, CHAINMAIL_CHESTPLATE, CHAINMAIL_HELMET -> Sound.ITEM_ARMOR_EQUIP_CHAIN
                GOLDEN_BOOTS, GOLDEN_LEGGINGS, GOLDEN_CHESTPLATE, GOLDEN_HELMET -> Sound.ITEM_ARMOR_EQUIP_GOLD
                DIAMOND_BOOTS, DIAMOND_LEGGINGS, DIAMOND_CHESTPLATE, DIAMOND_HELMET -> Sound.ITEM_ARMOR_EQUIP_DIAMOND
                NETHERITE_BOOTS, NETHERITE_LEGGINGS, NETHERITE_CHESTPLATE, NETHERITE_HELMET -> Sound.ITEM_ARMOR_EQUIP_NETHERITE
                SHIELD -> Sound.ITEM_SHIELD_BLOCK
                IRON_SWORD, GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD -> Sound.ENTITY_PLAYER_ATTACK_SWEEP
                else -> Sound.ENTITY_ITEM_PICKUP
            }

            val pitch = 1F + Random.nextDouble(-0.25, 0.25).toFloat()

            for (player in nearbyPlayers) {
                player.playSound(loc, sound, 0.85F, pitch)
            }
        }
    }

}
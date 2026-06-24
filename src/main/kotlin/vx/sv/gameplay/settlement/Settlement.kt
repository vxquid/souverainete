package vx.sv.gameplay.settlement

import net.minecraft.core.BlockPos
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.plugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class Settlement(val data: SettlementData, val villagers: MutableSet<Villager> = mutableSetOf()) {

    data class SettlementData(
        val id: UUID,
        val worldUUID: UUID,
        var settlementName: String,
        val center: Location,
        val creationTime: Long,
        var dominantRace: String,
        var leaderId: UUID? = null,
        var leaderName: String? = null,
        val reputation: MutableMap<UUID, Int> = mutableMapOf(),
        val relations: MutableMap<UUID, RelationLevel> = mutableMapOf(),

        var diplomaticHistory: MutableMap<UUID, MutableList<String>>? = null,
        var activeRaid: RaidData? = null,

        var activeProjectId: UUID? = null,

        // ПЕРСИСТЕНТНЫЙ СПИСОК СЕРИАЛИЗОВАННЫХ ПРЕДМЕТОВ В BASE64 (БЕЗОПАСНО ДЛЯ GSON)
        val serializedInventory: MutableList<String> = mutableListOf()
    )

    data class RaidData(
        val attackerId: UUID,
        var status: RaidStatus = RaidStatus.ONGOING,
        var currentWave: Int = 0,
        val totalWaves: Int = 3,
        var totalRaidersInWave: Int = 0,
        val aliveRaiders: MutableSet<UUID> = mutableSetOf(),
        var activeTicks: Long = 0
    )

    val world = plugin.server.getWorld(data.worldUUID)!!

    // ИСПРАВЛЕНО: Увеличен радиус территории деревни со 64.0 до 120.0 блоков во все стороны для простора
    var territory = BoundingBox.of(data.center, 126.0, 128.0, 126.0)

    // РАКТИВНЫЙ РАНТАЙМ-ИНВЕНТАРЬ (ХРАНИТ РЕАЛЬНЫЕ ITEMSTACK В ПАМЯТИ)
    val villageInventory: MutableList<ItemStack> = mutableListOf()

    // КЭШ КРОВАТЕЙ ДЛЯ ОПТИМИЗАЦИИ ИИ СНА (BlockPos изголовья кровати)
    val cachedBeds = ConcurrentHashMap.newKeySet<BlockPos>()

    init {
        // Десериализуем предметы из Base64 при инициализации поселения
        if (data.serializedInventory.isNotEmpty()) {
            for (base64 in data.serializedInventory) {
                try {
                    val bytes = Base64.getDecoder().decode(base64)
                    val item = ItemStack.deserializeBytes(bytes)
                    villageInventory.add(item)
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to deserialize item in settlement ${data.settlementName}: ${e.message}")
                }
            }
        }

        // Деревенский инвентарь не должен быть пустым с самого начала
        if (villageInventory.isEmpty()) {
            villageInventory.add(ItemStack(org.bukkit.Material.BREAD, 64))
            villageInventory.add(ItemStack(org.bukkit.Material.BREAD, 64))
            villageInventory.add(ItemStack(org.bukkit.Material.BAKED_POTATO, 64))
            villageInventory.add(ItemStack(org.bukkit.Material.BAKED_POTATO, 64))
            // Сразу синхронизируем дефолтный инвентарь в персистентную структуру
            syncToData()
        }
    }

    // Сканирование и обновление кэша кроватей (выполняется планировщиком)
    fun refreshCachedBeds() {
        cachedBeds.clear()
        val radius = plugin.gameplayManager.config.settlement.detectionDistance.toInt()
        val center = data.center
        val centerCX = center.blockX shr 4
        val centerCZ = center.blockZ shr 4
        val cRadius = radius shr 4

        for (cx in (centerCX - cRadius)..(centerCX + cRadius)) {
            for (cz in (centerCZ - cRadius)..(centerCZ + cRadius)) {
                if (!world.isChunkLoaded(cx, cz)) continue
                try {
                    val chunk = world.getChunkAt(cx, cz)
                    chunk.tileEntities.forEach { tile ->
                        val block = tile.block
                        if (block.type.name.endsWith("_BED")) {
                            val bedData = block.blockData as? org.bukkit.block.data.type.Bed ?: return@forEach
                            if (bedData.part == org.bukkit.block.data.type.Bed.Part.HEAD) {
                                if (block.location.distanceSquared(center) <= radius * radius) {
                                    // Валидация выживания (свет и потолок) прямо на этапе кэширования
                                    if (block.lightLevel < 5) return@forEach
                                    if (world.getHighestBlockYAt(block.x, block.z) < block.y) return@forEach

                                    reservedBedsToBlockPos()
                                    cachedBeds.add(BlockPos(block.x, block.y, block.z))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun reservedBedsToBlockPos() {}

    // Синхронизирует активные предметы в плоский Base64 список перед сохранением мира
    fun syncToData() {
        data.serializedInventory.clear()
        for (item in villageInventory) {
            try {
                // Ограничиваем размер стака до maxStackSize во избежание Mojang-ошибок range [1;99]
                val maxStack = item.type.maxStackSize
                if (item.amount > maxStack) {
                    item.amount = maxStack
                }

                val base64 = Base64.getEncoder().encodeToString(item.serializeAsBytes())
                data.serializedInventory.add(base64)
            } catch (e: Exception) {
                plugin.logger.warning("Failed to serialize item ${item.type} in settlement ${data.settlementName}: ${e.message}")
            }
        }
    }

    fun electLeader() {
        if (villagers.isEmpty()) return

        data.leaderId?.let { oldId ->
            val oldLeader = villagers.find { it.uniqueId == oldId }
            oldLeader?.persistentDataContainer?.remove(LEADER_KEY)
        }

        val newLeader = villagers.random()
        data.leaderId = newLeader.uniqueId
        data.leaderName = newLeader.customName ?: "Leader"

        newLeader.persistentDataContainer.set(LEADER_KEY, PersistentDataType.STRING, data.id.toString())
    }

    fun size(): SettlementSize {
        return when {
            villagers.size in 1..10 -> SettlementSize.UNDERDEVELOPED
            villagers.size in 11..20 -> SettlementSize.EMERGING
            villagers.size in 21..30 -> SettlementSize.ESTABLISHED
            villagers.size in 31..50 -> SettlementSize.ADVANCED
            villagers.size > 50 -> SettlementSize.METROPOLIS
            else -> SettlementSize.UNDERDEVELOPED
        }
    }

    enum class SettlementSize { UNDERDEVELOPED, EMERGING, ESTABLISHED, ADVANCED, METROPOLIS }
    enum class RelationLevel { WAR, TENSE, NEUTRAL, WARM, ALLIANCE }
    enum class RaidStatus { ONGOING, VICTORY, LOSS, STOPPED }

    companion object {
        val LEADER_KEY = NamespacedKey(plugin, "settlement_leader")
    }
}

fun Villager.isSettlementLeader(): Boolean {
    return this.persistentDataContainer.has(Settlement.LEADER_KEY, PersistentDataType.STRING)
}

fun Villager.getLedSettlementId(): UUID? {
    val idString = this.persistentDataContainer.get(Settlement.LEADER_KEY, PersistentDataType.STRING) ?: return null
    return try {
        UUID.fromString(idString)
    } catch (e: IllegalArgumentException) {
        null
    }
}
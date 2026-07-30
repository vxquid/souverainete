package vx.sv.gameplay.settlement.rent

import com.google.gson.reflect.TypeToken
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.nms.entity.ai.construct.BuildingRecord
import vx.sv.nms.entity.ai.construct.SettlementPlanner
import vx.sv.persistent.PlayerPreferencesManager.preferences
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class RentPlotData(
    val plotId: UUID,
    val settlementId: UUID,
    var ownerId: UUID?,
    var rentExpiryGameTime: Long,
    val members: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
)

class RentManager : Listener {

    companion object {
        val plots = ConcurrentHashMap<UUID, RentPlotData>()
        private val rentPdcKey = NamespacedKey(plugin, "rent_plots_data")

        private val playerBossBars = ConcurrentHashMap<Player, BossBar>()

        fun getRentPlotsForSettlement(settlement: Settlement): List<Pair<BuildingRecord, RentPlotData>> {
            val records = SettlementPlanner.buildings[settlement.data.id]?.toList() ?: emptyList()
            val result = mutableListOf<Pair<BuildingRecord, RentPlotData>>()

            for (record in records) {
                if (record.type.startsWith("RENT_FOUNDATION")) {
                    val plotData = plots.computeIfAbsent(record.jobId) { id ->
                        RentPlotData(id, settlement.data.id, null, 0L)
                    }
                    result.add(Pair(record, plotData))
                }
            }
            return result
        }

        fun getRentPlotForLocation(loc: org.bukkit.Location): Pair<BuildingRecord, RentPlotData>? {
            val worldSettlements = SettlementManager.settlements[loc.world] ?: return null
            for (settlement in worldSettlements) {
                if (settlement.territory.contains(loc.toVector())) {
                    val rentPlots = getRentPlotsForSettlement(settlement)
                    for (pair in rentPlots) {
                        val box = pair.first.box
                        val expandedBox = BoundingBox(box.minX - 2.0, box.minY, box.minZ - 2.0, box.maxX + 2.0, box.maxY + 4.0, box.maxZ + 2.0)
                        if (expandedBox.contains(loc.toVector())) {
                            return pair
                        }
                    }
                }
            }
            return null
        }

        fun getRentedPlotsByPlayer(player: Player): List<Pair<BuildingRecord, RentPlotData>> {
            val result = mutableListOf<Pair<BuildingRecord, RentPlotData>>()
            for (worldSettlements in SettlementManager.settlements.values) {
                for (settlement in worldSettlements) {
                    val rentPlots = getRentPlotsForSettlement(settlement)
                    for (pair in rentPlots) {
                        if (pair.second.ownerId == player.uniqueId) {
                            result.add(pair)
                        }
                    }
                }
            }
            return result
        }

        fun canModifyPlot(player: Player, loc: org.bukkit.Location): Boolean {
            if (player.isOp || player.hasPermission("sv.rent.bypass")) return true

            val plotPair = getRentPlotForLocation(loc) ?: return true
            val plot = plotPair.second
            val ownerId = plot.ownerId ?: return false

            return player.uniqueId == ownerId || plot.members.contains(player.uniqueId)
        }

        fun getRaceCurrencyMaterial(settlement: Settlement): Material {
            val leaderId = settlement.data.leaderId ?: return Material.EMERALD
            val leader = settlement.villagers.find { it.uniqueId == leaderId } ?: settlement.villagers.firstOrNull()
            return leader?.race?.normalCurrency?.get() ?: Material.EMERALD
        }

        fun hasRaceCurrency(player: Player, settlement: Settlement, requiredAmount: Int): Boolean {
            val currencyMat = getRaceCurrencyMaterial(settlement)
            val currentAmount = player.inventory.filterNotNull()
                .filter { it.type == currencyMat }
                .sumOf { it.amount }
            return currentAmount >= requiredAmount
        }

        fun deductRaceCurrency(player: Player, settlement: Settlement, amount: Int): Boolean {
            val currencyMat = getRaceCurrencyMaterial(settlement)
            if (!hasRaceCurrency(player, settlement, amount)) return false

            var leftToRemove = amount
            for (item in player.inventory.contents) {
                if (item != null && item.type == currencyMat) {
                    if (item.amount <= leftToRemove) {
                        leftToRemove -= item.amount
                        item.amount = 0
                    } else {
                        item.amount -= leftToRemove
                        leftToRemove = 0
                        break
                    }
                }
            }
            player.updateInventory()
            return true
        }

        fun rentPlot(player: Player, settlement: Settlement, record: BuildingRecord, plotData: RentPlotData): Boolean {
            val cost = plugin.gameplayConfig.settlement.rentCurrencyCost
            if (!deductRaceCurrency(player, settlement, cost)) return false

            val durationTicks = plugin.gameplayConfig.settlement.rentDurationDays * 24000L
            plotData.ownerId = player.uniqueId
            plotData.rentExpiryGameTime = settlement.world.gameTime + durationTicks
            plotData.members.clear()

            saveAll()
            return true
        }

        fun renewPlotRent(player: Player, settlement: Settlement, plotData: RentPlotData): Boolean {
            val cost = plugin.gameplayConfig.settlement.rentCurrencyCost
            if (!deductRaceCurrency(player, settlement, cost)) return false

            val durationTicks = plugin.gameplayConfig.settlement.rentDurationDays * 24000L
            if (plotData.rentExpiryGameTime < settlement.world.gameTime) {
                plotData.rentExpiryGameTime = settlement.world.gameTime + durationTicks
            } else {
                plotData.rentExpiryGameTime += durationTicks
            }

            saveAll()
            return true
        }

        fun evictPlot(settlement: Settlement, record: BuildingRecord, plotData: RentPlotData) {
            val oldOwnerId = plotData.ownerId
            plotData.ownerId = null
            plotData.rentExpiryGameTime = 0L
            plotData.members.clear()

            if (oldOwnerId != null) {
                val player = Bukkit.getPlayer(oldOwnerId)
                if (player != null && player.isOnline) {
                    val msg = plugin.language.getString("rent.evicted-broadcast")
                        ?.replace("{settlement}", settlement.data.settlementName)
                        ?: "§cYour lease on the plot in ${settlement.data.settlementName} has expired!"
                    player.sendMessage(msg)
                }
            }
            saveAll()
        }

        fun updateBossBarForPlayers() {
            for (player in Bukkit.getOnlinePlayers()) {
                if (!player.preferences.showRentBossBar) {
                    removeBossBar(player)
                    continue
                }

                val plotPair = getRentPlotForLocation(player.location)
                if (plotPair != null) {
                    val plotData = plotPair.second
                    val settlement = SettlementManager.getById(plotData.settlementId)
                    val settlementName = settlement?.data?.settlementName ?: "Settlement"

                    val title: String
                    val progress: Float
                    val color: BarColor

                    if (plotData.ownerId == null) {
                        title = plugin.language.getString("rent.bossbar.free")
                            ?.replace("{settlement}", settlementName)
                            ?: "§e[Free Plot] §fAvailable for rent ({settlement})"
                        progress = 1.0f
                        color = BarColor.GREEN
                    } else {
                        val ownerName = Bukkit.getOfflinePlayer(plotData.ownerId!!).name ?: "Unknown"
                        val remainingTicks = plotData.rentExpiryGameTime - player.world.gameTime
                        val remainingDays = (remainingTicks / 24000L).coerceAtLeast(0L)
                        val maxDays = plugin.gameplayConfig.settlement.rentDurationDays.toFloat()

                        title = plugin.language.getString("rent.bossbar.rented")
                            ?.replace("{owner}", ownerName)
                            ?.replace("{days}", remainingDays.toString())
                            ?: "§a[Rented Plot] §7Owner: §f{owner} §7(Expires in: §e{days} days§7)"

                        progress = (remainingDays.toFloat() / maxDays).coerceIn(0.0f, 1.0f)
                        color = if (remainingDays <= 3) BarColor.RED else BarColor.YELLOW
                    }

                    var bar = playerBossBars[player]
                    if (bar == null) {
                        bar = Bukkit.createBossBar(title, color, BarStyle.SOLID)
                        bar.addPlayer(player)
                        playerBossBars[player] = bar
                    } else {
                        bar.setTitle(title)
                        bar.color = color
                        bar.setProgress(progress.toDouble())
                        if (!bar.players.contains(player)) bar.addPlayer(player)
                    }
                } else {
                    removeBossBar(player)
                }
            }
        }

        fun removeBossBar(player: Player) {
            playerBossBars.remove(player)?.let { bar ->
                bar.removePlayer(player)
            }
        }

        fun saveAll() {
            for (world in Bukkit.getWorlds()) {
                val pdc = world.persistentDataContainer
                val worldSettlements = SettlementManager.settlements[world] ?: continue
                val worldPlotSaves = mutableMapOf<String, RentPlotData>()

                for (s in worldSettlements) {
                    val plots = getRentPlotsForSettlement(s)
                    for (p in plots) {
                        worldPlotSaves[p.second.plotId.toString()] = p.second
                    }
                }

                if (worldPlotSaves.isNotEmpty()) {
                    val json = gson.toJson(worldPlotSaves)
                    val bytes = SettlementPlanner.compress(json)
                    pdc.set(rentPdcKey, PersistentDataType.BYTE_ARRAY, bytes)
                }
            }
        }

        fun loadWorldRentData(world: org.bukkit.World) {
            val pdc = world.persistentDataContainer
            if (!pdc.has(rentPdcKey, PersistentDataType.BYTE_ARRAY)) return

            try {
                val bytes = pdc.get(rentPdcKey, PersistentDataType.BYTE_ARRAY) ?: return
                val json = SettlementPlanner.decompress(bytes)
                val type = object : TypeToken<Map<String, RentPlotData>>() {}.type
                val loaded: Map<String, RentPlotData>? = gson.fromJson(json, type)

                loaded?.forEach { (idStr, data) ->
                    plots[UUID.fromString(idStr)] = data
                }
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load rent plots for world ${world.name}: ${e.message}")
            }
        }
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (world in Bukkit.getWorlds()) {
                val worldSettlements = SettlementManager.settlements[world] ?: continue
                val gameTime = world.gameTime

                for (settlement in worldSettlements) {
                    val rentPlots = getRentPlotsForSettlement(settlement)
                    for (pair in rentPlots) {
                        val record = pair.first
                        val plotData = pair.second

                        if (plotData.ownerId != null && gameTime >= plotData.rentExpiryGameTime) {
                            evictPlot(settlement, record, plotData)
                        }
                    }
                }
            }
            updateBossBarForPlayers()
        }, 20L, 20L)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        removeBossBar(event.player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block
        if (!canModifyPlot(player, block.location)) {
            event.isCancelled = true
            val msg = plugin.language.getString("rent.protection-deny")
                ?: "§cYou do not have permission to build or break blocks on this rented plot!"
            player.sendMessage(msg)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val block = event.block
        if (!canModifyPlot(player, block.location)) {
            event.isCancelled = true
            val msg = plugin.language.getString("rent.protection-deny")
                ?: "§cYou do not have permission to build or break blocks on this rented plot!"
            player.sendMessage(msg)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val player = event.player
        if (!canModifyPlot(player, block.location)) {
            event.isCancelled = true
            val msg = plugin.language.getString("rent.protection-deny")
                ?: "§cYou do not have permission to build or break blocks on this rented plot!"
            player.sendMessage(msg)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { block ->
            getRentPlotForLocation(block.location) != null
        }
    }
}
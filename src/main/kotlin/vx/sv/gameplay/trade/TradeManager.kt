package vx.sv.gameplay.trade

import com.google.gson.reflect.TypeToken
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.server.MapInitializeEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapCursor
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.event.MerchantTradeEvent
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.reputation.ReputationManager.Companion.opinionOn
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.gameplay.trade.ScoreCalculator.calculateScore
import vx.sv.gameplay.trade.ScoreCalculator.getBasicScore
import vx.sv.persistent.LivingEntityExtend.addItemToQuillInventory
import vx.sv.persistent.LivingEntityExtend.quests
import vx.sv.persistent.LivingEntityExtend.settlement
import vx.sv.persistent.LivingEntityExtend.subInventory
import vx.sv.persistent.LivingEntityExtend.takeItemFromQuillInventory
import java.util.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Handles custom trading mechanics, including quest-related trades, custom currency calculations,
 * and procedural map generation for cartographers.
 */
class TradeManager : Listener {

    @EventHandler
    private fun handleMerchantInventoryClick(event: InventoryClickEvent) {
        if (event.inventory.type == InventoryType.MERCHANT) {
            val player   = (event.whoClicked as Player)
            val villager = playerTradingInventories[player] ?: return
            val slot     = if (event.rawSlot != 2) return else 2

            val rewardItem  = event.inventory.getItem(slot) ?: return
            val firstTrade  = event.inventory.getItem(0) ?: return

            if (rewardItem.type != Material.AIR) {
                if (plugin.gameplayManager.config.general.vanillaTrading) {
                    val isQuest = villager.quests().any {
                        it.questItem.getItemStack().isSimilar(firstTrade)
                    }
                    if (!isQuest) return
                }

                val recipe = villager.recipes.find { it.result.isSimilar(rewardItem) && it.ingredients[0].isSimilar(firstTrade) } ?: throw NullPointerException("Null recipe on successful trade.")

                Bukkit.getServer().scheduler.runTask(plugin) { _ ->
                    Bukkit.getServer().pluginManager.callEvent(MerchantTradeEvent(villager, player, recipe))
                }
            }
        }
    }

    @EventHandler
    private fun handleTrade(event: MerchantTradeEvent) {
        (event.merchant as? Villager)?.let { villager: Villager ->
            event.recipe.let { recipe ->
                villager.quests().find {it.questItem.getItemStack().isSimilar(recipe.ingredients[0])}
                villager.addItemToQuillInventory(recipe.ingredients[0])
                if (recipe.ingredients.size > 1) villager.addItemToQuillInventory(recipe.ingredients[1])
                villager.takeItemFromQuillInventory(recipe.result, recipe.result.amount)
            }
        }
    }

    @EventHandler
    @Suppress("DEPRECATION")
    fun onMapInitialize(event: MapInitializeEvent) {
        val view = event.map
        val mapping = tradeMaps[view.id.toString()] ?: return

        val parts = mapping.split(";")
        if (parts.size != 2) return

        val originId = UUID.fromString(parts[0])
        val targetId = UUID.fromString(parts[1])

        val origin = SettlementManager.getById(originId) ?: return
        val target = SettlementManager.getById(targetId) ?: return

        applyTradeMapRenderer(view, origin, target)
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        val savedMapsJson = Bukkit.getWorlds()[0].persistentDataContainer.get(tradeMapsKey, PersistentDataType.STRING)
        if (savedMapsJson != null) {
            val typeToken = object : TypeToken<MutableMap<String, String>>() {}.type
            tradeMaps = gson.fromJson(savedMapsJson, typeToken) ?: mutableMapOf()
        }
    }

    companion object {

        private val tradeMapsKey = NamespacedKey(plugin, "trade_maps")
        private var tradeMaps = mutableMapOf<String, String>()

        private fun saveTradeMaps() {
            Bukkit.getWorlds()[0].persistentDataContainer.set(tradeMapsKey, PersistentDataType.STRING, gson.toJson(tradeMaps))
        }

        val parchmentPalette: ByteArray by lazy {
            fun lerp(start: Int, end: Int, t: Double): Int = (start + (end - start) * t).toInt()

            ByteArray(30) { i ->
                val t = i / 29.0
                val r: Int; val g: Int; val b: Int

                if (t < 0.3) {
                    val lt = t / 0.3
                    r = lerp(235, 215, lt); g = lerp(220, 195, lt); b = lerp(185, 150, lt)
                } else if (t < 0.6) {
                    val lt = (t - 0.3) / 0.3
                    r = lerp(215, 180, lt); g = lerp(195, 150, lt); b = lerp(150, 100, lt)
                } else if (t < 0.85) {
                    val lt = (t - 0.6) / 0.25
                    r = lerp(180, 110, lt); g = lerp(150, 75, lt); b = lerp(100, 45, lt)
                } else {
                    val lt = (t - 0.85) / 0.15
                    r = lerp(110, 45, lt); g = lerp(75, 30, lt); b = lerp(45, 15, lt)
                }

                @Suppress("DEPRECATION")
                org.bukkit.map.MapPalette.matchColor(r, g, b)
            }
        }

        val weavePalette: ByteArray by lazy {
            @Suppress("DEPRECATION")
            byteArrayOf(
                org.bukkit.map.MapPalette.matchColor(176, 102, 45),
                org.bukkit.map.MapPalette.matchColor(153, 84, 33),
                org.bukkit.map.MapPalette.matchColor(125, 63, 20),
                org.bukkit.map.MapPalette.matchColor(204, 131, 71)
            )
        }

        private fun applyTradeMapRenderer(view: MapView, origin: Settlement, target: Settlement) {
            val midX = (origin.data.center.blockX + target.data.center.blockX) / 2
            val midZ = (origin.data.center.blockZ + target.data.center.blockZ) / 2

            view.centerX = midX
            view.centerZ = midZ
            view.scale = MapView.Scale.FARTHEST

            view.isTrackingPosition = true
            view.isUnlimitedTracking = true

            view.renderers.toList().forEach { view.removeRenderer(it) }

            view.addRenderer(object : MapRenderer(true) {
                // To prevent infinite repainting per player, we save unique UUIDs of those who already painted the pixels
                val renderedPlayers = mutableSetOf<UUID>()

                fun getSmoothNoise(x: Double, y: Double): Double {
                    return (sin(x * 0.1) + cos(y * 0.1) + sin((x + y) * 0.05)) / 3.0
                }

                fun getWhiteNoise(x: Double, y: Double): Double {
                    val dot = x * 12.9898 + y * 78.233
                    return abs(sin(dot) * 43758.5453) % 1.0
                }

                @Suppress("DEPRECATION")
                override fun render(map: MapView, canvas: MapCanvas, player: Player) {
                    val playerId = player.uniqueId

                    fun getCursorCoord(block: Int, mid: Int): Byte {
                        val diff = block - mid
                        return (diff / 8).coerceIn(-128, 127).toByte()
                    }

                    // --- 1. RENDER PROCEDURAL PIXELS ONLY ONCE PER PLAYER ---
                    if (!renderedPlayers.contains(playerId)) {
                        renderedPlayers.add(playerId)

                        val rand = java.util.Random(map.id.toLong())

                        val numStains = rand.nextInt(5) + 3
                        val stains = Array(numStains) {
                            Triple(rand.nextInt(128), rand.nextInt(128), rand.nextDouble() * 20 + 10)
                        }

                        val numSplatters = rand.nextInt(7) + 4
                        val splatters = Array(numSplatters) {
                            Triple(rand.nextInt(128), rand.nextInt(128), rand.nextDouble() * 3.0 + 0.5)
                        }
                        val inkColor = org.bukkit.map.MapPalette.matchColor(25, 25, 35)

                        val isParchment = Array(128) { BooleanArray(128) }

                        // Draw Map Textures
                        for (x in 0..127) {
                            for (y in 0..127) {
                                val nx = x.toDouble()
                                val ny = y.toDouble()

                                val distEdgeX = minOf(x, 127 - x)
                                val distEdgeY = minOf(y, 127 - y)
                                val baseDistEdge = minOf(distEdgeX, distEdgeY).toDouble()

                                val dx = x - 64.0
                                val dy = y - 64.0
                                val distCenter = Math.sqrt(dx * dx + dy * dy)

                                val whiteNoise = getWhiteNoise(nx, ny)
                                val smoothNoise = getSmoothNoise(nx, ny)

                                val tearNoise = sin(nx * 0.15) * cos(ny * 0.15) * 4.0 + sin((nx - ny) * 0.7) * 2.0 + whiteNoise * 2.5
                                val tearThreshold = 3.0 + tearNoise

                                if (baseDistEdge < tearThreshold) {
                                    isParchment[x][y] = false
                                    val u = x + y
                                    val v = x - y + 128
                                    val band = v / 3
                                    val shift = if (band % 2 == 0) 0 else 8
                                    val segment = (u + shift) / 16
                                    val colorIndex = ((band % 4) + (segment % 3)) % weavePalette.size
                                    canvas.setPixel(x, y, weavePalette[colorIndex])
                                    continue
                                }

                                isParchment[x][y] = true
                                var age = 0.0

                                val isGridLine = (x % 24 == 0 || y % 24 == 0)
                                if (isGridLine && (x + y) % 4 != 0) age += 0.05
                                age += (distCenter / 110.0) * 0.25
                                age += (smoothNoise + 1.0) * 0.15
                                age += whiteNoise * 0.08
                                if (x in 63..64 || y in 63..64) age += 0.05
                                if (x in 31..32 || x in 95..96 || y in 31..32 || y in 95..96) age += 0.02
                                if ((y / 2) % 2 == 0) age += 0.03

                                for (stain in stains) {
                                    val sdx = x - stain.first
                                    val sdy = y - stain.second
                                    val sDist = Math.sqrt((sdx * sdx + sdy * sdy).toDouble())
                                    if (sDist < stain.third) {
                                        age += 0.2 * (1.0 - sDist / stain.third) * (whiteNoise * 0.5 + 0.5)
                                    }
                                }

                                val burnDist = baseDistEdge - tearThreshold
                                if (burnDist < 4.0) {
                                    age += (4.0 - burnDist) / 4.0 * 0.8 + whiteNoise * 0.2
                                } else if (burnDist < 10.0) {
                                    age += (10.0 - burnDist) / 10.0 * 0.3
                                }

                                var isInk = false
                                for (splat in splatters) {
                                    val sdx = x - splat.first
                                    val sdy = y - splat.second
                                    val sDist = Math.sqrt((sdx * sdx + sdy * sdy).toDouble())
                                    if (sDist < splat.third + whiteNoise * 1.5) {
                                        isInk = true
                                        break
                                    }
                                }

                                if (isInk) {
                                    canvas.setPixel(x, y, inkColor)
                                } else {
                                    val colorIndex = (age * 29).toInt().coerceIn(0, 29)
                                    canvas.setPixel(x, y, parchmentPalette[colorIndex])
                                }
                            }
                        }

                        // Draw Map Decorations
                        val decorDark = parchmentPalette[26]
                        val decorLight = parchmentPalette[14]

                        fun drawSafe(px: Int, py: Int, color: Byte) {
                            if (px in 0..127 && py in 0..127 && isParchment[px][py]) {
                                canvas.setPixel(px, py, color)
                            }
                        }

                        val cx = 22; val cy = 106
                        for (r in 0..359 step 5) {
                            val rad = Math.toRadians(r.toDouble())
                            drawSafe((cx + cos(rad) * 11).toInt(), (cy + sin(rad) * 11).toInt(), decorLight)
                            drawSafe((cx + cos(rad) * 7).toInt(), (cy + sin(rad) * 7).toInt(), decorLight)
                        }
                        for (i in 1..10) drawSafe(cx, cy - i, decorDark)
                        for (i in 1..7) drawSafe(cx, cy + i, decorDark)
                        for (i in 1..7) drawSafe(cx + i, cy, decorDark)
                        for (i in 1..7) drawSafe(cx - i, cy, decorDark)
                        drawSafe(cx - 1, cy - 2, decorDark); drawSafe(cx + 1, cy - 2, decorDark)
                        drawSafe(cx - 2, cy - 1, decorDark); drawSafe(cx + 2, cy - 1, decorDark)
                        drawSafe(cx - 1, cy + 2, decorDark); drawSafe(cx + 1, cy + 2, decorDark)
                        drawSafe(cx + 2, cy + 1, decorDark); drawSafe(cx - 2, cy + 1, decorDark)
                        drawSafe(cx, cy, decorLight)

                        for (i in 0..2) {
                            var mx = rand.nextInt(70) + 28
                            var my = rand.nextInt(70) + 28
                            val length = rand.nextInt(5) + 3
                            for (j in 0..length) {
                                drawSafe(mx, my - 2, decorDark)
                                drawSafe(mx - 1, my - 1, decorDark); drawSafe(mx + 1, my - 1, decorDark)
                                drawSafe(mx - 2, my, decorDark); drawSafe(mx + 2, my, decorDark)
                                drawSafe(mx, my - 1, decorLight); drawSafe(mx + 1, my, decorLight)
                                mx += rand.nextInt(7) - 3; my += rand.nextInt(5) - 2
                            }
                        }

                        for (i in 0..3) {
                            val fx = rand.nextInt(80) + 24
                            val fy = rand.nextInt(80) + 24
                            val size = rand.nextInt(12) + 8
                            for (j in 0..size) {
                                val tx = fx + rand.nextInt(16) - 8
                                val ty = fy + rand.nextInt(16) - 8
                                drawSafe(tx, ty - 2, decorDark)
                                drawSafe(tx - 1, ty - 1, decorDark); drawSafe(tx + 1, ty - 1, decorDark)
                                drawSafe(tx, ty - 1, decorLight)
                                drawSafe(tx, ty, decorDark)
                            }
                        }

                        for (i in 0..4) {
                            val hx = rand.nextInt(90) + 19
                            val hy = rand.nextInt(90) + 19
                            drawSafe(hx, hy - 2, decorDark)
                            drawSafe(hx - 1, hy - 1, decorDark); drawSafe(hx + 1, hy - 1, decorDark)
                            drawSafe(hx - 1, hy, decorDark); drawSafe(hx + 1, hy, decorDark)
                        }

                        // Draw Route Path
                        fun getPixelCoord(block: Int, mid: Int): Int {
                            val diff = block - mid
                            return (64 + diff / 16).coerceIn(0, 127)
                        }

                        val px1 = getPixelCoord(origin.data.center.blockX, midX)
                        val pz1 = getPixelCoord(origin.data.center.blockZ, midZ)
                        val px2 = getPixelCoord(target.data.center.blockX, midX)
                        val pz2 = getPixelCoord(target.data.center.blockZ, midZ)

                        val lineColor = org.bukkit.map.MapPalette.matchColor(180, 20, 20)

                        var px = px1
                        var py = pz1
                        val dx = abs(px2 - px1)
                        val dy = abs(pz2 - pz1)
                        val sx = if (px1 < px2) 1 else -1
                        val sy = if (pz1 < pz2) 1 else -1
                        var err = dx - dy
                        var step = 0

                        while (true) {
                            if (step % 8 < 4) {
                                if (isParchment[px][py]) canvas.setPixel(px, py, lineColor)
                                if (px < 127 && isParchment[px + 1][py]) canvas.setPixel(px + 1, py, lineColor)
                                if (py < 127 && isParchment[px][py + 1]) canvas.setPixel(px, py + 1, lineColor)
                                if (px < 127 && py < 127 && isParchment[px + 1][py + 1]) canvas.setPixel(px + 1, py + 1, lineColor)
                            }
                            step++

                            if (px == px2 && py == pz2) break
                            val e2 = 2 * err
                            if (e2 > -dy) {
                                err -= dy
                                px += sx
                            }
                            if (e2 < dx) {
                                err += dx
                                py += sy
                            }
                        }
                    }

                    // --- 2. UPDATE CURSORS MANUALLY EVERY TICK ---
                    val cursors = canvas.cursors
                    while (cursors.size() > 0) {
                        cursors.removeCursor(cursors.getCursor(0))
                    }

                    // Add static markers back
                    cursors.addCursor(MapCursor(
                        getCursorCoord(origin.data.center.blockX, midX),
                        getCursorCoord(origin.data.center.blockZ, midZ),
                        8.toByte(), MapCursor.Type.MANSION, true
                    ))

                    cursors.addCursor(MapCursor(
                        getCursorCoord(target.data.center.blockX, midX),
                        getCursorCoord(target.data.center.blockZ, midZ),
                        0.toByte(), MapCursor.Type.RED_X, true
                    ))

                    // Intercept tracking logic and manually draw Player cursor
                    val pLoc = player.location
                    if (pLoc.world == map.world) {
                        val px = getCursorCoord(pLoc.blockX, midX)
                        val pz = getCursorCoord(pLoc.blockZ, midZ)

                        // Converting Minecraft Yaw to MapCursor direction (0..15) using vanilla algorithm
                        // Bitwise AND 15 gracefully wraps negative or massive angles!
                        val direction = ((pLoc.yaw / 22.5f).roundToInt() and 15).toByte()

                        cursors.addCursor(MapCursor(px, pz, direction, MapCursor.Type.PLAYER, true))
                    }
                }
            })
        }

        @Suppress("DEPRECATION")
        private fun getSettlementMap(origin: Settlement, target: Settlement): ItemStack {
            val view = Bukkit.createMap(target.world)

            tradeMaps[view.id.toString()] = "${origin.data.id};${target.data.id}"
            saveTradeMaps()

            applyTradeMapRenderer(view, origin, target)

            val mapItem = ItemStack(Material.FILLED_MAP)
            val meta = mapItem.itemMeta as MapMeta
            meta.mapView = view
            meta.setDisplayName("§6Map: ${origin.data.settlementName} ➔ ${target.data.settlementName}")
            meta.lore = listOf(
                "§7Shows the trading route between",
                "§7${origin.data.settlementName} and ${target.data.settlementName}.",
                "§8Cartographer's exact copy."
            )
            mapItem.itemMeta = meta
            return mapItem
        }

        private data class TradingSlot(var currency: Material, var amount: Int) {
            fun calculateAmount(price: Int) : Int = price / currency.getBasicScore()
            fun totalPrice() : Int = currency.getBasicScore() * amount
            fun toItemStack() : ItemStack = ItemStack(currency, amount)
        }

        private val Villager.producedItems: List<ItemStack>
            get() {
                val itemsToProduce = plugin.professions.getStringList("villager-item-producing.profession.${this.profession.key.key.uppercase()}.item-produce")
                return subInventory.filterNotNull().filter { itemStack -> itemsToProduce.contains(itemStack.type.toString()) }.toList()
            }

        val playerTradingInventories = mutableMapOf<Player, Villager>()

        fun Villager.openTradeMenu(player: Player, open: Boolean = true) : Boolean {

            val race = race
            val currency = race.normalCurrency
            val specialCurrency = race.specialCurrency

            val vanillaTrading = plugin.gameplayManager.config.general.vanillaTrading

            if (!vanillaTrading) {
                recipes = mutableListOf<MerchantRecipe>()
            }

            val playerQuests = player.quests()
            val questTrades = mutableListOf<MerchantRecipe>()
            val activeQuests = quests()

            if (activeQuests.isNotEmpty()) {
                activeQuests.forEach { quest ->
                    if (playerQuests.find { it.id == quest.id } != null) {
                        val recipe = MerchantRecipe(quest.calculateReward(currency.get().toString()), 1)
                        recipe.addIngredient(quest.questItem.getItemStack())
                        questTrades += recipe
                    }
                }
            }

            if (vanillaTrading) {
                val currentVanillaRecipes = this.recipes.filter { recipe ->
                    activeQuests.none { q -> q.questItem.getItemStack().isSimilar(recipe.ingredients.firstOrNull()) }
                }

                this.recipes = (questTrades + currentVanillaRecipes).toMutableList()

                if (open) {
                    player.openMerchant(this, true)
                    playerTradingInventories[player] = this
                }
                return true
            }

            val tradeProfessionItemsOnly = plugin.professions.getBoolean("villager-item-producing.trade-profession-items-only")
            val itemsToTrade = if (tradeProfessionItemsOnly) producedItems else subInventory.filterNotNull()

            val settlement = settlement
            val multiplier = if (settlement != null) this.opinionOn(player).priceMultiplier.toFloat() else 1F

            itemsToTrade.forEach { item ->
                if (item.type == race.normalCurrency.get()) return@forEach
                if (recipes.find { recipe -> recipe.result.isSimilar(item) } != null) return@forEach

                val total = item.amount
                item.amount = 1

                val trade = TradingSlot(Material.AIR, 0) to TradingSlot(Material.AIR, 0)
                val price = (item.calculateScore() * multiplier).toInt()

                val useSpecialCurrency = price / (currency.get() ?: throw NullPointerException("Can't init currency!")).getBasicScore() > (currency.get() ?: throw NullPointerException("Can't init currency!")).maxStackSize * 2

                trade.first.let { firstSlot ->
                    firstSlot.currency = if (useSpecialCurrency) specialCurrency.get() ?: throw NullPointerException("Can't init currency!") else currency.get() ?: throw NullPointerException("Can't init currency!")
                    val useSecondSlot = firstSlot.calculateAmount(price) > firstSlot.currency.maxStackSize

                    if (!useSecondSlot) {
                        firstSlot.amount = firstSlot.calculateAmount(price)
                    } else {
                        firstSlot.amount = firstSlot.currency.maxStackSize
                        trade.second.let { secondSlot ->
                            secondSlot.currency = if (useSpecialCurrency) specialCurrency.get() ?: throw NullPointerException("Can't init currency!") else currency.get() ?: throw NullPointerException("Can't init currency!")
                            secondSlot.amount   = secondSlot.calculateAmount(price - firstSlot.totalPrice())
                        }
                    }

                    if (price - firstSlot.totalPrice() > 0 && !useSecondSlot && useSpecialCurrency) {
                        trade.second.currency = currency.get() ?: throw NullPointerException("Can't init currency!")
                        trade.second.amount   = trade.second.calculateAmount(price - firstSlot.totalPrice())
                    }
                }

                if (trade.first.amount == 0) return@forEach

                val recipe = MerchantRecipe(item, total)
                recipe.addIngredient(trade.first.toItemStack())

                if (trade.second.amount > 0)
                    recipe.addIngredient(trade.second.toItemStack())

                this.recipes = recipes + recipe
            }

            if (this.profession == Villager.Profession.CARTOGRAPHER) {
                if (settlement != null) {
                    val knownSettlements = settlement.data.relations.keys.mapNotNull { SettlementManager.getById(it) }
                    val mapPrice = (15 * multiplier).toInt().coerceIn(1, 64)

                    knownSettlements.forEach { targetSettlement ->
                        val mapItem = getSettlementMap(settlement, targetSettlement)

                        if (recipes.none { it.result.itemMeta?.displayName == mapItem.itemMeta?.displayName }) {
                            val recipe = MerchantRecipe(mapItem, 12)
                            recipe.addIngredient(ItemStack(currency.get() ?: Material.EMERALD, mapPrice))
                            recipe.addIngredient(ItemStack(Material.COMPASS, 1))

                            this.recipes = recipes + recipe
                        }
                    }
                }
            }

            val sorted = recipes.toMutableList().sortedWith(compareBy({ it.result.type }, { it.result.calculateScore() }))

            if (recipes.isEmpty() && questTrades.isEmpty()) {
                this.shakeHead()
                return false
            }

            if (open) {
                recipes = questTrades + sorted
                player.openMerchant(this, true)
                playerTradingInventories[player] = this
            }

            return true
        }

    }

}
package vx.sv.gameplay.settlement.gui

import com.destroystokyo.paper.profile.ProfileProperty
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.gameplay.humanoid.protocol.ProtocolListener.Companion.skin
import vx.sv.gameplay.settlement.Settlement
import java.text.SimpleDateFormat
import java.util.*

object SettlementMenus : Listener {

    private val renamingSessions = mutableMapOf<UUID, Settlement>()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy")

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    class SettlementHolder(val settlement: Settlement, val type: MenuType) : InventoryHolder {
        override fun getInventory(): Inventory = throw UnsupportedOperationException()
    }

    enum class MenuType { MAIN, POPULATION, SETTINGS }

    // --- MAIN MENU ---
    fun openMainMenu(player: Player, settlement: Settlement) {
        val titleRaw = settlement.data.settlementName
        val title = LegacyComponentSerializer.legacySection().deserialize("§8$titleRaw")

        val inv = Bukkit.createInventory(SettlementHolder(settlement, MenuType.MAIN), 27, title)

        fillBorders(inv)

        // 1. Кнопка населения
        inv.setItem(11, createItem(
            Material.PLAYER_HEAD,
            lang("settlement.menu.main.population.name", "§a👥 Citizens"),
            langList("settlement.menu.main.population.lore", listOf("§7Click to view", "§7resident list."))
        ))

        // 2. Кнопка настроек
        inv.setItem(15, createItem(
            Material.COMPARATOR,
            lang("settlement.menu.main.settings.name", "§6⚙ Management"),
            langList("settlement.menu.main.settings.lore", listOf("§7Rename and settings."))
        ))

        // 3. Книга статистики (Считаем кровати в основном потоке, т.к. это быстро через TileEntities)
        val bedCount = countValidBeds(settlement)
        val statsLore = langList("settlement.menu.main.stats.lore", listOf(
            "§7Population: §b{pop}",
            "§7Size: §a{size}",
            "§7Beds: §e{beds}",
            "§7Established: §f{date}",
            "",
            "§7Reputation: {rep}"
        )).map { line ->
            line.replace("{pop}", settlement.villagers.size.toString())
                .replace("{size}", settlement.size().name)
                .replace("{beds}", bedCount.toString())
                .replace("{date}", dateFormat.format(Date(settlement.data.creationTime)))
                .replace("{rep}", getReputationString(player, settlement))
        }

        inv.setItem(13, createItem(
            Material.BOOK,
            lang("settlement.menu.main.stats.name", "§e📜 Information"),
            statsLore
        ))

        player.openInventory(inv)
        playClickSound(player)
    }

    // --- POPULATION MENU ---
    fun openPopulationMenu(player: Player, settlement: Settlement) {
        val citizens = settlement.villagers.filter { it.isValid }.toList()
        val size = minOf(54, ((citizens.size / 9) + 1) * 9).coerceAtLeast(27)

        val titleRaw = lang("settlement.menu.population.title", "§8Citizens ({count})")
            .replace("{count}", citizens.size.toString())
        val title = LegacyComponentSerializer.legacySection().deserialize(titleRaw)

        val inv = Bukkit.createInventory(SettlementHolder(settlement, MenuType.POPULATION), size, title)

        for (villager in citizens) {
            val texture = try { villager.skin() } catch (e: Exception) { null }

            // Получаем голову (используем твой метод)
            val head = if (texture != null) {
                getSkull(texture.value)
            } else {
                ItemStack(Material.PLAYER_HEAD)
            }

            val meta = head.itemMeta as SkullMeta
            val name = villager.customName ?: lang("villager-professions.${villager.profession.key.key}", villager.profession.key.key)
            meta.setDisplayName("§e$name")

            val health = villager.health.toInt()
            val maxHealth = villager.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value?.toInt() ?: 20

            meta.lore = langList("settlement.menu.population.head-lore", listOf(
                "§7Job: §f{job}",
                "§7Health: §c{hp}§7/§c{max_hp}"
            )).map { line ->
                line.replace("{job}", lang("villager-professions.${villager.profession.key.key}", villager.profession.key.key))
                    .replace("{hp}", health.toString())
                    .replace("{max_hp}", maxHealth.toString())
            }

            head.itemMeta = meta
            inv.addItem(head)
        }

        inv.setItem(size - 9, createItem(Material.ARROW, lang("settlement.menu.common.back", "§cBack"), emptyList()))
        player.openInventory(inv)
        playClickSound(player)
    }

    // --- SETTINGS MENU ---
    fun openSettingsMenu(player: Player, settlement: Settlement) {
        val titleRaw = lang("settlement.menu.settings.title", "§8Management")
        val title = LegacyComponentSerializer.legacySection().deserialize(titleRaw)
        val inv = Bukkit.createInventory(SettlementHolder(settlement, MenuType.SETTINGS), 27, title)

        inv.setItem(13, createItem(
            Material.NAME_TAG,
            lang("settlement.menu.settings.rename.name", "§e✎ Rename Settlement"),
            langList("settlement.menu.settings.rename.lore", listOf(
                "§7Current: §f{name}",
                "",
                "§eClick to type new name."
            )).map { it.replace("{name}", settlement.data.settlementName) }
        ))

        inv.setItem(26, createItem(Material.ARROW, lang("settlement.menu.common.back", "§cBack"), emptyList()))

        fillBorders(inv)
        player.openInventory(inv)
        playClickSound(player)
    }

    // --- МЕТОД ПОЛУЧЕНИЯ ГОЛОВЫ (Твой фикс) ---
    private fun getSkull(textures: String): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { skullMeta ->
            val uuid = UUID.randomUUID()
            val playerProfile = Bukkit.createProfile(uuid, uuid.toString().substring(0, 16))
            playerProfile.setProperty(ProfileProperty("textures", textures))
            skullMeta.playerProfile = playerProfile
        }
        return head
    }

    // --- ПОДСЧЕТ КРОВАТЕЙ (В основном потоке) ---
    private fun countValidBeds(settlement: Settlement): Int {
        var count = 0
        val world = settlement.world
        val center = settlement.data.center
        val radius = plugin.gameplayManager.config.settlement.detectionDistance.toInt()

        // Считаем координаты чанков для поиска
        val centerCX = center.blockX shr 4
        val centerCZ = center.blockZ shr 4
        val cRadius = radius shr 4

        for (cx in (centerCX - cRadius)..(centerCX + cRadius)) {
            for (cz in (centerCZ - cRadius)..(centerCZ + cRadius)) {
                if (!world.isChunkLoaded(cx, cz)) continue

                // Проход по TileEntities чанка — это быстро
                world.getChunkAt(cx, cz).tileEntities.forEach { tile ->
                    val block = tile.block
                    if (!block.type.name.endsWith("_BED")) return@forEach

                    val bedData = block.blockData as? Bed ?: return@forEach
                    if (bedData.part != Bed.Part.HEAD) return@forEach

                    if (block.location.distance(center) > radius) return@forEach

                    // Условия комфорта
                    if (block.lightLevel < 5) return@forEach
                    if (world.getHighestBlockYAt(block.x, block.z) <= block.y) return@forEach

                    count++
                }
            }
        }
        return count
    }

    // --- EVENTS ---
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? SettlementHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as Player
        val item = event.currentItem ?: return
        if (item.type == Material.AIR || item.type == Material.GRAY_STAINED_GLASS_PANE) return

        playClickSound(player)

        when (holder.type) {
            MenuType.MAIN -> {
                when (event.slot) {
                    11 -> openPopulationMenu(player, holder.settlement)
                    15 -> openSettingsMenu(player, holder.settlement)
                }
            }
            MenuType.POPULATION -> if (item.type == Material.ARROW) openMainMenu(player, holder.settlement)
            MenuType.SETTINGS -> {
                when (event.slot) {
                    13 -> {
                        player.closeInventory()
                        renamingSessions[player.uniqueId] = holder.settlement
                        player.sendFormattedMessage(lang("settlement.menu.rename.prompt", "§eWrite new name in chat (or 'cancel'):"))
                    }
                    26 -> openMainMenu(player, holder.settlement)
                }
            }
        }
    }

    @EventHandler
    fun onChat(event: AsyncPlayerChatEvent) {
        val settlement = renamingSessions.remove(event.player.uniqueId) ?: return
        event.isCancelled = true
        val message = event.message

        plugin.server.scheduler.runTask(plugin, Runnable {
            if (message.equals("cancel", true)) {
                event.player.sendFormattedMessage(lang("settlement.menu.rename.cancelled", "§cCancelled."))
                openSettingsMenu(event.player, settlement)
            } else {
                settlement.data.settlementName = message
                event.player.sendFormattedMessage(lang("settlement.menu.rename.success", "§aSettlement renamed to: §6{name}").replace("{name}", message))
                openSettingsMenu(event.player, settlement)
            }
        })
    }

    // --- HELPERS ---
    private fun lang(path: String, def: String) = plugin.language.getString(path, def) ?: def
    private fun langList(path: String, def: List<String>) = plugin.language.getStringList(path).ifEmpty { def }

    private fun getReputationString(player: Player, settlement: Settlement): String {
        val score = settlement.data.reputation[player.uniqueId] ?: 0
        val config = plugin.gameplayManager.config.reputation
        val statusKey = when {
            score <= config.hostileRequired -> "hostile"
            score <= config.unfriendlyRequired -> "unfriendly"
            score >= config.exaltedRequired -> "exalted"
            score >= config.friendlyRequired -> "friendly"
            else -> "neutral"
        }
        return lang("reputation.status.$statusKey", statusKey.replaceFirstChar { it.uppercase() }) + " ($score)"
    }

    private fun createItem(material: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.setDisplayName(name)
        meta.lore = lore
        item.itemMeta = meta
        return item
    }

    private fun fillBorders(inv: Inventory) {
        val glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", emptyList())
        for (i in 0 until inv.size) {
            if (inv.size == 27 && (i < 9 || i > 17 || i % 9 == 0 || i % 9 == 8)) {
                if (inv.getItem(i) == null) inv.setItem(i, glass)
            }
        }
    }

    private fun playClickSound(player: Player) {
        try {
            val soundName = plugin.gameplayManager.config.reputation.statusUpdateSound
            player.playSound(player.location, soundName, 1f, 1f)
        } catch (e: Exception) {
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1f)
        }
    }
}
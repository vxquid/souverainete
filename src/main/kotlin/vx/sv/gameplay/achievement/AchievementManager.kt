package vx.sv.gameplay.achievement

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class AchievementManager {

    data class AchievementDefinition(
        val id: String,
        val icon: Material,
        val frame: FrameType = FrameType.TASK,
        val parentId: String? = "root"
    ) {
        enum class FrameType { TASK, CHALLENGE, GOAL }

        val title: String
            get() = plugin.language.getString("achievements.list.$id.title") ?: id

        val description: String
            get() = plugin.language.getString("achievements.list.$id.description") ?: ""

        val rewardMessage: String?
            get() = plugin.language.getString("achievements.list.$id.reward").takeIf { !it.isNullOrEmpty() }
    }

    companion object {
        private val registry = ConcurrentHashMap<String, AchievementDefinition>()
        private val pdcKey = NamespacedKey(plugin, "unlocked_achievements")
        @Volatile private var isInitialized = false

        private fun convertColorsToJsonComponent(text: String): String {
            // Принудительно приводим все § к & для надёжного парсинга LegacyComponentSerializer
            val normalized = text.replace("§", "&")
            val component = LegacyComponentSerializer.legacyAmpersand().deserialize(normalized)
            return GsonComponentSerializer.gson().serialize(component)
        }

        fun registerAll() {
            if (isInitialized) return
            isInitialized = true

            // 0. КОРНЕВАЯ ВКЛАДКА
            register(AchievementDefinition(id = "root", icon = Material.BOOK, frame = AchievementDefinition.FrameType.TASK, parentId = null))

            // --- ВЕТКА 1: ВЫЖИВАНИЕ И МИР ---
            register(AchievementDefinition(id = "first_steps_world", icon = Material.GRASS_BLOCK, parentId = "root"))
            register(AchievementDefinition(id = "first_visit_village", icon = Material.BELL, parentId = "first_steps_world"))
            register(AchievementDefinition(id = "founder", icon = Material.OAK_SIGN, parentId = "first_visit_village"))

            // --- ВЕТКА 2: ЖИТЕЛИ И КВЕСТЫ ---
            register(AchievementDefinition(id = "quest_novice", icon = Material.PAPER, parentId = "first_visit_village"))
            register(AchievementDefinition(id = "quest_master", icon = Material.WRITTEN_BOOK, parentId = "quest_novice", frame = AchievementDefinition.FrameType.GOAL))
            register(AchievementDefinition(id = "courier_betrayal", icon = Material.PLAYER_HEAD, parentId = "quest_novice", frame = AchievementDefinition.FrameType.CHALLENGE))

            // --- ВЕТКА 3: АРЕНДА И СТРОИТЕЛЬСТВО ---
            register(AchievementDefinition(id = "landlord", icon = Material.STONE_BRICKS, parentId = "founder"))
            register(AchievementDefinition(id = "master_builder", icon = Material.SCAFFOLDING, parentId = "landlord", frame = AchievementDefinition.FrameType.GOAL))
            register(AchievementDefinition(id = "lumberjack", icon = Material.OAK_LOG, parentId = "master_builder"))
            register(AchievementDefinition(id = "miner_lord", icon = Material.DIAMOND_ORE, parentId = "master_builder"))

            // --- ВЕТКА 4: КОМПАНЬОНЫ И ПАТИ ---
            register(AchievementDefinition(id = "party_starter", icon = Material.LEAD, parentId = "first_visit_village"))
            register(AchievementDefinition(id = "tactician", icon = Material.SPECTRAL_ARROW, parentId = "party_starter"))
            register(AchievementDefinition(id = "commander", icon = Material.DIAMOND_HELMET, parentId = "tactician", frame = AchievementDefinition.FrameType.CHALLENGE))

            // --- ВЕТКА 5: ВОЙНА И ДИПЛОМАТИЯ ---
            register(AchievementDefinition(id = "first_contact", icon = Material.MAP, parentId = "founder"))
            register(AchievementDefinition(id = "peacemaker", icon = Material.WHITE_BANNER, parentId = "first_contact"))
            register(AchievementDefinition(id = "warmonger", icon = Material.IRON_SWORD, parentId = "first_contact", frame = AchievementDefinition.FrameType.CHALLENGE))
            register(AchievementDefinition(id = "defender", icon = Material.SHIELD, parentId = "founder", frame = AchievementDefinition.FrameType.CHALLENGE))

            // --- ВЕТКА 6: СЕКРЕТНЫЕ / ТЯЖЕЛЫЕ ИВЕНТЫ ---
            register(AchievementDefinition(id = "exiled", icon = Material.ROTTEN_FLESH, parentId = "root"))
            register(AchievementDefinition(id = "exalted_hero", icon = Material.GOLDEN_APPLE, parentId = "exiled", frame = AchievementDefinition.FrameType.CHALLENGE))

            register(AchievementDefinition(id = "villager_killer", icon = Material.IRON_AXE, parentId = "root", frame = AchievementDefinition.FrameType.CHALLENGE))
            register(AchievementDefinition(id = "villager_victim", icon = Material.SKELETON_SKULL, parentId = "root", frame = AchievementDefinition.FrameType.CHALLENGE))
            register(AchievementDefinition(id = "unique_item_buyer", icon = Material.EMERALD, parentId = "first_visit_village", frame = AchievementDefinition.FrameType.GOAL))
            register(AchievementDefinition(id = "expensive_gift", icon = Material.NETHER_STAR, parentId = "first_visit_village", frame = AchievementDefinition.FrameType.GOAL))
        }

        fun register(def: AchievementDefinition) {
            registry[def.id] = def

            try {
                val nsKey = NamespacedKey(plugin, def.id.lowercase())

                // Пересоздаём ачивку с чистым кэшем, если она уже была сломана
                if (Bukkit.getAdvancement(nsKey) != null) {
                    try {
                        Bukkit.getUnsafe().removeAdvancement(nsKey)
                    } catch (_: Exception) {}
                }

                if (Bukkit.getAdvancement(nsKey) == null) {
                    val rootJson = JsonObject()
                    val display = JsonObject()

                    val icon = JsonObject()
                    icon.addProperty("id", def.icon.key.toString())
                    display.add("icon", icon)

                    // Конвертация в валидные Json-компоненты с цветами
                    val titleJsonString = convertColorsToJsonComponent(def.title)
                    display.add("title", JsonParser.parseString(titleJsonString))

                    val descJsonString = convertColorsToJsonComponent(def.description)
                    display.add("description", JsonParser.parseString(descJsonString))

                    display.addProperty("frame", def.frame.name.lowercase())
                    display.addProperty("show_toast", def.id != "root")
                    display.addProperty("announce_to_chat", def.id != "root")
                    display.addProperty("hidden", false)

                    // ИСПРАВЛЕНО: Верный путь к текстуре со словом textures!
                    if (def.id == "root") {
                        display.addProperty("background", "minecraft:block/stone")
                    }

                    val criteria = JsonObject()
                    val trigger = JsonObject()
                    trigger.addProperty("trigger", "minecraft:impossible")
                    criteria.add("trigger", trigger)

                    rootJson.add("display", display)
                    rootJson.add("criteria", criteria)

                    if (def.parentId != null) {
                        rootJson.addProperty("parent", "${plugin.name.lowercase()}:${def.parentId.lowercase()}")
                    }

                    Bukkit.getUnsafe().loadAdvancement(nsKey, rootJson.toString())
                }
            } catch (e: Exception) {
                plugin.logger.warning("Failed to inject advancement ${def.id}: ${e.message}")
            }
        }

        fun getUnlocked(player: Player): MutableSet<String> {
            val json = player.persistentDataContainer.get(pdcKey, PersistentDataType.STRING) ?: return mutableSetOf()
            return try {
                val type = object : TypeToken<MutableSet<String>>() {}.type
                gson.fromJson(json, type) ?: mutableSetOf()
            } catch (_: Exception) {
                mutableSetOf()
            }
        }

        fun hasUnlocked(player: Player, id: String): Boolean {
            return getUnlocked(player).contains(id)
        }

        fun syncPlayerAdvancements(player: Player) {
            if (!isInitialized) registerAll()

            val unlocked = getUnlocked(player)
            unlocked.add("root")

            for (id in unlocked) {
                val def = registry[id] ?: continue
                val nsKey = NamespacedKey(plugin, def.id.lowercase())
                val adv = Bukkit.getAdvancement(nsKey) ?: continue
                val progress = player.getAdvancementProgress(adv)
                if (!progress.isDone) {
                    for (criteria in progress.remainingCriteria) {
                        progress.awardCriteria(criteria)
                    }
                }
            }
        }

        fun grant(player: Player, id: String): Boolean {
            if (!isInitialized) registerAll()

            val def = registry[id] ?: return false
            val unlocked = getUnlocked(player)

            if (unlocked.contains(id)) {
                val nsKey = NamespacedKey(plugin, def.id.lowercase())
                val adv = Bukkit.getAdvancement(nsKey) ?: return false
                val progress = player.getAdvancementProgress(adv)
                if (!progress.isDone) {
                    for (criteria in progress.remainingCriteria) {
                        progress.awardCriteria(criteria)
                    }
                }
                return false
            }

            unlocked.add(id)
            player.persistentDataContainer.set(pdcKey, PersistentDataType.STRING, gson.toJson(unlocked))

            val nsKey = NamespacedKey(plugin, def.id.lowercase())
            val adv = Bukkit.getAdvancement(nsKey)
            if (adv != null) {
                val progress = player.getAdvancementProgress(adv)
                for (criteria in progress.remainingCriteria) {
                    progress.awardCriteria(criteria)
                }
            }

            return true
        }
    }
}
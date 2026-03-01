package vx.sv.gameplay.trade

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.profession.UniqueItemManager.Companion.getUniqueItemRarity
import java.util.*
import kotlin.random.Random

object ScoreCalculator {

    // Кэш для быстрого доступа без обращения к YamlConfiguration
    private val materialPriceCache = EnumMap<Material, Int>(Material::class.java)
    private val potionPriceCache = mutableMapOf<String, Int>()

    // Сет для отслеживания уже залогированных предметов (защита от флуда в консоли и падения TPS)
    private val missingPricesLogged = EnumSet.noneOf(Material::class.java)

    /**
     * Вызывать при запуске плагина (onEnable) и при перезагрузке конфигов.
     */
    fun init() {
        materialPriceCache.clear()
        potionPriceCache.clear()
        missingPricesLogged.clear()

        val pricingConfig = plugin.prices

        // Предзагрузка цен на материалы
        for (material in Material.entries) {
            if (material.isAir) continue
            val price = pricingConfig.getInt(material.name, -1)
            if (price != -1) {
                materialPriceCache[material] = price
            }
        }

        // Предзагрузка цен на зелья
        val potionSection = pricingConfig.getConfigurationSection("effect-type")
        potionSection?.getKeys(false)?.forEach { key ->
            potionPriceCache[key.uppercase()] = potionSection.getInt(key)
        }
    }

    /**
     * Вычисляет общую стоимость стака предметов.
     */
    fun ItemStack.calculateScore(): Int {
        if (this.type == Material.AIR) return 0

        val baseScore = this.type.getBasicScore()
        val amountMultiplier = this.amount

        // Быстрая проверка типа перед тяжелым кастом меты
        val potionBonus = if (this.type == Material.POTION || this.type == Material.SPLASH_POTION || this.type == Material.LINGERING_POTION) {
            calculatePotionBonus()
        } else 0

        val rarityBonus = this.getUniqueItemRarity().extraPrice

        return (baseScore * amountMultiplier) + potionBonus + rarityBonus
    }

    /**
     * Вычисляет бонус для зелий. Если цены нет в конфиге, возвращает рандомное значение.
     */
    private fun ItemStack.calculatePotionBonus(): Int {
        val meta = itemMeta as? PotionMeta ?: return 0
        val potionType = meta.basePotionType ?: return 0

        val cachedPrice = potionPriceCache[potionType.name]
        return cachedPrice ?: (2400 + Random.nextInt(1, 6) * 200)
    }

    /**
     * Получает базовую цену предмета. Если цены нет — пишет в лог (1 раз) и возвращает дефолт.
     */
    fun Material.getBasicScore(defaultPrice: Int = 50): Int {
        if (this.isAir) return 0

        val cachedPrice = materialPriceCache[this]
        if (cachedPrice != null) {
            return cachedPrice
        }

        // Если цены нет в кэше, добавляем материал в сет "залогированных".
        // Метод add() вернет true только если этого материала там еще не было.
        if (missingPricesLogged.add(this)) {
            plugin.logger.warning("Price for material $this (key: ${this.name}) not found in config. Using default: $defaultPrice")
        }

        return defaultPrice
    }

}
package vx.ignis.gameplay.quest.pragma.strategy

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.dictionary.CustomItem
import vx.ignis.gameplay.quest.pragma.QuestItemStrategy
import vx.ignis.persistent.LivingEntityExtend.subInventory

class EnchantedBookQuestItemStrategy : QuestItemStrategy() {

    override fun get(questGiver: LivingEntity): CustomItem {
        val item = this.randomEnchantedBook(questGiver as? Villager ?: throw IllegalStateException("EnchantedBookQuest is a villager quest!"))
        return CustomItem(item.type.name, 8500, item)
    }

    fun randomEnchantedBook(questGiver: Villager): ItemStack {

        fun EnchantmentStorageMeta.hasStoredEnchant(key: String): Boolean {
            val enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(key.lowercase())) ?: return false
            return this.hasStoredEnchant(enchantment)
        }

        val allowedEnchantments = plugin.prompts.getStringList("enchanted-book-quest.allowed-enchantments").apply {
            removeIf { enchantName ->
                questGiver.subInventory.contents.filterNotNull().any { item ->
                    item.itemMeta is EnchantmentStorageMeta && (item.itemMeta as EnchantmentStorageMeta).hasStoredEnchant(enchantName)
                }
            }
        }.map {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(it.lowercase()))
        }

        val enchantment = allowedEnchantments.random()!!

        return ItemStack(Material.ENCHANTED_BOOK).apply {
            itemMeta = (itemMeta as EnchantmentStorageMeta).apply {
                addStoredEnchant(enchantment, enchantment.maxLevel, false)
            }
        }
    }

}
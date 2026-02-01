package vx.sv.util

import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.*

object HeadUtils {

    fun getHeadByTexture(textureValue: String, textureSignature: String? = null): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta as SkullMeta

        val profile = GameProfile(UUID.randomUUID(), null)
        profile.properties.put("textures", Property("textures", textureValue, textureSignature))

        try {
            val profileField = meta.javaClass.getDeclaredField("profile")
            profileField.isAccessible = true
            profileField.set(meta, profile)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        head.itemMeta = meta
        return head
    }
}
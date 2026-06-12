package vx.sv.util

import com.google.gson.*
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import vx.sv.util.InventorySerializer.Companion.fromBase64
import vx.sv.util.InventorySerializer.Companion.toBase64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Type

class InventorySerializer {

    companion object {

        @Deprecated("Use default entity inventory instead of this.")
        fun inventoryToJSON(inventory: Inventory): JsonObject {
            val obj = JsonObject()

            obj.addProperty("size", inventory.size)

            val items = JsonArray()
            for (i in 0 until inventory.size) {
                val item: ItemStack? = inventory.getItem(i)
                if (item != null) {
                    val jitem = JsonObject()
                    jitem.addProperty("slot", i)
                    val itemData: String = toBase64(item)
                    jitem.addProperty("data", itemData)
                    items.add(jitem)
                }
            }
            obj.add("items", items)

            return obj
        }

        @Deprecated("Use default entity inventory instead of this.")
        fun inventoryFromJSON(jsonInventory: String): Inventory {
            try {
                val jsonObject = JsonParser.parseString(jsonInventory).asJsonObject
                val inventory: Inventory = Bukkit.createInventory(null, jsonObject["size"].asInt)
                val items = jsonObject["items"].asJsonArray
                for (element in items) {
                    val jsonItem = element.asJsonObject
                    val item: ItemStack = fromBase64(jsonItem["data"].asString)
                    inventory.setItem(jsonItem["slot"].asInt, item)
                }
                return inventory
            } catch (ex: Exception) {
                return Bukkit.createInventory(null, 54)
            }
        }


        fun toBase64(item: ItemStack?): String {
            val outputStream = ByteArrayOutputStream()
            val dataOutput = BukkitObjectOutputStream(outputStream)
            dataOutput.writeObject(item)
            dataOutput.close()
            return Base64Coder.encodeLines(outputStream.toByteArray())
        }

        fun fromBase64(base64: String): ItemStack {
            val inputStream = ByteArrayInputStream(Base64Coder.decodeLines(base64))
            val dataInput = BukkitObjectInputStream(inputStream)
            val item = dataInput.readObject() as ItemStack
            dataInput.close()
            return item
        }

    }

}

class ItemStackSerializer : JsonSerializer<ItemStack> {
    override fun serialize(src: ItemStack?, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        if (src == null) {
            return JsonNull.INSTANCE
        }
        return JsonPrimitive(toBase64(src))
    }
}

class ItemStackDeserializer : JsonDeserializer<ItemStack> {
    override fun deserialize(json: JsonElement?, typeOfT: Type, context: JsonDeserializationContext): ItemStack {
        if (json == null || json.isJsonNull) {
            return ItemStack(Material.AIR)
        }
        return fromBase64(json.asString)
    }
}
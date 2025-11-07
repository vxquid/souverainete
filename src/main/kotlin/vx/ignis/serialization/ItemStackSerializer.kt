package vx.ignis.serialization

import com.google.gson.*
import org.bukkit.inventory.ItemStack
import java.lang.reflect.Type
import java.util.*

class ItemStackSerializer : JsonSerializer<ItemStack>, JsonDeserializer<ItemStack> {

    override fun serialize(src: ItemStack?, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        if (src == null) {
            return JsonNull.INSTANCE
        }
        val base64 = Base64.getEncoder().encodeToString(src.serializeAsBytes())
        return JsonPrimitive(base64)
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ItemStack? {
        if (json.isJsonNull) {
            return null
        }
        return try {
            val base64 = json.asString
            val bytes = Base64.getDecoder().decode(base64)
            ItemStack.deserializeBytes(bytes)
        } catch (e: Exception) {
            null
        }
    }

}
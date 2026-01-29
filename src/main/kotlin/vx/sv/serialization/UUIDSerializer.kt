package vx.sv.serialization

import com.google.gson.*
import java.lang.reflect.Type
import java.util.*

class UUIDSerializer : JsonSerializer<UUID>, JsonDeserializer<UUID> {

    override fun serialize(src: UUID?, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        if (src == null) {
            return JsonNull.INSTANCE
        }
        return JsonPrimitive(src.toString())
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): UUID? {
        if (json.isJsonNull) {
            return null
        }
        return try {
            UUID.fromString(json.asString)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

}
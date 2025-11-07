package vx.ignis.serialization

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import org.bukkit.Location
import org.bukkit.Bukkit
import java.lang.reflect.Type

class LocationSerializer : JsonSerializer<Location>, JsonDeserializer<Location> {

    override fun serialize(src: Location?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        val jsonObject = JsonObject()
        if (src != null) {
            jsonObject.addProperty("x", src.x)
            jsonObject.addProperty("y", src.y)
            jsonObject.addProperty("z", src.z)
            jsonObject.addProperty("world", src.world?.name)
        }
        return jsonObject
    }

    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Location? {
        if (json != null && json.isJsonObject) {
            val jsonObject = json.asJsonObject
            val x = jsonObject.get("x").asDouble
            val y = jsonObject.get("y").asDouble
            val z = jsonObject.get("z").asDouble
            val worldName = jsonObject.get("world").asString
            val world = Bukkit.getWorld(worldName)
            return Location(world, x, y, z)
        }
        return null
    }

}
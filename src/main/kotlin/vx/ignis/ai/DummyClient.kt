package vx.ignis.ai

import org.bukkit.configuration.file.YamlConfiguration
import vx.ignis.ai.base.AIClient
import kotlin.reflect.KClass

class DummyClient() : AIClient {

    /**
     * DUMMY IMPLEMENTATION
     * Always returns null to simulate a failed or non-existent AI response.
     * This prevents the plugin from crashing when the provider is not configured.
     */
    override fun <T : Any> sendPromptWithSchema(
        prompt: String,
        targetClass: KClass<T>
    ): T? {
        return null
    }

    /**
     * DUMMY IMPLEMENTATION
     * Always returns null.
     */
    override fun translate(yamlConfig: YamlConfiguration): YamlConfiguration? {
        return null
    }

}
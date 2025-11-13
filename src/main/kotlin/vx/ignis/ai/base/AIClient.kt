package vx.ignis.ai.base

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.reflect.KClass

interface AIClient {

    /** ### **DO NOT use it in the main server tick!** */
    fun <T : Any> sendPromptWithSchema(
        prompt: String,
        targetClass: KClass<T>
    ): T?

    fun translate(yamlConfig: YamlConfiguration): YamlConfiguration?

}
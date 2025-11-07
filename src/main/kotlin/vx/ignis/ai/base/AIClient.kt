package vx.ignis.ai.base

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.reflect.KClass

interface AIClient {

    /** ### **DO NOT use it in the main server tick!** */
    fun <T : Any> sendPromptWithSchema(
        prompt: String,
        targetClass: KClass<T>
    ): T?

    fun translate(file: File, onSuccess: (YamlConfiguration) -> Unit)

    val jsonPrompt: String
        get() = """
                Return the response as a JSON object strictly adhering to the schema described in the prompt.
                
                Ensure the response is valid JSON enclosed in curly braces {} and contains only the fields specified in the schema.
                Do NOT include code fences (```json or ```), additional text, or explanations outside the JSON object.
            """

    val yamlPrompt: String
        get() = """
                Translate YAML content below to %s, keeping keys, special symbols (like §), and placeholders untranslated.
                Wrap the result in ```yaml```.
                ```yaml
                %s
                ```
            """

}
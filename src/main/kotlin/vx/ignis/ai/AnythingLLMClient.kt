package vx.ignis.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.bukkit.configuration.file.YamlConfiguration
import vx.ignis.Ignis.Companion.gson
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.ai.base.AIClient
import vx.ignis.config.ProviderConfiguration
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class AnythingLLMClient(
    private val keyManager: KeyManager,
    private val config: ProviderConfiguration
) : AIClient {

    // AnythingLLM endpoint. Обычно это http://localhost:3001/api/v1/openai/chat/completions
    // Если config.url пустой, используем дефолтный локальный адрес
    private val endpoint: String = if (config.url.isNullOrEmpty()) {
        "http://localhost:3001/api/v1/openai/chat/completions"
    } else {
        // Убираем слеш в конце если есть и добавляем путь, если пользователь указал только хост
        val cleanUrl = config.url.trimEnd('/')
        if (cleanUrl.endsWith("completions")) cleanUrl else "$cleanUrl/api/v1/openai/chat/completions"
    }

    class KeyManager(keys: List<String>) {
        data class Key(val key: String, var requestCounter: Int = 0, var quota: Boolean = false)

        private val apiKeys = keys.map { Key(it) }.toMutableList()

        fun getAvailableKey(): Key {
            return apiKeys.filter { !it.quota }.randomOrNull()
                ?: throw IllegalStateException("All API keys have exceeded quota or are invalid.")
        }
    }

    private val proxyHost = config.proxy.host
    private val proxyPort = config.proxy.port
    private val proxyType = config.proxy.type
    private val username  = config.proxy.user
    private val password  = config.proxy.pass

    private val proxy = Proxy(proxyType, InetSocketAddress(proxyHost, proxyPort))

    private val proxyAuthenticator: Authenticator = Authenticator { _, response ->
        val credential = Credentials.basic(username, password)
        response.request.newBuilder()
            .addHeader("Proxy-Authorization", credential)
            .build()
    }

    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).apply {
        if (proxyHost != "PROXY_HOST") {
            plugin.logger.info("Proxy usage in config.yml detected. Using proxy for requests.")
            proxy(proxy).proxyAuthenticator(proxyAuthenticator)
        }
    }.build()

    private val lang  = config.language
    private val rules = "[Rules: `Use $lang language.`, `Generate content in ${config.setting} setting.`, `Use ${config.namingStyle} naming style.`] "
    private val temp  = config.temperature
    private val model = config.model.ifEmpty { "gpt-3.5-turbo" } // AnythingLLM часто игнорирует модель или требует конкретное имя воркспейса

    override fun <T : Any> sendPromptWithSchema(
        prompt: String,
        targetClass: KClass<T>
    ): T? = try {
        val fullPrompt =
            "$rules$prompt\n\nReturn the response as a JSON object strictly adhering to the schema described in the prompt. Ensure the response is valid JSON enclosed in curly braces {} and contains only the fields specified in the schema. Do NOT include code fences (```json```)"
        sendRequestWithRetry(fullPrompt, targetClass, config.maxRetries)
    } catch (_: Exception) {
        null
    }

    override fun translate(yamlConfig: YamlConfiguration): YamlConfiguration? = try {
        val yamlText = yamlConfig.saveToString()
        val prompt = """
            You are a forced, expert-level translator and language replacer. Your sole task is to translate the provided YAML content.
            
            **ORIGINAL LANGUAGE:** English
            **TARGET LANGUAGE:** $lang
            
            **ACTIONS REQUIRED:**
            1. **TRANSLATE ALL** visible string values from English to **$lang**. Translation is MANDATORY.
            2. **PRESERVE ALL YAML KEYS** exactly as they appear. They are never translated.
            3. **NEVER** translate any text inside placeholders (e.g., %player%, {amount}, <item>) or special symbols (like §, &). Preserve them precisely.
            4. The output **MUST** be ONLY the translated YAML content, enclosed in a single **```yaml```** code block. Do NOT include any introductory text, explanations, or comments outside the code block.
            
            YAML Content to process:
            $yamlText
        """.trimIndent()
        translateWithRetry(prompt, config.maxRetries)
    } catch (_: Exception) {
        null
    }

    private fun <T : Any> sendRequestWithRetry(
        prompt: String,
        responseType: KClass<T>,
        retries: Int
    ): T? {

        if (retries <= 0) {
            return null
        }

        val key = try {
            keyManager.getAvailableKey()
        } catch (_: IllegalStateException) {
            return null
        }

        val parser = AdvancedJsonParser()
        val escapedPrompt = parser.escapeJsonString(prompt)
        // AnythingLLM/OpenAI использует формат "messages" вместо "contents"
        val requestBodyJson = parser.createJsonRequest(escapedPrompt, model, temp)
        val requestBody = requestBodyJson.toRequestBody("application/json".toMediaTypeOrNull())
        
        // Ключ передается в заголовке Authorization, а не в URL
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${key.key}")
            .post(requestBody)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                handleFailedResponse(response, key)
                return sendRequestWithRetry(prompt, responseType, retries - 1)
            }
            val content = response.body?.string() ?: run {
                return null
            }
            try {
                parser.parseResponse(content, responseType.java)
            } catch (_: JsonParseException) {
                sendRequestWithRetry(prompt, responseType, retries - 1)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun translateWithRetry(
        prompt: String,
        retries: Int
    ): YamlConfiguration? {

        if (retries <= 0) {
            return null
        }

        val key = try {
            keyManager.getAvailableKey()
        } catch (_: IllegalStateException) {
            return null
        }

        val parser = AdvancedJsonParser()
        val escapedPrompt = parser.escapeJsonString(prompt)
        val requestBodyJson = parser.createJsonRequest(escapedPrompt, model, temp)
        val requestBody = requestBodyJson.toRequestBody("application/json".toMediaTypeOrNull())
        
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${key.key}")
            .post(requestBody)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                handleFailedResponse(response, key)
                return translateWithRetry(prompt, retries - 1)
            }
            val content = response.body?.string() ?: run {
                return null
            }
            try {
                val extractedText = parser.extractTextFromResponse(content)
                val cleanedData = parser.findYaml(extractedText).let { parser.unescapeString(it) }
                YamlConfiguration.loadConfiguration(StringReader(cleanedData))
            } catch (_: JsonParseException) {
                translateWithRetry(prompt, retries - 1)
            } catch (_: NullPointerException) {
                translateWithRetry(prompt, retries - 1)
            } catch (_: Exception) {
                null
            }
        }
    }

    private inner class AdvancedJsonParser {

        // OpenAI/AnythingLLM Structure: choices[0].message.content
        fun <T : Any> parseResponse(content: String, responseType: Class<T>): T {
            val jsonResponse = gson.fromJson(content, JsonObject::class.java)
            
            // Адаптация под структуру OpenAI API
            var cleanedContent = jsonResponse
                .getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?.let { cleanJson(it) }
                ?: throw JsonParseException("Failed to extract JSON content from response!")

            cleanedContent = repairJson(cleanedContent)

            return gson.fromJson(cleanedContent, JsonObject::class.java)
                ?.let { gson.fromJson(it, responseType) }
                ?: throw JsonParseException("Failed to parse cleaned response as JSON!")
        }

        fun extractTextFromResponse(content: String): String {
            val jsonResponse = gson.fromJson(content, JsonObject::class.java)
            return jsonResponse
                .getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: throw JsonParseException("Failed to extract text from response!")
        }

        fun findYaml(yaml: String): String {
            val regex = """```yaml([\s\S]*?)```""".toRegex()
            return regex.find(yaml)?.groups?.get(1)?.value?.trim()
                ?: throw NullPointerException("Can't find yaml pattern during translation task.")
        }

        private fun cleanJson(input: String): String =
            input.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

        fun escapeJsonString(input: String): String =
            input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

        fun unescapeString(input: String): String =
            input.replace(Regex("""\\+n"""), "\n")
                .replace(Regex("""\\+""""), "\"")

        // Структура запроса для OpenAI API / AnythingLLM
        fun createJsonRequest(prompt: String, modelName: String, temperature: Double): String =
            """{
                "model": "$modelName",
                "messages": [
                    {
                        "role": "user",
                        "content": "$prompt"
                    }
                ],
                "temperature": $temperature
            }""".trimIndent()

        private fun repairJson(jsonStr: String): String {
            var repaired = jsonStr.trim()

            // Remove trailing commas
            repaired = repaired.replace(Regex(",\\s*([}\\]])"), "$1")

            // Balance braces
            val openBraces = repaired.count { it == '{' }
            val closeBraces = repaired.count { it == '}' }
            if (openBraces > closeBraces) {
                repaired += "}".repeat(openBraces - closeBraces)
            } else if (closeBraces > openBraces) {
                repaired = "{".repeat(closeBraces - openBraces) + repaired
            }

            // Balance quotes
            val quoteCount = repaired.count { it == '"' }
            if (quoteCount % 2 != 0) {
                repaired += "\""
            }

            // Strip non-JSON prefix/suffix
            val start = repaired.indexOf('{').takeIf { it >= 0 } ?: 0
            val end = repaired.lastIndexOf('}').takeIf { it >= 0 } ?: repaired.length
            repaired = repaired.substring(start, end + 1)

            try {
                gson.fromJson(repaired, JsonObject::class.java)
            } catch (_: Exception) {
            }

            return repaired
        }
    }

    private fun handleFailedResponse(response: Response, key: KeyManager.Key) {
        val code = response.code
        response.body?.string()?.let { body ->
            plugin.logger.warning("AnythingLLM Request failed. Code: $code, Body: $body")
            
            // Если код 429 или 401, можно временно отключить ключ
            if (code == 429 || code == 401) {
                key.quota = true
                plugin.server.scheduler.runTaskLater(plugin, { _ -> key.quota = false }, 60 * 20)
            }
        }
    }
}
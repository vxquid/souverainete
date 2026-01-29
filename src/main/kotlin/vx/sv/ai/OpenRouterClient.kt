package vx.sv.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.bukkit.configuration.file.YamlConfiguration
import vx.sv.Souverainete.Companion.gson
import vx.sv.Souverainete.Companion.plugin
import vx.sv.ai.base.AIClient
import vx.sv.config.ProviderConfiguration
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class OpenRouterClient(
    private val apiKey: String,
    private val apiUrl: String = "https://openrouter.ai/api/v1/chat/completions",
    private val config: ProviderConfiguration
) : AIClient {

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

    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).apply {
        if (proxyHost != "PROXY_HOST") {
            plugin.logger.info("Proxy usage in config.yml detected. Using proxy for requests.")
            proxy(proxy).proxyAuthenticator(proxyAuthenticator)
        }
    }.build()

    private val model = config.model
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val lang  = config.language
    private val rules = "[Rules: `Use $lang language.`, `Generate content in ${config.setting} setting.`, `Use ${config.namingStyle} naming style.`] "
    private val temp  = config.temperature

    data class OpenRouterRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double
    ) {
        data class Message(val role: String, val content: String)
    }

    data class OpenRouterResponse(val choices: List<Choice>?) {
        data class Choice(val message: Message?) {
            data class Message(val content: String)
        }
    }

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
        """.trimIndent() // Используем тройные кавычки для многострочности
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
            logError("Max retries reached for prompt: $prompt")
            return null
        }

        val requestBody = createRequestBody(prompt)
        val request = createRequest(requestBody)

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                handleFailedResponse(response)
                return sendRequestWithRetry(prompt, responseType, retries - 1)
            }
            val content = response.body?.string()?.let { body ->
                gson.fromJson(body, OpenRouterResponse::class.java)
                    ?.choices?.firstOrNull()?.message?.content
            } ?: run {
                logError("Empty or invalid response")
                return null
            }
            try {
                val cleanedContent = content.cleanJson()
                return gson.fromJson(cleanedContent, JsonObject::class.java)
                    ?.let { gson.fromJson(it, responseType.java) }
                    ?: run {
                        logError("Failed to parse cleaned response as JSON")
                        null
                    }
            } catch (e: JsonParseException) {
                logError("Failed to parse JSON: ${e.message}")
                sendRequestWithRetry(prompt, responseType, retries - 1)
            } catch (e: Exception) {
                logError("Unexpected error parsing response: ${e.message}")
                null
            }
        }
    }

    private fun translateWithRetry(
        prompt: String,
        retries: Int
    ): YamlConfiguration? {
        if (retries <= 0) {
            logError("Max retries reached for translate prompt: $prompt")
            return null
        }

        val requestBody = createRequestBody(prompt)
        val request = createRequest(requestBody)

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                handleFailedResponse(response)
                return translateWithRetry(prompt, retries - 1)
            }
            val content = response.body?.string()?.let { body ->
                gson.fromJson(body, OpenRouterResponse::class.java)
                    ?.choices?.firstOrNull()?.message?.content
            } ?: run {
                logError("Empty or invalid response")
                return null
            }
            try {
                val cleanedData = findYaml(content).unescapeString()
                YamlConfiguration.loadConfiguration(StringReader(cleanedData))
            } catch (e: NullPointerException) {
                logError("Failed to extract YAML: ${e.message}")
                translateWithRetry(prompt, retries - 1)
            } catch (e: Exception) {
                logError("Unexpected error during translation: ${e.message}")
                null
            }
        }
    }

    private fun createRequestBody(prompt: String): RequestBody {
        val requestData = OpenRouterRequest(
            model = model,
            messages = listOf(OpenRouterRequest.Message("user", prompt)),
            temperature = temp
        )
        return gson.toJson(requestData).toRequestBody(mediaType)
    }

    private fun createRequest(body: RequestBody): Request {
        return Request.Builder()
            .url(apiUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
    }

    private fun findYaml(yaml: String): String {
        val regex = """```yaml([\s\S]*?)```""".toRegex()
        return regex.find(yaml)?.groups?.get(1)?.value?.trim()
            ?: throw NullPointerException("Can't find yaml pattern during translation task.")
    }

    private fun String.cleanJson(): String =
        trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

    private fun String.unescapeString(): String =
        replace(Regex("""\\+n"""), "\n")
            .replace(Regex("""\\+""""), "\"")

    private fun handleFailedResponse(response: Response) {
        response.body?.string()?.let { reason ->
            logError("Request failed: ${response.code}, $reason")
        } ?: logError("Empty response body")
    }

    private fun logError(message: String) {
        plugin.logger.warning("[OpenRouterClient] [ERROR] $message")
    }

}
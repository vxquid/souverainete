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
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class GeminiClient(
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=",
    private val keyManager: KeyManager,
    private val config: ProviderConfiguration
) : AIClient {

    class KeyManager(keys: List<String>) {
        data class Key(val key: String, var requestCounter: Int = 0, var quota: Boolean = false)

        private val apiKeys = keys.map { Key(it) }.toMutableList()

        fun getAvailableKey(): Key {
            return apiKeys.filter { !it.quota }.randomOrNull()
                ?: throw IllegalStateException("All API keys have exceeded quota. Please add new keys.")
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

    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).apply {
        if (proxyHost != "PROXY_HOST") {
            plugin.logger.info("Proxy usage in config.yml detected. Using proxy for requests.")
            proxy(proxy).proxyAuthenticator(proxyAuthenticator)
        }
    }.build()

    private val lang  = config.language
    private val rules = "[Rules: `Use $lang language.`, `Generate content in ${config.setting} setting.`, `Use ${config.namingStyle} naming style.`] "
    private val temp  = config.temperature

    override fun <T : Any> sendPromptWithSchema(
        prompt: String,
        targetClass: KClass<T>
    ): T? = try {
        val fullPrompt =
            "$rules$prompt\n\nReturn the response as a JSON object strictly adhering to the schema described in the prompt. Ensure the response is valid JSON enclosed in curly braces {} and contains only the fields specified in the schema. Do NOT include code fences (```json```)"
        sendRequestWithRetry(fullPrompt, targetClass, config.maxRetries)
    } catch (any: Exception) {
        null
    }

    override fun translate(file: File, onSuccess: (YamlConfiguration) -> Unit) {
        val prompt =
            "Translate YAML file below to $lang, keep the keys and special symbols (like ยง) and DO NOT translate placeholders. Wrap result as ```yaml```. \n```yaml\n${file.readText()}\n```"
        val key = try {
            keyManager.getAvailableKey()
        } catch (e: IllegalStateException) {
            logError("No available API keys: ${e.message}")
            return
        }

        val requestBody =
            createJsonRequest(prompt.escapeJsonString()).toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url("$baseUrl${key.key}").post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logError("Network issue: ${e.message}")
                retryTranslate(file, onSuccess)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        logError("HTTP ${response.code} - ${response.message}")
                        retryTranslate(file, onSuccess)
                        return
                    }
                    val content = response.body?.string() ?: run {
                        logError("Empty response body")
                        retryTranslate(file, onSuccess)
                        return
                    }
                    try {
                        val cleanedData = findYaml(content).unescapeString()
                        onSuccess(YamlConfiguration.loadConfiguration(StringReader(cleanedData)))
                    } catch (e: NullPointerException) {
                        logError("Failed to extract YAML: ${e.message}")
                        retryTranslate(file, onSuccess)
                    } catch (e: Exception) {
                        logError("Unexpected error during translation: ${e.message}")
                    }
                }
            }
        })
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

        val key = try {
            keyManager.getAvailableKey()
        } catch (e: IllegalStateException) {
            logError("No available API keys: ${e.message}")
            return null
        }

        val requestBody =
            createJsonRequest(prompt.escapeJsonString()).toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url("$baseUrl${key.key}").post(requestBody).build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                handleFailedResponse(response, key)
                return sendRequestWithRetry(prompt, responseType, retries - 1)
            }
            val content = response.body?.string() ?: run {
                logError("Empty response body")
                return null
            }
            try {
                val jsonResponse = gson.fromJson(content, JsonObject::class.java)
                val cleanedContent = jsonResponse
                    .getAsJsonArray("candidates")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString
                    ?.cleanJson()
                    ?: run {
                        logError("Failed to extract JSON content from response!")
                        return null
                    }
                gson.fromJson(cleanedContent, JsonObject::class.java)
                    ?.let { gson.fromJson(it, responseType.java) }
                    ?: run {
                        logError("Failed to parse cleaned response as JSON!")
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

    private fun retryTranslate(file: File, onSuccess: (YamlConfiguration) -> Unit) {
        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            translate(file, onSuccess)
        }, 200L)
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

    private fun String.escapeJsonString(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun String.unescapeString(): String =
        replace(Regex("""\\+n"""), "\n")
            .replace(Regex("""\\+""""), "\"")

    private fun createJsonRequest(prompt: String): String =
        """{
            "contents": [{
                "parts": [{
                    "text": "$prompt"
                }]
            }],
            "safetySettings": [{
                "category": "7",
                "threshold": "4"
            }],
            "generationConfig": {
                "responseMimeType": "application/json",
                "temperature": $temp
            }
        }""".trimIndent()

    private fun handleFailedResponse(response: Response, key: KeyManager.Key) {
        response.body?.string()?.let { reason ->
            if (reason.lowercase().contains("quota")) {
                key.quota = true
                plugin.logger.info("Quota exceeded. Resetting key in 60 seconds.")
                plugin.server.scheduler.runTaskLater(plugin, { _ -> key.quota = false }, 60 * 20)
            } else {
                logError("Request failed: ${response.code}, $reason")
            }
        } ?: logError("Empty response body")
    }

    private fun logError(message: String) {
        plugin.logger.warning("[GeminiClient] [ERROR] $message")
    }

}
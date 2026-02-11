package vx.sv.config.lib

import org.bukkit.configuration.file.YamlConfiguration
import vx.sv.Souverainete
import vx.sv.ai.base.AIClient
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest

class TranslationManager(
    private val plugin: Souverainete,
    private val client: AIClient,
    private val targetLanguage: String
) {
    private val cacheDir = plugin.dataFolder.resolve("cache").apply { mkdirs() }

    enum class TranslationResult { SUCCESS, SKIPPED, QUOTA_LIMIT, ERROR }

    /**
     * Translates language.yml. Used during plugin startup.
     */
    fun getTranslated(originalFile: File): YamlConfiguration {
        val resourceText = plugin.getResource("language.yml")?.let { InputStreamReader(it).readText() } ?: ""
        val resourceHash = computeHash(resourceText)

        if (originalFile.exists()) {
            if (computeHash(originalFile.readText()) != resourceHash) plugin.saveResource("language.yml", true)
        } else plugin.saveResource("language.yml", false)

        val originalHash = computeHash(originalFile.readText())
        val cacheFile = cacheDir.resolve("translations/language_${targetLanguage}.yml")
        val hashFile = cacheDir.resolve("translations/language_${targetLanguage}_hash.txt")

        if (cacheFile.exists() && hashFile.exists() && hashFile.readText() == originalHash) {
            return YamlConfiguration.loadConfiguration(cacheFile)
        }

        val translated = client.translate(YamlConfiguration.loadConfiguration(originalFile))
        if (translated != null) {
            cacheFile.parentFile.mkdirs()
            translated.save(cacheFile)
            hashFile.writeText(originalHash)
            return translated
        }
        return YamlConfiguration.loadConfiguration(originalFile)
    }

    /**
     * Translates a specific file (names.yml or phrases.yml) with state tracking.
     */
    fun translateFileWithState(sourceFile: File, relativePath: String): TranslationResult {
        val cacheFile = cacheDir.resolve("$relativePath.yml")
        val hashFile = cacheDir.resolve("${relativePath}_hash.txt")

        if (!sourceFile.exists()) return TranslationResult.ERROR

        val sourceText = sourceFile.readText()
        val sourceHash = computeHash(sourceText)

        // Skip if hash matches (already translated)
        if (cacheFile.exists() && hashFile.exists() && hashFile.readText() == sourceHash) {
            return TranslationResult.SKIPPED
        }

        return try {
            val sourceConfig = YamlConfiguration.loadConfiguration(sourceFile)
            val translated = client.translate(sourceConfig)

            if (translated != null) {
                cacheFile.parentFile.mkdirs()
                translated.save(cacheFile)
                hashFile.writeText(sourceHash)
                TranslationResult.SUCCESS
            } else {
                TranslationResult.QUOTA_LIMIT
            }
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("429") || msg.contains("quota") || msg.contains("limit")) {
                TranslationResult.QUOTA_LIMIT
            } else {
                plugin.logger.severe("Translation error for $relativePath: ${e.message}")
                TranslationResult.ERROR
            }
        }
    }

    fun computeHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
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

    private val cacheDir = plugin.dataFolder.resolve("cache/translations").apply { mkdirs() }

    fun getTranslated(originalFile: File): YamlConfiguration {
        // Step 1: Ensure the latest language.yml from JAR is saved/updated in data folder
        val resourceText = plugin.getResource("language.yml")?.let { InputStreamReader(it).readText() } ?: ""
        val resourceHash = computeHash(resourceText)

        if (originalFile.exists()) {
            val savedText = originalFile.readText()
            val savedHash = computeHash(savedText)
            if (savedHash != resourceHash) {
                plugin.logger.info("Updating language.yml from JAR (hash mismatch detected).")
                plugin.saveResource("language.yml", true) // Overwrite if exists
            }
        } else {
            plugin.logger.info("Saving default language.yml from JAR.")
            plugin.saveResource("language.yml", false)
        }

        // Now load the original config (updated if necessary)
        val originalConfig = YamlConfiguration.loadConfiguration(originalFile)
        val originalText = originalFile.readText()
        val originalHash = computeHash(originalText)

        val cacheFile = cacheDir.resolve("language_${targetLanguage}.yml")
        val hashFile = cacheDir.resolve("language_${targetLanguage}_hash.txt")

        if (cacheFile.exists() && hashFile.exists() && hashFile.readText() == originalHash) {
            plugin.logger.info("Loading translated language from cache (hash matches).")
            return YamlConfiguration.loadConfiguration(cacheFile)
        }

        plugin.logger.info("Translating language.yml (cache invalid or hash mismatch).")
        val translated = client.translate(originalConfig)
        if (translated != null) {
            translated.save(cacheFile)
            hashFile.writeText(originalHash)
            plugin.logger.info("Translation saved to cache with new hash.")
            return translated
        } else {
            plugin.logger.warning("Translation failed, using original configuration.")
            return originalConfig
        }
    }

    private fun computeHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

}
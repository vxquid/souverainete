package vx.sv.config.lib

import org.bukkit.configuration.file.YamlConfiguration
import vx.sv.Souverainete
import vx.sv.ai.base.AIClient
import vx.sv.ai.base.DummyClient
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

    companion object {
        /**
         * Проверяет, является ли язык стандартным (английским).
         */
        fun isDefaultLanguage(language: String): Boolean {
            val lang = language.trim().lowercase()
            return lang in setOf("english", "en", "eng", "default", "none") ||
                    lang.startsWith("en-") || lang.startsWith("en_")
        }
    }

    /**
     * Translates language.yml. Used during plugin startup.
     */
    fun getTranslated(originalFile: File): YamlConfiguration {
        if (!originalFile.exists()) {
            plugin.saveResource("language.yml", false)
        } else {
            val resourceStream = plugin.getResource("language.yml")
            if (resourceStream != null) {
                val defaultConfig = YamlConfiguration.loadConfiguration(InputStreamReader(resourceStream, Charsets.UTF_8))
                val existingConfig = YamlConfiguration.loadConfiguration(originalFile)

                // Проверяем, есть ли ключи, которые есть в эталонном файле, но отсутствуют у юзера
                val hasMissingKeys = defaultConfig.getKeys(true).any { !existingConfig.isSet(it) }

                if (hasMissingKeys) {
                    // Вызываем наш умный инжектор, чтобы сохранить структуру, комментарии и пробелы!
                    updateConfigPreservingStructure(originalFile, defaultConfig, existingConfig)
                    plugin.logger.info("Language file 'language.yml' has been successfully updated with missing keys!")
                }
            }
        }

        // Если выбран дефолтный язык (English) — пропускаем обращение к ИИ
        if (isDefaultLanguage(targetLanguage)) {
            plugin.logger.info("Target language is set to default ($targetLanguage). Skipping AI translation for language.yml.")
            return YamlConfiguration.loadConfiguration(originalFile)
        }

        if (client is DummyClient) {
            plugin.logger.info("Language.yml translation attempt failed. AI features is DISABLED in provider.yml!")
            return YamlConfiguration.loadConfiguration(originalFile)
        }

        val originalHash = computeHash(originalFile.readText())
        val cacheFile = cacheDir.resolve("translations/language_${targetLanguage}.yml")
        val hashFile = cacheDir.resolve("translations/language_${targetLanguage}_hash.txt")

        // Проверяем кэш
        if (cacheFile.exists() && hashFile.exists() && hashFile.readText() == originalHash) {
            return YamlConfiguration.loadConfiguration(cacheFile)
        }

        plugin.logger.info("Trying to translate language.yml.")
        // Попытка перевода ИИ.
        val translated = client.translate(YamlConfiguration.loadConfiguration(originalFile))
        if (translated != null) {
            cacheFile.parentFile.mkdirs()
            translated.save(cacheFile)
            hashFile.writeText(originalHash)
            return translated
        }

        plugin.logger.info("Language.yml translation attempt failed. Result is null. Default localization will be used.")
        return YamlConfiguration.loadConfiguration(originalFile)
    }

    /**
     * Translates a specific file (names.yml or phrases.yml) with state tracking.
     */
    fun translateFileWithState(sourceFile: File, relativePath: String): TranslationResult {
        // Если выбран дефолтный язык, пропускаем генерацию файлов
        if (isDefaultLanguage(targetLanguage)) {
            return TranslationResult.SKIPPED
        }

        val cacheFile = cacheDir.resolve("$relativePath.yml")
        val hashFile = cacheDir.resolve("${relativePath}_hash.txt")

        if (!sourceFile.exists()) return TranslationResult.ERROR

        val sourceText = sourceFile.readText()
        val sourceHash = computeHash(sourceText)

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

    /**
     * Читает оригинальный ресурс плагина как текст, чтобы сохранить все комментарии и отступы,
     * и подставляет в него значения, которые уже настроил пользователь.
     */
    private fun updateConfigPreservingStructure(file: File, defaultConfig: YamlConfiguration, existingConfig: YamlConfiguration) {
        val resourceStream = plugin.getResource("language.yml") ?: return
        val defaultLines = InputStreamReader(resourceStream, Charsets.UTF_8).readLines()
        val outLines = mutableListOf<String>()
        val pathStack = mutableListOf<Pair<Int, String>>()
        var skipIndent = -1

        for (line in defaultLines) {
            if (skipIndent != -1) {
                if (line.isBlank()) {
                    outLines.add(line)
                    continue
                }
                val matchKey = Regex("^(\\s*)([a-zA-Z0-9_-]+):").find(line)
                if (matchKey != null) {
                    val currentIndent = matchKey.groupValues[1].length
                    if (currentIndent <= skipIndent) skipIndent = -1
                    else continue
                } else {
                    continue
                }
            }

            val match = Regex("^(\\s*)([a-zA-Z0-9_-]+):(.*)$").find(line)
            if (match != null) {
                val indent = match.groupValues[1].length
                val keyName = match.groupValues[2]

                pathStack.removeAll { it.first >= indent }
                pathStack.add(indent to keyName)

                val currentPath = pathStack.joinToString(".") { it.second }

                if (existingConfig.isSet(currentPath) && !defaultConfig.isConfigurationSection(currentPath)) {
                    val temp = YamlConfiguration()
                    temp.set(keyName, existingConfig.get(currentPath))

                    var yamlStr = temp.saveToString().trimEnd()
                    val startIndex = yamlStr.indexOf("$keyName:")
                    if (startIndex != -1) yamlStr = yamlStr.substring(startIndex)

                    val replacedLines = yamlStr.lines().map { match.groupValues[1] + it }
                    outLines.addAll(replacedLines)

                    skipIndent = indent
                    continue
                }
            }
            outLines.add(line)
        }

        file.writeText(outLines.joinToString("\n"))
    }
}
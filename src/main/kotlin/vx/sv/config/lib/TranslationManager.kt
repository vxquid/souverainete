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
        // Если используется DummyClient (или лимит исчерпан), translated будет null
        val translated = client.translate(YamlConfiguration.loadConfiguration(originalFile))
        if (translated != null) {
            cacheFile.parentFile.mkdirs()
            translated.save(cacheFile)
            hashFile.writeText(originalHash)
            return translated
        }

        plugin.logger.info("Language.yml translation attempt failed. Result is null. Default localization will be used.")
        // Если ИИ отключен или недоступен (возвращен null) — просто возвращаем
        // локальный файл, в который УЖЕ добавлены все новые ключи на предыдущем шаге.
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
                TranslationResult.QUOTA_LIMIT // Обработка DummyClient возврата null (можно поменять на другой статус)
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
     * Если у юзера нет какого-то ключа, останется дефолтное значение из JAR.
     */
    private fun updateConfigPreservingStructure(file: File, defaultConfig: YamlConfiguration, existingConfig: YamlConfiguration) {
        val resourceStream = plugin.getResource("language.yml") ?: return
        val defaultLines = InputStreamReader(resourceStream, Charsets.UTF_8).readLines()
        val outLines = mutableListOf<String>()
        val pathStack = mutableListOf<Pair<Int, String>>()
        var skipIndent = -1

        for (line in defaultLines) {
            // Если мы находимся в процессе пропуска многострочного значения (например списков) из дефолтного файла
            if (skipIndent != -1) {
                if (line.isBlank()) {
                    outLines.add(line)
                    continue
                }
                val matchKey = Regex("^(\\s*)([a-zA-Z0-9_-]+):").find(line)
                if (matchKey != null) {
                    val currentIndent = matchKey.groupValues[1].length
                    if (currentIndent <= skipIndent) skipIndent = -1 // Вышли из узла
                    else continue // Продолжаем пропускать вложенные
                } else {
                    continue // Пропускаем элементы списка
                }
            }

            val match = Regex("^(\\s*)([a-zA-Z0-9_-]+):(.*)$").find(line)
            if (match != null) {
                val indent = match.groupValues[1].length
                val keyName = match.groupValues[2]

                // Поддерживаем корректный путь до ключа (например, raid.bossbar.init)
                pathStack.removeAll { it.first >= indent }
                pathStack.add(indent to keyName)

                val currentPath = pathStack.joinToString(".") { it.second }

                // Если ключ есть у юзера, и это финальное значение (не секция) - меняем строку
                if (existingConfig.isSet(currentPath) && !defaultConfig.isConfigurationSection(currentPath)) {
                    val temp = YamlConfiguration()
                    temp.set(keyName, existingConfig.get(currentPath))

                    var yamlStr = temp.saveToString().trimEnd()
                    val startIndex = yamlStr.indexOf("$keyName:")
                    if (startIndex != -1) yamlStr = yamlStr.substring(startIndex) // Защита от мусорных заголовков Bukkit

                    // Применяем отступ эталонного файла к многострочным значениям
                    val replacedLines = yamlStr.lines().map { match.groupValues[1] + it }
                    outLines.addAll(replacedLines)

                    skipIndent = indent
                    continue
                }
            }
            // Добавляем строку без изменений (будут сохранены пустые строки, комменты и новые ключи)
            outLines.add(line)
        }

        // Перезаписываем файл сохраненным текстом
        file.writeText(outLines.joinToString("\n"))
    }
}
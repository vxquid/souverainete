package vx.ignis.config.lib

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.config.lib.annotations.Comment
import vx.ignis.config.lib.annotations.Configuration
import vx.ignis.config.lib.annotations.Header
import vx.ignis.config.lib.annotations.Ignore
import java.io.File
import java.io.FileWriter
import java.io.StringWriter
import java.lang.reflect.Field
import java.lang.reflect.Modifier

@Suppress("UNCHECKED_CAST")
object ConfigurationManager {

    private val yaml = Yaml(DumperOptions().apply {
        isPrettyFlow = true
        indent = 2
        width = 80
    })

    fun <T : Any> load(configClass: Class<T>): T {
        val configInstance = configClass.getDeclaredConstructor().newInstance()
        val configPath = configClass.getAnnotation(Configuration::class.java)?.name ?: "config.yml"
        val configFile = plugin.dataFolder.resolve(configPath)

        if (!configFile.exists()) {
            configFile.parentFile?.mkdirs()
            configFile.createNewFile()
            save(configInstance)
            return configInstance
        }

        val loadedData = yaml.load<MutableMap<String, Any?>>(configFile.readText()) ?: mutableMapOf()

        val missing = applyToInstance(configInstance, loadedData, "")

        if (missing.isNotEmpty()) {
            val defaultData = toMap(configInstance)
            missing.forEach { key ->
                setNested(loadedData, key, getNested(defaultData, key))
            }
            saveMap(loadedData, configFile, configInstance)
        }

        return configInstance
    }

    fun save(config: Any) {
        val configPath = config::class.java.getAnnotation(Configuration::class.java)?.name ?: "config.yml"
        val configFile = plugin.dataFolder.resolve(configPath)
        val data = toMap(config)
        saveMap(data, configFile, config)
    }

    private fun applyToInstance(instance: Any, data: Map<String, Any?>, prefix: String): MutableList<String> {
        val missing = mutableListOf<String>()
        val fields = getFields(instance::class.java)
        fields.forEach { field ->
            val kebabField = toKebabCase(field.name)
            val fullKey = if (prefix.isEmpty()) kebabField else "$prefix.$kebabField"
            if (field.isAnnotationPresent(Ignore::class.java)) return@forEach

            field.isAccessible = true
            val value = data[kebabField]
            if (value == null) {
                missing.add(fullKey)
                return@forEach
            }

            if (isSimpleType(field.type)) {
                field.set(instance, convertValue(value, field.type))
            } else if (field.type == List::class.java) {
                field.set(instance, (value as? List<*>)?.map { convertValue(it, Any::class.java) } ?: emptyList<Any>())
            } else if (field.type == Map::class.java) {
                field.set(instance, value as? Map<*, *>)
            } else {
                val nestedInstance = field.get(instance) ?: field.type.getDeclaredConstructor().newInstance()
                field.set(instance, nestedInstance)
                val nestedPrefix = fullKey
                missing.addAll(applyToInstance(nestedInstance, value as? Map<String, Any?> ?: emptyMap(), nestedPrefix))
            }
        }
        return missing
    }

    private fun <T> convertValue(value: Any?, type: Class<T>): T? {
        if (value == null) return null
        return when (type) {
            Int::class.java -> (value as Number).toInt() as T
            Double::class.java -> (value as Number).toDouble() as T
            Float::class.java -> (value as Number).toFloat() as T
            Boolean::class.java -> value as Boolean as T
            String::class.java -> value.toString() as T
            else -> if (type.isEnum) type.enumConstants.first { it.toString() == value.toString() } as T else value as T
        }
    }

    private fun toMap(instance: Any): MutableMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val fields = getFields(instance::class.java)
        fields.forEach { field ->
            if (field.isAnnotationPresent(Ignore::class.java)) return@forEach
            field.isAccessible = true
            val value = field.get(instance)
            val kebabKey = toKebabCase(field.name)
            if (isSimpleType(field.type)) {
                map[kebabKey] = if (field.type.isEnum) value?.toString() else value
            } else if (field.type == List::class.java || field.type == Map::class.java) {
                map[kebabKey] = value
            } else {
                map[kebabKey] = toMap(value!!)
            }
        }
        return map
    }

    private fun saveMap(data: Map<String, Any?>, file: File, config: Any) {
        val writer = StringWriter()
        // Добавляем хэдэр в начало
        addHeader(writer, config)
        dumpWithComments(data, writer, config, 0)
        FileWriter(file).use { it.write(writer.toString()) }
    }

    private fun addHeader(writer: StringWriter, config: Any) {
        val headerAnnotation = config::class.java.getAnnotation(Header::class.java)
        if (headerAnnotation != null && headerAnnotation.comments.isNotEmpty()) {
            headerAnnotation.comments.forEach { line ->
                val prefixed = if (!line.trim().startsWith('#')) "# $line" else line
                writer.append(prefixed + "\n")
            }
        } else {
            val className = config::class.java.simpleName
            writer.append("# $className Configuration File\n")
        }
        writer.append("\n")
    }

    private fun dumpWithComments(data: Map<String, Any?>, writer: StringWriter, config: Any, indentLevel: Int) {
        val indent = "  ".repeat(indentLevel)

        // Добавляем class-level comments перед всей секцией
        config::class.java.getAnnotation(Comment::class.java)?.value?.forEach { comment ->
            val prefixed = if (!comment.trim().startsWith('#')) "# $comment" else comment
            writer.append("$indent$prefixed\n")
        }

        val fields = getFields(config::class.java) // Убрали .sortedBy { it.name } — теперь порядок как в объявлении полей
        fields.forEachIndexed { index, field ->
            if (field.isAnnotationPresent(Ignore::class.java)) return@forEachIndexed

            // Добавляем field-level comments
            field.getAnnotation(Comment::class.java)?.value?.forEach { comment ->
                val prefixed = if (!comment.trim().startsWith('#')) "# $comment" else comment
                writer.append("$indent$prefixed\n")
            }

            val originalKey = field.name
            val kebabKey = toKebabCase(originalKey)
            val value = data[kebabKey]
            writer.append("$indent$kebabKey: ")

            if (value is Map<*, *>) {
                writer.append("\n")
                field.isAccessible = true
                val nested = field.get(config)!!
                dumpWithComments(value as Map<String, Any?>, writer, nested, indentLevel + 1)
            } else if (value is List<*>) {
                writer.append("\n")
                value.forEach { item ->
                    writer.append("$indent  - ${yaml.dump(item).trim()}\n")
                }
            } else {
                writer.append(yaml.dump(value).trim() + "\n")
            }

            // Добавляем пустую строку между глобальными ключами (только на верхнем уровне)
            if (indentLevel == 0 && index < fields.size - 1) {
                writer.append("\n")
            }
        }
    }

    private fun toKebabCase(camelCase: String): String {
        return camelCase.split(Regex("(?=[A-Z])"))
            .joinToString("-") { it.lowercase() }
            .replace(Regex("-+"), "-") // Убираем лишние dashes, если есть
    }

    private fun getFields(clazz: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current = clazz
        while (current != Any::class.java) {
            fields.addAll(current.declaredFields.filter { !Modifier.isStatic(it.modifiers) })
            current = current.superclass ?: break
        }
        return fields
    }

    private fun isSimpleType(type: Class<*>): Boolean {
        return type.isPrimitive || type == String::class.java || Number::class.java.isAssignableFrom(type) ||
                Boolean::class.java == type || type.isEnum
    }

    private fun setNested(map: MutableMap<String, Any?>, key: String, value: Any?) {
        val parts = key.split(".")
        var current = map
        parts.dropLast(1).forEach { part ->
            current = current.computeIfAbsent(part) { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>
        }
        current[parts.last()] = value
    }

    private fun getNested(map: Map<String, Any?>, key: String): Any? {
        val parts = key.split(".")
        var current: Any? = map
        parts.forEach { part ->
            current = (current as? Map<String, Any?>)?.get(part)
        }
        return current
    }
}
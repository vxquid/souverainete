package vx.ignis.ai

import de.exlll.configlib.YamlConfigurations
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.properties
import vx.ignis.ai.base.AIClient
import vx.ignis.config.ProviderConfiguration
import vx.ignis.config.ProviderConfiguration.ProviderType
import java.io.File

class ProviderManager {

    val config: ProviderConfiguration = run {
        YamlConfigurations.update(File(plugin.dataFolder, "provider.yml").toPath(), ProviderConfiguration::class.java, properties)
        YamlConfigurations.load(File(plugin.dataFolder, "provider.yml").toPath(), ProviderConfiguration::class.java, properties)
    }

    val client: AIClient = run {

        val apiKeys = config.apiKey
        if (apiKeys[0] == "YOUR_API_KEY") {
            plugin.logger.severe("You must configure AI provider before using the plugin. Follow installation instructions.")
            plugin.server.pluginManager.disablePlugin(plugin)
            throw IllegalStateException("ACAI AI provider is not configured.")
        }

        plugin.logger.info("Selected AI provider is ${config.providerType}.")

        when (config.providerType) {
            ProviderType.GEMINI -> GeminiClient(keyManager = GeminiClient.KeyManager(apiKeys), config = config)
            ProviderType.OPENROUTER -> OpenRouterClient(apiKeys[0], config = config)
        }
    }

}
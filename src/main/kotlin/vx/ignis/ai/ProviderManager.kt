package vx.ignis.ai

import vx.ignis.Ignis.Companion.plugin
import vx.ignis.ai.base.AIClient
import vx.ignis.config.ProviderConfiguration
import vx.ignis.config.ProviderConfiguration.ProviderType
import vx.ignis.config.lib.ConfigurationManager

class ProviderManager {

    val config: ProviderConfiguration = ConfigurationManager.load(plugin, ProviderConfiguration::class.java)

    val client: AIClient = run {

        val apiKeys = config.apiKey
        if (apiKeys[0] == "YOUR_API_KEY") {
            plugin.logger.severe("You must configure AI provider before using the plugin. Follow installation instructions.")
            plugin.server.pluginManager.disablePlugin(plugin)
            throw IllegalStateException("Ignis provider is not configured.")
        }

        plugin.logger.info("Selected AI provider is ${config.providerType}.")

        when (config.providerType) {
            ProviderType.GEMINI -> GeminiClient(keyManager = GeminiClient.KeyManager(apiKeys), config = config)
            ProviderType.OPENROUTER -> OpenRouterClient(apiKeys[0], config = config)
        }
    }

}
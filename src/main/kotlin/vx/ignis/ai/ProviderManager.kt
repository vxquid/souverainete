package vx.ignis.ai

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.Ignis.Companion.sendFormattedMessage
import vx.ignis.ai.base.AIClient
import vx.ignis.config.ProviderConfiguration
import vx.ignis.config.ProviderConfiguration.ProviderType
import vx.ignis.config.lib.ConfigurationManager

class ProviderManager {

    val config: ProviderConfiguration = ConfigurationManager.load(plugin, ProviderConfiguration::class.java)

    val client: AIClient = run {

        val apiKey = listOf(config.apiKey)

        // Check if the API key is set to the default value
        if (config.apiKey == "YOUR_API_KEY") {
            // 1. Send console message about AI generation and configuration requirement
            plugin.logger.warning("Ignis can generate content using AI. For full plugin functionality, you must configure provider.yml.")

            // 3. Enter special state: Register a listener to notify OPs on join
            plugin.server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun onPlayerJoin(event: PlayerJoinEvent) {
                    if (event.player.isOp) {
                        event.player.sendFormattedMessage("AI provider is not configured! Please check provider.yml to enable AI features.")
                    }
                }
            }, plugin)

            // 2. Do not disable the plugin. instead, return a Placeholder/Dummy Client.
            return@run DummyClient()
        }

        plugin.logger.info("Selected AI provider is ${config.providerType}.")

        when (config.providerType) {
            ProviderType.GEMINI -> GeminiClient(keyManager = GeminiClient.KeyManager(apiKey), config = config)
            ProviderType.OPENROUTER -> OpenRouterClient(apiKey[0], config = config)
            ProviderType.GROQ -> GroqClient(keyManager = GroqClient.KeyManager(apiKey), config)
            ProviderType.DEEPSEEK -> DeepSeekClient(DeepSeekClient.KeyManager(apiKey), config)
            ProviderType.CHATGPT -> ChatGPTClient(ChatGPTClient.KeyManager(apiKey), config)
        }
    }

}
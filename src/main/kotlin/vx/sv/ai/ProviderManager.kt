package vx.sv.ai

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import vx.sv.Souverainete.Companion.plugin
import vx.sv.Souverainete.Companion.sendFormattedMessage
import vx.sv.ai.base.AIClient
import vx.sv.ai.base.DummyClient
import vx.sv.config.ProviderConfiguration
import vx.sv.config.ProviderConfiguration.ProviderType
import vx.sv.config.lib.ConfigurationManager

class ProviderManager {

    val config: ProviderConfiguration = ConfigurationManager.load(plugin, ProviderConfiguration::class.java)

    val client: AIClient = run {

        val apiKey = listOf(config.apiKey)

        // Check if the API key is set to the default value
        if (config.apiKey == "YOUR_API_KEY") {
            plugin.logger.warning("Souverainete can generate content using AI. For full plugin functionality, you must configure provider.yml.")

            plugin.server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun onPlayerJoin(event: PlayerJoinEvent) {
                    if (event.player.isOp) {
                        event.player.sendFormattedMessage("AI provider is not configured! Please check provider.yml to enable AI features.")
                    }
                }
            }, plugin)

            return@run DummyClient()
        }

        plugin.logger.info("Selected AI provider is ${config.providerType}.")

        when (config.providerType) {
            ProviderType.GEMINI -> GeminiClient(keyManager = GeminiClient.KeyManager(apiKey), config = config)
            ProviderType.OPENROUTER -> OpenRouterClient(apiKey[0], config = config)
            ProviderType.GROQ -> GroqClient(keyManager = GroqClient.KeyManager(apiKey), config)
            ProviderType.DEEPSEEK -> DeepSeekClient(DeepSeekClient.KeyManager(apiKey), config)
            ProviderType.CHATGPT -> ChatGPTClient(ChatGPTClient.KeyManager(apiKey), config)
            ProviderType.ANYTHINGLLM -> AnythingLLMClient(AnythingLLMClient.KeyManager(apiKey), config)
        }
    }
}
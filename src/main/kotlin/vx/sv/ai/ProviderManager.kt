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

class ProviderManager : Listener {

    lateinit var config: ProviderConfiguration
    lateinit var client: AIClient

    init {
        load()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /**
     * Loads the configuration from disk and initializes the AI Client.
     * Can be called multiple times to hot-reload settings.
     */
    fun load() {
        this.config = ConfigurationManager.load(plugin, ProviderConfiguration::class.java)
        this.client = createClient()
    }

    private fun createClient(): AIClient {
        val apiKey = listOf(config.apiKey)

        // Return a DummyClient if the key is default or empty
        if (config.apiKey == "YOUR_API_KEY" || config.apiKey.isBlank()) {
            return DummyClient()
        }

        plugin.logger.info("Initializing AI provider: ${config.providerType}")

        return when (config.providerType) {
            ProviderType.GEMINI -> GeminiClient(GeminiClient.KeyManager(apiKey), config)
            ProviderType.OPENROUTER -> OpenRouterClient(apiKey[0], config = config)
            ProviderType.GROQ -> GroqClient(GroqClient.KeyManager(apiKey), config)
            ProviderType.DEEPSEEK -> DeepSeekClient(DeepSeekClient.KeyManager(apiKey), config)
            ProviderType.CHATGPT -> ChatGPTClient(ChatGPTClient.KeyManager(apiKey), config)
            ProviderType.ANYTHINGLLM -> AnythingLLMClient(AnythingLLMClient.KeyManager(apiKey), config)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (event.player.isOp && (config.apiKey == "YOUR_API_KEY" || config.apiKey.isBlank())) {
            event.player.sendFormattedMessage("§cAI is not configured! Run §6/s setup §cto begin.")
        }
    }
}
package vx.sv.config

import vx.sv.config.lib.annotations.Comment
import vx.sv.config.lib.annotations.Configuration

@Configuration("provider.yml")
class ProviderConfiguration {

    @Comment(
        "The provider type for content generation. Choose between CEREBRAS, GEMINI, OPENROUTER, GROQ, DEEPSEEK, CHATGPT, ANYTHINGLLM.",
        "CEREBRAS (cloud.cerebras.ai) provides blazing fast inference for open-source models (like Llama 3) and offers a generous free tier. Recommended, used by default.",
        "GEMINI (aistudio.google.com) have a free tier, but since December 7 (2025), it's unusable because of too low request limitation (20 RPD).",
        "OPENROUTER (openrouter.ai) requires payment (with competitive pricing; however trial is available). The best option, but paid.",
        "GROQ (console.groq.com) is free but limited. Suitable for testing, but may be too limited for production (if we are talking about a free plan and large servers).",
        "DEEPSEEK (deepseek.com) is paid-only but the cheapest option.",
        "CHATGPT (chatgpt.com) is default OpenAI API.",
        "ANYTHINGLLM (useanything.com) - Self-hosted solution. Requires running the software locally or on a server. Fully private and free (depending on your local backend like Ollama)."
    )
    var providerType: ProviderType = ProviderType.CEREBRAS

    @Comment(
        "For CEREBRAS, default model is \"gpt-oss-120b\".",
        "For GEMINI, default model is \"gemini-2.5-flash-lite\".",
        "For OPENROUTER, default model is \"google/gemini-2.5-flash-lite\".",
        "For GROQ, I recommend \"openai/gpt-oss-120b\".",
        "For DEEPSEEK, default model is \"deepseek-chat\".",
        "For ANYTHINGLLM, this usually depends on your workspace settings. You can often leave it as \"gpt-3.5-turbo\" for compatibility."
    )
    var model = "gpt-oss-120b"

    @Comment(
        "The API Endpoint URL. Required ONLY for self-hosted providers like ANYTHINGLLM.",
        "Standard local address for AnythingLLM is \"http://localhost:3001\".",
        "If you are using cloud providers (Cerebras, Gemini, Groq, OpenRouter, etc.), this field is ignored."
    )
    var url: String = "http://localhost:3001"

    var apiKey: String = "YOUR_API_KEY"

    @Comment(
        "The language for generated content. Specify the desired language (e.g., 'English', 'Spanish', 'Russian', 'Dalek Language', 'Moonspeak', etc.)."
    )
    var language: String = "English"

    @Comment(
        "The naming convention for generated content. For example, 'English Names' for standard English-style names."
    )
    var namingStyle: String = "Fantasy Names"

    @Comment(
        "The thematic setting for content generation. For example, 'Fantasy' for a fantasy-themed world."
    )
    var setting: String = "Fantasy"

    @Comment(
        "Controls the randomness of generated content. Higher values (e.g., 2.0) increase creativity but may reduce coherence."
    )
    var temperature: Double = 2.0

    @Comment(
        "The maximum number of retry attempts after a failed content generation request."
    )
    var maxRetries: Int = 1

    @Comment(
        "Proxy configuration for connecting to providers in regions where they are restricted. Won't be used if host name is PROXY_HOST."
    )
    var proxy: Proxy = Proxy()

    @Configuration
    data class Proxy(
        @Comment("The type of proxy to use (e.g., HTTP, SOCKS).")
        var type: java.net.Proxy.Type = java.net.Proxy.Type.HTTP,

        @Comment("The proxy server hostname or IP address.")
        var host: String = "PROXY_HOST",

        @Comment("The port number for the proxy server.")
        var port: Int = 1337,

        @Comment("The username for proxy authentication, if required.")
        var user: String = "PROXY_USERNAME",

        @Comment("The password for proxy authentication, if required.")
        var pass: String = "PROXY_PASSWORD"
    )

    enum class ProviderType {
        CEREBRAS, GEMINI, OPENROUTER, GROQ, DEEPSEEK, CHATGPT, ANYTHINGLLM
    }

}
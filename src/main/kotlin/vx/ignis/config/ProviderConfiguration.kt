package vx.ignis.config

import vx.ignis.config.lib.annotations.Comment
import vx.ignis.config.lib.annotations.Configuration

@Configuration("provider.yml")
class ProviderConfiguration {

    @Comment(
        "The provider type for content generation. Choose between GEMINI or OPENROUTER.",
        "GEMINI is free, supports multiple API keys for automatic failover, but may be less stable.",
        "OPENROUTER supports any model available on openrouter.ai but requires payment (with competitive pricing; however trial is available). Much more stable."
    )
    var providerType: ProviderType = ProviderType.GEMINI

    @Comment(
        "List of API keys for the provider. For GEMINI, multiple keys can be used for automatic rotation upon quota limits.",
        "Note: Each GEMINI key requires a separate Google account (you may ask players for assistance in creating accounts).",
        "For OPENROUTER, only the first key in the list is used; additional keys are ignored."
    )
    var apiKey: List<String> = listOf("YOUR_API_KEY")

    @Comment(
        "The language for generated content. Specify the desired language (e.g., 'English', 'Spanish', 'Russian', 'Dalek Language', 'Moonspeak', etc.)."
    )
    var language: String = "English"

    @Comment(
        "The naming convention for generated content. For example, 'English Names' for standard English-style names."
    )
    var namingStyle: String = "Fantasy Names"

    @Comment(
        "Enables or disables the use of profanity in generated content. Set to true to allow swearing."
    )
    var swearing: Boolean = true

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
    var maxRetries: Int = 3

    @Comment(
        "Proxy configuration for connecting to Gemini in regions where it is restricted (e.g., Russia, China). Won't be used if host name is PROXY_HOST."
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
        GEMINI, OPENROUTER
    }

}
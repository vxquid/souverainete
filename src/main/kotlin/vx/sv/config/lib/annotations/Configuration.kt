package vx.sv.config.lib.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Configuration(val name: String = "config.yml")
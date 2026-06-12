package vx.sv.config.lib.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Header(vararg val comments: String)
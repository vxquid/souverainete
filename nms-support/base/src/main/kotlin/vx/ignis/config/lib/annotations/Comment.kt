package vx.ignis.config.lib.annotations

@Target(AnnotationTarget.FIELD, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Comment(vararg val value: String) // Комментарии, как в ConfigLib
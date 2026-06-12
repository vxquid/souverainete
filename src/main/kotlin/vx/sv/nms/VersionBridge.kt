package vx.sv.nms

import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.java.JavaPlugin
import vx.sv.nms.EntityProvider.Humanoid

class VersionBridge(val plugin: JavaPlugin) {

    // Монтируем провайдер напрямую без динамических проверок версий
    val entityProvider: EntityProvider = vx.sv.nms.v1_21_R7.VersionSpecificHumanoidEntityProvider(plugin)

    init {
        entityProvider.replaceEntityTypes()
        Companion.entityProvider = this.entityProvider
    }

    companion object {

        private lateinit var entityProvider: EntityProvider

        fun LivingEntity.asHumanoid() : Humanoid? {
            return try {
                entityProvider.asHumanoid(this)
            } catch (_: ClassCastException) {
                return null
            }
        }
    }
}
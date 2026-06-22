package vx.sv.nms

import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.java.JavaPlugin
import vx.sv.nms.entity.EntityProvider
import vx.sv.nms.entity.EntityProvider.Humanoid

class VersionBridge(val plugin: JavaPlugin) {

    // Монтируем провайдер напрямую без динамических проверок версий
    val entityProvider: EntityProvider = vx.sv.nms.VersionSpecificHumanoidEntityProvider(plugin)

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
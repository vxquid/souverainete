package vx.ignis.nms

import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.java.JavaPlugin
import vx.ignis.nms.EntityProvider.Humanoid

class VersionBridge(val plugin: JavaPlugin) {

    val entityProvider: EntityProvider = when {

        plugin.server.version.contains("1.21.11") -> {
            vx.ignis.nms.v1_21_R7.VersionSpecificHumanoidEntityProvider(plugin)
        }

        plugin.server.version.contains("1.21.10") -> {
            vx.ignis.nms.v1_21_R6.VersionSpecificHumanoidEntityProvider(plugin)
        }

        plugin.server.version.contains("1.21.1") -> {
            vx.ignis.nms.v1_21_R1.VersionSpecificHumanoidEntityProvider(plugin)
        }

        plugin.server.version.contains("1.21.4") -> {
            vx.ignis.nms.v1_21_R3.VersionSpecificHumanoidEntityProvider(plugin)
        }

        plugin.server.version.contains("1.21.8") -> {
            vx.ignis.nms.v1_21_R5.VersionSpecificHumanoidEntityProvider(plugin)
        }

        else -> {
            plugin.logger.info("Your server version is not supported. Yet...")
            throw IllegalStateException("Server version is not supported by plugin.")
        }

    }

    init {

        entityProvider.replaceEntityTypes()
        Companion.entityProvider = this.entityProvider

    }

    companion object {

        private lateinit var entityProvider: EntityProvider

        fun LivingEntity.asHumanoid() : Humanoid? {
            return try {
                entityProvider.asHumanoid(this)
            } catch (exception: ClassCastException) {
                exception.printStackTrace()
                return null
            }

        }
    }

}
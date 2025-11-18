package vx.ignis.nms.v1_21_R5

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.level.storage.PrimaryLevelData
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.java.JavaPlugin
import vx.ignis.nms.EntityProvider
import vx.ignis.nms.EntityProvider.Humanoid
import vx.ignis.nms.Reflection
import vx.ignis.nms.v1_21_R5.entity.HumanoidVillager
import java.lang.reflect.Field

class VersionSpecificHumanoidEntityProvider(override val plugin: JavaPlugin) : EntityProvider {

    init {
        Companion.plugin = this.plugin
    }

    override fun asHumanoid(entity: LivingEntity): Humanoid {
        return ((entity as CraftLivingEntity).handle as HumanoidVillager) as? Humanoid ?: run {
            throw ClassCastException("Entity ${entity.type} is not humanoid instance!")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun replaceEntityTypes() {
        try {
            val reflection = Reflection()
            val field: Field = EntityType::class.java.getDeclaredField("cc")
            reflection.setFieldUsingUnsafe(
                field,
                EntityType.VILLAGER,
                EntityType.EntityFactory { _, level ->
                    if (level.getLevelData() is PrimaryLevelData) {
                        return@EntityFactory HumanoidVillager(EntityType.VILLAGER, level)
                    }
                    Villager(EntityType.VILLAGER, level)
                })
        } catch (exception: ReflectiveOperationException) {
            exception.printStackTrace()
        }
    }

    companion object {
        lateinit var plugin: JavaPlugin
    }

}
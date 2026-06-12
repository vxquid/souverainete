package vx.sv.nms.v1_21_R7

import net.minecraft.world.entity.EntityType
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.java.JavaPlugin
import vx.sv.nms.EntityProvider
import vx.sv.nms.EntityProvider.Humanoid
import vx.sv.nms.Reflection
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import java.lang.reflect.Field

class VersionSpecificHumanoidEntityProvider(override val plugin: JavaPlugin) : EntityProvider {

    init {
        Companion.plugin = this.plugin
    }

    override fun asHumanoid(entity: LivingEntity): Humanoid? {
        return ((entity as CraftLivingEntity).handle as? HumanoidVillager) as? Humanoid
    }

    @Suppress("UNCHECKED_CAST")
    override fun replaceEntityTypes() {
        try {
            val reflection = Reflection()

            val field: Field = EntityType::class.java.declaredFields.firstOrNull {
                it.type == EntityType.EntityFactory::class.java
            } ?: throw NoSuchFieldException("EntityFactory field not found in EntityType")

            // ВАЖНО: Мы полностью убрали условие PrimaryLevelData.
            // Теперь HumanoidVillager спавнится абсолютно во всех мирах (Nether, End, кастомные миры),
            // и у всех жителей стабильно будет работать квестовое и диалоговое поведение.
            reflection.setFieldUsingUnsafe(
                field,
                EntityType.VILLAGER,
                EntityType.EntityFactory { _, level ->
                    HumanoidVillager(EntityType.VILLAGER, level)
                })
        } catch (exception: ReflectiveOperationException) {
            exception.printStackTrace()
        }
    }

    companion object {
        lateinit var plugin: JavaPlugin
    }

}
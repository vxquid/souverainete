package vx.sv.nms

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.java.JavaPlugin
import vx.sv.nms.entity.EntityProvider
import vx.sv.nms.entity.EntityProvider.Humanoid
import vx.sv.nms.entity.HumanoidVillager
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
                EntityTypes.VILLAGER,
                EntityType.EntityFactory { _, level ->
                    HumanoidVillager(EntityTypes.VILLAGER, level)
                })
        } catch (exception: ReflectiveOperationException) {
            exception.printStackTrace()
        }
    }

    companion object {
        lateinit var plugin: JavaPlugin
    }

}
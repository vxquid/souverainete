package vx.ignis.gameplay.humanoid

import com.github.retrooper.packetevents.PacketEvents
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.level.storage.PrimaryLevelData
import org.bukkit.World
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.entity.LivingEntity
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.entity.Humanoid
import vx.ignis.gameplay.humanoid.entity.HumanoidInfo
import vx.ignis.gameplay.humanoid.entity.HumanoidVillager
import vx.ignis.gameplay.humanoid.race.RaceManager
import vx.ignis.util.Reflection
import java.lang.reflect.Field

class HumanoidManager {

    val entityProvider   = EntityProvider()
    val raceManager      = RaceManager()
    val protocolListener = ProtocolListener()
    val equipmentManager = EquipmentManager()

    init {
        entityProvider.replaceEntityTypes()
        raceManager.loadRaces()
        PacketEvents.getAPI().eventManager.registerListener(protocolListener)
        plugin.server.pluginManager.registerEvents(protocolListener, plugin)
    }

    class EntityProvider {

        private val reflection = Reflection()

        @Suppress("UNCHECKED_CAST")
        fun replaceEntityTypes() {
            try {
                val field: Field = EntityType::class.java.getDeclaredField("cf")
                reflection.setFieldUsingUnsafe(
                    field,
                    EntityType.VILLAGER,
                    EntityType.EntityFactory { _, level ->
                        if (level.getLevelData() is PrimaryLevelData) {
                            return@EntityFactory HumanoidVillager(EntityType.VILLAGER, level)
                        }
                        Villager(EntityType.VILLAGER, level)
                    } as EntityType.EntityFactory<Villager?>)
            } catch (exception: ReflectiveOperationException) {
                exception.printStackTrace()
            }
        }

        fun asHumanoid(entity: LivingEntity): Humanoid {
            return ((entity as CraftLivingEntity).handle as HumanoidVillager) as? Humanoid ?: run {
                throw ClassCastException("Entity ${entity.type} is not humanoid instance!")
            }
        }

    }

    companion object {

        fun LivingEntity.asHumanoid() : Humanoid? {
            return try {
                 plugin.gameplayManager.humanoidManager.entityProvider.asHumanoid(this)
            } catch (exception: ClassCastException) {
                plugin.logger.warning("Pzzzt! Looks like developer of this plugin is an idiot.")
                exception.printStackTrace()
                return null
            }
        }

    }

}
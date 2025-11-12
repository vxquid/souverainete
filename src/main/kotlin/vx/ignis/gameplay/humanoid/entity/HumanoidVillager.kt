package vx.ignis.gameplay.humanoid.entity

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.mojang.datafixers.util.Pair
import com.mojang.serialization.Dynamic
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.Brain
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.ai.attributes.AttributeMap
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.behavior.Behavior
import net.minecraft.world.entity.ai.behavior.BehaviorControl
import net.minecraft.world.entity.ai.behavior.ShowTradesToPlayer
import net.minecraft.world.entity.ai.behavior.VillagerGoalPackages
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.ai.sensing.SensorType
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.npc.VillagerType
import net.minecraft.world.entity.schedule.Activity
import net.minecraft.world.entity.schedule.Schedule
import net.minecraft.world.level.Level
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.entity.behavior.LookAndFollowDuringConversation
import vx.ignis.util.Reflection
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class HumanoidVillager(type: EntityType<out Villager?>?, val level: Level?, villagerType: ResourceKey<VillagerType>) : Villager(type, level, villagerType), Humanoid {

    constructor(type: EntityType<out Villager?>?, level: Level?) : this(type, level, VillagerType.PLAINS)

    init {
        this.registerAttribute(this, Attributes.ATTACK_DAMAGE, 2.0)
        this.registerAttribute(this, Attributes.ATTACK_SPEED, 4.0)
        this.registerAttribute(this, Attributes.MAX_HEALTH, 20.0)
        this.refreshBrain(level as ServerLevel)
        this.setPersistenceRequired()
    }

    override fun makeBrain(dynamic: Dynamic<*>?): Brain<*> {
        val brain = brainProvider().makeBrain(dynamic)
        this.registerBrainGoals(brain)
        return brain
    }

    override fun brainProvider(): Brain.Provider<Villager> {
        return Brain.provider(ImmutableList.of(
            MemoryModuleType.HOME,
            MemoryModuleType.JOB_SITE,
            MemoryModuleType.POTENTIAL_JOB_SITE,
            MemoryModuleType.MEETING_POINT,
            MemoryModuleType.NEAREST_LIVING_ENTITIES,
            MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
            MemoryModuleType.VISIBLE_VILLAGER_BABIES,
            MemoryModuleType.NEAREST_PLAYERS,
            MemoryModuleType.NEAREST_VISIBLE_PLAYER,
            MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER,
            MemoryModuleType.WALK_TARGET,
            MemoryModuleType.LOOK_TARGET,
            MemoryModuleType.INTERACTION_TARGET,
            MemoryModuleType.BREED_TARGET,
            MemoryModuleType.PATH,
            MemoryModuleType.DOORS_TO_CLOSE,
            MemoryModuleType.NEAREST_BED,
            MemoryModuleType.HURT_BY,
            MemoryModuleType.HURT_BY_ENTITY,
            MemoryModuleType.NEAREST_HOSTILE,
            MemoryModuleType.SECONDARY_JOB_SITE,
            MemoryModuleType.HIDING_PLACE,
            MemoryModuleType.HEARD_BELL_TIME,
            MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
            MemoryModuleType.LAST_SLEPT,
            MemoryModuleType.LAST_WOKEN,
            MemoryModuleType.LAST_WORKED_AT_POI,
            MemoryModuleType.GOLEM_DETECTED_RECENTLY,
            MemoryModuleType.ATTACK_TARGET,
            MemoryModuleType.ATTACK_COOLING_DOWN
        ), ImmutableList.of(
            SensorType.NEAREST_BED,
            SensorType.HURT_BY,
            SensorType.VILLAGER_BABIES,
            SensorType.GOLEM_DETECTED,
            SensorType.NEAREST_PLAYERS,
            SensorType.NEAREST_LIVING_ENTITIES,
            SensorType.VILLAGER_HOSTILES,
            SensorType.NEAREST_ITEMS,
            SensorType.SECONDARY_POIS
        ))
    }

    override fun refreshBrain(level: ServerLevel?) {
        val brain = getBrain()
        brain.stopAll(level, this)
        this.brain = brain.copyWithoutBehaviors()
        this.brain.removeAllBehaviors()
        this.registerBrainGoals(getBrain())
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerAttribute(entity: LivingEntity, attribute: Holder<Attribute>, value: Double) {

        val methodHandle = Reflection.getField(
            AttributeMap::class.java,
            Map::class.java, "a", true, "attributes"
        ) ?: throw NullPointerException()

        try {
            val attributes = (methodHandle.invoke(entity.attributes) as? MutableMap<Holder<Attribute>, AttributeInstance> ?: return)
            attributes[attribute] = AttributeInstance(attribute) { it.attribute }
            entity.getAttribute(attribute)?.baseValue = value
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    // Первая попытка в кастомное поведение.
    private fun registerBrainGoals(brain: Brain<Villager>) {

        val profession = this.villagerData.profession
        if (this.isBaby) {
            brain.schedule = Schedule.VILLAGER_BABY
            brain.addActivity(Activity.PLAY, VillagerGoalPackages.getPlayPackage(0.5f))
        } else {
            brain.schedule = Schedule.VILLAGER_DEFAULT
            brain.addActivityWithConditions(
                Activity.WORK, VillagerGoalPackages.getWorkPackage(profession, 0.5f)
                    .remove(ShowTradesToPlayer::class), ImmutableSet.of(
                    Pair.of(MemoryModuleType.JOB_SITE, MemoryStatus.VALUE_PRESENT)
                )
            )
        }

        brain.addActivity(Activity.CORE, VillagerGoalPackages.getCorePackage(profession, 0.5f)
            .insert(2, LookAndFollowDuringConversation(0.65f) as Behavior<Villager>)
            .remove(ShowTradesToPlayer::class))

        brain.addActivityWithConditions(
            Activity.MEET, VillagerGoalPackages.getMeetPackage(profession, 0.5f)
                .remove(ShowTradesToPlayer::class), ImmutableSet.of(
                Pair.of(MemoryModuleType.MEETING_POINT, MemoryStatus.VALUE_PRESENT)
            )
        )

        brain.addActivity(Activity.REST, VillagerGoalPackages.getRestPackage(profession, 0.5f))

        brain.addActivity(Activity.IDLE, VillagerGoalPackages.getIdlePackage(profession, 0.5f)
            .remove(ShowTradesToPlayer::class))

        // brain.addActivity(Activity.PANIC, VillagerGoalPackages.getPanicPackage(profession, 0.5f))

        brain.addActivity(
            Activity.PRE_RAID,
            VillagerGoalPackages.getPreRaidPackage(profession, 0.5f)
        )

        brain.addActivity(Activity.RAID, VillagerGoalPackages.getRaidPackage(profession, 0.5f))
        brain.addActivity(Activity.HIDE, VillagerGoalPackages.getHidePackage(profession, 0.5f))
        brain.setCoreActivities(ImmutableSet.of(Activity.CORE))
        brain.setDefaultActivity(Activity.IDLE)
        brain.setActiveActivityIfPossible(Activity.IDLE)
        brain.updateActivityFromSchedule(level().dayTime, level().gameTime)
    }

    override fun consume(world: World, item: ItemStack, sound: Sound, duration: Int, location: Location, period: Long, onDone: () -> Unit) {
        val nmsItem = CraftItemStack.asNMSCopy(item)
        var ticks = 0
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            this.isNoAi = true
            this.setItemSlot(EquipmentSlot.MAINHAND, nmsItem)
            world.playSound(location, sound, 1F, 1F)
            ticks++; if (ticks >= duration) {
                this.setItemSlot(EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY)
                onDone.invoke()
                this.isNoAi = false
                task.cancel()
            }
        }, 0, period)
    }

    override fun equip(slot: org.bukkit.inventory.EquipmentSlot, item: ItemStack) {

        // Slot conversion. We can't skip that. :(
        val nmsSlot = when (slot) {
            org.bukkit.inventory.EquipmentSlot.HAND -> EquipmentSlot.MAINHAND
            org.bukkit.inventory.EquipmentSlot.OFF_HAND -> EquipmentSlot.OFFHAND
            org.bukkit.inventory.EquipmentSlot.HEAD -> EquipmentSlot.HEAD
            org.bukkit.inventory.EquipmentSlot.CHEST -> EquipmentSlot.CHEST
            org.bukkit.inventory.EquipmentSlot.LEGS -> EquipmentSlot.LEGS
            org.bukkit.inventory.EquipmentSlot.FEET -> EquipmentSlot.FEET
            org.bukkit.inventory.EquipmentSlot.BODY -> EquipmentSlot.BODY
            org.bukkit.inventory.EquipmentSlot.SADDLE -> EquipmentSlot.SADDLE
        }

        val nmsItem = CraftItemStack.asNMSCopy(item)

        this.setItemSlot(nmsSlot, nmsItem)

        val attackSpeed = item.itemMeta?.attributeModifiers?.get(org.bukkit.attribute.Attribute.ATTACK_SPEED)
        this.attributes.getInstance(Attributes.ATTACK_SPEED)?.baseValue = attackSpeed?.firstOrNull()?.amount ?: 0.25

    }

    override var talkingPlayer: Player? = null
        get() = field
        set(value) {
            field = value
        }

    private fun ImmutableList<Pair<Int, out BehaviorControl<in Villager>?>>.insert(priority: Int, behavior: Behavior<Villager>): ImmutableList<Pair<Int, out BehaviorControl<in Villager>?>> {
        return ImmutableList.copyOf(this.toMutableList().apply { add(Pair.of(priority, behavior)) }.sortedBy { it.first })
    }

    private fun ImmutableList<Pair<Int, out BehaviorControl<in Villager>?>>.remove(behavior: KClass<out BehaviorControl<in Villager>>): ImmutableList<Pair<Int, out BehaviorControl<in Villager>?>> {
        return ImmutableList.copyOf(this.filterNot { it.second?.javaClass == behavior.java }.sortedBy { it.first })
    }

}
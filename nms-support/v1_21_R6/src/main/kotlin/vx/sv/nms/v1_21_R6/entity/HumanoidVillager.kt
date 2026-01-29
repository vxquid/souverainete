package vx.sv.nms.v1_21_R6.entity

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.mojang.datafixers.util.Pair
import com.mojang.serialization.Dynamic
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
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
import net.minecraft.world.entity.monster.CrossbowAttackMob
import net.minecraft.world.entity.monster.RangedAttackMob
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.npc.VillagerType
import net.minecraft.world.entity.schedule.Activity
import net.minecraft.world.entity.schedule.Schedule
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ChargedProjectiles
import net.minecraft.world.level.Level
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import vx.sv.event.VillagerKillTargetEvent
import vx.sv.nms.EntityProvider.Humanoid
import vx.sv.nms.Reflection
import vx.sv.nms.v1_21_R6.VersionSpecificHumanoidEntityProvider.Companion.plugin
import vx.sv.nms.v1_21_R6.entity.ai.*
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
class HumanoidVillager(type: EntityType<out Villager>?, val level: Level?, villagerType: ResourceKey<VillagerType>) : Villager(type, level, villagerType), Humanoid, CrossbowAttackMob, RangedAttackMob {

    constructor(type: EntityType<out Villager>?, level: Level?) : this(type, level, VillagerType.PLAINS)

    // Хранит количество оставшихся ударов. Null означает "бить до смерти".
    private var attacksLeft: Int? = null

    init {
        this.registerAttribute(this, Attributes.ATTACK_DAMAGE, 2.0)
        this.registerAttribute(this, Attributes.ATTACK_SPEED, 4.0)
        this.registerAttribute(this, Attributes.MAX_HEALTH, 20.0)
        this.refreshBrain(level as ServerLevel)
        this.setPersistenceRequired()
    }

    // --- РЕАЛИЗАЦИЯ ИНТЕРФЕЙСА HUMANOID (Атака) ---

    override fun attack(target: org.bukkit.entity.LivingEntity) {
        this.attack(target, -1)
    }

    override fun attack(target: org.bukkit.entity.LivingEntity, maxStrikes: Int) {
        val nmsTarget = (target as CraftLivingEntity).handle

        // Устанавливаем квоту ударов
        this.attacksLeft = if (maxStrikes > 0) maxStrikes else null

        // Принудительно ставим память
        this.brain.setMemory(MemoryModuleType.ATTACK_TARGET, nmsTarget)

        // Сбрасываем память о том, что бой закончен, если она была
        this.brain.eraseMemory(MemoryModuleType.PACIFIED)
    }

    // Метод проверки квоты ударов. Вызывается после каждой успешной атаки.
    private fun consumeAttackQuota() {
        if (attacksLeft != null) {
            attacksLeft = attacksLeft!! - 1
            if (attacksLeft!! <= 0) {
                // Лимит исчерпан — забываем цель
                this.brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
                this.attacksLeft = null
                this.stopUsingItem() // Опустить оружие
                this.isAggressive = false
            }
        }
    }

    // Перехват ближнего боя
    override fun doHurtTarget(serverLevel: ServerLevel, target: Entity): Boolean {
        val result = super.doHurtTarget(serverLevel, target)
        if (result) {
            consumeAttackQuota()
        }
        return result
    }

    // --- КОНЕЦ БЛОКА АТАКИ ---

    override fun makeBrain(dynamic: Dynamic<*>): Brain<*> {
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

    override fun refreshBrain(level: ServerLevel) {
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

        // --- CORE (Базовые инстинкты, работают всегда) ---
        // 0 - высший приоритет.
        val corePackage = VillagerGoalPackages.getCorePackage(profession, 0.5f)
            .remove(ShowTradesToPlayer::class)

            // 1. Сначала ищем врагов
            .insert(0, FindEnemyBehavior(120.0) as Behavior<Villager>)

            // 2. БОЕВОЙ БЛОК. Система сама выберет то, условие чего вернет true.
            // Важно: Поведение арбалета и лука имеет проверку "isHolding(BOW)", поэтому оно не сработает, если в руке меч.
            .insert(1, CrossbowAttackBehavior(0.65f) as Behavior<Villager>)
            .insert(1, BowAttackBehavior(0.65f) as Behavior<Villager>)
            .insert(1, TacticalAttackBehavior(0.65f, 15) as Behavior<Villager>)

            // Следование за лидером (если есть)
            .insert(2, FollowLeaderBehavior(0.65f, 4.0f, 32.0f) as Behavior<Villager>)

            // 3. Разговор
            .insert(3, LookAndFollowDuringConversation(0.65f) as Behavior<Villager>)

        brain.addActivity(Activity.CORE, corePackage)

        // --- ОСТАЛЬНОЕ ---
        // Оставляем стандартные активности, они будут работать, пока нет врагов

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

        // Activity.FIGHT можно удалить или оставить пустым, так как мы перенесли бой в CORE

        brain.addActivityWithConditions(
            Activity.MEET, VillagerGoalPackages.getMeetPackage(profession, 0.5f)
                .remove(ShowTradesToPlayer::class), ImmutableSet.of(
                Pair.of(MemoryModuleType.MEETING_POINT, MemoryStatus.VALUE_PRESENT)
            )
        )

        brain.addActivity(Activity.REST, VillagerGoalPackages.getRestPackage(profession, 0.5f))

        brain.addActivity(Activity.IDLE, VillagerGoalPackages.getIdlePackage(profession, 0.5f)
            .remove(ShowTradesToPlayer::class))

        // Убираем панику, чтобы он не убегал
        // brain.addActivity(Activity.PANIC, VillagerGoalPackages.getPanicPackage(profession, 0.5f))

        brain.addActivity(Activity.PRE_RAID, VillagerGoalPackages.getPreRaidPackage(profession, 0.5f))
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
        val nmsSlot = when (slot) {
            org.bukkit.inventory.EquipmentSlot.HAND -> EquipmentSlot.MAINHAND
            org.bukkit.inventory.EquipmentSlot.OFF_HAND -> EquipmentSlot.OFFHAND
            org.bukkit.inventory.EquipmentSlot.HEAD -> EquipmentSlot.HEAD
            org.bukkit.inventory.EquipmentSlot.CHEST -> EquipmentSlot.CHEST
            org.bukkit.inventory.EquipmentSlot.LEGS -> EquipmentSlot.LEGS
            org.bukkit.inventory.EquipmentSlot.FEET -> EquipmentSlot.FEET
            org.bukkit.inventory.EquipmentSlot.BODY -> EquipmentSlot.BODY
            org.bukkit.inventory.EquipmentSlot.SADDLE -> EquipmentSlot.BODY
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

    override fun performRangedAttack(target: LivingEntity, velocity: Float) {
        val mainHandStack = this.mainHandItem
        val offHandStack = this.offhandItem

        val weaponStack = when {
            mainHandStack.`is`(Items.BOW) || mainHandStack.`is`(Items.CROSSBOW) -> mainHandStack
            offHandStack.`is`(Items.BOW) || offHandStack.`is`(Items.CROSSBOW) -> offHandStack
            else -> return
        }

        val dX = target.x - this.x
        val dZ = target.z - this.z
        val targetYaw = (Math.toDegrees(kotlin.math.atan2(dZ, dX)) - 90.0).toFloat()

        this.yRot = targetYaw
        this.yHeadRot = targetYaw
        this.yBodyRot = targetYaw
        this.hasImpulse = true // 1.21.10 specific

        val arrowEntity = net.minecraft.world.entity.projectile.Arrow(
            level(),
            this,
            net.minecraft.world.item.ItemStack(Items.ARROW),
            weaponStack
        )

        val dist = kotlin.math.sqrt(dX * dX + dZ * dZ)
        val targetHeightOffset = target.bbHeight * 0.6
        val targetY = target.y + targetHeightOffset
        val dY = targetY - arrowEntity.y

        if (weaponStack.`is`(Items.CROSSBOW)) {
            this.playSound(
                net.minecraft.sounds.SoundEvents.CROSSBOW_SHOOT,
                1.0f,
                1.0f / (this.getRandom().nextFloat() * 0.4f + 0.8f)
            )
            arrowEntity.shoot(dX, dY + dist * 0.05, dZ, 3.5f, 0.5f)
            weaponStack.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY)
            this.onCrossbowAttackPerformed()

        } else {
            this.playSound(
                net.minecraft.sounds.SoundEvents.SKELETON_SHOOT,
                1.0f,
                1.0f / (this.getRandom().nextFloat() * 0.4f + 0.8f)
            )
            val speed = velocity * 2.5f
            arrowEntity.shoot(dX, dY + dist * 0.1, dZ, speed, 1.0f)
        }

        this.level().addFreshEntity(arrowEntity)

        // Уменьшаем счетчик ударов при выстреле
        consumeAttackQuota()
    }

    override fun killedEntity(level: ServerLevel, victim: LivingEntity, damageSource: DamageSource): Boolean {
        val result = super.killedEntity(level, victim, damageSource)
        val bukkitVillager = this.bukkitEntity as? org.bukkit.entity.Villager
        val bukkitVictim = victim.bukkitEntity as? org.bukkit.entity.LivingEntity

        if (bukkitVillager != null && bukkitVictim != null) {
            val dSource = victim.lastDamageSource
            val killType = when {
                dSource != null && !dSource.isDirect -> VillagerKillTargetEvent.KillType.RANGED
                dSource != null && dSource.isDirect -> VillagerKillTargetEvent.KillType.MELEE
                else -> VillagerKillTargetEvent.KillType.OTHER
            }
            val event = VillagerKillTargetEvent(bukkitVillager, bukkitVictim, killType)
            Bukkit.getPluginManager().callEvent(event)
        }

        // Сбрасываем счетчик, если цель убита
        this.attacksLeft = null

        return result
    }

    // Связываем зарядку арбалета с флагом агрессии для визуализации
    override fun setChargingCrossbow(charging: Boolean) {
        this.isAggressive = charging
    }

    override fun canFireProjectileWeapon(weapon: net.minecraft.world.item.ProjectileWeaponItem): Boolean {
        return weapon == Items.BOW || weapon == Items.CROSSBOW || super.canFireProjectileWeapon(weapon)
    }

    override fun performCrossbowAttack(shooter: LivingEntity, velocity: Float) {
        this.onCrossbowAttackPerformed()
    }

    override fun onCrossbowAttackPerformed() {
        this.noActionTime = 0
    }
}
package vx.sv.nms.v1_21_R7.entity

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.mojang.datafixers.util.Pair
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.SimpleContainer
import net.minecraft.world.attribute.EnvironmentAttributes
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
import net.minecraft.world.entity.ai.behavior.BehaviorControl
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.monster.CrossbowAttackMob
import net.minecraft.world.entity.monster.RangedAttackMob
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerType
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.entity.schedule.Activity
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ChargedProjectiles
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.phys.Vec3
import org.bukkit.*
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import vx.sv.event.VillagerKillTargetEvent
import vx.sv.gameplay.humanoid.race.RaceManager.Companion.race
import vx.sv.gameplay.settlement.Settlement
import vx.sv.nms.EntityProvider.Humanoid
import vx.sv.nms.v1_21_R7.VersionSpecificHumanoidEntityProvider.Companion.plugin
import vx.sv.nms.v1_21_R7.entity.ai.*
import vx.sv.nms.v1_21_R7.entity.ai.construct.*
import vx.sv.persistent.LivingEntityExtend.settlement
import vx.sv.util.InventorySerializer
import vx.sv.util.VillagerBridge
import java.util.*
import kotlin.math.atan2
import kotlin.math.sqrt

class HumanoidVillager(
    type: EntityType<out Villager>?,
    level: Level?,
    villagerType: ResourceKey<VillagerType>
) : VillagerBridge(type!!, level!!, villagerType), Humanoid, CrossbowAttackMob, RangedAttackMob {

    constructor(type: EntityType<out Villager>, level: Level) : this(type, level, VillagerType.PLAINS)

    private var attacksLeft: Int? = null

    var activeBuildJob: SchematicBuildJob? = null
    var savedJobId: UUID? = null
    var assignedBlock: BlockToPlace? = null
    var previousMainHandItem: net.minecraft.world.item.ItemStack? = null
    var nextBuildAvailableTime: Long = 0L
    var digTicks: Int = 0
    var buildTicks: Int = 0
    var buildBreakUntilTime: Long = 0L
    var lastBuildActionTime: Long = 0L
    var isBuildDistanceHackActive: Boolean = false

    var lastPosition: Vec3? = null
    var stuckTicks: Int = 0

    init {
        this.registerAttribute(this, Attributes.ATTACK_DAMAGE, 2.0)
        this.registerAttribute(this, Attributes.ATTACK_SPEED, 4.0)
        this.registerAttribute(this, Attributes.MAX_HEALTH, 20.0)

        this.expandInventory(54)

        val bukkitVillager = this.bukkitEntity as? org.bukkit.entity.Villager
        if (bukkitVillager != null) {
            val pdc = bukkitVillager.persistentDataContainer
            val initKey = NamespacedKey(plugin, "InventoryInitialized")
            if (!pdc.has(initKey, PersistentDataType.BYTE)) {
                this.populateStarterItems(bukkitVillager)
            }
        }

        this.refreshBrain(level as ServerLevel)
        this.setPersistenceRequired()
    }

    private fun expandInventory(size: Int) {
        try {
            val inventoryField = AbstractVillager::class.java.declaredFields.firstOrNull {
                it.type == SimpleContainer::class.java
            } ?: return

            inventoryField.isAccessible = true
            val currentInv = inventoryField.get(this) as? SimpleContainer ?: return

            if (currentInv.containerSize != size) {
                val newInventory = SimpleContainer(size)
                for (i in 0 until minOf(currentInv.containerSize, size)) {
                    newInventory.setItem(i, currentInv.getItem(i))
                }
                inventoryField.set(this, newInventory)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun populateStarterItems(bukkitVillager: org.bukkit.entity.Villager) {
        val inv = bukkitVillager.inventory
        val pdc = bukkitVillager.persistentDataContainer
        val initKey = NamespacedKey(plugin, "InventoryInitialized")

        pdc.set(initKey, PersistentDataType.BYTE, 1.toByte())

        bukkitVillager.race.spawnItems.forEach { item -> inv.addItem(item.build()) }

        // ИСПРАВЛЕНО: Даем жителю стартовый набор сытной еды, чтобы они не умирали до завершения ферм
        inv.addItem(ItemStack(Material.BREAD, 32))
        inv.addItem(ItemStack(Material.BAKED_POTATO, 32))

        val weaponWeights = mapOf(
            Material.STONE_SWORD to 60,
            Material.BOW to 60,
            Material.CROSSBOW to 40,
            Material.IRON_SWORD to 25,
            Material.DIAMOND_SWORD to 5,
            Material.NETHERITE_SWORD to 1
        )

        val armorAndMiscWeights = mapOf(
            Material.LEATHER_HELMET to 80, Material.LEATHER_CHESTPLATE to 80,
            Material.LEATHER_LEGGINGS to 80, Material.LEATHER_BOOTS to 80,
            Material.CHAINMAIL_HELMET to 50, Material.CHAINMAIL_CHESTPLATE to 50,
            Material.CHAINMAIL_LEGGINGS to 50, Material.CHAINMAIL_BOOTS to 50,
            Material.SHIELD to 40,
            Material.IRON_HELMET to 20, Material.IRON_CHESTPLATE to 20,
            Material.IRON_LEGGINGS to 20, Material.IRON_BOOTS to 20,
            Material.DIAMOND_CHESTPLATE to 3, Material.NETHERITE_CHESTPLATE to 1
        )

        val globalPool = weaponWeights + armorAndMiscWeights

        fun pickWeighted(weights: Map<Material, Int>): ItemStack {
            val totalWeight = weights.values.sum()
            var random = (0 until totalWeight).random()

            for ((material, weight) in weights) {
                random -= weight
                if (random < 0) return ItemStack(material)
            }
            return ItemStack(weights.keys.last())
        }

        inv.addItem(pickWeighted(weaponWeights))

        val rollCountWeights = mapOf(
            0 to 10,
            1 to 30,
            2 to 40,
            3 to 15,
            4 to 5
        )

        val totalRollsWeight = rollCountWeights.values.sum()
        var randomRoll = (0 until totalRollsWeight).random()
        var itemsCount = 0
        for ((count, weight) in rollCountWeights) {
            randomRoll -= weight
            if (randomRoll < 0) {
                itemsCount = count
                break
            }
        }

        repeat(itemsCount) {
            inv.addItem(pickWeighted(globalPool))
        }
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)

        this.expandInventory(54)

        val bukkitVillager = this.bukkitEntity as? org.bukkit.entity.Villager
        if (bukkitVillager != null) {
            val pdc = bukkitVillager.persistentDataContainer

            val legacyKey = NamespacedKey(plugin, "Inventory")
            if (pdc.has(legacyKey, PersistentDataType.STRING)) {
                val jsonStr = pdc.get(legacyKey, PersistentDataType.STRING)
                if (!jsonStr.isNullOrEmpty()) {
                    try {
                        val legacyInv = InventorySerializer.inventoryFromJSON(jsonStr)
                        val nativeInv = bukkitVillager.inventory
                        for (i in 0 until minOf(legacyInv.size, nativeInv.size)) {
                            val item = legacyInv.getItem(i)
                            if (item != null) {
                                nativeInv.setItem(i, item)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                pdc.remove(legacyKey)
                pdc.set(NamespacedKey(plugin, "InventoryInitialized"), PersistentDataType.BYTE, 1.toByte())
            }

            val initKey = NamespacedKey(plugin, "InventoryInitialized")
            if (!pdc.has(initKey, PersistentDataType.BYTE)) {
                pdc.set(initKey, PersistentDataType.BYTE, 1.toByte())
            }

            val jobUuidKey = NamespacedKey(plugin, "active_build_job_uuid")
            if (pdc.has(jobUuidKey, PersistentDataType.STRING)) {
                val uuidStr = pdc.get(jobUuidKey, PersistentDataType.STRING)
                if (!uuidStr.isNullOrEmpty()) {
                    try {
                        this.savedJobId = UUID.fromString(uuidStr)
                    } catch (e: Exception) {
                        pdc.remove(jobUuidKey)
                    }
                }
            }

        }
    }

    override fun attack(target: org.bukkit.entity.LivingEntity) {
        this.attack(target, -1)
    }

    override fun attack(target: org.bukkit.entity.LivingEntity, maxStrikes: Int) {
        val nmsTarget = (target as CraftLivingEntity).handle
        this.attacksLeft = if (maxStrikes > 0) maxStrikes else null
        this.brain.setMemory(MemoryModuleType.ATTACK_TARGET, nmsTarget)
        this.brain.eraseMemory(MemoryModuleType.PACIFIED)
    }

    private fun consumeAttackQuota() {
        if (attacksLeft != null) {
            attacksLeft = attacksLeft!! - 1
            if (attacksLeft!! <= 0) {
                this.brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
                this.attacksLeft = null
                this.stopUsingItem()
                this.isAggressive = false
            }
        }
    }

    override fun doHurtTarget(serverLevel: ServerLevel, target: Entity): Boolean {
        val result = super.doHurtTarget(serverLevel, target)
        if (result) consumeAttackQuota()
        return result
    }

    override fun makeBrain(packedBrain: Brain.Packed): Brain<Villager> {
        val brain = super.makeBrain(packedBrain)

        try {
            val behaviorsField = Brain::class.java.getDeclaredField("availableBehaviorsByPriority")
            behaviorsField.isAccessible = true

            val availableBehaviors = behaviorsField.get(brain) as? Map<*, *>

            if (availableBehaviors != null) {
                for (priorityMap in availableBehaviors.values) {
                    if (priorityMap is MutableMap<*, *>) {
                        priorityMap.remove(Activity.PANIC)

                        for (activityBehaviors in priorityMap.values) {
                            if (activityBehaviors is MutableSet<*>) {
                                activityBehaviors.removeIf { behavior ->
                                    behavior != null && behavior.javaClass.name.contains("ShowTradesToPlayer")
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        val customCoreBehaviors = ImmutableList.of(
            Pair.of(0, FindEnemyBehavior(120.0) as BehaviorControl<Villager>),
            Pair.of(1, CrossbowAttackBehavior(0.65f) as BehaviorControl<Villager>),
            Pair.of(1, BowAttackBehavior(0.65f) as BehaviorControl<Villager>),
            Pair.of(1, TacticalAttackBehavior(0.65f, 15) as BehaviorControl<Villager>),

            // Взаимоисключающие поведения приоритета 2
            Pair.of(2, BuildBreakBehavior(0.6f) as BehaviorControl<Villager>),
            Pair.of(2, ConstructionBehavior(0.65f) as BehaviorControl<Villager>),
            Pair.of(2, ShepherdBehavior(0.65f) as BehaviorControl<Villager>),
            Pair.of(2, ButcherBehavior(0.65f) as BehaviorControl<Villager>),

            // ИСПРАВЛЕНО: Интегрирован ИИ Шахтёра под приоритет 2
            Pair.of(2, MinerBehavior(0.65f) as BehaviorControl<Villager>),

            Pair.of(3, FollowLeaderBehavior(0.65f, 4.0f, 32.0f) as BehaviorControl<Villager>),
            Pair.of(4, LookAndFollowDuringConversation(0.65f) as BehaviorControl<Villager>)
        )

        brain.addActivity(
            Activity.CORE,
            customCoreBehaviors,
            ImmutableSet.of<Pair<MemoryModuleType<*>, MemoryStatus>>(),
            ImmutableSet.of<MemoryModuleType<*>>()
        )
        return brain
    }

    override fun refreshBrain(level: ServerLevel) {
        @Suppress("UNCHECKED_CAST")
        val oldBrain = this.brain as Brain<Villager>
        oldBrain.stopAll(level, this)
        val newBrain = this.makeBrain(oldBrain.pack() as Brain.Packed)
        this.brain = newBrain

        this.registerBrainGoals(newBrain)
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerAttribute(entity: LivingEntity, attribute: Holder<Attribute>, value: Double) {
        try {
            val attributesMapField = AttributeMap::class.java.declaredFields.firstOrNull {
                Map::class.java.isAssignableFrom(it.type)
            } ?: return

            attributesMapField.isAccessible = true
            val currentMap = attributesMapField.get(entity.attributes) as? Map<Holder<Attribute>, AttributeInstance> ?: return

            val newMap = HashMap(currentMap)
            if (!newMap.containsKey(attribute)) {
                newMap[attribute] = AttributeInstance(attribute) { it.attribute }
            }
            attributesMapField.set(entity.attributes, newMap)

            entity.getAttribute(attribute)?.baseValue = value
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun registerBrainGoals(brain: Brain<Villager>) {
        if (this.isBaby) {
            brain.setSchedule(EnvironmentAttributes.BABY_VILLAGER_ACTIVITY)
        } else {
            brain.setSchedule(EnvironmentAttributes.VILLAGER_ACTIVITY)
        }
        brain.updateActivityFromSchedule(this.level().environmentAttributes(), this.level().getGameTime(), this.position())
    }

    override fun consume(world: World, item: ItemStack, sound: Sound, duration: Int, location: Location, period: Long, onDone: () -> Unit) {
        val nmsItem = CraftItemStack.asNMSCopy(item)
        var ticks = 0

        this.setItemInHand(InteractionHand.MAIN_HAND, nmsItem)
        this.startUsingItem(InteractionHand.MAIN_HAND)

        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            this.navigation.stop()
            this.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            this.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)

            world.playSound(location, sound, 1F, 1F)
            ticks++
            if (ticks >= duration) {
                this.stopUsingItem()
                this.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
                onDone.invoke()
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
            else -> EquipmentSlot.MAINHAND
        }
        val nmsItem = CraftItemStack.asNMSCopy(item)
        this.setItemSlot(nmsSlot, nmsItem)

        val attackSpeed = item.itemMeta?.attributeModifiers?.get(org.bukkit.attribute.Attribute.ATTACK_SPEED)
        this.attributes.getInstance(Attributes.ATTACK_SPEED)?.baseValue = attackSpeed?.firstOrNull()?.amount ?: 0.25
    }

    override var talkingPlayer: Player? = null

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
        val targetYaw = (Math.toDegrees(atan2(dZ, dX)) - 90.0).toFloat()

        this.yRot = targetYaw
        this.yHeadRot = targetYaw
        this.yBodyRot = targetYaw
        this.hurtMarked = true

        val arrowEntity = Arrow(level(), this, net.minecraft.world.item.ItemStack(Items.ARROW), weaponStack)
        val dist = sqrt(dX * dX + dZ * dZ)
        val targetHeightOffset = target.bbHeight * 0.6
        val targetY = target.y + targetHeightOffset
        val dY = targetY - arrowEntity.y

        if (weaponStack.`is`(Items.CROSSBOW)) {
            this.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0f, 1.0f / (this.random.nextFloat() * 0.4f + 0.8f))
            arrowEntity.shoot(dX, dY + dist * 0.05, dZ, 3.5f, 0.5f)
            weaponStack.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY)
            this.onCrossbowAttackPerformed()
        } else {
            this.playSound(SoundEvents.SKELETON_SHOOT, 1.0f, 1.0f / (this.random.nextFloat() * 0.4f + 0.8f))
            val speed = velocity * 2.5f
            arrowEntity.shoot(dX, dY + dist * 0.1, dZ, speed, 1.0f)
        }

        this.level().addFreshEntity(arrowEntity)

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

        this.attacksLeft = null

        return result
    }

    override fun setChargingCrossbow(charging: Boolean) {
        this.isAggressive = charging
    }

    override fun performCrossbowAttack(shooter: LivingEntity, velocity: Float) {
        this.onCrossbowAttackPerformed()
    }

    override fun onCrossbowAttackPerformed() {
        this.noActionTime = 0
    }

    val settlement: Settlement?
        get() = (this.bukkitEntity as org.bukkit.entity.Villager).settlement

}
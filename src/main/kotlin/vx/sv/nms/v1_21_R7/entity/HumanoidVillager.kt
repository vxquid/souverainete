package vx.sv.nms.v1_21_R7.entity

// Импорты для навигации и плавания
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.mojang.datafixers.util.Pair
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
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
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
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
import net.minecraft.world.level.pathfinder.PathType
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

        // Устанавливаем высоту шага 1.0 блок, чтобы жители могли заходить на сушу
        this.registerAttribute(this, Attributes.STEP_HEIGHT, 1.0)

        this.expandInventory(54)

        // ИСПРАВЛЕНО: Предотвращаем усыпление жителя ядром Spigot Entity Activation Range (EAR)
        this.activatedTick = java.lang.Long.MAX_VALUE

        // Настройка плавания и навигации в воде
        this.moveControl = VillagerSwimMoveControl(this)

        // ИСПРАВЛЕНО: Возвращаем штраф воде (8.0), чтобы житель стремился выйти на берег и не лез в пещеры
        this.setPathfindingMalus(PathType.WATER, 8.0F)

        val bukkitVillager = this.bukkitEntity as? org.bukkit.entity.Villager
        if (bukkitVillager != null) {
            val pdc = bukkitVillager.persistentDataContainer
            val initKey = NamespacedKey(plugin, "InventoryInitialized")
            if (!pdc.has(initKey, PersistentDataType.BYTE)) {
                this.populateStarterItems(bukkitVillager)
            }

            // ИСПРАВЛЕНО: Отключаем коллизию, чтобы жители могли беспрепятственно проходить сквозь друг друга и не забивали проходы/двери
            bukkitVillager.isCollidable = false

            // АКТИВИРУЕМ ПОДНЯТИЕ ПРЕДМЕТОВ
            bukkitVillager.canPickupItems = true
        }

        this.refreshBrain(level as ServerLevel)
        this.setPersistenceRequired()
    }

    override fun createNavigation(level: Level): PathNavigation {
        return AmphibiousPathNavigation(this, level)
    }

    fun wantsToSwim(): Boolean {
        // Плывем горизонтально только тогда, когда глубина воды под нами превышает 0.5 блока
        return this.isInWater && this.getFluidHeight(FluidTags.WATER) > 0.5
    }

    override fun isPushedByFluid(): Boolean {
        return !this.isSwimming
    }

    override fun travelInWater(input: Vec3, baseGravity: Double, isFalling: Boolean, oldY: Double) {
        if (this.isInWater && this.wantsToSwim()) {
            this.moveRelative(0.01F, input)
            this.move(net.minecraft.world.entity.MoverType.SELF, this.deltaMovement)
            this.deltaMovement = this.deltaMovement.scale(0.9)
        } else {
            super.travelInWater(input, baseGravity, isFalling, oldY)
        }
    }

    override fun updateSwimming() {
        if (!this.level().isClientSide) {
            val swimming = this.isEffectiveAi() && this.wantsToSwim()
            this.isSwimming = swimming

            if (swimming) {
                this.setPose(net.minecraft.world.entity.Pose.SWIMMING)
            } else if (this.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
                this.setPose(net.minecraft.world.entity.Pose.STANDING)
            }
        }
    }

    override fun isVisuallySwimming(): Boolean {
        return this.isSwimming && !this.isPassenger()
    }

    override fun aiStep() {
        super.aiStep()

        // === ЗАПРЕТ СОЦИАЛИЗАЦИИ ВО ВРЕМЯ ПЛАВАНИЯ ===
        if (this.isInWater && this.wantsToSwim()) {
            if (this.brain.hasMemoryValue(MemoryModuleType.INTERACTION_TARGET)) {
                this.brain.eraseMemory(MemoryModuleType.INTERACTION_TARGET)
            }
            if (this.brain.hasMemoryValue(MemoryModuleType.BREED_TARGET)) {
                this.brain.eraseMemory(MemoryModuleType.BREED_TARGET)
            }
            if (this.talkingPlayer != null) {
                this.talkingPlayer = null
            }
        }

        // === ЭКСТРЕННОЕ СПАСЕНИЕ ПРИ НЕХВАТКЕ КИСЛОРОДА (ТОНУЩИЙ ЖИТЕЛЬ) ===
        if (this.isInWater && this.airSupply < 150) {
            // Экстренно прерываем текущие ИИ-таски навигации и атаки, чтобы житель плыл спасаться
            this.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
            this.brain.eraseMemory(MemoryModuleType.PATH)
            this.brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)

            if (this.isUnderWater()) {
                val lift = 0.04 // Плавный и реалистичный импульс всплытия (вместо 0.12)
                val currentPos = this.blockPosition()
                var bestAirDirection: Vec3? = null

                // Поиск ближайшего блока воздуха в радиусе 3х3 (чтобы житель плыл к берегу, если он под потолком)
                for (xOffset in -3..3) {
                    for (zOffset in -3..3) {
                        val checkPos = currentPos.offset(xOffset, 1, zOffset)
                        if (this.level().getBlockState(checkPos).isAir) {
                            bestAirDirection = Vec3(xOffset.toDouble(), 0.0, zOffset.toDouble()).normalize()
                            break
                        }
                    }
                    if (bestAirDirection != null) break
                }

                // Применяем импульс движения вверх и в сторону берега/воздуха
                if (bestAirDirection != null) {
                    this.deltaMovement = this.deltaMovement.add(bestAirDirection.x * 0.015, lift, bestAirDirection.z * 0.015)
                } else {
                    this.deltaMovement = this.deltaMovement.add(0.0, lift, 0.0)
                }
            }
        }

        // === ИЩЕМ СУШУ, ЕСЛИ ЖИТЕЛЬ БЕЗДЕЙСТВУЕТ В ВОДЕ ===
        if (this.tickCount % 20 == 0 && this.isInWater) {
            val isIdle = this.activeBuildJob == null &&
                    this.assignedBlock == null &&
                    this.talkingPlayer == null &&
                    !this.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET) &&
                    !this.brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)

            if (isIdle) {
                // Ищем сушу методом направленного лучевого сканирования (до 48 блоков!)
                val landPos = this.findNearestDryLandRaycast(48)
                if (landPos != null) {
                    val walkTarget = net.minecraft.world.entity.ai.memory.WalkTarget(
                        landPos,
                        0.65F, // Нормальная скорость перемещения к берегу
                        1     // Дистанция приближения к целевой точке
                    )
                    this.brain.setMemory(MemoryModuleType.WALK_TARGET, walkTarget)
                }
            }
        }

        // === АВТОМАТИЧЕСКИЙ СБОР ПРЕДМЕТОВ С ЗЕМЛИ ===
        val bukkitNpc = this.bukkitEntity as? org.bukkit.entity.Villager ?: return
        if (this.settlement == null) return // Затягиваем ресурсы только если житель состоит в поселении

        val currentLoc = bukkitNpc.location
        val bukkitWorld = bukkitNpc.world

        val nearbyItems = bukkitWorld.getEntitiesByClass(org.bukkit.entity.Item::class.java).filter { item ->
            item.isValid && !item.isDead && item.location.distanceSquared(currentLoc) <= 16.0 // в радиусе 4 блоков
        }
        for (item in nearbyItems) {
            val itemLoc = item.location
            val distanceSq = itemLoc.distanceSquared(currentLoc)
            if (distanceSq <= 2.25) { // 1.5 блока — критическая близость
                val pickupEvent = org.bukkit.event.entity.EntityPickupItemEvent(
                    bukkitNpc,
                    item,
                    0
                )
                Bukkit.getPluginManager().callEvent(pickupEvent)
                if (pickupEvent.isCancelled) {
                    bukkitNpc.playPickupItemAnimation(item, 1)
                }
            } else {
                // Притягиваем предмет к жителю с легким ускорением в воздухе
                val vector = currentLoc.toVector().subtract(itemLoc.toVector()).normalize().multiply(0.2)
                item.velocity = vector
            }
        }
    }

    private fun findNearestDryLandRaycast(maxDistance: Int): net.minecraft.core.BlockPos? {
        val startPos = this.blockPosition()
        val level = this.level()

        // 8 векторов направлений (без конфликтов импорта Pair благодаря синтаксису "to" в Kotlin)
        val directions = listOf(
            0 to 1, 0 to -1, 1 to 0, -1 to 0,
            1 to 1, 1 to -1, -1 to 1, -1 to -1
        )

        var closestLand: net.minecraft.core.BlockPos? = null
        var closestDistanceSq = Double.MAX_VALUE

        for (dir in directions) {
            val dx = dir.first
            val dz = dir.second

            for (dist in 1..maxDistance) {
                // Сканируем только узкий Y-диапазон, чтобы не тратить процессорное время
                for (dy in -2..2) {
                    val checkPos = startPos.offset(dx * dist, dy, dz * dist)
                    val blockBelow = level.getBlockState(checkPos.below())
                    val fluidBelow = level.getFluidState(checkPos.below())
                    val blockCurrent = level.getBlockState(checkPos)
                    val fluidCurrent = level.getFluidState(checkPos)
                    val blockAbove = level.getBlockState(checkPos.above())

                    // Наша стандартная проверка на безопасный и сухой блок суши
                    if (!blockBelow.isAir && !fluidBelow.`is`(net.minecraft.tags.FluidTags.WATER) &&
                        blockCurrent.isAir && !fluidCurrent.`is`(net.minecraft.tags.FluidTags.WATER) &&
                        blockAbove.isAir) {

                        val distSq = checkPos.distSqr(startPos).toDouble()
                        if (distSq < closestDistanceSq) {
                            closestDistanceSq = distSq
                            closestLand = checkPos
                        }
                        break
                    }
                }
                // Если мы уже нашли берег на этом направлении, нет смысла продолжать пускать луч дальше
                if (closestLand != null && closestDistanceSq <= (dist * dist).toDouble()) {
                    break
                }
            }
        }

        // Если лучевой поиск на расстоянии 48 блоков ничего не дал (например в океане), используем локальный 3D поиск
        if (closestLand == null) {
            return findNearestDryLand(8)
        }

        return closestLand
    }

    private fun findNearestDryLand(radius: Int): net.minecraft.core.BlockPos? {
        val startPos = this.blockPosition()
        val level = this.level()
        var closestLand: net.minecraft.core.BlockPos? = null
        var closestDistanceSq = Double.MAX_VALUE

        for (x in -radius..radius) {
            for (y in -2..2) { // Ограничиваем Y-диапазон локального поиска для производительности
                for (z in -radius..radius) {
                    val checkPos = startPos.offset(x, y, z)
                    val blockBelow = level.getBlockState(checkPos.below())
                    val fluidBelow = level.getFluidState(checkPos.below())
                    val blockCurrent = level.getBlockState(checkPos)
                    val fluidCurrent = level.getFluidState(checkPos)
                    val blockAbove = level.getBlockState(checkPos.above())

                    if (!blockBelow.isAir && !fluidBelow.`is`(net.minecraft.tags.FluidTags.WATER) &&
                        blockCurrent.isAir && !fluidCurrent.`is`(net.minecraft.tags.FluidTags.WATER) &&
                        blockAbove.isAir) {

                        val distSq = checkPos.distSqr(startPos).toDouble()
                        if (distSq < closestDistanceSq) {
                            closestDistanceSq = distSq
                            closestLand = checkPos
                        }
                    }
                }
            }
        }
        return closestLand
    }

    override fun inactiveTick() {
        // ИСПРАВЛЕНО: Если игроков нет рядом, Spigot пытается усыпить жителя через этот метод.
        // Мы пресекаем это, выставляя максимальные тики активации и принудительно вызывая полноценный tick().
        this.activatedTick = java.lang.Long.MAX_VALUE
        this.tick()
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

        // ИСПРАВЛЕНО: Гарантируем обход Spigot EAR после загрузки сущности из файла сохранения
        this.activatedTick = java.lang.Long.MAX_VALUE

        val bukkitVillager = this.bukkitEntity as? org.bukkit.entity.Villager
        if (bukkitVillager != null) {
            // ИСПРАВЛЕНО: Гарантируем отключение коллизии после загрузки сущности из файла сохранения
            bukkitVillager.isCollidable = false

            // АКТИВИРУЕМ ПОДНЯТИЕ ПРЕДМЕТОВ ПОСЛЕ ЗАГРУЗКИ
            bukkitVillager.canPickupItems = true

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
                                    behavior != null && (
                                            behavior.javaClass.name.contains("ShowTradesToPlayer") ||
                                                    // ВЫРЕЗАЕМ ИИ-ПОВЕДЕНИЕ SWIM, КОТОРОЕ СПАМИТ ПРЫЖКАМИ И ПОРТИТ АНИМАЦИЮ
                                                    behavior is net.minecraft.world.entity.ai.behavior.Swim<*>
                                            )
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

            Pair.of(2, BuildBreakBehavior(0.6f) as BehaviorControl<Villager>),

            // ИСПРАВЛЕНО: Регистрируем кастомное поведение сна в ядре AI
            Pair.of(2, SleepBehavior(0.65f) as BehaviorControl<Villager>),

            // ИСПРАВЛЕНО: Регистрируем кастомное поведение фермера в ядре AI
            Pair.of(2, FarmerBehavior(0.65f) as BehaviorControl<Villager>),

            Pair.of(2, ConstructionBehavior(0.65f) as BehaviorControl<Villager>),
            Pair.of(2, ShepherdBehavior(0.65f) as BehaviorControl<Villager>),
            Pair.of(2, ButcherBehavior(0.65f) as BehaviorControl<Villager>),

            Pair.of(2, MinerBehavior(0.65f) as BehaviorControl<Villager>),

            Pair.of(3, FindEnemyBehavior(120.0) as BehaviorControl<Villager>),
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
        this.setItemInHand(InteractionHand.MAIN_HAND, nmsItem)

        var ticks = 0
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            if (!this.bukkitEntity.isValid || this.bukkitEntity.isDead) {
                task.cancel()
                return@runTaskTimer
            }
            world.playSound(location, sound, 1F, 1F)
            this.swing(InteractionHand.MAIN_HAND)
            ticks++
            if (ticks >= duration) {
                this.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
                onDone.invoke()
                task.cancel()
            }
        }, 0L, period)
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

    // =======================================================================================
    // CUSTOM SWIM MOVE CONTROL
    // Контролирует поведение плавания, спринт-скорость игрока и выход на сушу.
    // =======================================================================================
    class VillagerSwimMoveControl(private val villager: HumanoidVillager) : net.minecraft.world.entity.ai.control.MoveControl(villager) {
        override fun tick() {
            if (this.villager.wantsToSwim() && this.villager.isInWater) {

                val isBelowSurface = this.villager.isUnderWater()
                val isIdle = this.villager.navigation.isDone()
                val isLowOnAir = this.villager.airSupply < 150

                // 1. ПОДСЧЕТ ГЛУБИНЫ ПОД ВОДОЙ (Защита от заплывов в пещеры)
                var waterAboveCount = 0
                val currentBlockPos = this.villager.blockPosition()
                for (i in 1..5) {
                    if (this.villager.level().getFluidState(currentBlockPos.above(i)).`is`(net.minecraft.tags.FluidTags.WATER)) {
                        waterAboveCount++
                    } else {
                        break
                    }
                }
                val isTooDeep = waterAboveCount >= 5

                // 2. Плавная плавучесть, удержание на воде и экстренное ограничение глубины
                if (isTooDeep) {
                    // Искусственное выталкивание наверх, если ушли глубже 4 блоков
                    this.villager.deltaMovement = this.villager.deltaMovement.add(0.0, 0.05, 0.0)
                } else if (this.wantedY > this.villager.y) {
                    // Обычное следование по высоте за целью пути
                    this.villager.deltaMovement = this.villager.deltaMovement.add(0.0, 0.002, 0.0)
                } else if (isBelowSurface && (isIdle || isLowOnAir)) {
                    // Если бездействует или мало воздуха — всплываем на поверхность
                    this.villager.deltaMovement = this.villager.deltaMovement.add(0.0, 0.03, 0.0)
                }

                // 3. Страховка выхода на сушу: если заплыли на отмель и уперлись в берег, пытаемся выпрыгнуть
                if (this.villager.horizontalCollision) {
                    this.villager.jumpControl.jump()
                }

                // 4. Вычисление направления к цели пути
                if (this.operation != Operation.MOVE_TO || this.villager.navigation.isDone()) {
                    this.villager.speed = 0.0F
                    return
                }

                val xd = this.wantedX - this.villager.x
                var yd = this.wantedY - this.villager.y
                val zd = this.wantedZ - this.villager.z
                val dd = kotlin.math.sqrt(xd * xd + yd * yd + zd * zd)

                if (dd > 0) {
                    // Нормализуем векторы направления для стабильной и предсказуемой скорости
                    val nx = xd / dd
                    val ny = yd / dd
                    val nz = zd / dd

                    val yRotD = (kotlin.math.atan2(zd, xd) * 180.0 / kotlin.math.PI).toFloat() - 90.0F
                    this.villager.yRot = this.rotlerp(this.villager.yRot, yRotD, 90.0F)
                    this.villager.yBodyRot = this.villager.yRot

                    // Плавная скорость плавания (~1.8–2.2 блоков/сек, аналогично нормальной скорости плавания игрока)
                    val swimSpeedMultiplier = 1.4f
                    val targetSpeed = (this.speedModifier * this.villager.getAttributeValue(Attributes.MOVEMENT_SPEED) * swimSpeedMultiplier).toFloat()
                    val newSpeed = this.villager.speed + 0.125F * (targetSpeed - this.villager.speed)
                    this.villager.speed = newSpeed

                    // Применяем реалистичные векторы движения к дельте
                    this.villager.deltaMovement = this.villager.deltaMovement.add(
                        nx * newSpeed * 0.025, // Комфортное горизонтальное ускорение
                        ny * newSpeed * 0.035, // Сбалансированный вертикальный импульс для ныряния и всплытия
                        nz * newSpeed * 0.025
                    )
                }
            } else {
                if (!this.villager.onGround()) {
                    this.villager.deltaMovement = this.villager.deltaMovement.add(0.0, -0.008, 0.0)
                }
                super.tick()
            }
        }
    }

}
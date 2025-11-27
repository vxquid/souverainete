package vx.ignis.gameplay.humanoid.protocol

import com.cryptomorin.xseries.XAttribute
import com.cryptomorin.xseries.reflection.XReflection
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract
import com.github.retrooper.packetevents.event.simple.PacketPlayReceiveEvent
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent
import com.github.retrooper.packetevents.protocol.attribute.Attributes
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.*
import com.github.retrooper.packetevents.protocol.sound.SoundCategory
import com.github.retrooper.packetevents.protocol.sound.Sounds
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.server.*
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scoreboard.Team
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.event.HumanoidInitializationEvent
import vx.ignis.gameplay.humanoid.race.RaceManager.Companion.race
import vx.ignis.gameplay.personality.PersonalityManager.Companion.gender
import vx.ignis.gameplay.personality.PersonalityManager.Gender.FEMALE
import vx.ignis.gameplay.personality.PersonalityManager.Gender.MALE
import java.util.*

class ProtocolListener(private val humanoidRegistry: HashMap<LivingEntity, HumanoidDataWrapper> = hashMapOf()) : SimplePacketListenerAbstract(), Listener {

    companion object {

        private val newIndexing = XReflection.supports(21, 9)
        private val skinIDKey = NamespacedKey(plugin, "SkinID")
        private val skinKey = NamespacedKey(plugin, "Skin")
        private val HUMANOID_VILLAGERS_ENABLED = plugin.gameplayManager.config.humanoid.humanoidVillagers

        // Predicate for removing invalid and necessary EntityData (especially VILLAGER_DATA, which causes a protocol error on the client).
        private val MUST_BE_REMOVED: (EntityData<*>) -> Boolean = {
            it.index == 15 || it.index == (if (newIndexing) 17 else 16) || it.index == (if (newIndexing) 16 else 17) && it.type != EntityDataTypes.BYTE || it.type == EntityDataTypes.VILLAGER_DATA
        }

        fun LivingEntity.skinID() = persistentDataContainer.get(skinIDKey, PersistentDataType.FLOAT)

        fun LivingEntity.skin() = race.let { r ->
            persistentDataContainer.get(skinKey, PersistentDataType.STRING)?.let { skin ->
                val (value, signature) = skin.split(":"); TextureProperty("textures", value, signature)
            } ?: run {
                val skins = (if (gender == MALE) r.maleSkins else r.femaleSkins)
                val id    = skins.keys.random()
                val skin  = skins[id] ?: throw NullPointerException()
                skin.also {
                    persistentDataContainer.set(skinIDKey, PersistentDataType.FLOAT, id)
                    persistentDataContainer.set(skinKey, PersistentDataType.STRING, "${skin.value}:${skin.signature}")
                }
            }
        }

    }

    @EventHandler
    private fun onPlayerQuit(event: PlayerQuitEvent) {
        humanoidRegistry.values.forEach { provider ->
            provider.subscribers.remove(event.player)
        }
    }

    @EventHandler
    private fun onHumanoidInitialization(event: HumanoidInitializationEvent) {

        // Checking nameless team existence.
        plugin.server.scoreboardManager.mainScoreboard.getEntryTeam("HideMyName") ?: plugin.server.scoreboardManager.mainScoreboard.registerNewTeam("NamelessTeam").also {
            if (!it.entries.contains("HideMyName")) {
                it.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
                it.addEntry("HideMyName")
            }
        }

        val player   = event.player
        val humanoid = event.entity
        val provider = event.humanoidInfo
        val metadata = event.metadata.toMutableList().also {
            it.add(EntityData(if (newIndexing) 16 else 17, EntityDataTypes.BYTE, SkinSection.ALL.mask))
        }

        // Modifying base attributes
        humanoid.race.let { race ->
            race.attributes.forEach { (attribute, value) ->

                // Skipping scale modification. Otherwise, villagers won't be able to get through the doors if they are too big. Scale changes through packets.
                // Cancelling only if entity is larger than 1.0.
                if (attribute == XAttribute.SCALE && value > 1.0)
                    return@forEach

                // Applying HP right after first modifying
                if (attribute == XAttribute.MAX_HEALTH && humanoid.getAttribute(attribute.get()!!)?.baseValue != value) {
                    humanoid.getAttribute(attribute.get()!!)?.baseValue = value
                    humanoid.health = value
                    return@forEach
                }

                attribute.get()?.let { humanoid.getAttribute(it)?.baseValue = value }

            }
        }

        // Before sending SPAWN_ENTITY packet with player data, we MUST send PLAYER_INFO.
        // Note the modification of the metadata list. We add data with index 16 to display all skin layers.
        val info = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(provider.profile, false, 20, GameMode.SURVIVAL, null, null);
        val playerInfoPacket = WrapperPlayServerPlayerInfoUpdate(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME), info)
        val spawnEntityPacket = WrapperPlayServerSpawnEntity(humanoid.entityId, provider.profile.uuid, EntityTypes.PLAYER, humanoid.location.toPacketEventsLocation(), humanoid.location.yaw, 0, null)

        player.sendVerbose(" §b> Sending spawn packet and cached metadata. §7[id ${humanoid.entityId}]")
        player.sendPacket(playerInfoPacket)
        player.sendPacket(spawnEntityPacket)
        player.sendPacket(WrapperPlayServerEntityMetadata(humanoid.entityId, metadata))

        // We should send equipment data as well...
        for (it in EquipmentSlot.entries) {
            val item = humanoid.equipment?.getItem(it)
            val slot = when (it) {
                EquipmentSlot.HAND -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND
                EquipmentSlot.OFF_HAND -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND
                EquipmentSlot.HEAD -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET
                EquipmentSlot.CHEST -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.CHEST_PLATE
                EquipmentSlot.LEGS -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.LEGGINGS
                EquipmentSlot.FEET -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BOOTS
                else -> continue
            }
            player.sendPacket(WrapperPlayServerEntityEquipment(humanoid.entityId, listOf(Equipment(slot, SpigotConversionUtil.fromBukkitItemStack(item)))))
        }

        // Scale attribute must be updated.
        player.sendPacket(WrapperPlayServerUpdateAttributes(humanoid.entityId,
            listOf(WrapperPlayServerUpdateAttributes.Property(Attributes.SCALE, (humanoid.race.attributes[XAttribute.SCALE] ?: 1.0) - if (humanoid.gender == FEMALE) 0.05 else 0.0, emptyList())))
        )

        // Delete the information about the fake player, so that the skin has time to load and the player list doesn't show non-existent nicknames.
        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            player.sendPacket(WrapperPlayServerPlayerInfoRemove(provider.profile.uuid))
        }, 40L)

        // Don't forget to collect the garbage.
        humanoidRegistry.keys.filter { !it.isValid }.forEach { garbage ->
            this.debug("Removing invalid humanoid with ID ${humanoid.entityId} from registry.")
            humanoidRegistry.remove(garbage)
        }

    }

    // We are currently listening for the sending of five packets: SOUND_EFFECT, SPAWN_ENTITY, ENTITY_METADATA, ENTITY_HEAD_LOOK, and DESTROY_ENTITIES.
    override fun onPacketPlaySend(event: PacketPlaySendEvent) {

        val player = event.getPlayer<Player>() ?: return
        val world  = player.world

        // Smart world check.
        if (!plugin.gameplayManager.allowedWorlds.contains(world))
            return

        when (event.packetType) {

            // To avoid showing the villagers' real nosy model, we cancel this packet until a packet with a fake entity is sent to the player.
            PacketType.Play.Server.SPAWN_ENTITY -> {

                val packet = WrapperPlayServerSpawnEntity(event)
                val entity = SpigotConversionUtil.getEntityById(world, packet.entityId) ?: return

                when (entity) {

                    is Villager -> {

                        // There's no point in hiding entities if there's no humanoid villagers
                        if (!HUMANOID_VILLAGERS_ENABLED)
                            return

                        player.sendVerbose(" §2> Trying to spawn a villager!")
                        this.debug("Trying to spawn a villager! [ID: ${packet.entityId}]")

                        val humanoidProvider = humanoidRegistry[entity] ?: run {
                            this.debug("Preventing villager with ID ${packet.entityId} from showing.")
                            event.isCancelled = true
                            return
                        }

                        if (!humanoidProvider.subscribers.contains(player)) {
                            this.debug("Preventing disguised villager with ID ${packet.entityId} from undisguising.")
                            event.isCancelled = true
                        }

                    }
                }
            }

            // To display arrows in the body, burning, potion effects, and other metadata of player-disguised villagers, the ENTITY_METADATA packet handling must be changed.
            // If the packet being sent is a villager metadata, cancel it, modify some data (if you don't do this, the player will be kicked because of a protocol error), and resend.
            // Besides, a mechanism of lazy initialization is implemented here. If a villager has no HumanoidProvider, it means only one thing — the player sees it for the FIRST time.
            PacketType.Play.Server.ENTITY_METADATA -> {

                val packet = PacketWrapper(event, false)
                val entity = SpigotConversionUtil.getEntityById(world, packet.readVarInt()) ?: return

                if (entity is Villager) {

                    val humanoidProvider = humanoidRegistry[entity]
                    val metadata = packet.readEntityMetadata()

                    val enabled     = HUMANOID_VILLAGERS_ENABLED
                    val registered  = humanoidProvider != null
                    val subscribed  = humanoidProvider?.subscribers?.contains(player) ?: false
                    val fixedPacket = metadata.removeIf(MUST_BE_REMOVED)
                    val forced      = humanoidProvider?.forcedViewers?.contains(player) ?: false

                    if (fixedPacket) {
                        player.sendVerbose(" §c> Preventing wrong villager metadata. §7[id ${entity.entityId}]")
                        event.isCancelled = true
                    } else {
                        player.sendVerbose(" §2> No wrong metadata was found. §7[id ${entity.entityId}]")
                    }

                    player.sendVerbose(" §3> When-check. $registered, $subscribed, $fixedPacket.")

                    when {

                        !registered -> {
                            HumanoidDataWrapper(
                                entity,
                                UserProfile(entity.uniqueId, "HideMyName"),
                                entity.race
                            ).also { controller ->
                                humanoidRegistry[entity] = controller
                                controller.subscribers.add(player)

                                // There's no point in messing with spawn packets if humanoid villagers feature is disabled
                                if (!enabled)
                                    return

                                // Load skin from PDC
                                controller.profile.textureProperties = listOf(entity.skin())

                                this.debug("Added a new villager with ID ${entity.entityId} at ${entity.location} to client entities registry.")
                                player.sendVerbose(" §3> Calling HumanoidInitializationEvent for a new villager. §7[id ${entity.entityId}]")
                                plugin.server.scheduler.runTaskLater(plugin, { _ ->
                                    plugin.server.pluginManager.callEvent(HumanoidInitializationEvent(player, entity, controller, metadata))
                                }, 10L)
                            }
                        }

                        enabled && registered && (fixedPacket || forced) && !subscribed -> {
                            humanoidProvider.subscribers.add(player)
                            humanoidProvider.forcedViewers.remove(player)
                            plugin.server.scheduler.runTask(plugin) { _ ->
                                player.sendVerbose(" §3> Calling HumanoidInitializationEvent for an existing villager. §7[id ${entity.entityId}]")
                                plugin.server.pluginManager.callEvent(HumanoidInitializationEvent(player, entity, humanoidProvider, metadata))
                            }
                        }

                        enabled && registered && fixedPacket -> {
                            player.sendVerbose(" §e> Sending fixed villager metadata. §7[id ${entity.entityId}]")
                            player.sendPacket(WrapperPlayServerEntityMetadata(entity.entityId, metadata))
                        }

                    }

                    if (!event.isCancelled) {
                        player.sendVerbose(" §6> Received metadata packet! §7[id ${entity.entityId}]")
                    }

                }

            }

            // To prevent the head of a villager disguised as a player from spinning 360 degrees without a body, we need to send the right packet.
            PacketType.Play.Server.ENTITY_HEAD_LOOK -> {

                if (!HUMANOID_VILLAGERS_ENABLED)
                    return

                val packet   = WrapperPlayServerEntityHeadLook(event)
                val entity   = SpigotConversionUtil.getEntityById(world, packet.entityId) ?: return
                val location = entity.location

                if (humanoidRegistry.keys.contains(entity)) {
                    player.sendPacket(WrapperPlayServerEntityRelativeMoveAndRotation(entity.entityId, 0.0, 0.0, 0.0, packet.headYaw, location.pitch, false))
                }

            }

            // It is very important to handle this packet, as low range distance players may “unseen” disguised villagers, although they still remain in memory.
            // It also makes it easy to remove fake players from the registry when they are unloaded from memory.
            PacketType.Play.Server.DESTROY_ENTITIES -> {

                if (!HUMANOID_VILLAGERS_ENABLED)
                    return

                val packet = WrapperPlayServerDestroyEntities(event)

                for (entityId in packet.entityIds) {
                    val entity = SpigotConversionUtil.getEntityById(world, entityId) ?: return
                    val humanoidProvider = humanoidRegistry[entity] ?: return

                    if (entity is Villager) {
                        player.sendVerbose(" §4> Destroying disguised villager. §7[id $entityId]")
                        humanoidProvider.subscribers.remove(player)
                        if (humanoidProvider.subscribers.isEmpty()) {
                            humanoidRegistry.remove(entity)?.let { data ->
                                player.sendVerbose(" §4> Due to lack of subscribers, entity with ID ${data.entity.entityId} was unregistred.")
                            }
                        } else humanoidProvider.forcedViewers.add(player)
                    }
                }

            }

            // If the villager's sound is categorised as NEUTRAL, there is a 99% chance that this is the standard villager "voice" and we need to remove it.
            // Sadly, we can't replace it right here, because we don't know the exact entity, so we need to handle sound stuff somehow.
            PacketType.Play.Server.SOUND_EFFECT -> {

                if (!HUMANOID_VILLAGERS_ENABLED)
                    return

                val packet = WrapperPlayServerSoundEffect(event)

                if (packet.soundCategory != SoundCategory.NEUTRAL)
                    return

                when (packet.sound) {
                    Sounds.ENTITY_VILLAGER_AMBIENT,
                    Sounds.ENTITY_VILLAGER_HURT,
                    Sounds.ENTITY_VILLAGER_DEATH,
                    Sounds.ENTITY_VILLAGER_NO,
                    Sounds.ENTITY_VILLAGER_YES,
                    Sounds.ENTITY_VILLAGER_CELEBRATE,
                    Sounds.ENTITY_VILLAGER_TRADE -> {
                        event.isCancelled = true
                    }
                }

            }

            else -> { /* ... */ }
        }

    }

    override fun onPacketPlayReceive(event: PacketPlayReceiveEvent) {

        val player = event.getPlayer<Player>() ?: return
        val world  = player.world

        // Smart world check.
        if (!plugin.gameplayManager.allowedWorlds.contains(world))
            return

        when (event.packetType) {

            // If you don't cancel INTERACT_ENTITY packet with the disguised villager, the player will be kicked due to a protocol error.
            PacketType.Play.Client.INTERACT_ENTITY -> {

                if (!HUMANOID_VILLAGERS_ENABLED)
                    return

                val packet = WrapperPlayClientInteractEntity(event)
                val action = packet.action

                if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK)
                    return

                val entity = SpigotConversionUtil.getEntityById(world, packet.entityId) ?: return

                if (humanoidRegistry.containsKey(entity)) {
                    event.isCancelled = true
                    plugin.server.scheduler.runTask(plugin) { _ ->
                        plugin.server.pluginManager.callEvent(PlayerInteractEntityEvent(player, entity))
                    }
                }
            }

            else -> { /* ... */ }
        }

    }

    private fun Location.toPacketEventsLocation() = com.github.retrooper.packetevents.protocol.world.Location(this.x, this.y, this.z, this.yaw, this.pitch)

    private fun Player.channel() : Any = PacketEvents.getAPI().playerManager.getChannel(this)

    private fun Player.sendPacket(packet: PacketWrapper<*>) {
        PacketEvents.getAPI().protocolManager.sendPacket(this.channel(), packet)
    }

    private val sendDebugMessages = false
    fun debug(message: String) {
        if (sendDebugMessages) plugin.logger.info("[DEBUG] $message")
    }

    fun Player.sendVerbose(message: String) {
        if (sendDebugMessages) this.sendMessage("§e$message")
    }

    /**
     * Temporarily equips an item in the hand of a humanoid entity for a specified number of ticks.
     * This method only affects client-side visuals for subscribers (players viewing the humanoid).
     * It does not change the server's entity equipment.
     *
     * @param entity The LivingEntity (humanoid) to equip the item to.
     * @param slot The hand slot (HAND for main hand, OFF_HAND for offhand).
     * @param item The ItemStack to temporarily equip.
     * @param ticks The duration in ticks to display the item before resetting to original.
     */
    fun temporaryEquip(entity: LivingEntity, slot: EquipmentSlot, item: ItemStack, ticks: Int) {

        if (!humanoidRegistry.containsKey(entity)) {
            debug("Cannot temporary equip: Entity ${entity.entityId} is not a registered humanoid.")
            return
        }

        val provider = humanoidRegistry[entity]!!
        val originalItem = entity.equipment?.getItem(slot) ?: ItemStack(Material.AIR)

        val peSlot = when (slot) {
            EquipmentSlot.HAND -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND
            EquipmentSlot.OFF_HAND -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND
            else -> {
                debug("Temporary equip only supports hand slots.")
                return
            }
        }

        val equipPacket = WrapperPlayServerEntityEquipment(
            entity.entityId,
            listOf(Equipment(peSlot, SpigotConversionUtil.fromBukkitItemStack(item)))
        )

        provider.subscribers.forEach { subscriber ->
            subscriber.sendPacket(equipPacket)
        }

        plugin.server.scheduler.runTaskLater(plugin, { _ ->
            val resetPacket = WrapperPlayServerEntityEquipment(
                entity.entityId,
                listOf(Equipment(peSlot, SpigotConversionUtil.fromBukkitItemStack(originalItem)))
            )

            provider.subscribers.toList().forEach { subscriber ->
                subscriber.sendPacket(resetPacket)
            }
        }, ticks.toLong())
    }

}
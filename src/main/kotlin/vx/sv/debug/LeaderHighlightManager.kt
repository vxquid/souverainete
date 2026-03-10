package vx.sv.debug

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import vx.sv.gameplay.settlement.isSettlementLeader
import java.util.*

/**
 * Manages the debug highlighting for settlement leaders.
 * Works strictly through packet metadata to avoid breaking custom humanoid disguises.
 */
object LeaderHighlightManager {

    // Set of player UUIDs who have the debug mode toggled ON
    val highlightingPlayers = mutableSetOf<UUID>()

    /**
     * Toggles the highlight mode for a specific player.
     * Manually updates the metadata to avoid using hideEntity/showEntity,
     * which would trigger DESTROY_ENTITIES and break the ProtocolListener's registry.
     * @return true if enabled, false if disabled.
     */
    fun toggleHighlight(player: Player): Boolean {
        val enabled = if (highlightingPlayers.contains(player.uniqueId)) {
            highlightingPlayers.remove(player.uniqueId)
            false
        } else {
            highlightingPlayers.add(player.uniqueId)
            true
        }

        // 0x40 is the bitmask for the "Glowing" effect in Index 0
        val glowByte = (if (enabled) 0x40 else 0x00).toByte()

        // Instantly update the visual state for all loaded leaders around the player
        player.world.entities
            .filterIsInstance<Villager>()
            .filter { it.isSettlementLeader() }
            .forEach { leader ->
                val packet = WrapperPlayServerEntityMetadata(
                    leader.entityId,
                    listOf(EntityData(0, EntityDataTypes.BYTE, glowByte))
                )
                PacketEvents.getAPI().playerManager.sendPacket(player, packet)
            }

        return enabled
    }
}
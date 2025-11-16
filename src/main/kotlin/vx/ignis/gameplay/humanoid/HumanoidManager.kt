package vx.ignis.gameplay.humanoid

import com.github.retrooper.packetevents.PacketEvents
import vx.ignis.Ignis.Companion.plugin
import vx.ignis.gameplay.humanoid.protocol.ProtocolListener
import vx.ignis.gameplay.humanoid.race.RaceManager

class HumanoidManager {

    val raceManager      = RaceManager()
    val protocolListener = ProtocolListener()
    val equipmentManager = EquipmentManager()
    val humanoidDisplay  = NametagDisplayManager()

    init {
        raceManager.loadRaces()
        PacketEvents.getAPI().eventManager.registerListener(protocolListener)
        plugin.server.pluginManager.registerEvents(protocolListener, plugin)
    }

}
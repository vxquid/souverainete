package vx.sv.gameplay.humanoid

import com.github.retrooper.packetevents.PacketEvents
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.humanoid.protocol.ProtocolListener
import vx.sv.gameplay.humanoid.race.RaceManager

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
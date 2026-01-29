package vx.sv.gameplay.humanoid.protocol

import com.github.retrooper.packetevents.protocol.player.UserProfile
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import vx.sv.gameplay.humanoid.race.RaceManager

data class HumanoidDataWrapper(val entity: LivingEntity,
                               val profile: UserProfile,
                               val race: RaceManager.Race?,
                               val subscribers: MutableList<Player> = mutableListOf(),
                               val forcedViewers: MutableList<Player> = mutableListOf())
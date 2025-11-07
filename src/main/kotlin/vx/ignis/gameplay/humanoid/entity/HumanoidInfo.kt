package vx.ignis.gameplay.humanoid.entity

import com.github.retrooper.packetevents.protocol.player.UserProfile
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import vx.ignis.gameplay.humanoid.race.RaceManager

data class HumanoidInfo(val entity: LivingEntity,
                        val profile: UserProfile,
                        val race: RaceManager.Race?,
                        val subscribers: MutableList<Player> = mutableListOf())
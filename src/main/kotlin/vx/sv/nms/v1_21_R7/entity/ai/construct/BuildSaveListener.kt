package vx.sv.nms.v1_21_R7.entity.ai.construct

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldSaveEvent
import vx.sv.gameplay.settlement.SettlementManager

class BuildSaveListener : Listener {

    @EventHandler
    fun onWorldSave(event: WorldSaveEvent) {
        val world = event.world

        // 1. Сохраняем все 3D-данные планировщика (разметку зданий, очереди задач и активные сессии)
        SettlementPlanner.saveBuildingsToWorld(world)

        // 2. Сохраняем основные данные поселений (жителей, репутацию, дипломатию)
        SettlementManager.saveSettlements(world)
    }
}
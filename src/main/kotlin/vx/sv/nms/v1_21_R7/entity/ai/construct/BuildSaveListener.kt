package vx.sv.nms.v1_21_R7.entity.ai.construct

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldSaveEvent

class BuildSaveListener : Listener {

    @EventHandler
    fun onWorldSave(event: WorldSaveEvent) {
        // Каждое периодическое сохранение карты безопасно записывает прогресс в PDC
        BuildJobManager.saveJobsToWorld()
    }
}
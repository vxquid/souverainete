package vx.sv.util

import org.bukkit.Bukkit
import vx.vivaldi.VivaldiAPI

object VivaldiHook {
    
    // Проверяем, загружен и включен ли плагин на сервере
    val isEnabled: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled("Vivaldi")

    /**
     * Возвращает название текущего сезона, если Vivaldi установлен.
     * Если плагина нет — возвращает null.
     */
    fun getCurrentSeasonName(): String? {
        if (!isEnabled) return null
        return getSeasonInternal()
    }

    // ВАЖНО: Вызов API вынесен в отдельный приватный метод!
    // Это не даст ClassLoader'у JVM упасть при загрузке класса VivaldiHook, 
    // если самого Vivaldi.jar нет на сервере.
    private fun getSeasonInternal(): String {
        return VivaldiAPI.getCurrentSeason().name.lowercase()
    }
}
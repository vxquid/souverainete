package vx.sv.nms.entity.ai.pathfinding

import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.Level
import net.minecraft.world.level.pathfinder.PathFinder

class HumanoidGroundNavigation(mob: Mob, level: Level) : GroundPathNavigation(mob, level) {

    // ОПТИМИЗАЦИЯ ИСПРАВЛЕНИЯ: Переопределяем метод создания PathFinder, так как он
    // вызывается супер-конструктором ДО инициализации блока init наследника.
    override fun createPathFinder(maxNodes: Int): PathFinder {
        val evaluator = HumanoidNodeEvaluator()
        evaluator.setCanPassDoors(true)
        evaluator.setCanOpenDoors(true)
        this.nodeEvaluator = evaluator
        return PathFinder(evaluator, maxNodes)
    }
}
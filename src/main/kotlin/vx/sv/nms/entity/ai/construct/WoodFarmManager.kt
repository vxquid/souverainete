package vx.sv.nms.entity.ai.construct

import net.minecraft.core.BlockPos
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.inventory.ItemStack
import vx.sv.Souverainete.Companion.plugin
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.nms.entity.HumanoidVillager
import org.bukkit.entity.Villager as BukkitVillager

class WoodFarmManager : Listener {

    @EventHandler
    fun onTreeGrow(event: BlockGrowEvent) {
        val block = event.block

        // Если выросло дерево (появился дубовый лог)
        if (block.type == Material.OAK_LOG) {
            // Проверяем, находится ли это дерево внутри какой-либо фермы дерева
            val settlement = findSettlementForTree(block.location) ?: return

            // Запускаем процесс контролируемой вырубки
            triggerTreeChopJob(settlement, block)
        }
    }

    private fun findSettlementForTree(loc: Location): Settlement? {
        val world = loc.world ?: return null

        // Получаем список поселений для текущего мира
        val worldSettlements = SettlementManager.settlements[world] ?: return null
        val vector = loc.toVector()

        // Проверяем, входит ли координата дерева в территорию (регион) какого-либо поселения
        return worldSettlements.find { it.territory.contains(vector) }
    }

    private fun triggerTreeChopJob(settlement: Settlement, logBlock: Block) {
        val world = logBlock.world
        val chopJob = SchematicBuildJob(world)

        // Сканируем дерево вверх от базового лога и добавляем все логи в задачу на "разрушение" (цель — AIR)
        var currentLog = logBlock
        val logsToChop = mutableListOf<Block>()

        while (currentLog.type == Material.OAK_LOG) {
            logsToChop.add(currentLog)

            val nmsPos = BlockPos(currentLog.x, currentLog.y, currentLog.z)
            // Мы просим ИИ установить на место дерева воздух (AIR). ИИ прибежит и срубит его!
            chopJob.addBlock(nmsPos, Material.AIR.createBlockData())

            currentLog = currentLog.getRelative(org.bukkit.block.BlockFace.UP)
        }

        // Выбираем свободного жителя поселения
        val chopper = settlement.villagers
            .mapNotNull { (it as? CraftVillager)?.handle as? HumanoidVillager }
            .firstOrNull { it.activeBuildJob == null } ?: return

        val bukkitChopper = chopper.bukkitEntity as BukkitVillager

        // Выдаем жителю топор в инвентарь для работы
        bukkitChopper.inventory.addItem(ItemStack(Material.IRON_AXE))

        // Назначаем ИИ задачу вырубки
        chopper.activeBuildJob = chopJob
        chopper.assignedBlock = null
        chopper.digTicks = 0

        // Каждые 5 секунд проверяем, закончил ли ИИ рубить дерево
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            if (chopJob.isFinished()) {
                // Дерево успешно срублено! Сажаем новый саженец на место срубленного ствола
                logBlock.type = Material.OAK_SAPLING
                task.cancel()
            }
        }, 100L, 100L)
    }
}
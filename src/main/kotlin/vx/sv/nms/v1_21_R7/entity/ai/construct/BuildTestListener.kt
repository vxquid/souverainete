package vx.sv.nms.v1_21_R7.entity.ai.construct

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.entity.CraftVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import vx.sv.gameplay.settlement.Settlement
import vx.sv.gameplay.settlement.SettlementManager
import vx.sv.nms.v1_21_R7.entity.HumanoidVillager
import vx.sv.util.SchematicLoader
import java.util.*
import org.bukkit.entity.Villager as BukkitVillager

class BuildTestListener : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player

        // =========================================================================
        // === ИНСТРУМЕНТ 1: ПАЛКА (STICK) - Запуск строительства схематика      ===
        // =========================================================================
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.material == Material.STICK) {
            val clickedBlock = event.clickedBlock ?: return
            event.isCancelled = true

            player.sendMessage("§7[DEBUG] Клик палкой по блоку обнаружен успешно.")

            val origin = clickedBlock.location
            val world = origin.world ?: return

            val relativeBlocks = try {
                SchematicLoader.loadSchematicFromJar("orc_hall_t1.schem")
            } catch (e: Exception) {
                player.sendMessage("§c[DEBUG] Ошибка при распаковке NBT файла: ${e.message}")
                e.printStackTrace()
                return
            }

            if (relativeBlocks.isEmpty()) {
                player.sendMessage("§c[DEBUG] Схематик пуст или не найден!")
                return
            }

            player.sendMessage("§a[DEBUG] Схематик загружен! Найдено блоков для установки: ${relativeBlocks.size}")

            val buildJob = SchematicBuildJob(world)
            relativeBlocks.forEach { relBlock ->
                val absolutePos = BlockPos(
                    origin.blockX + relBlock.relativePos.x,
                    origin.blockY + relBlock.relativePos.y,
                    origin.blockZ + relBlock.relativePos.z
                )
                buildJob.addBlock(absolutePos, relBlock.blockData)
            }

            val nearbyEntities = player.getNearbyEntities(20.0, 20.0, 20.0)
            val nearbyVillagers = nearbyEntities
                .filterIsInstance<BukkitVillager>()
                .mapNotNull { (it as? CraftVillager)?.handle as? HumanoidVillager }

            if (nearbyVillagers.isEmpty()) {
                player.sendMessage("§cПоблизости не найдено кастомных NPC HumanoidVillager!")
                return
            }

            player.sendMessage("§aНайдено ${nearbyVillagers.size} рабочих. Запуск строительства...")

            nearbyVillagers.forEach { npc ->
                val bukkitNpc = npc.bukkitEntity as BukkitVillager

                relativeBlocks.forEach { block ->
                    bukkitNpc.inventory.addItem(ItemStack(block.blockData.material.toBaseIngredient()))
                }

                npc.activeBuildJob = buildJob
                npc.assignedBlock = null
                npc.digTicks = 0
                npc.buildTicks = 0

                npc.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
                npc.brain.eraseMemory(MemoryModuleType.LOOK_TARGET)
            }
        }

        // =========================================================================
        // === ИНСТРУМЕНТ 2: КОСТЬ (BONE) - МГНОВЕННЫЙ СПАВН ДЕРЕВНИ ПОД СЕБЯ     ===
        // =========================================================================
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.material == Material.BONE) {
            val clickedBlock = event.clickedBlock ?: return
            event.isCancelled = true

            val world = clickedBlock.world
            val centerLoc = clickedBlock.location.add(0.0, 1.0, 0.0)

            player.sendMessage("§a§l[Souverainete] §fМгновенная генерация тестового поселения в точке клика...")

            // 1. Устанавливаем плиту и колокол в центре
            val slabBlock = centerLoc.block
            slabBlock.type = Material.STONE_SLAB

            val bellBlock = slabBlock.getRelative(BlockFace.UP)
            bellBlock.type = Material.BELL

            // 2. Спавним 4 стартовых кастомных безработных жителя
            val citizens = mutableSetOf<BukkitVillager>()
            for (i in 0 until 4) {
                val v = world.spawn(centerLoc, BukkitVillager::class.java) { villager ->
                    villager.profession = BukkitVillager.Profession.NONE
                    villager.villagerLevel = 1
                }
                citizens.add(v)
            }

            // 3. Формируем пакет данных нового поселения
            val newData = Settlement.SettlementData(
                UUID.randomUUID(),
                world.uid,
                "Settlement",
                centerLoc,
                System.currentTimeMillis(),
                "VILLAGER_RACE"
            )
            val settlement = Settlement(newData, citizens)

            // 4. Регистрируем поселение и запускаем подбор имени (через ИИ или расу)
            val manager = SettlementManager()
            manager.generateSettlementName(settlement)

            // 5. Инициализируем планировщик постройки и размечаем тестовые площадки по нативным типам
            val planner = SettlementPlanner(settlement)
            planner.planBuilding(VanillaBuildingType.BAKERY)
            planner.planBuilding(VanillaBuildingType.BLACKSMITH)
            planner.planBuilding(VanillaBuildingType.FARM)

            player.sendMessage("§a§l[Souverainete] §aПоселение успешно основано! Рабочие заспавнены и приступили к застройке тестовых плит.")
        }
    }
}
package vx.sv.gameplay.humanoid.leisure

import com.destroystokyo.paper.entity.Pathfinder
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import vx.sv.Souverainete.Companion.plugin
import kotlin.math.abs
import kotlin.random.Random

class HumanoidLeisureManager : Listener {

    // Стейты досуга
    enum class LeisureState { PATHING, SITTING }
    enum class Preference { INDOOR, OUTDOOR }

    // Дата-класс для хранения информации о текущей сессии отдыха
    data class LeisureSession(
        val villager: Villager,
        val targetSeat: Location,
        val preference: Preference,
        var state: LeisureState = LeisureState.PATHING,
        val startTime: Long = System.currentTimeMillis(),
        val maxDuration: Long = Random.nextLong(30000, 120000) // От 30 сек до 2 мин
    )

    private val activeSessions = mutableMapOf<Villager, LeisureSession>()
    private val occupiedSeats = mutableSetOf<Location>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Глобальный тикер поиска кандидатов на отдых
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            if (activeSessions.size > 20) return@runTaskTimer // Лимит отдыхающих

            val candidate = plugin.server.worlds
                .filter { plugin.gameplayManager.allowedWorlds.contains(it) }
                // 1. Не ищем места вечером и ночью (когда надо спать)
                .filter { it.time !in 13000..23500 }
                .flatMap { it.entities }
                .filterIsInstance<Villager>()
                .filter {
                    it.isValid &&
                            !activeSessions.containsKey(it) &&
                            it.vehicle == null &&
                            // 2. Игнорируем тех, кто уже в процессе сна или лежит на кровати
                            it.pose != org.bukkit.entity.Pose.SLEEPING &&
                            !it.isSleeping
                }
                .randomOrNull() ?: return@runTaskTimer

            startLeisureSearch(candidate)
        }, 200L, 200L) // Раз в 10 секунд

        // Тикер контроля уже отдыхающих (патфайндинг, перекусы, лимиты времени)
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            val iterator = activeSessions.values.iterator()
            val time = System.currentTimeMillis()

            while (iterator.hasNext()) {
                val session = iterator.next()
                val npc = session.villager

                // Валидация
                if (!npc.isValid || npc.isDead) {
                    freeSeat(session)
                    iterator.remove()
                    continue
                }

                // Проверка ночи для тех, кто сидит на улице
                val isNight = npc.world.time in 13000..23000
                if (isNight && session.preference == Preference.OUTDOOR) {
                    standUp(npc, session)
                    iterator.remove()
                    continue
                }

                // Проверка времени сессии
                if (time - session.startTime > session.maxDuration) {
                    standUp(npc, session)
                    iterator.remove()
                    continue
                }

                when (session.state) {
                    LeisureState.PATHING -> handlePathing(session)
                    LeisureState.SITTING -> handleSitting(session)
                }
            }
        }, 20L, 20L) // Проверка каждую секунду
    }

    // =======================================================================================
    // ОСНОВНАЯ ЛОГИКА
    // =======================================================================================

    // Функция проверки дистанции (чтобы не сидели вплотную друг к другу)
    private fun isPersonalSpaceInvaded(loc: Location, occupiedSet: Set<Location> = occupiedSeats): Boolean {
        // Проверяем 8 блоков вокруг (квадрат 3x3 на одном Y-уровне)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val checkLoc = loc.clone().add(dx.toDouble(), 0.0, dz.toDouble())
                if (occupiedSet.contains(checkLoc)) return true
            }
        }
        return false
    }

    private fun startLeisureSearch(npc: Villager) {
        val centerChunk = npc.location.chunk
        val world = npc.world

        // Выбираем изначальное желание NPC: 80% дом, 20% улица
        val desiredPreference = if (Random.nextDouble() < 0.8) Preference.INDOOR else Preference.OUTDOOR
        val isSocial = Random.nextBoolean()

        // Собираем слепки 3x3 чанков вокруг синхронно
        val snapshots = mutableListOf<org.bukkit.ChunkSnapshot>()
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (world.isChunkLoaded(centerChunk.x + dx, centerChunk.z + dz)) {
                    snapshots.add(world.getChunkAt(centerChunk.x + dx, centerChunk.z + dz).chunkSnapshot)
                }
            }
        }

        // Уходим в асинхрон для поиска идеального блока
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val npcY = npc.location.blockY
            val candidates = mutableListOf<Pair<Location, Int>>() // Location to Score
            val campfires = mutableListOf<Triple<Int, Int, Int>>() // worldX, y, worldZ

            // Функция для безопасного и быстрого получения материала из слепков (cross-chunk)
            val getMat = { wx: Int, wy: Int, wz: Int ->
                val cx = wx shr 4
                val cz = wz shr 4
                val snap = snapshots.find { it.x == cx && it.z == cz }
                snap?.getBlockType(wx and 15, wy, wz and 15) ?: Material.AIR
            }

            // Пре-пасс: ищем костры (ищем чуть шире по высоте, чтобы точно захватить)
            for (snapshot in snapshots) {
                for (y in (npcY - 4)..(npcY + 4)) {
                    for (x in 0..15) {
                        for (z in 0..15) {
                            val mat = snapshot.getBlockType(x, y, z)
                            if (mat == Material.CAMPFIRE || mat == Material.SOUL_CAMPFIRE) {
                                campfires.add(Triple((snapshot.x shl 4) + x, y, (snapshot.z shl 4) + z))
                            }
                        }
                    }
                }
            }

            for (snapshot in snapshots) {
                for (y in (npcY - 3)..(npcY + 2)) {
                    for (x in 0..15) {
                        for (z in 0..15) {
                            val material = snapshot.getBlockType(x, y, z)
                            if (material.isAir) continue

                            val isStairs = material.name.endsWith("STAIRS")

                            // Мировые координаты
                            val worldX = (snapshot.x shl 4) + x
                            val worldZ = (snapshot.z shl 4) + z

                            if (isStairs) {
                                val blockData = snapshot.getBlockData(x, y, z)
                                // Игнорируем перевернутые ступеньки
                                if (blockData is org.bukkit.block.data.type.Stairs && blockData.half == org.bukkit.block.data.Bisected.Half.TOP) continue

                                // === ФИЛЬТР ЛЕСТНИЧНЫХ ПРОЛЁТОВ ===
                                var isStaircase = false
                                for (dx in -1..1) {
                                    for (dz in -1..1) {
                                        val matUp = getMat(worldX + dx, y + 1, worldZ + dz)
                                        val matDown = getMat(worldX + dx, y - 1, worldZ + dz)
                                        if (matUp.name.endsWith("STAIRS") || matDown.name.endsWith("STAIRS")) {
                                            isStaircase = true
                                            break
                                        }
                                    }
                                    if (isStaircase) break
                                }
                                // Если это лестница - бракуем блок и идём дальше
                                if (isStaircase) continue
                            }

                            val isSolid = material.isSolid

                            if (isStairs || isSolid) {
                                val loc = Location(world, worldX.toDouble(), y.toDouble(), worldZ.toDouble())

                                // Если место уже занято — скипаем
                                if (occupiedSeats.contains(loc)) continue

                                // СТРОГАЯ МУЖСКАЯ ДИСТАНЦИЯ: Никто не сидит на соседних блоках
                                if (isPersonalSpaceInvaded(loc)) continue

                                // Проверка, что сверху 2 блока воздуха
                                if (!snapshot.getBlockType(x, y + 1, z).isAir || !snapshot.getBlockType(x, y + 2, z).isAir) {
                                    continue
                                }

                                var score = 0

                                // Определение: улица или помещение?
                                val highestY = snapshot.getHighestBlockYAt(x, z)
                                val isIndoor = highestY > y + 2
                                val actualPreference = if (isIndoor) Preference.INDOOR else Preference.OUTDOOR

                                // Накидываем очки за соответствие желанию
                                if (actualPreference == desiredPreference) score += 50
                                // Дома сидеть приоритетнее в любом случае
                                if (isIndoor) score += 30
                                // Очки за ступеньки
                                if (isStairs) score += 100

                                // Социальный интеллект: ищем соседние ступеньки (лавочка)
                                if (isStairs) {
                                    val neighbors = listOf(
                                        Pair(worldX + 1, worldZ), Pair(worldX - 1, worldZ),
                                        Pair(worldX, worldZ + 1), Pair(worldX, worldZ - 1)
                                    )
                                    for (n in neighbors) {
                                        val neighborMat = getMat(n.first, y, n.second)
                                        if (neighborMat.name.endsWith("STAIRS")) {
                                            score += 150 // Огромный приоритет за настоящую скамейку
                                            break
                                        }
                                    }
                                }

                                // Уютный костёр: если ищем место на улице, места у костра в приоритете
                                if (desiredPreference == Preference.OUTDOOR && actualPreference == Preference.OUTDOOR) {
                                    val nearCampfire = campfires.any { cf ->
                                        // Проверяем радиус: 6 блоков в стороны, 2 блока вверх/вниз
                                        abs(cf.first - worldX) <= 6 && abs(cf.second - y) <= 2 && abs(cf.third - worldZ) <= 6
                                    }
                                    if (nearCampfire) {
                                        score += 350 // Даём мощный буст (перебивает даже крутые домашние диваны)
                                    }
                                }

                                candidates.add(Pair(loc, score))
                            }
                        }
                    }
                }
            }

            // Выбираем лучшее место
            val bestSeat = candidates.maxByOrNull { it.second } ?: return@Runnable

            // Возвращаемся в основной поток для назначения задачи
            plugin.server.scheduler.runTask(plugin, Runnable {
                assignSeat(npc, bestSeat.first, desiredPreference)

                // Если социальный настрой и найдена скамейка - зовём корешей!
                if (isSocial && bestSeat.second >= 250) {
                    val friends = npc.getNearbyEntities(10.0, 5.0, 10.0)
                        .filterIsInstance<Villager>()
                        .filter { it.isValid && !activeSessions.containsKey(it) && it.vehicle == null }
                        .shuffled()
                        .take(Random.nextInt(1, 3)) // Зовём 1 или 2 друга

                    // Рассаживаем друзей на свободные блоки рядом с основным сиденьем
                    val friendSeats = getAdjacentFreeSeats(bestSeat.first, friends.size)
                    friends.forEachIndexed { index, friend ->
                        friendSeats.getOrNull(index)?.let { fLoc ->
                            assignSeat(friend, fLoc, desiredPreference)
                        }
                    }
                }
            })
        })
    }

    private fun assignSeat(npc: Villager, loc: Location, pref: Preference) {
        if (occupiedSeats.contains(loc)) return
        occupiedSeats.add(loc)
        activeSessions[npc] = LeisureSession(npc, loc, pref)
    }

    private fun handlePathing(session: LeisureSession) {
        val npc = session.villager
        val target = session.targetSeat

        if (npc.location.distanceSquared(target) < 2.5) {
            // Дошли! Садимся
            session.state = LeisureState.SITTING
            plugin.gameplayManager.humanoidManager.protocolListener.actionController.toggleSitting(npc, true, target.block)
            return
        }

        // Контролируем патфайндинг (используется Paper API)
        val pathfinder: Pathfinder = npc.pathfinder
        if (!pathfinder.hasPath() || pathfinder.currentPath?.finalPoint?.distanceSquared(target)!! > 2.0) {
            pathfinder.moveTo(target, 0.6)
        }
    }

    private fun handleSitting(session: LeisureSession) {
        val npc = session.villager

        // 5% шанс перекусить каждую секунду
        if (Random.nextDouble() < 0.05) {
            triggerRandomConsumption(npc)
        }
    }

    private fun triggerRandomConsumption(npc: Villager) {
        val humanoid = plugin.gameplayManager.versionBridge.entityProvider.asHumanoid(npc)

        val isDrink = Random.nextBoolean()
        val item = if (isDrink) {
            ItemStack(Material.POTION).apply {
                val meta = itemMeta as PotionMeta
                meta.color = org.bukkit.Color.fromRGB(Random.nextInt(255), Random.nextInt(255), Random.nextInt(255))
                itemMeta = meta
            }
        } else {
            val foods = listOf(Material.BREAD, Material.APPLE, Material.COOKED_BEEF, Material.CARROT)
            ItemStack(foods.random())
        }

        val sound = if (isDrink) Sound.ENTITY_GENERIC_DRINK else Sound.ENTITY_GENERIC_EAT

        humanoid.consume(npc.world, item, sound, 7, npc.location, 7) {
            // Никаких эффектов не накладываем, просто визуальная трапеза
        }
    }

    private fun standUp(npc: Villager, session: LeisureSession) {
        // Поднимаем жопу
        plugin.gameplayManager.humanoidManager.protocolListener.actionController.toggleSitting(npc, false)
        freeSeat(session)
    }

    private fun freeSeat(session: LeisureSession) {
        occupiedSeats.remove(session.targetSeat)
    }

    // Поиск соседних свободных мест для социального интерактива
    private fun getAdjacentFreeSeats(center: Location, count: Int): List<Location> {
        val seats = mutableListOf<Location>()
        val tempOccupied = occupiedSeats.toMutableSet() // Локальная копия, чтобы друзья не сели вплотную друг к другу

        // Ищем места с зазором минимум в 1 блок. То есть дистанция 2 и 3.
        val offsets = listOf(
            Pair(2, 0), Pair(-2, 0), Pair(0, 2), Pair(0, -2), // Через 1 блок по прямой
            Pair(2, 2), Pair(-2, -2), Pair(2, -2), Pair(-2, 2), // Через 1 блок по диагонали
            Pair(3, 0), Pair(-3, 0), Pair(0, 3), Pair(0, -3) // И чуть дальше для длинных лавочек
        )
        val world = center.world

        for (offset in offsets) {
            if (seats.size >= count) break
            val checkLoc = center.clone().add(offset.first.toDouble(), 0.0, offset.second.toDouble())
            val block = checkLoc.block
            val mat = block.type
            var isSeat = mat.name.endsWith("STAIRS")

            if (isSeat) {
                val bData = block.blockData
                if (bData is org.bukkit.block.data.type.Stairs && bData.half == org.bukkit.block.data.Bisected.Half.TOP) isSeat = false

                // Проверка на лестничный пролёт
                if (isSeat) {
                    var isStaircase = false
                    for (dx in -1..1) {
                        for (dz in -1..1) {
                            val up = world.getBlockAt(checkLoc.blockX + dx, checkLoc.blockY + 1, checkLoc.blockZ + dz).type
                            val down = world.getBlockAt(checkLoc.blockX + dx, checkLoc.blockY - 1, checkLoc.blockZ + dz).type
                            if (up.name.endsWith("STAIRS") || down.name.endsWith("STAIRS")) {
                                isStaircase = true
                                break
                            }
                        }
                        if (isStaircase) break
                    }
                    if (isStaircase) isSeat = false
                }
            }

            if (isSeat && !tempOccupied.contains(checkLoc)) {
                // Строгая проверка личного пространства (проверяем по локальному списку)
                if (isPersonalSpaceInvaded(checkLoc, tempOccupied)) continue

                // Проверка высоты (чтоб там было свободно)
                if (checkLoc.clone().add(0.0, 1.0, 0.0).block.type.isAir) {
                    seats.add(checkLoc)
                    tempOccupied.add(checkLoc) // Бронируем место, чтобы следующий друг не сел на соседний блок
                }
            }
        }
        return seats
    }

    // =======================================================================================
    // ИВЕНТЫ
    // =======================================================================================

    @EventHandler
    fun onNpcDamage(event: EntityDamageEvent) {
        val npc = event.entity as? Villager ?: return
        val session = activeSessions[npc] ?: return

        // Любой урон заставляет прервать отдых
        standUp(npc, session)
        activeSessions.remove(npc)
    }

    @EventHandler
    fun onNpcDeath(event: EntityDeathEvent) {
        val npc = event.entity as? Villager ?: return
        activeSessions.remove(npc)?.let { session ->
            freeSeat(session)
        }
    }
}
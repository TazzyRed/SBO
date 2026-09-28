package net.sbo.mod.diana.burrows

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.sbo.mod.SBOKotlin.mc
import net.sbo.mod.diana.guesses.ArrowGuessBurrow
import net.sbo.mod.settings.categories.Customization
import net.sbo.mod.settings.categories.Diana
import net.sbo.mod.utils.Player
import net.sbo.mod.utils.chat.Chat
import net.sbo.mod.utils.data.BurrowRecord
import net.sbo.mod.utils.data.SboDataObject
import net.sbo.mod.utils.events.Register
import net.sbo.mod.utils.game.InventoryUtils
import net.sbo.mod.utils.game.World
import net.sbo.mod.utils.math.SboVec
import net.sbo.mod.utils.render.RenderUtils3D
import net.sbo.mod.utils.waypoint.Waypoint
import net.sbo.mod.utils.waypoint.WaypointManager
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Predicts where an undiscovered Griffin burrow chain is most likely hiding, based on
 * where we've already swept a spade with nothing found.
 *
 * Ground truth this leans on: holding a spade for ~0.2-0.4s while within a true 30-block
 * 3D radius (Y included, not just horizontal) of an undiscovered burrow makes Hypixel
 * render its particles - see BurrowDetector/ArrowGuessBurrow. So "swept a spot with a
 * spade out for 400ms+ and nothing rendered" is real (if temporary) evidence that no
 * chain start exists within 29 real 3D blocks of that spot right now. The coverage grid
 * below is 3D (X/Y/Z cells) specifically so that never gets approximated down to a flat
 * horizontal circle - Hub's garden has real elevation, and a burrow one cliff-height away
 * horizontally can be well outside 30 actual blocks.
 *
 * That evidence goes stale: finishing any chain (ours - we can only see our own dig
 * completions) immediately spawns a new undiscovered chain somewhere, possibly inside
 * an area we already swept clean. Rather than wiping all coverage on every completion
 * (which would make this useless during active grinding), each cell stays "trusted
 * empty" for a few chain completions after being swept before it needs a re-sweep.
 *
 * We don't just walk you to the nearest unswept cell - a single isolated unswept cell
 * sitting in an otherwise-swept area is usually just a gap you didn't happen to walk
 * directly over, not a real unexplored zone. Instead we score every unswept, ground-
 * valid candidate by how many other unswept candidates surround it in a 5x5 (x/z) by
 * 3 (y) window, and target the middle of the single biggest such blob - a real chunk of
 * unexplored territory, not a stray cell. Every confirmed burrow ever found
 * (SboData.burrowRecords, logged by BurrowDetector.registerBurrow) also contributes a
 * density score used only as a tiebreaker between blobs of the same size, for now - see
 * the note below on where that's headed.
 *
 * Cells are also ruled out permanently once we've actually looked at their ground (once
 * the chunk is loaded) and found nothing grass/air-topped in them at all - no point
 * sending anyone to the middle of a lake or a building. Cells we haven't looked at yet
 * stay optimistically "maybe valid" until we have.
 *
 * This whole cell-grid approach is a stand-in for real data. Once burrowRecords has
 * enough history to reliably say which specific blocks (not just which 15-block region)
 * tend to host a burrow, the grid/blob logic here should probably be replaced outright
 * by scoring actual candidate blocks directly against that history.
 */
object BurrowPrediction {
    private const val CELL_SIZE = 15.0
    private const val SWEEP_RADIUS = 29.0 // true 3D distance, right under the real 30-block particle trigger
    private const val HIGHLIGHT_RADIUS = 100.0
    private const val STALE_AFTER_CHAIN_FINISHES = 3
    private const val MAX_ACTIVE_CHAINS_FOR_HINT = 5
    private const val BLOB_WINDOW_XZ = 2 // +/-2 cells -> a 5x5 window in x/z
    private const val BLOB_WINDOW_Y = 1 // +/-1 cell in y - grid is only 3 bands tall to begin with
    private const val GROUND_SAMPLE_STEP = 3 // blocks between sampled columns when checking a cell's ground
    private val SPADE_DWELL = 400.milliseconds

    private val boundsMin = ArrowGuessBurrow.HUB_BOUNDS_MIN
    private val boundsMax = ArrowGuessBurrow.HUB_BOUNDS_MAX

    private data class CellKey(val cx: Int, val cy: Int, val cz: Int)

    private val gridWidth = ceil((boundsMax.x - boundsMin.x) / CELL_SIZE).toInt()
    private val gridHeight = ceil((boundsMax.y - boundsMin.y) / CELL_SIZE).toInt()
    private val gridDepth = ceil((boundsMax.z - boundsMin.z) / CELL_SIZE).toInt()
    private val allCells: List<CellKey> = buildList {
        for (cx in 0 until gridWidth) {
            for (cy in 0 until gridHeight) {
                for (cz in 0 until gridDepth) {
                    add(CellKey(cx, cy, cz))
                }
            }
        }
    }

    private val sweptAtEpoch = ConcurrentHashMap<CellKey, Int>()
    private var currentEpoch = 0

    private val historyDensity = ConcurrentHashMap<CellKey, Int>()

    // absent = not checked yet (still an optimistic candidate); true/false once we've
    // actually looked at the cell's ground in a loaded chunk - permanent either way,
    // terrain doesn't change.
    private val cellGroundValidity = ConcurrentHashMap<CellKey, Boolean>()

    private var predictedCell: CellKey? = null
    private var highlightBlocks: List<SboVec> = emptyList()

    fun init() {
        rebuildHistoryDensity()

        BurrowDetector.onChainFinish { currentEpoch++ }

        Register.command("sboclearprediction") {
            sweptAtEpoch.clear()
            currentEpoch = 0
            cellGroundValidity.clear()
            clearPrediction()
            Chat.chat("§6[SBO] §4Burrow prediction coverage cleared!")
        }

        Register.onTick(5) { markSweep() }
        Register.onTick(20) { recomputePrediction() }
    }

    /** Called by BurrowDetector the moment a burrow is first confirmed at a position. */
    fun onBurrowDiscovered(pos: SboVec, type: String) {
        SboDataObject.sboData.burrowRecords.add(
            BurrowRecord(pos.x.toInt(), pos.y.toInt(), pos.z.toInt(), type)
        )
        touchHistoryDensity(pos, 1)
    }

    private fun rebuildHistoryDensity() {
        historyDensity.clear()
        SboDataObject.sboData.burrowRecords.forEach { record ->
            touchHistoryDensity(SboVec(record.x.toDouble(), record.y.toDouble(), record.z.toDouble()), 1)
        }
    }

    private fun touchHistoryDensity(pos: SboVec, delta: Int) {
        val center = cellOf(pos)
        val cellRadius = ceil(SWEEP_RADIUS / CELL_SIZE).toInt()

        for (dcx in -cellRadius..cellRadius) {
            for (dcy in -cellRadius..cellRadius) {
                for (dcz in -cellRadius..cellRadius) {
                    val cell = CellKey(center.cx + dcx, center.cy + dcy, center.cz + dcz)
                    if (cell.cx !in 0 until gridWidth || cell.cy !in 0 until gridHeight || cell.cz !in 0 until gridDepth) continue
                    if (cellCenter(cell).distanceTo(pos) > SWEEP_RADIUS) continue

                    historyDensity.merge(cell, delta) { a, b -> (a + b).coerceAtLeast(0) }
                }
            }
        }
    }

    fun renderHighlights(context: LevelRenderContext) {
        if (highlightBlocks.isEmpty()) return

        val color = Color(Customization.PredictedColor)
        val rgb = floatArrayOf(color.red / 255f, color.green / 255f, color.blue / 255f)

        highlightBlocks.forEach { block ->
            RenderUtils3D.renderBlockHighlight(block, rgb, 0.18f)
        }
    }

    private fun cellOf(pos: SboVec): CellKey = CellKey(
        floor((pos.x - boundsMin.x) / CELL_SIZE).toInt(),
        floor((pos.y - boundsMin.y) / CELL_SIZE).toInt(),
        floor((pos.z - boundsMin.z) / CELL_SIZE).toInt()
    )

    private fun cellCenter(cell: CellKey): SboVec = SboVec(
        boundsMin.x + (cell.cx + 0.5) * CELL_SIZE,
        boundsMin.y + (cell.cy + 0.5) * CELL_SIZE,
        boundsMin.z + (cell.cz + 0.5) * CELL_SIZE
    )

    private fun isCellSwept(cell: CellKey): Boolean {
        val sweptAt = sweptAtEpoch[cell] ?: return false
        return currentEpoch - sweptAt <= STALE_AFTER_CHAIN_FINISHES
    }

    /**
     * Sparsely samples a cell's footprint for any real burrow-spawnable ground (grass
     * with a valid block above it, same check ArrowGuessBurrow uses for its own
     * guesses). Only counts a column if its chunk is actually loaded; if every sampled
     * column came back loaded and none were valid, the cell is permanently ruled out.
     * Otherwise (nothing valid found yet, but some columns unloaded) it stays an
     * optimistic candidate until we're actually close enough to check properly.
     */
    private fun hasValidGround(cell: CellKey): Boolean {
        cellGroundValidity[cell]?.let { return it }

        val minX = (boundsMin.x + cell.cx * CELL_SIZE).toInt()
        val minZ = (boundsMin.z + cell.cz * CELL_SIZE).toInt()
        val searchStartY = (boundsMin.y + (cell.cy + 1) * CELL_SIZE).roundToInt()

        var sawUnloaded = false

        var x = minX
        while (x < minX + CELL_SIZE.toInt()) {
            var z = minZ
            while (z < minZ + CELL_SIZE.toInt()) {
                val samplePos = SboVec(x.toDouble(), searchStartY.toDouble(), z.toDouble())

                if (!samplePos.isInLoadedChunk()) {
                    sawUnloaded = true
                } else {
                    val ground = findGroundColumn(x, z, searchStartY)
                    if (ground != null && ArrowGuessBurrow.isBlockValid(ground)) {
                        cellGroundValidity[cell] = true
                        return true
                    }
                }

                z += GROUND_SAMPLE_STEP
            }
            x += GROUND_SAMPLE_STEP
        }

        if (!sawUnloaded) {
            cellGroundValidity[cell] = false
            return false
        }

        return true
    }

    private fun markSweep() {
        if (!Diana.predictBurrowChains) return
        if (World.getWorld() != "Hub") return
        if (!InventoryUtils.isItemHeld("SPADE", SPADE_DWELL)) return

        val playerPos = Player.getLastPosition()
        val centerCell = cellOf(playerPos)
        val cellRadius = ceil(SWEEP_RADIUS / CELL_SIZE).toInt()

        for (dcx in -cellRadius..cellRadius) {
            for (dcy in -cellRadius..cellRadius) {
                for (dcz in -cellRadius..cellRadius) {
                    val cell = CellKey(centerCell.cx + dcx, centerCell.cy + dcy, centerCell.cz + dcz)
                    if (cell.cx !in 0 until gridWidth || cell.cy !in 0 until gridHeight || cell.cz !in 0 until gridDepth) continue
                    // True 3D distance - a cell one cliff-height away must not count as swept just because it's horizontally close.
                    if (playerPos.distanceTo(cellCenter(cell)) > SWEEP_RADIUS) continue

                    sweptAtEpoch[cell] = currentEpoch
                }
            }
        }
    }

    private fun recomputePrediction() {
        if (!Diana.predictBurrowChains || World.getWorld() != "Hub") {
            clearPrediction()
            return
        }

        if (BurrowDetector.chains >= MAX_ACTIVE_CHAINS_FOR_HINT) {
            clearPrediction()
            return
        }

        val playerPos = Player.getLastPosition()
        val knownCells = WaypointManager.getAllGuessesAndBurrows().map { cellOf(it.pos) }.toSet()

        val candidates = allCells.filter { !isCellSwept(it) && it !in knownCells && hasValidGround(it) }
        if (candidates.isEmpty()) {
            clearPrediction()
            return
        }
        val candidateSet = candidates.toHashSet()

        // Biggest surrounding blob of unswept, ground-valid cells wins (a real unexplored
        // region, not a stray gap) - historical density and then distance only break ties
        // between equally-sized blobs.
        val target = candidates
            .map { cell ->
                var blobSize = 0
                var historySum = 0

                for (dcx in -BLOB_WINDOW_XZ..BLOB_WINDOW_XZ) {
                    for (dcy in -BLOB_WINDOW_Y..BLOB_WINDOW_Y) {
                        for (dcz in -BLOB_WINDOW_XZ..BLOB_WINDOW_XZ) {
                            val neighbor = CellKey(cell.cx + dcx, cell.cy + dcy, cell.cz + dcz)
                            if (neighbor in candidateSet) {
                                blobSize++
                                historySum += historyDensity[neighbor] ?: 0
                            }
                        }
                    }
                }

                Triple(cell, blobSize, historySum)
            }
            .minWithOrNull(
                compareByDescending<Triple<CellKey, Int, Int>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { playerPos.distanceTo(cellCenter(it.first)) }
            )
            ?.first

        if (target == null) {
            clearPrediction()
            return
        }

        if (target != predictedCell) {
            predictedCell = target
            WaypointManager.removeAllOfType("predicted")

            val center = cellCenter(target)
            WaypointManager.addWaypoint(
                Waypoint("§d§lPredicted Chain?", center.x, center.y, center.z, ttl = 0, type = "predicted")
            )
        }

        highlightBlocks = if (playerPos.distanceTo(cellCenter(target)) <= HIGHLIGHT_RADIUS + CELL_SIZE) {
            findCandidateBlocks(target, playerPos)
        } else {
            emptyList()
        }
    }

    private fun clearPrediction() {
        if (predictedCell != null) {
            predictedCell = null
            WaypointManager.removeAllOfType("predicted")
        }
        highlightBlocks = emptyList()
    }

    private fun findCandidateBlocks(cell: CellKey, playerPos: SboVec): List<SboVec> {
        val minX = (boundsMin.x + cell.cx * CELL_SIZE).toInt()
        val minZ = (boundsMin.z + cell.cz * CELL_SIZE).toInt()
        // Anchor the ground search to the predicted cell's own elevation band, not the
        // player's - the cell can be at a very different height than where we're standing.
        val searchStartY = (boundsMin.y + (cell.cy + 1) * CELL_SIZE).roundToInt()

        return buildList {
            for (x in minX until minX + CELL_SIZE.toInt()) {
                for (z in minZ until minZ + CELL_SIZE.toInt()) {
                    val ground = findGroundColumn(x, z, searchStartY) ?: continue
                    if (playerPos.distanceTo(ground) > HIGHLIGHT_RADIUS) continue
                    if (!ArrowGuessBurrow.isBlockValid(ground)) continue

                    add(ground)
                }
            }
        }
    }

    private fun findGroundColumn(x: Int, z: Int, startY: Int): SboVec? {
        val level = mc.level ?: return null
        val minY = boundsMin.y.toInt()

        var y = startY
        while (y >= minY) {
            if (level.getBlockState(BlockPos(x, y, z)).`is`(Blocks.GRASS_BLOCK)) {
                return SboVec(x.toDouble(), y.toDouble(), z.toDouble())
            }
            y--
        }

        return null
    }
}

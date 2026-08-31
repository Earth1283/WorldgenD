package io.github.eath1283.worldgend

import java.util.ArrayDeque

internal class PendingSpatialIndex(
    coords: Collection<Pair<Int, Int>>,
    private val conflictRadius: Int,
) {
    private val cellSize = conflictRadius + 1
    private val grid = HashMap<Long, MutableList<Pair<Int, Int>>>()
    private val pending = HashSet<Pair<Int, Int>>(coords.size * 2)
    private val reconsider = ArrayDeque<Pair<Int, Int>>()
    private val queued = HashSet<Pair<Int, Int>>()

    init {
        for (coord in coords) {
            grid.getOrPut(bucketKeyFor(coord)) { mutableListOf() }.add(coord)
            pending.add(coord)
        }
    }

    fun contains(coord: Pair<Int, Int>) = coord in pending

    fun remove(coord: Pair<Int, Int>) {
        if (!pending.remove(coord)) return
        grid[bucketKeyFor(coord)]?.remove(coord)
        queued.remove(coord)
    }

    fun reconsiderNear(center: Pair<Int, Int>) {
        val (cx, cz) = center
        val loX = cellIndex(cx - conflictRadius)
        val hiX = cellIndex(cx + conflictRadius)
        val loZ = cellIndex(cz - conflictRadius)
        val hiZ = cellIndex(cz + conflictRadius)

        for (bx in loX..hiX) {
            for (bz in loZ..hiZ) {
                for (candidate in grid[bucketKey(bx, bz)] ?: continue) {
                    if (queued.add(candidate)) reconsider.addLast(candidate)
                }
            }
        }
    }

    fun takeEligible(isSafe: (Pair<Int, Int>) -> Boolean): Pair<Int, Int>? {
        while (reconsider.isNotEmpty()) {
            val candidate = reconsider.removeFirst()
            queued.remove(candidate)
            if (candidate in pending && isSafe(candidate)) {
                remove(candidate)
                return candidate
            }
        }
        return null
    }

    private fun cellIndex(value: Int) = Math.floorDiv(value, cellSize)

    private fun bucketKeyFor(coord: Pair<Int, Int>) =
        bucketKey(cellIndex(coord.first), cellIndex(coord.second))

    private fun bucketKey(bx: Int, bz: Int) =
        (bx.toLong() shl 32) or (bz.toLong() and 0xFFFFFFFFL)
}

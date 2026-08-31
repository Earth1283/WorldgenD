package io.github.eath1283.worldgend

// Bucketed replacement for the O(heldCenters) linear scan isSafe() used to do (#42's own
// open question). Same cell-size/3x3-neighborhood approach as PendingSpatialIndex.
internal class HeldCenterIndex(private val conflictRadius: Int) {
    private val cellSize = conflictRadius + 1
    private val grid = HashMap<Long, MutableList<Pair<Int, Int>>>()
    private var count = 0

    val size: Int get() = count

    // Diagnostic only (#46 follow-up): enumerate held coords for cross-referencing
    // against Mojang's own pendingGenerationTasks during a stall.
    fun all(): List<Pair<Int, Int>> = grid.values.flatten()

    fun add(coord: Pair<Int, Int>) {
        grid.getOrPut(bucketKeyFor(coord)) { mutableListOf() }.add(coord)
        count++
    }

    fun remove(coord: Pair<Int, Int>) {
        val bucket = grid[bucketKeyFor(coord)] ?: return
        if (bucket.remove(coord)) count--
    }

    fun isSafe(cx: Int, cz: Int): Boolean {
        val loX = cellIndex(cx - conflictRadius)
        val hiX = cellIndex(cx + conflictRadius)
        val loZ = cellIndex(cz - conflictRadius)
        val hiZ = cellIndex(cz + conflictRadius)

        for (bx in loX..hiX) {
            for (bz in loZ..hiZ) {
                val bucket = grid[bucketKey(bx, bz)] ?: continue
                for ((hx, hz) in bucket) {
                    if (Math.abs(hx - cx) <= conflictRadius && Math.abs(hz - cz) <= conflictRadius) return false
                }
            }
        }
        return true
    }

    private fun cellIndex(value: Int) = Math.floorDiv(value, cellSize)

    private fun bucketKeyFor(coord: Pair<Int, Int>) =
        bucketKey(cellIndex(coord.first), cellIndex(coord.second))

    private fun bucketKey(bx: Int, bz: Int) =
        (bx.toLong() shl 32) or (bz.toLong() and 0xFFFFFFFFL)
}

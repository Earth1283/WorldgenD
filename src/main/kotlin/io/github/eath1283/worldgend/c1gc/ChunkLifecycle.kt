package io.github.eath1283.worldgend.c1gc

import java.util.concurrent.ConcurrentHashMap

// QUARANTINED: outside the retain radius, not yet proven safe. COLLECTIBLE: proven safe
// (ticket/POI/distance-manager cleared). DETACHED: heavy state copied out, chunk GC-eligible.
enum class ChunkLifecycle { ACTIVE, QUARANTINED, COLLECTIBLE, DETACHED, PERSISTED }

private val ALLOWED = mapOf(
    ChunkLifecycle.ACTIVE to ChunkLifecycle.QUARANTINED,
    ChunkLifecycle.QUARANTINED to ChunkLifecycle.COLLECTIBLE,
    ChunkLifecycle.COLLECTIBLE to ChunkLifecycle.DETACHED,
    ChunkLifecycle.DETACHED to ChunkLifecycle.PERSISTED,
)

class IllegalLifecycleTransition(pos: Long, from: ChunkLifecycle?, to: ChunkLifecycle) :
    IllegalStateException("chunk $pos: cannot go $from -> $to")

// Missing entry == ACTIVE (default state, never pre-populated).
class LifecycleTracker {
    private val states = ConcurrentHashMap<Long, ChunkLifecycle>()

    fun advance(pos: Long, to: ChunkLifecycle) {
        states.compute(pos) { _, current ->
            val from = current ?: ChunkLifecycle.ACTIVE
            if (ALLOWED[from] != to) throw IllegalLifecycleTransition(pos, from, to)
            to
        }
    }

    fun countsByState(): Map<ChunkLifecycle, Int> =
        states.values.groupingBy { it }.eachCount()
}

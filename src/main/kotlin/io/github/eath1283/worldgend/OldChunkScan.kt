package io.github.eath1283.worldgend

import java.lang.reflect.Method
import java.util.BitSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.LongAdder
import java.util.function.Supplier

// v5.9 (#83): Blender.of asks IOWorker whether any pre-1.18 chunk is nearby. The first ask per
// region scans all 1,024 chunk slots, one IO-thread round trip each, queued behind C1GC's writes,
// and every biomes/noise step near that region blocks until it ends. Here one IO-thread task
// checks whether the region holds any chunk at all (on disk or pending); only if it does is
// vanilla's scan run.
object OldChunkScan {
    @JvmField val enabled =
        System.getProperty("orion.oldChunkScan", if (System.getProperty("scheduler") == "orion5.9") "skipEmpty" else "vanilla") == "skipEmpty"
    @Volatile @JvmField var patched = false
    private val skipped = LongAdder()
    private val scanned = LongAdder()

    private class Handles(worker: Class<*>) {
        val submitTask: Method = worker.getDeclaredMethod("submitTask", Supplier::class.java).apply { isAccessible = true }
        val regionEmpty: Method = worker.getMethod("orionRegionEmpty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val scanRegion: Method = worker.getMethod("orionScanRegion", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }

    @Volatile private var handles: Handles? = null
    @Volatile private var storageEmpty: Method? = null

    @JvmStatic
    fun scan(worker: Any, regionX: Int, regionZ: Int): CompletableFuture<BitSet> {
        val h = handles ?: Handles(worker.javaClass).also { handles = it }
        @Suppress("UNCHECKED_CAST")
        val empty = h.submitTask.invoke(worker, Supplier { h.regionEmpty.invoke(worker, regionX, regionZ) }) as CompletableFuture<Boolean>
        return empty.thenCompose { isEmpty ->
            if (isEmpty) {
                skipped.increment()
                CompletableFuture.completedFuture(BitSet())
            } else {
                scanned.increment()
                @Suppress("UNCHECKED_CAST")
                h.scanRegion.invoke(worker, regionX, regionZ) as CompletableFuture<BitSet>
            }
        }
    }

    // IO thread only. RegionFileStorage is patched separately, so IOWorker reaches it by reflection.
    @JvmStatic
    fun storageEmpty(storage: Any, regionX: Int, regionZ: Int): Boolean {
        val method = storageEmpty ?: storage.javaClass.getMethod(
            "orionRegionEmpty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).also { storageEmpty = it }
        return method.invoke(storage, regionX, regionZ) as Boolean
    }

    fun report() = "mode=${if (enabled) "skipEmpty" else "vanilla"} patched=$patched skippedRegions=${skipped.sum()} scannedRegions=${scanned.sum()}"
}

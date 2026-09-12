package io.github.eath1283.worldgend

import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

// #62: v3/v4 held in-flight centers 2*8 chunks apart and funneled admission through a coarse
// lock, but the real ceiling was vanilla's serial "worldgen" lane, not neighbor conflicts.
// With OrionParallelSteps (-Dorion.patchParallelSteps=true) executing steps concurrently,
// vanilla's own GenerationChunkHolder bookkeeping (acquireStatusBump CAS, per-layer dependency
// waits) already resolves overlapping requests, so admission is just a bounded window.
class OrionV5(
    private val mc: Mc,
    private val dedicatedServer: Any,
    private val chunkSource: Any,
    getChunkFuture: Method,
    private val fullStatus: Any,
    private val pollTask: Method,
    private val mainThreadProcessor: Any,
    private val maxInFlight: Int,
) {
    companion object {
        private const val POLL_BACKOFF_FLOOR_NANOS = 1_000L
        private const val POLL_BACKOFF_CAP_NANOS = 100_000L
    }

    data class Result(val ok: Int, val failed: Int, val totalMs: Long)

    val chunkMspc: MutableList<Double> = Collections.synchronizedList(mutableListOf())

    private val getChunkFutureHandle = MethodHandles.lookup().unreflect(getChunkFuture)

    fun fill(
        coords: List<Pair<Int, Int>>,
        onComplete: (cx: Int, cz: Int, success: Boolean, result: Any?, error: Any?) -> Unit = { _, _, _, _, _ -> },
    ): Result {
        val start = System.nanoTime()
        val permits = Semaphore(maxInFlight)
        val completions = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val futures = arrayOfNulls<CompletableFuture<Any?>>(coords.size)

        // Single caller of pollTask(), same #34 constraint as every scheduler since v2.
        // stderr is unreliable after Bootstrap (#56), so a dead poller surfaces through fill() instead.
        val pollFailure = AtomicReference<Throwable>()
        val pollThread = Thread({
            var backoffNanos = 0L
            try {
                while (!stop.get()) {
                    val ranServer = pollTask.call(dedicatedServer) as Boolean
                    val ranChunks = pollTask.call(mainThreadProcessor) as Boolean
                    backoffNanos = if (ranServer || ranChunks) 0L
                        else minOf(maxOf(backoffNanos * 2, POLL_BACKOFF_FLOOR_NANOS), POLL_BACKOFF_CAP_NANOS)
                    if (backoffNanos > 0L) LockSupport.parkNanos(backoffNanos)
                }
            } catch (t: Throwable) {
                pollFailure.set(t)
            }
        }, "orion5-poll").apply { isDaemon = true; start() }

        val submitThread = Thread({
            for ((i, coord) in coords.withIndex()) {
                permits.acquireUninterruptibly()
                val submitNanos = System.nanoTime()
                @Suppress("UNCHECKED_CAST")
                val future = getChunkFutureHandle.invoke(chunkSource, coord.first, coord.second, fullStatus, true) as CompletableFuture<Any?>
                futures[i] = future
                future.whenComplete { _, _ ->
                    chunkMspc.add((System.nanoTime() - submitNanos) / 1_000_000.0)
                    permits.release()
                    completions.incrementAndGet()
                }
            }
        }, "orion5-submit").apply { isDaemon = true; start() }

        while (completions.get() < coords.size) {
            pollFailure.get()?.let { throw IllegalStateException("orion5-poll died", it) }
            LockSupport.parkNanos(200_000)
        }
        stop.set(true)
        submitThread.join()
        pollThread.join()

        var ok = 0
        var failed = 0
        for ((i, future) in futures.withIndex()) {
            val (cx, cz) = coords[i]
            val result = future!!.join()!!
            if (mc.publicMethodCached(result.javaClass, "isSuccess").call(result) as Boolean) {
                ok++
                onComplete(cx, cz, true, result, null)
            } else {
                failed++
                onComplete(cx, cz, false, null, mc.publicMethodCached(result.javaClass, "getError").call(result))
            }
        }
        return Result(ok, failed, (System.nanoTime() - start) / 1_000_000)
    }
}

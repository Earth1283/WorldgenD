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
        onProgress: (completed: Int, total: Int, elapsedMs: Long) -> Unit = { _, _, _ -> },
        // Runs on the poll thread between pollTask calls: main-thread work outside any vanilla task.
        onPoll: () -> Boolean = { false },
    ): Result {
        val start = System.nanoTime()
        val permits = Semaphore(maxInFlight)
        val completions = AtomicInteger(0)
        val progressEvery = maxOf(1, coords.size / 100)
        val stop = AtomicBoolean(false)
        val ok = AtomicInteger(0)
        val failed = AtomicInteger(0)

        // Single caller of pollTask(), same #34 constraint as every scheduler since v2.
        // stderr is unreliable after Bootstrap (#56), so a dead poller surfaces through fill() instead.
        val pollFailure = AtomicReference<Throwable>()
        val pollThread = Thread({
            var backoffNanos = 0L
            try {
                while (!stop.get()) {
                    val ranServer = pollTask.call(dedicatedServer) as Boolean
                    val ranChunks = pollTask.call(mainThreadProcessor) as Boolean
                    val ranHook = onPoll()
                    backoffNanos = if (ranServer || ranChunks || ranHook) 0L
                        else minOf(maxOf(backoffNanos * 2, POLL_BACKOFF_FLOOR_NANOS), POLL_BACKOFF_CAP_NANOS)
                    if (backoffNanos > 0L) LockSupport.parkNanos(backoffNanos)
                }
            } catch (t: Throwable) {
                pollFailure.set(t)
            }
        }, "orion5-poll").apply { isDaemon = true; start() }

        // onComplete used to fire from a loop *after* every chunk had already finished (a
        // second pass over a `futures` array kept alive for the whole run), so nothing that
        // relies on it -- like evicting a chunk once it's done -- ever ran until it was too
        // late to matter: every chunk was already resident. Firing it straight from
        // whenComplete (per-chunk, as it actually finishes) is what makes streaming eviction
        // possible; a future's own result is dropped as soon as its callback returns instead
        // of being retained in an array for the whole fill(). A future completing
        // exceptionally (not a normal ChunkResult.isSuccess()==false, but a real thrown
        // exception) is fatal, same as the old code's unchecked `future.join()!!` -- recorded
        // here and rethrown from the wait loop instead of thrown from inside a worker thread.
        val fillFailure = AtomicReference<Throwable>()
        val submitThread = Thread({
            for (coord in coords) {
                permits.acquireUninterruptibly()
                val submitNanos = System.nanoTime()
                @Suppress("UNCHECKED_CAST")
                val future = getChunkFutureHandle.invoke(chunkSource, coord.first, coord.second, fullStatus, true) as CompletableFuture<Any?>
                future.whenComplete { result, error ->
                    chunkMspc.add((System.nanoTime() - submitNanos) / 1_000_000.0)
                    permits.release()
                    if (error != null) {
                        fillFailure.compareAndSet(null, error)
                    } else if (mc.publicMethodCached(result!!.javaClass, "isSuccess").call(result) as Boolean) {
                        ok.incrementAndGet()
                        onComplete(coord.first, coord.second, true, result, null)
                    } else {
                        failed.incrementAndGet()
                        onComplete(coord.first, coord.second, false, null, mc.publicMethodCached(result.javaClass, "getError").call(result))
                    }
                    val completed = completions.incrementAndGet()
                    if (completed == coords.size || completed % progressEvery == 0) {
                        onProgress(completed, coords.size, (System.nanoTime() - start) / 1_000_000)
                    }
                }
            }
        }, "orion5-submit").apply { isDaemon = true; start() }

        while (completions.get() < coords.size) {
            pollFailure.get()?.let { throw IllegalStateException("orion5-poll died", it) }
            fillFailure.get()?.let { throw IllegalStateException("chunk future completed exceptionally", it) }
            LockSupport.parkNanos(200_000)
        }
        stop.set(true)
        submitThread.join()
        pollThread.join()
        fillFailure.get()?.let { throw IllegalStateException("chunk future completed exceptionally", it) }

        return Result(ok.get(), failed.get(), (System.nanoTime() - start) / 1_000_000)
    }
}

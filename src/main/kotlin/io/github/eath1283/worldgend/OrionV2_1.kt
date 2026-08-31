package io.github.eath1283.worldgend

import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

// v2 + a spatial-grid candidate index instead of a full-backlog rescan (#27-#29). isSafe()
// stays the sole dispatch authority; the grid only narrows what gets checked.
class OrionV2_1(
    private val mc: Mc,
    private val dedicatedServer: Any,
    private val chunkSource: Any,
    private val getChunkFuture: Method,
    private val fullStatus: Any,
    private val pollTask: Method,
    private val mainThreadProcessor: Any,
    private val dispatchThreads: Int,
    private val maxInFlight: Int,
    private val lockRadius: Int = Orion.DEPENDENCY_RADIUS,
    private val telemetry: File? = null,
) {
    companion object {
        private const val POLL_BACKOFF_FLOOR_NANOS = 1_000L
        private const val POLL_BACKOFF_CAP_NANOS = 100_000L
    }

    data class Result(val ok: Int, val failed: Int, val totalMs: Long)
    data class Submitted(val cx: Int, val cz: Int, val future: CompletableFuture<Any?>)

    val chunkMspc = Collections.synchronizedList(mutableListOf<Double>())

    private val alreadyClaimed = ConcurrentHashMap.newKeySet<Long>()
    private val telemetryStart = System.nanoTime()

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

    private fun logTelemetry(event: String, cx: Int, cz: Int) {
        val log = telemetry ?: return
        val elapsedMs = (System.nanoTime() - telemetryStart) / 1_000_000
        synchronized(log) { log.appendText("$elapsedMs $event $cx,$cz\n") }
    }

    fun fill(
        coords: List<Pair<Int, Int>>,
        onComplete: (cx: Int, cz: Int, success: Boolean, result: Any?, error: Any?) -> Unit = { _, _, _, _, _ -> },
    ): Result {
        val start = System.nanoTime()
        val target = coords.filter { (cx, cz) -> alreadyClaimed.add(key(cx, cz)) }

        val pending = PendingSpatialIndex(target, 2 * lockRadius)

        val heldCenters = mutableListOf<Pair<Int, Int>>()
        val releaseQueue = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val completionQueue = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val allFutures = Collections.synchronizedList(mutableListOf<Submitted>())
        val completions = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        var cursor = 0

        fun isSafe(cx: Int, cz: Int) = heldCenters.none { (hx, hz) ->
            Math.abs(hx - cx) <= 2 * lockRadius && Math.abs(hz - cz) <= 2 * lockRadius
        }

        val workers = (0 until dispatchThreads).map { i ->
            Thread({
                while (!stop.get() || releaseQueue.isNotEmpty()) {
                    val coord = releaseQueue.poll()
                    if (coord == null) {
                        LockSupport.parkNanos(50_000)
                        continue
                    }
                    val (cx, cz) = coord
                    logTelemetry("DISPATCH", cx, cz)
                    val submitNanos = System.nanoTime()
                    @Suppress("UNCHECKED_CAST")
                    val future = getChunkFuture.call(chunkSource, cx, cz, fullStatus, true) as CompletableFuture<Any?>
                    future.whenComplete { _, _ ->
                        chunkMspc.add((System.nanoTime() - submitNanos) / 1_000_000.0)
                        completionQueue.add(coord)
                        logTelemetry("COMPLETE", cx, cz)
                    }
                    allFutures.add(Submitted(cx, cz, future))
                }
            }, "orion2.1-worker-$i").apply { isDaemon = true; start() }
        }

        var pollBackoffNanos = 0L
        var nextPollNanos = System.nanoTime()

        while (completions.get() < target.size) {
            var progressed = false
            var completedThisTick = false

            while (true) {
                val done = completionQueue.poll() ?: break
                heldCenters.remove(done)
                completions.incrementAndGet()
                progressed = true
                completedThisTick = true
                pending.reconsiderNear(done)
            }

            while (heldCenters.size < maxInFlight) {
                var candidate = pending.takeEligible { isSafe(it.first, it.second) }
                while (candidate == null && cursor < target.size) {
                    val frontier = target[cursor++]
                    if (pending.contains(frontier) && isSafe(frontier.first, frontier.second)) {
                        pending.remove(frontier)
                        candidate = frontier
                    }
                }
                if (candidate != null) {
                    heldCenters.add(candidate)
                    releaseQueue.add(candidate)
                    progressed = true
                } else break
            }

            val now = System.nanoTime()
            var taskRan = false
            if (completedThisTick || now >= nextPollNanos) {
                val a = pollTask.call(dedicatedServer) as Boolean
                val b = pollTask.call(mainThreadProcessor) as Boolean
                taskRan = a || b
                pollBackoffNanos = if (taskRan) 0L
                    else minOf(maxOf(pollBackoffNanos * 2, POLL_BACKOFF_FLOOR_NANOS), POLL_BACKOFF_CAP_NANOS)
                nextPollNanos = now + pollBackoffNanos
            }

            if (!progressed && !taskRan) LockSupport.parkNanos(50_000)
        }

        stop.set(true)
        workers.forEach { it.join() }

        val allDone = CompletableFuture.allOf(*allFutures.map { it.future }.toTypedArray())
        allDone.join()

        var ok = 0
        var failed = 0
        for ((cx, cz, future) in allFutures) {
            val result = future.join()!!
            val isSuccess = mc.publicMethodCached(result.javaClass, "isSuccess").call(result) as Boolean
            if (isSuccess) {
                ok++
                onComplete(cx, cz, true, result, null)
            } else {
                failed++
                val error = mc.publicMethodCached(result.javaClass, "getError").call(result)
                onComplete(cx, cz, false, null, error)
            }
        }

        return Result(ok, failed, (System.nanoTime() - start) / 1_000_000)
    }
}

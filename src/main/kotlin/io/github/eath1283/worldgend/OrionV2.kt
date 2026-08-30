package io.github.eath1283.worldgend

import java.io.File
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

// Escapes #25's unexplained ReentrantAreaLock discrepancy by construction rather than by
// fixing it: pending/heldCenters are touched only by the scheduler thread (this fill() call's
// own caller), never by the worker threads, so there is no concurrent access to the conflict
// state left to have a bug in. Workers only push/pop two plain queues.
class OrionV2(
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
        val pending = ArrayDeque(target)
        val heldCenters = mutableListOf<Pair<Int, Int>>()
        val releaseQueue = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val completionQueue = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val allFutures = Collections.synchronizedList(mutableListOf<Submitted>())
        val completions = AtomicInteger(0)
        val stop = AtomicBoolean(false)

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
            }, "orion2-worker-$i").apply { isDaemon = true; start() }
        }

        while (completions.get() < target.size) {
            var progressed = false

            while (true) {
                val done = completionQueue.poll() ?: break
                heldCenters.remove(done)
                completions.incrementAndGet()
                progressed = true
            }

            val it = pending.iterator()
            while (it.hasNext() && heldCenters.size < maxInFlight) {
                val candidate = it.next()
                if (isSafe(candidate.first, candidate.second)) {
                    it.remove()
                    heldCenters.add(candidate)
                    releaseQueue.add(candidate)
                    progressed = true
                }
            }

            val a = pollTask.call(dedicatedServer) as Boolean
            val b = pollTask.call(mainThreadProcessor) as Boolean
            if (!progressed && !a && !b) LockSupport.parkNanos(50_000)
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

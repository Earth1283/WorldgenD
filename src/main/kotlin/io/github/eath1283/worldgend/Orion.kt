package io.github.eath1283.worldgend

import ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock
import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BooleanSupplier

class Orion(
    private val mc: Mc,
    private val dedicatedServer: Any,
    private val chunkSource: Any,
    private val getChunkFuture: Method,
    private val fullStatus: Any,
    private val managedBlock: Method,
    maxInFlight: Int,
    private val lockRadius: Int = DEPENDENCY_RADIUS,
    private val telemetry: File? = null,
) {
    companion object {
        const val DEPENDENCY_RADIUS = 8
    }

    data class Result(val ok: Int, val failed: Int, val totalMs: Long)

    val chunkMspc = Collections.synchronizedList(mutableListOf<Double>())

    private val areaLock = ReentrantAreaLock(4)
    private val inFlightPermits = Semaphore(maxInFlight)
    private val alreadyClaimed = ConcurrentHashMap.newKeySet<Long>()
    private val completions = AtomicLong(0)
    private val currentlyInFlight = AtomicInteger(0)
    private val telemetryStart = System.nanoTime()

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

    private fun logTelemetry(event: String, cx: Int, cz: Int, remainingAfter: Int) {
        val log = telemetry ?: return
        val elapsedMs = (System.nanoTime() - telemetryStart) / 1_000_000
        synchronized(log) {
            log.appendText("$elapsedMs $event $cx,$cz inFlight=${currentlyInFlight.get()} remaining=$remainingAfter\n")
        }
    }

    fun fill(
        coords: List<Pair<Int, Int>>,
        onComplete: (cx: Int, cz: Int, success: Boolean, result: Any?, error: Any?) -> Unit = { _, _, _, _, _ -> },
    ): Result {
        val start = System.nanoTime()
        val target = coords.filter { (cx, cz) -> alreadyClaimed.add(key(cx, cz)) }
        var remaining = ArrayDeque(target.map { it })
        data class Submitted(val cx: Int, val cz: Int, val future: CompletableFuture<Any?>)
        val allFutures = mutableListOf<Submitted>()

        while (remaining.isNotEmpty()) {
            val deferred = ArrayDeque<Pair<Int, Int>>()
            var dispatchedThisSweep = 0

            while (remaining.isNotEmpty()) {
                val (cx, cz) = remaining.removeFirst()
                if (!inFlightPermits.tryAcquire()) {
                    deferred.add(cx to cz)
                    continue
                }
                val node = areaLock.tryLock(cx, cz, lockRadius)
                if (node == null) {
                    inFlightPermits.release()
                    deferred.add(cx to cz)
                    continue
                }
                dispatchedThisSweep++
                currentlyInFlight.incrementAndGet()
                logTelemetry("DISPATCH", cx, cz, remaining.size)
                val submitNanos = System.nanoTime()
                @Suppress("UNCHECKED_CAST")
                val future = getChunkFuture.call(chunkSource, cx, cz, fullStatus, true) as CompletableFuture<Any?>
                val callMs = (System.nanoTime() - submitNanos) / 1_000_000.0
                telemetry?.let { synchronized(it) { it.appendText("CALL $cx,$cz took=${callMs}ms\n") } }
                future.whenComplete { _, _ ->
                    chunkMspc.add((System.nanoTime() - submitNanos) / 1_000_000.0)
                    areaLock.unlock(node)
                    inFlightPermits.release()
                    completions.incrementAndGet()
                    currentlyInFlight.decrementAndGet()
                    logTelemetry("COMPLETE", cx, cz, remaining.size)
                }
                allFutures.add(Submitted(cx, cz, future))
            }

            remaining = deferred
            if (remaining.isNotEmpty() && dispatchedThisSweep == 0) {
                val waitFrom = completions.get()
                managedBlock.call(dedicatedServer, BooleanSupplier { completions.get() != waitFrom })
            }
        }

        val allDone = CompletableFuture.allOf(*allFutures.map { it.future }.toTypedArray())
        managedBlock.call(dedicatedServer, BooleanSupplier { allDone.isDone })
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

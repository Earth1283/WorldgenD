package io.github.eath1283.worldgend

import ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock
import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
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
    // v1.1: getChunkFuture() only takes vanilla's non-blocking async path (#24's bytecode
    // trace of ServerChunkCache.getChunkFuture) when called from a thread other than the
    // one registered as "mainThread" at DedicatedServer construction.
    private val pollTask: Method? = null,
    private val mainThreadProcessor: Any? = null,
    private val dispatchThreads: Int = 1,
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
    private val heldBoxes = mutableListOf<Pair<Int, Int>>()
    val overlapViolations = AtomicInteger(0)

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
    ): Result = if (dispatchThreads > 1) fillMultiThreaded(coords, onComplete) else fillSingleThreaded(coords, onComplete)

    private fun fillSingleThreaded(
        coords: List<Pair<Int, Int>>,
        onComplete: (cx: Int, cz: Int, success: Boolean, result: Any?, error: Any?) -> Unit,
    ): Result {
        val start = System.nanoTime()
        val target = coords.filter { (cx, cz) -> alreadyClaimed.add(key(cx, cz)) }
        var remaining = ArrayDeque(target.map { it })
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

    data class Submitted(val cx: Int, val cz: Int, val future: CompletableFuture<Any?>)

    private fun fillMultiThreaded(
        coords: List<Pair<Int, Int>>,
        onComplete: (cx: Int, cz: Int, success: Boolean, result: Any?, error: Any?) -> Unit,
    ): Result {
        val poll = requireNotNull(pollTask) { "dispatchThreads > 1 requires pollTask" }
        val mtp = requireNotNull(mainThreadProcessor) { "dispatchThreads > 1 requires mainThreadProcessor" }

        val start = System.nanoTime()
        val target = coords.filter { (cx, cz) -> alreadyClaimed.add(key(cx, cz)) }
        val pending = ConcurrentLinkedDeque(target)
        val allFutures = Collections.synchronizedList(mutableListOf<Submitted>())
        val completionsGoal = completions.get() + target.size

        fun dispatchOne(): Boolean {
            val coord = pending.pollFirst() ?: return false
            val (cx, cz) = coord
            if (!inFlightPermits.tryAcquire()) {
                pending.addLast(coord)
                return false
            }
            val node = areaLock.tryLock(cx, cz, lockRadius)
            if (node == null) {
                inFlightPermits.release()
                pending.addLast(coord)
                return false
            }
            synchronized(heldBoxes) {
                for ((hx, hz) in heldBoxes) {
                    if (Math.abs(hx - cx) <= 2 * lockRadius && Math.abs(hz - cz) <= 2 * lockRadius) {
                        overlapViolations.incrementAndGet()
                        telemetry?.let { synchronized(it) { it.appendText("VIOLATION [$cx,$cz] overlaps held [$hx,$hz]\n") } }
                    }
                }
                heldBoxes.add(cx to cz)
            }
            currentlyInFlight.incrementAndGet()
            logTelemetry("DISPATCH", cx, cz, pending.size)
            val submitNanos = System.nanoTime()
            @Suppress("UNCHECKED_CAST")
            val future = getChunkFuture.call(chunkSource, cx, cz, fullStatus, true) as CompletableFuture<Any?>
            future.whenComplete { _, _ ->
                chunkMspc.add((System.nanoTime() - submitNanos) / 1_000_000.0)
                synchronized(heldBoxes) { heldBoxes.remove(cx to cz) }
                areaLock.unlock(node)
                inFlightPermits.release()
                completions.incrementAndGet()
                currentlyInFlight.decrementAndGet()
                logTelemetry("COMPLETE", cx, cz, pending.size)
            }
            allFutures.add(Submitted(cx, cz, future))
            return true
        }

        val workers = (0 until dispatchThreads).map { i ->
            Thread({
                while (completions.get() < completionsGoal) {
                    if (!dispatchOne()) LockSupport.parkNanos(50_000)
                }
            }, "orion-dispatch-$i").apply { isDaemon = true; start() }
        }

        while (completions.get() < completionsGoal) {
            val a = poll.call(dedicatedServer) as Boolean
            val b = poll.call(mtp) as Boolean
            if (!a && !b) LockSupport.parkNanos(50_000)
        }
        workers.forEach { it.join() }

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

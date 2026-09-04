package io.github.eath1283.worldgend

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock

// v2.1's architecture, minus its one single-threaded admission loop. Idea #4 from the
// #41-#43 tuning pass: isSafe() stays the sole dispatch authority (untouched semantics),
// but now runs under one coarse lock so any of the dispatch threads can claim a candidate
// and submit it themselves, instead of funneling every admission decision through one
// dedicated scheduler thread. Deliberately NOT v1's mistake: v1's unexplained
// overlapViolations (#25) came from a third-party lock library's own behavior under real
// JVM conditions, never fully diagnosed. Here the critical section is one explicit,
// auditable `synchronized` block around plain data structures we already trust
// (HeldCenterIndex, PendingSpatialIndex) — no external locking library involved.
//
// Mojang's own pollTask() funnel (#34) must still be called from exactly one thread —
// pendingGenerationTasks is a plain unsynchronized ArrayList, safe only because a single
// caller drains it. That stays a dedicated poll thread, fully decoupled from admission
// (same decoupling #41 already proved out on v2.1).
class OrionV3(
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
    // #50: safety-net timeout on workAvailable.await(), not a busy-poll interval — every
    // release already signals directly via releaseLockedAndSignal(). Configurable per #54's
    // sweep of whether a shorter ceiling moves tail latency; default reproduces #49/#50 exactly.
    // #54: p99 tail latency dropped ~35% (3127->2028ms) going from 50ms to 10ms, eMSPC
    // flat inside the ~9% noise band across the whole sweep — no measured throughput
    // downside, so 10ms is now the code-level default (was 50ms).
    private val waitCeilingMs: Long = 10L,
) {
    companion object {
        private const val POLL_BACKOFF_FLOOR_NANOS = 1_000L
        private const val POLL_BACKOFF_CAP_NANOS = 100_000L

        // #47's deadlock: pollTask() on mainThreadProcessor can synchronously run a task
        // that recursively needs pollTask() called again on the same executor to finish,
        // and there is only one thread allowed to call it (#34's single-caller safety
        // requirement on ChunkMap.pendingGenerationTasks). #48 tried draining the queue
        // directly from a second thread as a rescue — reverted, see #48: the live
        // evidence (500+ permanently-blocked threads within 90s and still climbing)
        // shows this isn't a finite recursive chain more polling can resolve, it behaves
        // like a self-perpetuating or circular wait between concurrently in-flight
        // candidates. Not fixed. pollHeartbeatNanos below stays for diagnosis only.
    }

    data class Result(val ok: Int, val failed: Int, val totalMs: Long)
    data class Submitted(val cx: Int, val cz: Int, val future: CompletableFuture<Any?>)

    val chunkMspc = Collections.synchronizedList(mutableListOf<Double>())

    private val alreadyClaimed = ConcurrentHashMap.newKeySet<Long>()
    private val telemetryStart = System.nanoTime()

    // #50: getChunkFuture.call(...) on the hot per-chunk dispatch path went through plain
    // reflective Method.invoke (0.19% of total CPU, 55/29160 JFR samples). Resolved once
    // here instead of per chunk.
    private val getChunkFutureHandle = MethodHandles.lookup().unreflect(getChunkFuture)

    private fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

    private fun logTelemetry(event: String, cx: Int, cz: Int) {
        val log = telemetry ?: return
        val elapsedMs = (System.nanoTime() - telemetryStart) / 1_000_000
        synchronized(log) { log.appendText("$elapsedMs $event $cx,$cz\n") }
    }

    // Diagnostic only (#45 follow-up). Grabs Mojang's own background ForkJoinPool via
    // any live Worker-Main thread's getPool() (ForkJoinWorkerThread is public API) and
    // reads its own counters — no assumption about where the pool is stored, just asking
    // a live worker what pool it belongs to.
    private fun fjpStatsFromLiveWorker(): String {
        val allThreads = Thread.getAllStackTraces().keys
        val workers = allThreads.filter { it.name.startsWith("Worker-Main") }
        val runnable = workers.count { it.state == Thread.State.RUNNABLE }
        val waiting = workers.count { it.state == Thread.State.WAITING || it.state == Thread.State.TIMED_WAITING }
        val blockedOnJoin = workers.count { t ->
            t.stackTrace.any { it.className == "java.util.concurrent.CompletableFuture" && it.methodName == "join" }
        }
        val worker = workers.firstOrNull { it is java.util.concurrent.ForkJoinWorkerThread }
            as? java.util.concurrent.ForkJoinWorkerThread
            ?: return "workerMainRunnable=$runnable workerMainWaiting=$waiting workerMainBlockedOnJoin=$blockedOnJoin fjp=none-live"
        val pool = worker.pool ?: return "workerMainRunnable=$runnable workerMainWaiting=$waiting workerMainBlockedOnJoin=$blockedOnJoin fjp=no-pool"
        return "workerMainRunnable=$runnable workerMainWaiting=$waiting workerMainBlockedOnJoin=$blockedOnJoin " +
            "fjpPoolSize=${pool.poolSize} fjpActive=${pool.activeThreadCount} " +
            "fjpRunning=${pool.runningThreadCount} fjpQueuedTasks=${pool.queuedTaskCount} " +
            "fjpQueuedSubmissions=${pool.queuedSubmissionCount} fjpParallelism=${pool.parallelism}"
    }

    // Diagnostic only (#46 follow-up). ChunkMap.pendingGenerationTasks is a *different*
    // queue from the ForkJoinPool's own (confirmed via javap, no decompiler, matching
    // this project's own discipline): ServerChunkCache.chunkMap -> ChunkMap
    // .pendingGenerationTasks: List<ChunkGenerationTask>, each with a `pos: ChunkPos`
    // (private x/z) and public final `targetStatus`. This is the queue orion3-poll
    // alone drains (#34) — reading it directly answers "was this neighbor even
    // scheduled, and by whom" instead of inferring from FJP-level counters that don't
    // see this queue at all.
    private fun dumpPendingGenerationTasks(heldNow: List<Pair<Int, Int>>, pendingIndexContains: (Pair<Int, Int>) -> Boolean): String {
        return try {
            val cChunkMap = mc.c("net.minecraft.server.level.ChunkMap")
            val cChunkGenerationTask = mc.c("net.minecraft.server.level.ChunkGenerationTask")
            val cChunkPos = mc.c("net.minecraft.world.level.ChunkPos")
            val cServerChunkCache = mc.c("net.minecraft.server.level.ServerChunkCache")
            val chunkMap = mc.field(cServerChunkCache, "chunkMap", chunkSource)!!
            @Suppress("UNCHECKED_CAST")
            val tasks = mc.field(cChunkMap, "pendingGenerationTasks", chunkMap) as List<Any>
            if (tasks.isEmpty()) return "pendingGenerationTasks=EMPTY (nothing queued for orion3-poll to drain)"
            val sb = StringBuilder("pendingGenerationTasks=${tasks.size}:\n")
            for (task in tasks) {
                val pos = mc.field(cChunkGenerationTask, "pos", task)!!
                val tx = mc.field(cChunkPos, "x", pos) as Int
                val tz = mc.field(cChunkPos, "z", pos) as Int
                val status = mc.field(cChunkGenerationTask, "targetStatus", task)
                val coord = tx to tz
                sb.append(
                    "  ($tx,$tz) targetStatus=$status " +
                        "orionHeld=${coord in heldNow} orionPending=${pendingIndexContains(coord)}\n"
                )
            }
            sb.toString()
        } catch (t: Throwable) {
            "pendingGenerationTasks read failed: ${t}"
        }
    }

    fun fill(
        coords: List<Pair<Int, Int>>,
        onComplete: (cx: Int, cz: Int, success: Boolean, result: Any?, error: Any?) -> Unit = { _, _, _, _, _ -> },
    ): Result {
        val start = System.nanoTime()
        val target = coords.filter { (cx, cz) -> alreadyClaimed.add(key(cx, cz)) }

        val lock = ReentrantLock()
        val workAvailable = lock.newCondition()
        val pending = PendingSpatialIndex(target, 2 * lockRadius)
        val heldCenters = HeldCenterIndex(2 * lockRadius)
        var cursor = 0

        val allFutures = Collections.synchronizedList(mutableListOf<Submitted>())
        val completions = AtomicInteger(0)
        val stop = AtomicBoolean(false)

        fun isSafe(cx: Int, cz: Int) = heldCenters.isSafe(cx, cz)

        // Must run under `lock`: pulls one claimable candidate (spatial index first, then
        // the cursor frontier fallback) and marks it held, or returns null if none is
        // admissible right now (backlog empty, or every remaining candidate conflicts).
        fun claimOneLocked(): Pair<Int, Int>? {
            if (heldCenters.size >= maxInFlight) return null
            var candidate = pending.takeEligible { isSafe(it.first, it.second) }
            while (candidate == null && cursor < target.size) {
                val frontier = target[cursor++]
                if (pending.contains(frontier) && isSafe(frontier.first, frontier.second)) {
                    pending.remove(frontier)
                    candidate = frontier
                }
            }
            if (candidate != null) heldCenters.add(candidate)
            return candidate
        }

        // Sleeps on `workAvailable` instead of busy-polling the lock (#44's diagnosed
        // regression — 8 threads spinning on a monitor every 50us starved everything
        // else, including vanilla's own worker-pool ramp-up). The 50ms timeout is a
        // safety net against a missed signal, not the normal wakeup path.
        fun claimOrWait(): Pair<Int, Int>? {
            lock.lock()
            try {
                var candidate = claimOneLocked()
                while (candidate == null && !stop.get()) {
                    workAvailable.await(waitCeilingMs, TimeUnit.MILLISECONDS)
                    candidate = claimOneLocked()
                }
                return candidate
            } finally {
                lock.unlock()
            }
        }

        fun releaseLockedAndSignal(coord: Pair<Int, Int>) {
            lock.lock()
            try {
                heldCenters.remove(coord)
                pending.reconsiderNear(coord)
                workAvailable.signalAll()
            } finally {
                lock.unlock()
            }
        }

        val dispatchers = (0 until dispatchThreads).map { i ->
            Thread({
                while (true) {
                    val candidate = claimOrWait() ?: break
                    val (cx, cz) = candidate
                    logTelemetry("DISPATCH", cx, cz)
                    val submitNanos = System.nanoTime()
                    @Suppress("UNCHECKED_CAST")
                    val future = getChunkFutureHandle.invoke(chunkSource, cx, cz, fullStatus, true) as CompletableFuture<Any?>
                    future.whenComplete { _, _ ->
                        chunkMspc.add((System.nanoTime() - submitNanos) / 1_000_000.0)
                        releaseLockedAndSignal(candidate)
                        completions.incrementAndGet()
                        logTelemetry("COMPLETE", cx, cz)
                    }
                    allFutures.add(Submitted(cx, cz, future))
                }
            }, "orion3-dispatch-$i").apply { isDaemon = true; start() }
        }

        val diagFile = telemetry?.let { File(it.parentFile, "orion3_diag.log") }
            ?: File(System.getProperty("java.io.tmpdir"), "orion3_diag.log")
        diagFile.writeText("")

        // Diagnosis only (#47): tracks how long the primary poller has gone without
        // returning from a pollTask() call. Nothing acts on staleness since #48 reverted
        // the rescue attempt — kept because it's what proved the deadlock live in #46/#47.
        val pollHeartbeatNanos = java.util.concurrent.atomic.AtomicLong(System.nanoTime())

        val pollThread = Thread({
            var pollBackoffNanos = 0L
            while (!stop.get()) {
                pollHeartbeatNanos.set(System.nanoTime())
                val a = pollTask.call(dedicatedServer) as Boolean
                val b = pollTask.call(mainThreadProcessor) as Boolean
                pollHeartbeatNanos.set(System.nanoTime())
                val taskRan = a || b
                pollBackoffNanos = if (taskRan) 0L
                    else minOf(maxOf(pollBackoffNanos * 2, POLL_BACKOFF_FLOOR_NANOS), POLL_BACKOFF_CAP_NANOS)
                if (pollBackoffNanos > 0L) LockSupport.parkNanos(pollBackoffNanos)
            }
        }, "orion3-poll").apply { isDaemon = true; start() }

        // #47's rescue attempt REVERTED (#48): draining mainThreadProcessor's
        // pendingRunnables directly during a stall is not a fix, it's worse than the
        // original deadlock. Live evidence: a single synchronous rescuer just got stuck
        // the same way the primary did (one rescue, then silence); handing each drained
        // task to a disposable worker thread instead didn't resolve anything either —
        // it fired every ~100ms indefinitely, each new worker immediately blocking too,
        // producing 500+ permanently-stuck threads within 90 seconds and climbing with
        // no sign of ever stopping. mainThreadProcessor.pendingTasksCount returning to
        // exactly 1 after every single drain, forever, is itself the tell: this isn't a
        // finite recursive chain that more draining capacity eventually resolves, it
        // behaves like a self-perpetuating or circular wait between two or more
        // concurrently in-flight candidates' own recursive structure-check dependencies
        // — something only this project's own higher admission concurrency can create
        // in the first place (v2.1's naturally-paced single-threaded admission likely
        // never has two candidates whose recursive checks reference each other in
        // flight at the same time). No amount of extra polling/rescue threads can break
        // an actual cycle; it can only keep leaking threads chasing it. pollHeartbeat
        // stays (harmless, useful for diagnosis) but nothing acts on staleness anymore.

        // Diagnostic only (#44 follow-up): if heldCenters climbs and plateaus below
        // maxInFlight while completions stalls, that's a leaked-permit bookkeeping bug,
        // not a benign ForkJoinPool idle-shrink trough. Removed once settled either way.
        // Writes straight to a file (like logTelemetry) rather than System.err — plain
        // println output doesn't reliably reach the redirected log until process exit
        // in this setup (#23), so it can't be watched live.
        val diagThread = Thread({
            var lastCompletions = -1
            var stallSamples = 0
            var dumpedThisStall = false
            while (!stop.get()) {
                lock.lock()
                val held = heldCenters.size
                val cur = cursor
                val heldNow = heldCenters.all()
                lock.unlock()
                val nowCompletions = completions.get()
                if (nowCompletions == lastCompletions && nowCompletions > 0) stallSamples++
                else { stallSamples = 0; dumpedThisStall = false }
                lastCompletions = nowCompletions

                val fjpStats = fjpStatsFromLiveWorker()
                diagFile.appendText(
                    "elapsedMs=${(System.nanoTime() - start) / 1_000_000} " +
                        "held=$held completions=$nowCompletions cursor=$cur target=${target.size} " +
                        "stallSamples=$stallSamples $fjpStats\n"
                )

                // #46's specific ask: on a confirmed stall (5s flat), read Mojang's own
                // pendingGenerationTasks queue directly and cross-reference every entry
                // against our own held/pending bookkeeping, once per stall. Also read
                // mainThreadProcessor's own separate pendingRunnables queue (via the
                // public getPendingTasksCount(), no field reflection needed) — the
                // live stack trace shows the block happening in an AsyncSupply pulled
                // from *that* queue, not ChunkMap's.
                if (stallSamples >= 5 && !dumpedThisStall) {
                    dumpedThisStall = true
                    val dump = dumpPendingGenerationTasks(heldNow) { coord ->
                        lock.lock()
                        try { pending.contains(coord) } finally { lock.unlock() }
                    }
                    val mtpPending = try {
                        mc.publicMethodCached(mainThreadProcessor.javaClass, "getPendingTasksCount")
                            .call(mainThreadProcessor)
                    } catch (t: Throwable) { "read failed: $t" }
                    val dsPending = try {
                        mc.publicMethodCached(dedicatedServer.javaClass, "getPendingTasksCount")
                            .call(dedicatedServer)
                    } catch (t: Throwable) { "read failed: $t" }
                    diagFile.appendText(
                        "STALL DUMP at elapsedMs=${(System.nanoTime() - start) / 1_000_000}\n" +
                            "mainThreadProcessor.pendingTasksCount=$mtpPending dedicatedServer.pendingTasksCount=$dsPending\n$dump\n"
                    )
                }
                LockSupport.parkNanos(1_000_000_000)
            }
        }, "orion3-diag").apply { isDaemon = true; start() }

        while (completions.get() < target.size) LockSupport.parkNanos(200_000)

        stop.set(true)
        lock.lock()
        try { workAvailable.signalAll() } finally { lock.unlock() }
        dispatchers.forEach { it.join() }
        pollThread.join()
        diagThread.join()

        val heldAtExit = heldCenters.size
        diagFile.appendText("EXIT heldCenters.size=$heldAtExit (expected 0)\n")

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

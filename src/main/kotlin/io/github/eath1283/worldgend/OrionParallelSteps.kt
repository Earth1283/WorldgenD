package io.github.eath1283.worldgend

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.function.BiFunction

// #62: vanilla submits every ChunkGenerationTask to one ConsecutiveExecutor("worldgen"), so
// surface/carvers/features for the whole level run one at a time. The agent reroutes
// ChunkMap.applyStep's ChunkStep.apply call here; each step then runs on the background pool
// under an area lock sized by its write radius (Moonrise's ChunkTaskScheduler model).
object OrionParallelSteps {
    // StructureStart pieces and StructureTemplate palette caches are shared across chunks and
    // mutated during placement (see C2ME's fixes-worldgen-threading-issues), so every
    // structure-touching step serializes here instead of patching each piece class.
    private val structures = ReentrantLock()

    // Moonrise's model: starts and references only write their own chunk. The shared state they
    // touch is the stronghold statics (#56) and the template palette cache (agent). Placement
    // (FEATURES) and spawn still take the lock.
    @JvmField val parallelStructureStarts =
        System.getProperty("orion.structureStarts", if (System.getProperty("scheduler") == "orion5.9") "parallel" else "serial") == "parallel"

    // global: one lock for all placement (v5.9). start: placement locks only its own StructureStart,
    // striped. none: unsafe ceiling probe, no placement lock.
    private val lockMode = System.getProperty("orion.structureLock", "global")
    private val stripes = Array(256) { ReentrantLock() }

    @JvmStatic fun lockStructures() = acquire(structures)

    @JvmStatic fun unlockStructures() = release(structures)

    @JvmStatic fun lockStart(start: Any) {
        when (lockMode) {
            "none" -> {}
            "start" -> acquire(stripes[System.identityHashCode(start) and 255])
            else -> acquire(structures)
        }
    }

    @JvmStatic fun unlockStart(start: Any) {
        when (lockMode) {
            "none" -> {}
            "start" -> release(stripes[System.identityHashCode(start) and 255])
            else -> release(structures)
        }
    }

    private fun acquire(lock: ReentrantLock) {
        if (!OrionTrace.enabled) return lock.lock()
        val since = System.nanoTime()
        lock.lock()
        if (lock.holdCount == 1) OrionTrace.structureWaited(since)
    }

    private fun release(lock: ReentrantLock) {
        if (OrionTrace.enabled && lock.holdCount == 1) OrionTrace.structureReleasing()
        lock.unlock()
    }

    private class Task(val x: Int, val z: Int, val radius: Int, val status: Int) {
        var blockers = 0
        var bypassed = 0
        fun overlaps(other: Task): Boolean {
            val reach = radius + other.radius
            return Math.abs(other.x - x) <= reach && Math.abs(other.z - z) <= reach
        }
        lateinit var body: Runnable
        // OrionTrace record, see OrionTrace.step for the layout
        val trace: LongArray? = if (OrionTrace.enabled) LongArray(9).also { it[0] = status.toLong(); it[1] = x.toLong(); it[2] = z.toLong() } else null
    }

    // Per-chunk FIFO of tasks whose square covers that chunk. A task runs once it heads every
    // queue it joined; joins happen atomically in submission order, so no cycle can form.
    private val cells = HashMap<Long, ArrayDeque<Task>>()

    // v5.9 (#83): the FIFO queues chain neighboring FEATURES squares into single file, because a
    // task queues behind waiters it does not conflict with once they run. Greedy mode starts any
    // task whose square is free; a waiter passed over BYPASS_LIMIT times reserves its square.
    private val greedy = System.getProperty("orion.areaLock", if (System.getProperty("scheduler") == "orion5.9") "greedy" else "fifo") == "greedy"
    private const val BYPASS_LIMIT = 16
    private val held = HashSet<Long>()
    private val waiting = ArrayList<Task>()
    private val bypasses = AtomicLong()

    @Volatile private var applyHandle: MethodHandle? = null
    @Volatile var executor: Executor? = null
        private set

    private val verify = System.getProperty("orion.parallelSteps.verify") == "true"
    private val runningSquares = ConcurrentHashMap.newKeySet<Task>()
    private val overlapViolations = AtomicLong()

    private val scheduled = AtomicLong()
    private val deferred = AtomicLong()
    private val inline = AtomicLong()
    private val running = AtomicInteger()
    private val maxRunning = AtomicInteger()

    @JvmStatic
    fun apply(step: Any, context: Any, cache: Any, chunk: Any, x: Int, z: Int, status: String, writeRadius: Int): CompletableFuture<*> {
        val handle = applyHandle ?: resolve(step)
        val name = status.substringAfter(':')
        val index = OrionTrace.statusIndex(name)
        return when (name) {
            // Light already has its own dispatcher lane; FULL hops to the main thread itself.
            "empty", "initialize_light", "light", "full" -> {
                inline.incrementAndGet()
                val trace = if (OrionTrace.enabled) LongArray(9).also {
                    it[0] = index.toLong(); it[1] = x.toLong(); it[2] = z.toLong(); it[3] = System.nanoTime()
                } else null
                OrionTrace.spin(index)
                val future = handle.invoke(step, context, cache, chunk) as CompletableFuture<*>
                if (trace != null) {
                    trace[7] = System.nanoTime()
                    future.whenComplete { _, _ -> trace[8] = System.nanoTime(); OrionTrace.step(trace) }
                }
                future
            }
            "structure_starts", "structure_references" ->
                schedule(Task(x, z, 0, index), serial = !parallelStructureStarts, handle, step, context, cache, chunk)
            "spawn" -> schedule(Task(x, z, 0, index), serial = true, handle, step, context, cache, chunk)
            "features" -> featureOrder?.let { order ->
                scheduleFeaturesInOrder(order, x, z) {
                    schedule(Task(x, z, maxOf(writeRadius, 0), index), serial = false, handle, step, context, cache, chunk)
                }
            } ?: schedule(Task(x, z, maxOf(writeRadius, 0), index), serial = false, handle, step, context, cache, chunk)
            else -> schedule(Task(x, z, maxOf(writeRadius, 0), index), serial = false, handle, step, context, cache, chunk)
        }
    }

    // #65, MC-55596: two FEATURES steps within Chebyshev distance 2 touch a shared chunk, so which
    // runs first changes the blocks. Orienting every such pair by a fixed 3x3 color key (lower key
    // first) makes each chunk's blocks a function of positions, not of scheduling.
    // bounds (minX, minZ, maxX, maxZ inclusive) limits waits to chunks the caller will generate to
    // FEATURES; null waits on every lower-key neighbor, pulling in a bounded closure (depth <= 16).
    class FeatureOrder(val bounds: IntArray?, val kick: BiFunction<Int, Int, CompletableFuture<*>>) {
        fun covers(x: Int, z: Int) =
            bounds == null || (x >= bounds[0] && z >= bounds[1] && x <= bounds[2] && z <= bounds[3])
    }

    @Volatile private var featureOrder: FeatureOrder? = null

    @JvmStatic fun orderFeatures(order: FeatureOrder) { featureOrder = order }

    private class Gate(var pending: Int, val begin: Runnable)

    private val featuresLock = Any()
    private val featuresStarted = HashSet<Long>()
    private val featuresDone = HashSet<Long>()
    private val featuresKicked = HashSet<Long>()
    private val gatesWaitingOn = HashMap<Long, ArrayList<Gate>>()
    private val gated = AtomicLong()
    private val kicks = AtomicLong()
    private val orderViolations = AtomicLong()
    private val kickFailures = AtomicLong()
    private val violationSample = ArrayList<String>()

    private fun colorKey(x: Int, z: Int) = 3 * Math.floorMod(x, 3) + Math.floorMod(z, 3)

    private fun scheduleFeaturesInOrder(order: FeatureOrder, x: Int, z: Int, run: () -> CompletableFuture<Any?>): CompletableFuture<Any?> {
        val self = pack(x, z)
        val key = colorKey(x, z)
        val result = CompletableFuture<Any?>()
        val begin = Runnable {
            if (verify) checkOrder(order, x, z)
            run().whenComplete { value, error ->
                markFeaturesDone(self)
                if (error != null) result.completeExceptionally(error) else result.complete(value)
            }
        }
        val waitingOn = ArrayList<Long>()
        val toKick = ArrayList<Long>()
        synchronized(featuresLock) {
            featuresStarted.add(self)
            for (dx in -2..2) {
                for (dz in -2..2) {
                    val px = x + dx
                    val pz = z + dz
                    if (colorKey(px, pz) >= key || !order.covers(px, pz)) continue
                    val p = pack(px, pz)
                    if (p in featuresDone) continue
                    waitingOn.add(p)
                    if (p !in featuresStarted && featuresKicked.add(p)) toKick.add(p)
                }
            }
            if (waitingOn.isNotEmpty()) {
                val gate = Gate(waitingOn.size, begin)
                for (p in waitingOn) gatesWaitingOn.getOrPut(p) { ArrayList(4) }.add(gate)
            }
        }
        if (waitingOn.isEmpty()) {
            begin.run()
        } else {
            gated.incrementAndGet()
            kicks.addAndGet(toKick.size.toLong())
            // Chunks that reached FEATURES before ordering was enabled (spawn prep) never pass
            // through here, so the kicked future completing is also a done signal.
            for (p in toKick) {
                order.kick.apply((p shr 32).toInt(), p.toInt()).whenComplete { _, error ->
                    if (error != null) kickFailures.incrementAndGet()
                    markFeaturesDone(p)
                }
            }
        }
        return result
    }

    private fun markFeaturesDone(self: Long) {
        val ready = ArrayList<Gate>()
        synchronized(featuresLock) {
            featuresDone.add(self)
            gatesWaitingOn.remove(self)?.forEach { if (--it.pending == 0) ready.add(it) }
        }
        for (gate in ready) gate.begin.run()
    }

    private fun checkOrder(order: FeatureOrder, x: Int, z: Int) = synchronized(featuresLock) {
        val key = colorKey(x, z)
        for (dx in -2..2) {
            for (dz in -2..2) {
                if ((dx == 0 && dz == 0) || !order.covers(x + dx, z + dz)) continue
                val done = pack(x + dx, z + dz) in featuresDone
                if (done != (colorKey(x + dx, z + dz) < key) && orderViolations.incrementAndGet() <= 6) {
                    violationSample.add("${x + dx},${z + dz}->$x,$z")
                }
            }
        }
    }

    @JvmStatic
    fun stuckSample(limit: Int): String = synchronized(featuresLock) {
        gatesWaitingOn.keys.take(limit).joinToString(" ") { p ->
            "[${p shr 32},${p.toInt()} started=${p in featuresStarted} kicked=${p in featuresKicked} gates=${gatesWaitingOn[p]?.size}]"
        }
    }

    private fun pack(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    @Synchronized
    private fun resolve(step: Any): MethodHandle {
        applyHandle?.let { return it }
        val method = step.javaClass.methods.single { it.name == "apply" && it.parameterCount == 3 }
        val handle = MethodHandles.publicLookup().unreflect(method).asType(
            MethodType.methodType(CompletableFuture::class.java, Any::class.java, Any::class.java, Any::class.java, Any::class.java)
        )
        val util = Class.forName("net.minecraft.util.Util", true, step.javaClass.classLoader)
        executor = util.getMethod("backgroundExecutor").invoke(null) as Executor
        applyHandle = handle
        return handle
    }

    private fun schedule(
        task: Task, serial: Boolean, handle: MethodHandle,
        step: Any, context: Any, cache: Any, chunk: Any,
    ): CompletableFuture<Any?> {
        val result = CompletableFuture<Any?>()
        val trace = task.trace
        task.body = Runnable {
            queuedOnPool.remove(task)
            active.add(task)
            maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            if (trace != null) { trace[5] = System.nanoTime(); OrionTrace.started.incrementAndGet() }
            if (verify) checkOverlap(task)
            val inner = try {
                if (serial) {
                    lockStructures()
                    try {
                        if (trace != null) trace[6] = System.nanoTime()
                        OrionTrace.spin(task.status)
                        handle.invoke(step, context, cache, chunk) as CompletableFuture<*>
                    } finally { unlockStructures() }
                } else {
                    OrionTrace.spin(task.status)
                    handle.invoke(step, context, cache, chunk) as CompletableFuture<*>
                }
            } catch (t: Throwable) {
                CompletableFuture.failedFuture<Any?>(t)
            }
            if (trace != null) trace[7] = System.nanoTime()
            inner.whenComplete { value, error ->
                if (trace != null) { trace[8] = System.nanoTime(); OrionTrace.ended.incrementAndGet(); OrionTrace.step(trace) }
                running.decrementAndGet()
                active.remove(task)
                if (verify) runningSquares.remove(task)
                release(task)
                if (error != null) result.completeExceptionally(error) else result.complete(value)
            }
        }
        scheduled.incrementAndGet()
        if (trace != null) { trace[3] = System.nanoTime(); OrionTrace.submitted.incrementAndGet() }
        if (enqueue(task)) {
            if (trace != null) { trace[4] = System.nanoTime(); OrionTrace.ready.incrementAndGet() }
            queuedOnPool.add(task)
            executor!!.execute(task.body)
        } else deferred.incrementAndGet()
        return result
    }

    private fun squareFree(task: Task): Boolean {
        forEachCell(task) { key -> if (key in held) return false }
        return true
    }

    // Caller holds the cells monitor. index is the task's position in waiting, or waiting.size for a new arrival.
    private fun tryAcquire(task: Task, index: Int): Boolean {
        if (!squareFree(task)) return false
        for (i in 0 until index) {
            val ahead = waiting[i]
            if (ahead.bypassed >= BYPASS_LIMIT && ahead.overlaps(task)) return false
        }
        for (i in 0 until index) {
            val ahead = waiting[i]
            if (ahead.overlaps(task)) { ahead.bypassed++; bypasses.incrementAndGet() }
        }
        forEachCell(task) { key -> held.add(key) }
        return true
    }

    private fun releaseGreedy(task: Task): ArrayList<Task> = synchronized(cells) {
        forEachCell(task) { key -> check(held.remove(key)) { "area cell not held at $key" } }
        val ready = ArrayList<Task>(2)
        var i = 0
        while (i < waiting.size) {
            if (tryAcquire(waiting[i], i)) ready.add(waiting.removeAt(i)) else i++
        }
        ready
    }

    private fun enqueue(task: Task): Boolean = synchronized(cells) {
        if (greedy) {
            if (tryAcquire(task, waiting.size)) return true
            waiting.add(task)
            return false
        }
        forEachCell(task) { key ->
            val queue = cells.getOrPut(key) { ArrayDeque() }
            if (queue.isNotEmpty()) task.blockers++
            queue.addLast(task)
        }
        task.blockers == 0
    }

    private fun release(task: Task) {
        val ready = if (greedy) releaseGreedy(task) else ArrayList<Task>(2)
        if (!greedy) synchronized(cells) {
            forEachCell(task) { key ->
                val queue = cells.getValue(key)
                check(queue.removeFirst() === task) { "area queue head mismatch at $key" }
                if (queue.isEmpty()) {
                    cells.remove(key)
                } else {
                    val next = queue.first()
                    if (--next.blockers == 0) ready.add(next)
                }
            }
        }
        for (next in ready) {
            next.trace?.let { it[4] = System.nanoTime(); OrionTrace.ready.incrementAndGet() }
            queuedOnPool.add(next)
            executor!!.execute(next.body)
        }
    }

    private inline fun forEachCell(task: Task, action: (Long) -> Unit) {
        for (dx in -task.radius..task.radius) {
            for (dz in -task.radius..task.radius) {
                action(((task.x + dx).toLong() shl 32) or ((task.z + dz).toLong() and 0xFFFFFFFFL))
            }
        }
    }

    private fun checkOverlap(task: Task) {
        for (other in runningSquares) {
            val reach = task.radius + other.radius
            if (Math.abs(other.x - task.x) <= reach && Math.abs(other.z - task.z) <= reach) overlapViolations.incrementAndGet()
        }
        runningSquares.add(task)
    }

    private val active = ConcurrentHashMap.newKeySet<Task>()
    private val queuedOnPool = ConcurrentHashMap.newKeySet<Task>()

    // Stall diagnostics: steps holding or waiting for area squares near a chunk.
    @JvmStatic
    fun describeNear(x: Int, z: Int, reach: Int): String {
        fun near(t: Task) = Math.abs(t.x - x) <= reach && Math.abs(t.z - z) <= reach
        fun show(t: Task) = "${OrionTrace.statuses.getOrNull(t.status)}@${t.x},${t.z} r=${t.radius} bypassed=${t.bypassed}"
        val waitingNow = synchronized(cells) { waiting.filter(::near).map(::show) + cells.values.flatMap { q -> q.drop(1).filter(::near).map(::show) }.distinct() }
        return "active=${active.filter(::near).map(::show)} queuedOnPool=${queuedOnPool.filter(::near).map(::show)} " +
            "totalQueuedOnPool=${queuedOnPool.size} waiting=$waitingNow totalWaiting=${synchronized(cells) { waiting.size }} totalActive=${active.size}"
    }

    @JvmStatic
    fun report(): String =
        "scheduled=${scheduled.get()} deferred=${deferred.get()} inline=${inline.get()} " +
            "maxRunning=${maxRunning.get()} leakedCells=${synchronized(cells) { cells.size + held.size + waiting.size }}" +
            " structureLock=$lockMode areaLock=${if (greedy) "greedy" else "fifo"} bypasses=${bypasses.get()} structureStarts=${if (parallelStructureStarts) "parallel" else "serial"}" +
            (if (verify) " overlapViolations=${overlapViolations.get()}" else "") +
            (if (featureOrder != null) " gated=${gated.get()} kicks=${kicks.get()} kickFailures=${kickFailures.get()} featuresDone=${synchronized(featuresLock) { featuresDone.size }} " +
                "stuckGates=${synchronized(featuresLock) { gatesWaitingOn.size }}" + (if (verify) " orderViolations=${orderViolations.get()} ${synchronized(featuresLock) { violationSample.toString() }}" else "") else "")
}

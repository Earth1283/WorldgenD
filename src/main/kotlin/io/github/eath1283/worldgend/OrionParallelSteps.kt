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

    @JvmStatic fun lockStructures() = structures.lock()
    @JvmStatic fun unlockStructures() = structures.unlock()

    private class Task(val x: Int, val z: Int, val radius: Int) {
        var blockers = 0
        lateinit var body: Runnable
    }

    // Per-chunk FIFO of tasks whose square covers that chunk. A task runs once it heads every
    // queue it joined; joins happen atomically in submission order, so no cycle can form.
    private val cells = HashMap<Long, ArrayDeque<Task>>()

    @Volatile private var applyHandle: MethodHandle? = null
    @Volatile private var executor: Executor? = null

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
        return when (status.substringAfter(':')) {
            // Light already has its own dispatcher lane; FULL hops to the main thread itself.
            "empty", "initialize_light", "light", "full" -> {
                inline.incrementAndGet()
                handle.invoke(step, context, cache, chunk) as CompletableFuture<*>
            }
            "structure_starts", "structure_references", "spawn" ->
                schedule(Task(x, z, 0), serial = true, handle, step, context, cache, chunk)
            "features" -> featureOrder?.let { order ->
                scheduleFeaturesInOrder(order, x, z) {
                    schedule(Task(x, z, maxOf(writeRadius, 0)), serial = false, handle, step, context, cache, chunk)
                }
            } ?: schedule(Task(x, z, maxOf(writeRadius, 0)), serial = false, handle, step, context, cache, chunk)
            else -> schedule(Task(x, z, maxOf(writeRadius, 0)), serial = false, handle, step, context, cache, chunk)
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
        task.body = Runnable {
            maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            if (verify) checkOverlap(task)
            val inner = try {
                if (serial) {
                    structures.lock()
                    try { handle.invoke(step, context, cache, chunk) as CompletableFuture<*> } finally { structures.unlock() }
                } else {
                    handle.invoke(step, context, cache, chunk) as CompletableFuture<*>
                }
            } catch (t: Throwable) {
                CompletableFuture.failedFuture<Any?>(t)
            }
            inner.whenComplete { value, error ->
                running.decrementAndGet()
                if (verify) runningSquares.remove(task)
                release(task)
                if (error != null) result.completeExceptionally(error) else result.complete(value)
            }
        }
        scheduled.incrementAndGet()
        if (enqueue(task)) executor!!.execute(task.body) else deferred.incrementAndGet()
        return result
    }

    private fun enqueue(task: Task): Boolean = synchronized(cells) {
        forEachCell(task) { key ->
            val queue = cells.getOrPut(key) { ArrayDeque() }
            if (queue.isNotEmpty()) task.blockers++
            queue.addLast(task)
        }
        task.blockers == 0
    }

    private fun release(task: Task) {
        val ready = ArrayList<Task>(2)
        synchronized(cells) {
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
        for (next in ready) executor!!.execute(next.body)
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

    @JvmStatic
    fun report(): String =
        "scheduled=${scheduled.get()} deferred=${deferred.get()} inline=${inline.get()} " +
            "maxRunning=${maxRunning.get()} leakedCells=${synchronized(cells) { cells.size }}" +
            (if (verify) " overlapViolations=${overlapViolations.get()}" else "") +
            (if (featureOrder != null) " gated=${gated.get()} kicks=${kicks.get()} kickFailures=${kickFailures.get()} featuresDone=${synchronized(featuresLock) { featuresDone.size }} " +
                "stuckGates=${synchronized(featuresLock) { gatesWaitingOn.size }}" + (if (verify) " orderViolations=${orderViolations.get()} ${synchronized(featuresLock) { violationSample.toString() }}" else "") else "")
}

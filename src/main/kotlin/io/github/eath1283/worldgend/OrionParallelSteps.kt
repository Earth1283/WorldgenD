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
            else -> schedule(Task(x, z, maxOf(writeRadius, 0)), serial = false, handle, step, context, cache, chunk)
        }
    }

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
            if (verify) " overlapViolations=${overlapViolations.get()}" else ""
}

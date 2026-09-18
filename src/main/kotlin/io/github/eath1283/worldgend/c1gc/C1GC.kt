package io.github.eath1283.worldgend.c1gc

import io.github.eath1283.worldgend.Mc
import io.github.eath1283.worldgend.call
import java.lang.invoke.MethodHandles
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.util.concurrent.ArrayBlockingQueue
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Same x-frontier bound as ChunkEvictor.kt, kept as its own copy since this ring feeds the
// COLLECTIBLE proof rather than a direct evict.
private class FrontierRing(private val capacity: Int) {
    private val buf = LongArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun push(value: Long): Long? {
        var bumped: Long? = null
        if (size == capacity) {
            bumped = buf[head]
            head = (head + 1) % capacity
            size--
        }
        buf[(head + size) % capacity] = value
        size++
        return bumped
    }

    @Synchronized
    fun drainAll(): LongArray {
        val out = LongArray(size)
        for (i in out.indices) out[i] = buf[(head + i) % capacity]
        head = 0
        size = 0
        return out
    }

    @Synchronized
    fun occupancy() = size
}

// Bounded chunk-reclamation for sustained Orion runs. See c1gc/README.md for the pitch.
class C1GC(
    private val mc: Mc,
    private val chunkSource: Any,
    private val minX: Int,
    private val maxX: Int,
    private val zPerX: Int,
    ringCapacity: Int = RING_CAPACITY,
    maxIoWorkers: Int = MAX_IO_WORKERS,
    // v5.5: reclaim nothing until the old gen's post-collection live set exceeds this fraction
    // of its max; 0 reclaims from the first chunk (v5.4's behavior).
    private val pressure: Double = 0.0,
) {
    companion object {
        const val RETAIN_RADIUS = 12
        const val RING_CAPACITY = 512
        const val MAX_IO_WORKERS = 4
        // #69's JFR profile: runDistanceManagerUpdates()'s graph settle (DynamicGraphMinFixedPoint)
        // was ~40% of total CPU time called once per chunk. Removing tickets in batches before
        // settling once amortizes that cost across BATCH_SIZE chunks instead of paying it per chunk.
        const val BATCH_SIZE = 64
        const val PRESSURE_CHECK_EVERY = 64
        // Per drain() call, so the poll thread keeps serving vanilla's main-thread tasks between batches.
        const val DRAIN_PER_CALL = BATCH_SIZE

        private fun oldGen(): MemoryPoolMXBean? = ManagementFactory.getMemoryPoolMXBeans().firstOrNull {
            it.type == MemoryType.HEAP && it.isCollectionUsageThresholdSupported &&
                (it.name.contains("Old") || it.name.contains("Tenured"))
        }
    }

    private data class Pending(val cx: Int, val cz: Int, val pos: Any, val packed: Long, val chunk: Any)

    private val lifecycle = LifecycleTracker()
    private val remainingPerX = IntArray(maxX - minX + 1) { zPerX }
    private var frontier = 0
    private val pendingByX = HashMap<Int, MutableList<Int>>()
    private val ring = FrontierRing(ringCapacity)
    private val pendingBatch = mutableListOf<Pending>()
    private val backlog = ArrayDeque<Long>()
    private val oldGenPool = if (pressure > 0.0) oldGen() else null
    @Volatile private var armed = pressure <= 0.0
    private var armedAtCompletion = if (armed) 0 else -1
    private var completions = 0
    private var peakOldGenUsed = 0L

    private val quarantined = AtomicInteger(0)
    private val collectible = AtomicInteger(0)
    private val detached = AtomicInteger(0)
    private val persisted = AtomicInteger(0)
    private val ioThreadNum = AtomicInteger(0)
    private val pendingWrites = Collections.synchronizedList(mutableListOf<CompletableFuture<*>>())

    private val chunkMap = mc.field(chunkSource.javaClass, "chunkMap", chunkSource)!!
    private val ticketStorage = mc.field(chunkMap.javaClass, "ticketStorage", chunkMap)!!
    private val level = mc.field(chunkMap.javaClass, "level", chunkMap)!!
    private val cChunkPos = mc.c("net.minecraft.world.level.ChunkPos")
    private val chunkPosCtor = mc.ctor(cChunkPos, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    private val packChunkPos = mc.publicMethod(cChunkPos, "pack")
    private val cTicket = mc.c("net.minecraft.server.level.Ticket")
    private val cTicketType = mc.c("net.minecraft.server.level.TicketType")
    private val ticketCtor = mc.ctor(cTicket, cTicketType, Int::class.javaPrimitiveType!!)
    private val ticketTypeUnknown = mc.staticField(cTicketType, "UNKNOWN")!!
    private val ticketLevel: Int = run {
        val cChunkLevel = mc.c("net.minecraft.server.level.ChunkLevel")
        val cChunkStatus = mc.c("net.minecraft.world.level.chunk.status.ChunkStatus")
        val full = mc.staticField(cChunkStatus, "FULL")
        mc.publicMethod(cChunkLevel, "byStatus", cChunkStatus).call(null, full) as Int
    }
    private val removeTicket = ticketStorage.javaClass.getMethod("removeTicket", cTicket, cChunkPos)
    private val runDistanceManagerUpdates = mc.method(chunkSource.javaClass, "runDistanceManagerUpdates")
    private val processUnloads = mc.method(
        chunkMap.javaClass, "processUnloads", java.util.function.BooleanSupplier::class.java,
    )
    private val alwaysTrue = java.util.function.BooleanSupplier { true }

    private val poiManager = mc.field(chunkMap.javaClass, "poiManager", chunkMap)!!
    private val cSectionStorage = mc.c("net.minecraft.world.level.chunk.storage.SectionStorage")
    private val poiStorage = mc.field(cSectionStorage, "storage", poiManager)!!
    private val poiLoadedChunks = mc.field(cSectionStorage, "loadedChunks", poiManager)!!
    private val poiFlush = mc.publicMethod(cSectionStorage, "flush", cChunkPos)
    private val lookup = MethodHandles.publicLookup()
    private val poiStorageRemove = lookup.unreflect(poiStorage.javaClass.getMethod("remove", Long::class.javaPrimitiveType)).bindTo(poiStorage)
    private val poiLoadedChunksRemove = lookup.unreflect(poiLoadedChunks.javaClass.getMethod("remove", Long::class.javaPrimitiveType)).bindTo(poiLoadedChunks)
    private val heightAccessor = mc.field(cSectionStorage, "levelHeightAccessor", poiManager)!!
    private val cLevelHeightAccessor = mc.c("net.minecraft.world.level.LevelHeightAccessor")
    private val minSectionY = mc.publicMethod(cLevelHeightAccessor, "getMinSectionY").call(heightAccessor) as Int
    private val maxSectionY = mc.publicMethod(cLevelHeightAccessor, "getMaxSectionY").call(heightAccessor) as Int
    private val cSectionPos = mc.c("net.minecraft.core.SectionPos")
    private val sectionPosAsLong = lookup.unreflect(mc.publicMethod(
        cSectionPos, "asLong",
        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
    ))

    private val getUpdatingChunkIfPresent = chunkMap.javaClass.getMethod("getUpdatingChunkIfPresent", Long::class.javaPrimitiveType)
    private val getLatestChunk = mc.publicMethod(mc.c("net.minecraft.server.level.GenerationChunkHolder"), "getLatestChunk")

    private val cChunkAccess = mc.c("net.minecraft.world.level.chunk.ChunkAccess")
    private val tryMarkSaved = mc.publicMethod(cChunkAccess, "tryMarkSaved")
    private val cServerLevel = mc.c("net.minecraft.server.level.ServerLevel")
    private val cSerializableChunkData = mc.c("net.minecraft.world.level.chunk.storage.SerializableChunkData")
    private val copyOf = mc.publicMethod(cSerializableChunkData, "copyOf", cServerLevel, cChunkAccess)
    private val writeRecord = mc.publicMethod(cSerializableChunkData, "write")
    private val cCompoundTag = mc.c("net.minecraft.nbt.CompoundTag")
    // inherited from SimpleRegionStorage; getMethod (unlike getDeclaredMethod) finds it anyway
    private val writeToRegionFile = mc.publicMethod(chunkMap.javaClass, "write", cChunkPos, cCompoundTag)

    // grows past 1 worker only once the queue is full; CallerRunsPolicy blocks the producer
    // once maxIoWorkers is also saturated, instead of growing the backlog unbounded.
    private val ioQueue = ArrayBlockingQueue<Runnable>(ringCapacity)
    private val ioPool = ThreadPoolExecutor(
        1, maxIoWorkers, 30, TimeUnit.SECONDS, ioQueue,
        { r -> Thread(r, "c1gc-io-${ioThreadNum.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    private fun pack(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
    private fun unpackX(p: Long) = (p shr 32).toInt()
    private fun unpackZ(p: Long) = p.toInt()

    @Synchronized
    fun markComplete(cx: Int, cz: Int) {
        val idx = cx - minX
        remainingPerX[idx]--
        while (frontier <= maxX - minX && remainingPerX[frontier] == 0) frontier++
        pendingByX.getOrPut(cx) { mutableListOf() }.add(cz)

        val safeUpToX = (minX + frontier) - RETAIN_RADIUS - 1
        val it = pendingByX.entries.iterator()
        while (it.hasNext()) {
            val (x, zs) = it.next()
            if (x > safeUpToX) continue
            for (z in zs) {
                val bumped = ring.push(pack(x, z))
                if (bumped != null) backlog.addLast(bumped)
            }
            it.remove()
        }

        if (!armed && ++completions % PRESSURE_CHECK_EVERY == 0) checkPressure()
    }

    @Synchronized
    private fun takeBacklog(max: Int): LongArray {
        val n = minOf(max, backlog.size)
        return LongArray(n) { backlog.removeFirst() }
    }

    // Ticket removal, distance-manager settles, and processUnloads mutate vanilla's main-thread-only
    // DistanceManager graph. markComplete fires on whichever thread completed the chunk (often a
    // worker), so reclamation runs here instead: on the poll thread, between pollTask calls, where
    // nothing vanilla is mid-update. Returns whether it did any work.
    fun drain(): Boolean {
        if (!armed) return false
        val items = takeBacklog(DRAIN_PER_CALL)
        for (p in items) quarantineOne(unpackX(p), unpackZ(p))
        return items.isNotEmpty()
    }

    // Current occupancy, not collectionUsage: ParallelGC only refreshes the latter after a full GC,
    // which first happens once the old gen is already full (#70's 65,536-chunk run armed at 10.6GB
    // live and spent 698s in full GCs). Promoted garbage can arm it early; that only costs v5.4's
    // always-on behavior, never an unbounded heap.
    private fun checkPressure() {
        val pool = oldGenPool ?: run { arm(); return }
        val usage = pool.usage
        peakOldGenUsed = maxOf(peakOldGenUsed, usage.used)
        val max = usage.max.takeIf { it > 0 } ?: Runtime.getRuntime().maxMemory()
        if (usage.used > pressure * max) arm()
    }

    private fun arm() {
        armed = true
        armedAtCompletion = completions
    }

    // Only after fill() has stopped the poll thread, so this is the sole main-thread caller.
    fun flush() {
        if (armed) {
            for (p in takeBacklog(Int.MAX_VALUE)) quarantineOne(unpackX(p), unpackZ(p))
            for (p in ring.drainAll()) quarantineOne(unpackX(p), unpackZ(p))
        }
        settleBatch()
        ioPool.shutdown()
        ioPool.awaitTermination(10, TimeUnit.MINUTES)
        val outstanding = synchronized(pendingWrites) { pendingWrites.toTypedArray() }
        CompletableFuture.allOf(*outstanding).get(5, TimeUnit.MINUTES)
    }

    // tryMarkSaved()'s dirty flag tracks incremental re-saves on a ticking server; this
    // runtime never ticks, so it's already false for most chunks by the time we get here
    // (set at status promotion, never consumed by anything else). Not a signal we can gate
    // on -- call it anyway so vanilla's own processUnloads-triggered save() no-ops instead of
    // racing us, but detach unconditionally regardless of what it returns.
    //
    // Only the ticket removal itself happens per chunk here. The distance-manager settle and
    // processUnloads are shared, expensive, whole-graph operations (#69's JFR profile: ~40% of
    // total CPU in DynamicGraphMinFixedPoint/ChunkTracker) -- paying that cost once per chunk
    // instead of once per batch was the actual bug, not anything about C1GC's own bookkeeping.
    private fun quarantineOne(cx: Int, cz: Int) {
        val pos = chunkPosCtor.newInstance(cx, cz)
        val packed = packChunkPos.call(pos) as Long
        lifecycle.advance(packed, ChunkLifecycle.QUARANTINED)

        val holder = getUpdatingChunkIfPresent.invoke(chunkMap, packed)
        val chunk = holder?.let { getLatestChunk.invoke(it) } ?: error("no live chunk at $cx,$cz for quarantine")
        quarantined.incrementAndGet()

        poiFlush.invoke(poiManager, pos)
        tryMarkSaved.invoke(chunk)

        val ticket = ticketCtor.newInstance(ticketTypeUnknown, ticketLevel)
        removeTicket.invoke(ticketStorage, ticket, pos)

        pendingBatch.add(Pending(cx, cz, pos, packed, chunk))
        if (pendingBatch.size >= BATCH_SIZE) settleBatch()
    }

    private fun settleBatch() {
        if (pendingBatch.isEmpty()) return
        var rounds = 0
        while (runDistanceManagerUpdates.invoke(chunkSource) as Boolean && rounds++ < 8) { /* drain */ }
        processUnloads.invoke(chunkMap, alwaysTrue)

        for (item in pendingBatch) {
            for (y in minSectionY..maxSectionY) {
                val key = sectionPosAsLong.invoke(item.cx, y, item.cz) as Long
                poiStorageRemove.invoke(key)
            }
            poiLoadedChunksRemove.invoke(item.packed)
            collectible.incrementAndGet()
            lifecycle.advance(item.packed, ChunkLifecycle.COLLECTIBLE)

            detachOne(item.pos, item.packed, item.chunk)
        }
        pendingBatch.clear()
    }

    private fun detachOne(pos: Any, packed: Long, chunk: Any) {
        val record = copyOf.invoke(null, level, chunk)
        detached.incrementAndGet()
        lifecycle.advance(packed, ChunkLifecycle.DETACHED)

        ioPool.execute {
            val tag = writeRecord.invoke(record)
            val future = writeToRegionFile.invoke(chunkMap, pos, tag) as CompletableFuture<*>
            pendingWrites.add(future)
            future.whenComplete { _, err ->
                if (err != null) {
                    System.err.println("[C1GC] persist failed for $pos: $err")
                } else {
                    persisted.incrementAndGet()
                    lifecycle.advance(packed, ChunkLifecycle.PERSISTED)
                }
            }
        }
    }

    fun report(): String {
        val counts = lifecycle.countsByState()
        return "quarantined=${quarantined.get()} collectible=${collectible.get()} " +
            "detached=${detached.get()} persisted=${persisted.get()} " +
            "ringOccupancy=${ring.occupancy()} ioQueueOccupancy=${ioQueue.size} " +
            "ioPoolSize=${ioPool.poolSize} ioPoolActive=${ioPool.activeCount} " +
            "pressure=$pressure armed=$armed armedAtCompletion=$armedAtCompletion unreclaimed=${backlog.size + ring.occupancy()} " +
            "oldGen=${oldGenPool?.name} peakOldGenUsedMb=${peakOldGenUsed shr 20} " +
            "lifecycleCounts=$counts"
    }
}

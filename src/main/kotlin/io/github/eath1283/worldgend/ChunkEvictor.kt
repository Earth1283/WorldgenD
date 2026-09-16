package io.github.eath1283.worldgend

// WorldgenD never runs a tick loop, so nothing ever calls the vanilla path
// (TicketStorage.removeTicket / DistanceManager purge) that would drop a chunk. Every
// chunk generated via getChunkFuture(..., true) gets a permanent TicketType.UNKNOWN
// ticket and stays resident in ChunkMap's holder map (full ChunkAccess: block-state
// palettes, biome data, heightmaps) for the rest of the JVM's life -- confirmed by
// linear live-set growth in GC logs across a champion-scale run. Opt-in via
// -Dorion.evictChunks=true. Eviction saves the chunk to disk (vanilla's own
// scheduleUnload path) before dropping it from memory, so nothing generated is lost --
// this is the "ring buffer to write" the flag exists for, not just a memory optimization.
//
// Safety: the generation pyramid's maximum neighbor-dependency radius is 8
// (STRUCTURE_STARTS; see this file's own header comment in HeadlessWorldgen.kt,
// confirmed from the jar's bytecode, not assumed). The mosaic target list is built
// x-outer, z-inner (row-major), so tracking one scalar -- the smallest x-coordinate
// among not-yet-complete target chunks -- is sufficient to prove safety: Chebyshev
// distance is at least |dx|, so a completed chunk more than RETAIN_RADIUS away in x
// from every incomplete chunk cannot be within radius 8 of any of them, regardless of
// z or completion order. RETAIN_RADIUS (12) is 8 plus a safety margin.
//
// Ticket removal + processUnloads alone does NOT reduce heap: it only drops the
// lightweight ChunkHolder from ChunkMap's maps. The real payload leak is PoiManager
// (village/POI data), a SectionStorage subclass whose per-section cache (`storage`)
// has no eviction path at all in vanilla -- SectionStorage.tick() only flushes dirty
// entries to disk, it never removes them from memory. Since worldgen touches POI data
// for most chunks, that map grows forever regardless of ChunkHolder eviction. This is
// stripped by hand below (flush first, in case a write is pending, then remove the
// section entries) -- confirmed by GC-log comparison, not just reasoned.
// Fixed-capacity FIFO hand-off between "safe to evict" (decided by the x-frontier logic
// below) and "actually written and dropped" (evictOne). Chunks sit here, in completion
// order, until the buffer fills; pushing past capacity bumps the oldest entry out for
// eviction. This is a real bounded ring, not just a naming choice: `buf` is reused
// circularly as `head`/`size` wrap, so occupancy never exceeds `capacity` regardless of
// how long the run goes on.
private class RingBuffer(private val capacity: Int) {
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

class ChunkEvictor(
    private val mc: Mc,
    private val chunkSource: Any,
    private val minX: Int,
    private val maxX: Int,
    private val zPerX: Int,
) {
    companion object {
        const val RETAIN_RADIUS = 12
        const val RING_CAPACITY = 512
    }

    private val remainingPerX = IntArray(maxX - minX + 1) { zPerX }
    private var frontier = 0
    private val pendingByX = HashMap<Int, MutableList<Int>>()
    private val ring = RingBuffer(RING_CAPACITY)
    private var evicted = 0

    private val chunkMap = mc.field(chunkSource.javaClass, "chunkMap", chunkSource)!!
    private val ticketStorage = mc.field(chunkMap.javaClass, "ticketStorage", chunkMap)!!
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
    // runDistanceManagerUpdates() only propagates ticket levels (DistanceManager.runAllUpdates)
    // and populates ChunkMap's `toDrop` set -- it never calls ChunkMap.tick()/processUnloads(),
    // so the ChunkHolder never actually leaves `updatingChunkMap` without this. tick() itself
    // would skip processUnloads whenever ServerLevel.noSave() is true (which WorldgenD's level
    // is), so this calls processUnloads directly rather than going through tick(). This is also
    // the disk-write side of eviction: scheduleUnload's cleanup calls ChunkMap.save(chunkAccess)
    // on every dropped holder before removing it, writing the chunk to its .mca region file --
    // verified directly (region file byte counts match evicted-chunk counts with
    // -Dsaveworld=false, so the writes aren't coming from the end-of-run saveAllChunks() pass).
    private val processUnloads = mc.method(
        chunkMap.javaClass, "processUnloads", java.util.function.BooleanSupplier::class.java,
    )
    private val alwaysTrue = java.util.function.BooleanSupplier { true }

    private val poiManager = mc.field(chunkMap.javaClass, "poiManager", chunkMap)!!
    private val cSectionStorage = mc.c("net.minecraft.world.level.chunk.storage.SectionStorage")
    private val poiStorage = mc.field(cSectionStorage, "storage", poiManager)!!
    private val poiLoadedChunks = mc.field(cSectionStorage, "loadedChunks", poiManager)!!
    private val poiFlush = mc.publicMethod(cSectionStorage, "flush", cChunkPos)
    private val poiStorageRemove = poiStorage.javaClass.getMethod("remove", Long::class.javaPrimitiveType)
    private val poiLoadedChunksRemove = poiLoadedChunks.javaClass.getMethod("remove", Long::class.javaPrimitiveType)
    private val heightAccessor = mc.field(cSectionStorage, "levelHeightAccessor", poiManager)!!
    private val cLevelHeightAccessor = mc.c("net.minecraft.world.level.LevelHeightAccessor")
    private val minSectionY = mc.publicMethod(cLevelHeightAccessor, "getMinSectionY").call(heightAccessor) as Int
    private val maxSectionY = mc.publicMethod(cLevelHeightAccessor, "getMaxSectionY").call(heightAccessor) as Int
    private val cSectionPos = mc.c("net.minecraft.core.SectionPos")
    private val sectionPosAsLong = mc.publicMethod(
        cSectionPos, "asLong",
        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
    )

    private fun pack(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
    private fun unpackX(p: Long) = (p shr 32).toInt()
    private fun unpackZ(p: Long) = p.toInt()

    // Diagnostic, not part of the eviction mechanism itself: does removing the holder +
    // stripping PoiManager actually make the chunk's payload collectible, or is something
    // else still holding a live reference to it? A WeakReference tells the truth regardless
    // of which unknown object graph is doing the holding -- no need to guess the culprit
    // class first. Sampled (every 16th eviction) so this stays cheap at champion scale.
    private val getUpdatingChunkIfPresent = chunkMap.javaClass.getMethod("getUpdatingChunkIfPresent", Long::class.javaPrimitiveType)
    private val getLatestChunk = mc.publicMethod(mc.c("net.minecraft.server.level.GenerationChunkHolder"), "getLatestChunk")
    private val sampledRefs = java.util.Collections.synchronizedList(mutableListOf<java.lang.ref.WeakReference<Any>>())

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
                if (bumped != null) evictOne(unpackX(bumped), unpackZ(bumped))
            }
            it.remove()
        }
    }

    // Drain whatever's still sitting in the ring, unwritten. Call once after the fill is
    // done -- otherwise the last RING_CAPACITY-ish chunks never get their eager save/evict
    // (they're still safe: the end-of-run saveAllChunks() pass covers anything still
    // resident, this just finishes the job the ring started).
    @Synchronized
    fun flush() {
        for (p in ring.drainAll()) evictOne(unpackX(p), unpackZ(p))
    }

    private fun evictOne(cx: Int, cz: Int) {
        val pos = chunkPosCtor.newInstance(cx, cz)
        val packed = packChunkPos.call(pos) as Long
        if (evicted % 16 == 0) {
            val holder = getUpdatingChunkIfPresent.invoke(chunkMap, packed)
            val chunk = holder?.let { getLatestChunk.invoke(it) }
            if (chunk != null) sampledRefs.add(java.lang.ref.WeakReference(chunk))
        }
        val ticket = ticketCtor.newInstance(ticketTypeUnknown, ticketLevel)
        removeTicket.invoke(ticketStorage, ticket, pos)
        // runAllUpdates() can return early after processing just one internal work queue
        // (chunksToUpdateFutures) without ever reaching the ticket-release/toDrop logic --
        // vanilla's tick loop calls this every tick until it settles, so a single call isn't
        // guaranteed to be enough. Loop until it reports no more work, matching that.
        var rounds = 0
        while (runDistanceManagerUpdates.invoke(chunkSource) as Boolean && rounds++ < 8) { /* drain */ }
        processUnloads.invoke(chunkMap, alwaysTrue)

        poiFlush.invoke(poiManager, pos)
        for (y in minSectionY..maxSectionY) {
            val key = sectionPosAsLong.invoke(null, cx, y, cz) as Long
            poiStorageRemove.invoke(poiStorage, key)
        }
        poiLoadedChunksRemove.invoke(poiLoadedChunks, packed)

        evicted++
    }

    // Forces two full GCs (a single System.gc() under some collectors only promises a
    // best-effort collection) then counts how many sampled chunks are still reachable.
    // Any survivors prove a real external retainer exists, independent of guessing which
    // class it is.
    fun verifyReclaimed(): String {
        System.gc()
        System.gc()
        val total = sampledRefs.size
        val immediateAlive = sampledRefs.count { it.get() != null }
        // If survivors are just in-flight background work (light engine tasks, task
        // dispatcher backlog) rather than a structural leak, letting that work drain before
        // re-checking should make the count drop -- distinguishes "still finishing" from
        // "actually pinned forever."
        Thread.sleep(8000)
        System.gc()
        System.gc()
        val delayedAlive = sampledRefs.count { it.get() != null }
        return "sampled=$total stillAliveImmediate=$immediateAlive stillAliveAfter8s=$delayedAlive"
    }

    // Bounded BFS over the live object graph from `chunkMap` (which reaches the whole
    // ServerLevel via its own `level` field, and from there almost everything else) looking
    // for a reference chain to `target`. No heap-dump tooling available in this environment,
    // so this answers "what actually retains it" the direct way: walk real fields until we
    // find it or give up. Capped so a target with no path (already collected) doesn't hang.
    // A retainer that never showed up in the field-walk BFS (even at a multi-million node
    // budget) is the classic signature of a ThreadLocal: the value lives in the owning
    // Thread's own private ThreadLocalMap, not in any field reachable from chunkMap. Needs
    // --add-opens java.base/java.lang=ALL-UNNAMED to introspect; falls back to skipping
    // silently (still-null roots just don't add anything) if that wasn't granted.
    private fun threadLocalRoots(): List<Pair<Any, List<String>>> {
        val roots = mutableListOf<Pair<Any, List<String>>>()
        try {
            val threadLocalsField = Thread::class.java.getDeclaredField("threadLocals").apply { isAccessible = true }
            val mapClass = Class.forName("java.lang.ThreadLocal\$ThreadLocalMap")
            val tableField = mapClass.getDeclaredField("table").apply { isAccessible = true }
            val entryClass = Class.forName("java.lang.ThreadLocal\$ThreadLocalMap\$Entry")
            val valueField = entryClass.getDeclaredField("value").apply { isAccessible = true }
            for (thread in Thread.getAllStackTraces().keys) {
                val map = try { threadLocalsField.get(thread) } catch (e: Exception) { null } ?: continue
                val table = try { tableField.get(map) as Array<*> } catch (e: Exception) { null } ?: continue
                for ((i, entry) in table.withIndex()) {
                    if (entry == null) continue
                    val value = try { valueField.get(entry) } catch (e: Exception) { null } ?: continue
                    roots.add(value to listOf("Thread[${thread.name}].threadLocals[$i]"))
                }
            }
        } catch (e: Exception) {
            roots.add("<threadlocal introspection unavailable: ${e.javaClass.simpleName}: ${e.message}>" to listOf("(diagnostic)"))
        }
        return roots
    }

    private fun findRetainer(target: Any, extraRoots: List<Pair<Any, List<String>>>, nodeBudget: Int = 3_000_000): String {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val queue = ArrayDeque<Pair<Any, List<String>>>()
        queue.add(chunkMap to listOf("chunkMap"))
        visited.add(chunkMap)
        for ((root, path) in extraRoots) {
            if (root === target) return path.joinToString(".")
            if (visited.add(root)) queue.add(root to path)
        }
        var visitedCount = 0
        fun consider(v: Any?, path: List<String>): List<String>? {
            if (v == null || v is String || v is Number || v is Boolean || v is Char) return null
            if (v === target) return path
            if (visited.add(v)) queue.add(v to path)
            return null
        }
        // Background threads (light engine, task dispatchers) are still mutating some of these
        // collections live even after fill() returns -- a ConcurrentModificationException just
        // means skip this node's remaining children, not abort the whole search.
        fun walkMap(m: Map<*, *>, path: List<String>): List<String>? {
            var i = 0
            try {
                for ((k, v) in m) {
                    consider(v, path + "[$k]")?.let { return it }
                    if (++i > 20000) break
                }
            } catch (e: java.util.ConcurrentModificationException) { /* best-effort */ }
            return null
        }
        fun walkCollection(coll: Collection<*>, path: List<String>): List<String>? {
            var i = 0
            try {
                for (v in coll) {
                    consider(v, path + "[$i]")?.let { return it }
                    if (++i > 20000) break
                }
            } catch (e: java.util.ConcurrentModificationException) { /* best-effort */ }
            return null
        }
        fun walkArray(arr: Array<*>, path: List<String>): List<String>? {
            for ((i, v) in arr.withIndex()) {
                consider(v, path + "[$i]")?.let { return it }
                if (i > 20000) break
            }
            return null
        }
        fun walkFields(obj: Any, path: List<String>): List<String>? {
            val cls = obj.javaClass
            if (cls.name.startsWith("java.") || cls.name.startsWith("kotlin.") || cls.isPrimitive) return null
            var c: Class<*>? = cls
            while (c != null && !c.name.startsWith("java.") && !c.name.startsWith("kotlin.")) {
                for (f in c.declaredFields) {
                    if (f.type.isPrimitive) continue
                    val v = try {
                        f.isAccessible = true
                        f.get(obj)
                    } catch (e: Exception) { null }
                    consider(v, path + f.name)?.let { return it }
                }
                c = c.superclass
            }
            return null
        }
        while (queue.isNotEmpty() && visitedCount < nodeBudget) {
            val (obj, path) = queue.removeFirst()
            visitedCount++
            if (path.size >= 10) continue
            val found = when (obj) {
                is Map<*, *> -> walkMap(obj, path)
                is Collection<*> -> walkCollection(obj, path)
                is Array<*> -> walkArray(obj, path)
                else -> walkFields(obj, path)
            }
            if (found != null) return found.joinToString(".")
        }
        return if (visitedCount >= nodeBudget) "NOT FOUND (budget exhausted at $nodeBudget nodes)" else "NOT FOUND (graph exhausted, $visitedCount nodes)"
    }

    fun findFirstRetainerPath(): String {
        val alive = sampledRefs.mapNotNull { it.get() }.firstOrNull() ?: return "no surviving sample to trace"
        val roots = threadLocalRoots()
        val diagnosticFailure = roots.singleOrNull()?.first as? String
        val threadLocalSummary = diagnosticFailure ?: "threadLocalRoots=${roots.size}"
        return "$threadLocalSummary; ${findRetainer(alive, roots)}"
    }

    fun report(): String {
        val updating = mc.field(chunkMap.javaClass, "updatingChunkMap", chunkMap)!!
        val visible = mc.field(chunkMap.javaClass, "visibleChunkMap", chunkMap)!!
        val pending = mc.field(chunkMap.javaClass, "pendingUnloads", chunkMap)!!
        val unloadQueue = mc.field(chunkMap.javaClass, "unloadQueue", chunkMap)!!
        fun sizeOf(o: Any) = o.javaClass.getMethod("size").invoke(o)
        return "evicted=$evicted pendingBuckets=${pendingByX.size} ringOccupancy=${ring.occupancy()} retainRadius=$RETAIN_RADIUS " +
            "updatingChunkMap=${sizeOf(updating)} visibleChunkMap=${sizeOf(visible)} " +
            "pendingUnloads=${sizeOf(pending)} unloadQueue=${(unloadQueue as java.util.Queue<*>).size} " +
            "poiStorage=${sizeOf(poiStorage)}"
    }
}

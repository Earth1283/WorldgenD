# C1GC (Chunk-First Garbage Collector)

*A collector for a heap that Java's own collectors were never told about.*

## The incident

Someone ran Orion v5.3 at 256x256 chunks (65,536 of them, per the discovery report) and
watched it either OOM outright or grind itself into GC purgatory depending on the heap you
gave it. Nothing was wrong with generation. Nothing was wrong with the scheduler. The bug was
that WorldgenD was never taught that a chunk, once generated, is allowed to stop existing.

Every chunk `getChunkFuture(..., true)` ever hands back gets a permanent
`TicketType.UNKNOWN` ticket and sits in `ChunkMap`'s holder map for the rest of the JVM's
life. At smoke-test scale (6,400 chunks) this is a rounding error. At 65,536 chunks it is a
heap that only ever grows, monitored by a garbage collector doing an increasingly heroic and
increasingly pointless job, because none of the garbage it's hunting for is actually garbage.
The chunks are alive. They are just alive for no reason.

C1GC is not a JIT trick, not a new GC algorithm, and not a lie about what "garbage collected"
means. It is a bouncer standing at the door of `ChunkMap`, and its only job is convincing
Java's real garbage collector that some of these chunks are, in fact, allowed to leave.

## The five stages of chunk grief

```
ACTIVE ---> QUARANTINED ---> COLLECTIBLE ---> DETACHED ---> PERSISTED
 (born)      (suspected)       (proven)        (exhaled)      (immortalized,
                                                                on a disk,
                                                                as NBT)
```

**ACTIVE.** The chunk is alive, resented by nobody, possibly still load-bearing for a
neighbor's generation step. Standard chunk behavior. Boring. Fine.

**QUARANTINED.** The x-frontier says this chunk is now more than `RETAIN_RADIUS` (12; the
generation pyramid's real dependency radius is 8, per `ChunkEvictor.kt`'s own bytecode-checked
math, plus a margin because trust is earned) columns behind the leading edge of generation.
Nobody *should* need it anymore. This is a heuristic, not a proof, and C1GC is very clear with
itself about the difference — which is why nothing gets thrown away yet.

**COLLECTIBLE.** The proof. Ticket revoked. `DistanceManager` updates drained until it stops
finding work. Every POI section this chunk owns, flushed and removed from
`PoiManager`'s own storage map (which, left to vanilla, never removes anything — SectionStorage
only flushes dirty entries, forever, into a map that only grows). After this step, nothing left
in vanilla's own live object graph can reach this chunk, mutate it, or ask it a question. This
is the one state transition in this whole file that's actually a legal argument, not a vibe.

**DETACHED.** `SerializableChunkData.copyOf(level, chunk)` — a single real Mojang method,
found the hard way (`javap -c` on the actual server jar, not guessed from Stack Overflow) — walks
the live `ChunkAccess`'s sections, heightmaps, block entities, and structure data into an
immutable Java `record`. That record holds no reference back to the thing it came from. The
instant this call returns, C1GC drops its own reference to the heavy object, and for the first
time since this chunk was born, nothing anywhere is stopping the JVM's own collector from doing
its actual job. This is the whole point of the exercise. Everything before this state was
plumbing; this line is the collection.

**PERSISTED.** The compact record's NBT bytes reach a real `.mca` region file, through real
vanilla machinery (`ChunkMap.write(ChunkPos, CompoundTag)`, inherited from
`SimpleRegionStorage`, dispatched into vanilla's own `IOWorker`/`RegionFileStorage`). Not a
bespoke format. Not a debug dump. The exact bytes a real, unmodified, ticking Paper server would
have written, had it ever bothered to tick.

A chunk may only advance one state at a time, in this exact order, or the transition table in
`ChunkLifecycle.kt` throws. This project has learned the hard way (many times over, see
`cursed-scientific-advancements.md`) that a scheduler which "shouldn't" reach an impossible
state and a scheduler which *cannot* reach an impossible state are very different scheduling
guarantees. C1GC picked the second one.

## The plot twist nobody asked for

Mojang already ships a dedicated IO worker. It's called `IOWorker`. It has a
`PriorityConsecutiveExecutor` and a `pendingWrites` map and everything. It's sitting right there
in every vanilla server, doing nothing useful in a benchmark harness that never asked it to
persist anything, because `-Dsaveworld=false` is the default and nobody was calling `save()`
until a chunk got evicted.

C1GC did not reinvent this. C1GC noticed it, and got out of its way for the part it's actually
good at (writing bytes to an `.mca` file, which is a solved problem Mojang solved years ago) —
while keeping the part that was missing entirely: an explicit, *bounded*, elastic pool of
workers doing the CPU-bound NBT encode *before* handing off to that real `IOWorker`, so
serializing a chunk's data doesn't have to fight Orion's own generation threads for a slot on
the shared `ForkJoinPool` they both, until now, were quietly sharing.

## The ring, and why it's allowed to make you wait

The "ring buffer" is an `ArrayBlockingQueue`, which is a ring buffer with paperwork: bounded
capacity, thread-safe, and — this part matters — it *blocks*. Feeding it is a
`ThreadPoolExecutor` that starts with one dedicated IO thread and grows, elastically, up to
`orion.c1gc.maxIoWorkers` additional ones, but only once the queue is actually full. If disk
throughput ever falls behind generation throughput hard enough to fill the queue *and* max out
every IO worker, `CallerRunsPolicy` kicks in: whatever thread just finished generating a chunk
gets handed the persistence job itself, synchronously, as a personal favor.

This is real backpressure, not a suggestion. Heap stays bounded because the number of
"detached but not yet persisted" chunks in flight is capped by the ring's capacity, full stop —
not by however fast a spinning disk feels like going that day. A slow disk makes Orion
generate more slowly. It does not make Orion generate itself into an OOM. That trade was
the entire point.

## The bill vanilla was actually sending us

The first cut of C1GC settled vanilla's distance/light graph (`runDistanceManagerUpdates()`,
which walks `ChunkTracker`/`DynamicGraphMinFixedPoint`) once per chunk, immediately after removing
that chunk's ticket. A JFR profile at champion scale said otherwise: that one call chain was
**~40% of all CPU time**, more than actual worldgen. Vanilla doesn't need to hear about a ticket
removal the instant it happens — the graph keeps its own pending-update state regardless of how
many mutations pile up before the next settle. So C1GC now removes tickets one at a time (cheap)
but only settles the graph and calls `processUnloads()` once every `BATCH_SIZE` (64) chunks. Same
correctness proof, paid for in bulk instead of retail. Post-fix profile: 0.7%.

## v5.5: collect when there's pressure, on the right thread, with a cheaper codec

**Don't collect an empty heap.** Under `orion5.5`, C1GC keeps its bookkeeping from the first chunk
but only starts reclaiming once the old generation's *current* occupancy crosses
`-Dorion.c1gc.pressure` (default `0.5`) of its max. At champion scale (6,400 chunks) it never
arms, so the ticket/POI/NBT/disk work costs nothing. At 65,536 chunks it arms partway through,
drains the backlog it had been deferring, then behaves like v5.4. `pressure=0` reproduces v5.4's
reclaim-from-the-first-chunk behavior. The first cut read `collectionUsage` (live set after the
last collection). That was a mistake: ParallelGC refreshes it only after a full GC, and the first
full GC happens only once the old gen is already full. It armed at 10.6GB live, then spent 698s
in back-to-back full GCs. Current occupancy arms while there's still room (finding #70).

**Main-thread work happens on the main thread.** `markComplete` fires from whichever thread
completed the chunk future, often a worker. v5.4 removed tickets and settled `DistanceManager`
right there, racing the poll thread's own `runDistanceManagerUpdates()` on a graph that isn't
thread-safe. It showed up once as an `ArrayIndexOutOfBoundsException` inside
`LongLinkedOpenHashSet.fixPointers`. Now `markComplete` only records (and checks pressure), and
`drain()` does the reclamation from the poll thread between `pollTask` calls, where no vanilla
task is mid-update. This fix applies to `orion5.4` as well.

**LZ4 regions.** `orion5.5` sets vanilla's own `region-file-compression=lz4`
(`RegionFileVersion` id 4, `LZ4BlockOutputStream`) before boot. Vanilla reads it back through
the per-chunk codec byte. Verified by decoding a whole 65,536-chunk world through
`RegionFile.getChunkDataInputStream` with 0 errors. `-Dorion.c1gc.compression=deflate`
restores the default. The tradeoff: about 45% more disk for less encode CPU on the `IO-Worker`
lane.

## What C1GC is explicitly not trying to do

Make worldgen faster. It won't, and if a benchmark run ever claims it did, don't believe it —
go find the actual explanation, the way this project always has. C1GC's only promise is that a
sustained run at any tile size spends a bounded amount of heap on chunks that have already done
their job, so a small heap and a big task can coexist without one of them losing catastrophically
to the other at 3 in the morning while everyone's asleep.

## Known simplifications (said out loud, not buried)

- `ChunkMap.save()`'s own `markPosition(pos, chunkType)` bookkeeping (an `isExistingChunkFull`
  bitset used for later save-path checks) is skipped — C1GC bypasses `save()` entirely rather
  than calling it, so this one piece of vanilla accounting never runs. Costs nothing observable
  in a one-shot headless run that exits after generation; would need revisiting for a long-lived
  ticking server.
- The final `chunkMap.write()` dispatch still funnels through vanilla's own single `IOWorker`
  thread underneath, regardless of how many C1GC IO workers call it. The elastic pool
  parallelizes the CPU-bound `copyOf`/`write()` encode step, not the actual disk fsync — that
  part of the ceiling is still Mojang's, on purpose, because two threads racing to write the
  same region file is a corruption bug waiting to happen, not a feature.
- `tryMarkSaved()`'s dirty flag turned out to be a red herring in this headless runtime (see
  the finding this shipped with) — it's consumed for hygiene, not trusted as a gate. If this
  code ever runs inside an actual ticking server, that assumption is exactly the first thing to
  re-check.

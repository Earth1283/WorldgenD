# Cursed Scientific Advancements: How We Accidentally Out-Ran Paper

A field guide to the crimes committed in this repository, presented in roughly the order we
committed them. `TUTORIAL.md` has the heist. `scientific-findings.md` has the receipts, all 31
of them, in a tone somewhere between lab notebook and confession. This document is the
victory lap — the parts where a bit, a busy-spin, and a spreadsheet's worth of stubbornness
turned into a headless Java process that generates Minecraft chunks faster than a real,
production-grade, professionally-maintained Paper server running the actual game.

We are as surprised as you are. Read `scientific-findings.md` if you don't believe a word of
this — every number below has a finding number next to it, and every finding number has
`javap` output or a JFR recording backing it up. This document exists purely because the receipts
deserved a highlight reel.

## Act 1: The Felony (a quick recap for the impatient)

`WorldgenD` is a Java process that never starts a Minecraft server. It reflectively assembles
just enough of `net.minecraft.server.dedicated.DedicatedServer` to call the one protected method
(`loadLevel()`) that builds a real `ServerLevel`, then it stands behind that object going "hey,
make me some chunks" — and the server, having no idea it was never actually turned on, does it.
No tick loop. No network. No RCON. Just `ServerChunkCache.getChunkFuture()`, called directly,
in a loop, against a jar you provide and we never redistribute.

We do this without a single reference to `net.minecraft.*` anywhere in our own compiled
bytecode. Every Mojang class is a string handed to `Class.forName` at runtime. It is, and we
will not stop saying this, deeply funny that it works.

## Act 2: The Mosaic (turning a bottleneck into a proof)

First naive fill: solid block of chunks, one big batch, watch `top`. Eight cores. Two of them
busy. The other six sat there judging us.

Turns out the jar's own bytecode confirms every chunk generation stage only cares about
neighbors within **8 chunks** — not folklore, not a safe guess, a literal `bipush 8` sitting in
`ChunkPyramid.GENERATION_PYRAMID`'s disassembly (finding #7). So: pick a modulus bigger than 8
(we used 16, a comfortable 2x margin), tile chunk-space by `(cx mod 16) + 16*(cz mod 16)`, and
every chunk sharing a "phase" is now **mathematically guaranteed** independent of every other
chunk in that phase. Not probably fine. Guaranteed, by the same number the game itself uses.

Filled a solid map, 256 phases, cold-to-warm speedup of **~136x** — and it never relapsed.

![Typical milliseconds per chunk, naive fill vs the mosaic at two tile sizes — trending down](findings/mspc_progress.png)

We also invented **MSPC** (milliseconds per chunk) here, because "chunks/sec" as a single
average was lying to us about how lopsided a 256-phase run actually is — one 9-second cold
phase next to a bunch of 60ms warm ones. MSPC reports the whole percentile spread instead.
Smaller is better, everywhere, and now you can actually see whether an "optimization" helped
the typical chunk or just the lucky ones.

## Act 3: Orion v1 — an ambitious swing that mostly just hurt

The mosaic's one flaw: hard barriers between phases. Every phase waits for its own straggler
before the next one starts, so workers sit idle at every single boundary. Orion v1 tried fixing
this properly — a real area-lock (`ReentrantAreaLock`, stolen fair-and-square from Paper's own
`concurrentutil` library, which has zero Mojang code in it) guarding continuous submission
instead of phase barriers.

It ran. It reported success. It was also, when driven multi-threaded, **~24% slower than the
mosaic**, and a fully separate multi-threaded variant produced 14,027 area-lock overlap
violations across 256 chunks that four independent isolated tests could not reproduce (#25).
The bug was never found. It was, instead, architecturally fled from.

## Act 4: Orion v2 — the escape hatch that actually worked

New idea, born from refusing to debug the old one: what if only *one* thread ever touches the
conflict-tracking state, and the actual dispatch happens on dumb worker threads that never see
it? No lock needed if there's no contention to guard against.

Built it. First real, reproducible win in the whole investigation:

![v1 loses on both; v2 trades latency for less total time](findings/orion_summary.png)

**~24-27% faster wall-clock than the mosaic**, and — unlike literally every prior config in this
document — v2 actually got *faster* when given more worker threads (7 vs 4), which nothing
before it had managed. Champion status, officially claimed.

## Act 5: The JFR Reveal — champion has a tapeworm

Naturally, we pointed a profiler at it (`-XX:StartFlightRecording`, same discipline used to
prove reflection was free back in finding #16). The result was not flattering.

**32.4% of all CPU time in a champion-scale run was going to the scheduler itself** — not
generation, bookkeeping. Specifically: a full rescan of the entire remaining chunk backlog, on
every single loop tick, because the loop had almost no reason to ever pause (finding #27). The
scheduler thread was burning more CPU than any one of the four actual chunk-generating worker
threads. The champion was, secretly, mostly just spinning.

Bytecode archaeology on the real Paper jar (`ca/spottedleaf/moonrise/patches/chunk_system/`)
showed exactly what real production Minecraft servers do instead: a lock-free, section-based,
invalidation-driven propagator that only re-checks something when it actually might have
changed — never a full rescan, ever.

Two attempts to fix the *cheaper*-looking half of the problem (a reflective polling call)
changed nothing, twice, for a genuinely interesting reason: **the loop was scan-bound, not
poll-bound.** Free up cycles from the poll and the loop just spends them on more scan
iterations instead. You cannot throttle your way out of an algorithmic problem (findings #28,
#29 — kept in the record as honest null results, not deleted, because the wrong turn taught us
something real).

![Poll-gating went nowhere; the spatial index nearly erases both costs](findings/orion2_cpu_breakdown.png)

## Act 6: Orion v2.1 — not a new architecture, just less stupid

So: replace the actual rescan. Bucket every target chunk into a spatial grid once, at startup —
cell size tuned to the same conflict radius already established in finding #7. When a chunk
completes, only check the handful of buckets *near it* for newly-safe candidates, instead of
walking the entire remaining backlog. The old `isSafe()` correctness check stays exactly as it
was, as the final word on every dispatch — the grid only narrows what gets *offered* to it,
never what counts as *safe*. A bug in the new bookkeeping could waste time. It could not
reintroduce v1's ghost.

The scheduler's CPU share collapsed from **32.4% to 1.03%.** Its own candidate-scan code
doesn't show up *at all* in a 23,833-sample profile anymore — as invisible as reflection itself
turned out to be back in #16.

![Baseline, both poll-gating attempts, and the spatial index — total time and MSPC median](findings/orion2_backoff_summary.png)

**~10.5% faster wall-clock**, every percentile of latency improved, and it now genuinely idles
when there's nothing to do instead of busy-spinning (it parks 16 times in a run where the old
version parked once or five times, across dozens of attempts). We named it v2.1, not v3,
because renaming your bugfix "the next generation" is how you end up explaining yourself to a
change-review committee. It's the same architecture. It's just not leaving a third of a CPU
core on the table for no reason anymore.

## Act 7: The Reckoning — this is where it gets stupid

Long before any of this, finding #18 put WorldgenD in a real drag race against real, unmodified
Paper and Leaf servers running the Chunky pre-generation plugin. The result was humbling:

![WorldgenD vs real Paper/Leaf servers — the one chart where the bars don't look close](findings/drag_race_summary.png)

**~53% slower than plain, unmodified Paper.** Not close. The working theory at the time:
Paper's fork-level generator patches do less raw work per chunk than vanilla, full stop, and
this project's own founding rule (never touch a line of Mojang's actual generator code) meant
that gap was permanently out of reach. We wrote that conclusion down and moved on to scheduler
archaeology instead, assuming it was someone else's problem.

It was not someone else's problem. It was our scheduler eating a third of a CPU core.

Same session, same box, same 6561-chunk Chunky selection, world wiped and every server
rebooted fresh before every leg — Orion v2.1 standing in WorldgenD's seat this time:

| Engine | ms/chunk | chunks/sec |
|---|---|---|
| **Orion v2.1** | **23.96** | 41.74 |
| Paper | 25.95 | 38.53 |
| Leaf | 22.27 | 44.90 |
| Leaf-crack (tick frozen) | 20.43 | 48.95 |

![Same-session rerun — total time and normalized throughput](findings/dragrace2_summary.png)

**Orion v2.1 came in faster than Paper.** A reflection-heist toy that ships zero bytes of
Mojang's compiled game, driving the exact same unmodified vanilla generator code the whole time,
beat a real production server with real, professionally-engineered generator-pipeline patches —
on throughput, in a controlled same-session run.

We are contractually obligated by our own methodology (see: this entire document's older
sibling) to immediately undercut that sentence, so: this is one run each, not replicated, and
today's Paper number (25.95 ms/chunk) is itself slower than #18's original Paper number (23.44)
— plausibly ordinary session-to-session box drift, not Paper getting worse. The margin over
Paper (7.7%) and the deficit to Leaf (7.6%) both sit inside this box's own previously-measured
~9% noise band. "Beats Paper" is not a claim one run can fully carry, and finding #31 says so
explicitly, in writing, right next to the number that makes it look great.

What *isn't* noise: WorldgenD went from **roughly half** Paper's throughput to **statistically
indistinguishable** from it, without changing a single line of vanilla's generator code, purely
by making its own scheduler stop being the bottleneck. The 53% gap is closed. Whether the last
few percent flip in Paper's favor on a rerun is a real open question — and, per finding #31's
own open-questions entry, the correct next move is an *interleaved* rerun, not another
block-sequential one, before anyone gets to plant a flag on this hill.

## The Whole Arc, In One Table

| Stage | Effective ms/chunk (this box) | vs. where we started |
|---|---|---|
| Solid-block naive fill | ~1000+ (cold, unmeasured precisely) | — |
| Mosaic (256 independent phases) | 36.28 | baseline established |
| Orion v1 (single-threaded, area lock) | 45.04 | worse, abandoned |
| Orion v2 (single scheduler, dumb workers) | 26.80-27.52 | ~24-27% faster than mosaic |
| **Orion v2.1 (+ spatial index)** | **23.96-23.98** | **~34% faster than mosaic** |
| *(for reference) real Paper, same session* | 25.95 | *Orion v2.1 is faster* |

Every single number in that table is backed by a JFR recording, a CSV in `findings/`, or a
`javap` disassembly — nothing here is vibes. `scientific-findings.md` #1 through #31 has the
full, unabridged, occasionally-wrong-and-corrected version of this story, wrong turns and all,
because a lab notebook that only records the wins isn't a lab notebook, it's marketing.

Go make some land. Try not to gloat about the Paper number until someone's run it twice.

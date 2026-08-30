# Cursed Scientific Advancements: How We Accidentally Out-Ran Paper (Allegedly, on paper, at least)

A field guide to the crimes committed in this repository, presented in roughly the order we
committed them. `TUTORIAL.md` has the heist. `scientific-findings.md` has the receipts, all 35
of them and counting, in a tone somewhere between lab notebook and confession. This document is
the highlight reel — the parts where a bit, a busy-spin, and a spreadsheet's worth of stubbornness
turned into a headless Java process that got genuinely competitive with a real,
production-grade, professionally-maintained Paper server running the actual game.

"Allegedly" is doing real work in that title. Keep reading — we get there, then we take some of
it back, then we take some of *that* back too, because that's how this document works. Every
number below has a finding number next to it, and every finding number has `javap` output or a
JFR recording backing it up. Read `scientific-findings.md` if you don't believe a word of this.

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
on throughput, in a controlled same-session run. We wrote that sentence and immediately felt
nervous about it. Correctly, as it turns out — keep reading.

## Act 8: The Correction — turns out we'd drawn a good hand, not a winning one

We are, per this document's own stated rules, contractually obligated to check our own homework
before gloating. Finding #31's own open-questions entry said the honest next step was an
*interleaved* rerun (v2.1, Paper, v2.1, Paper, ...) rather than one block-sequential leg each —
block-sequential can't tell a real effect from both engines just having one good or bad run in a
row. So we ran it: three rounds, strictly alternating, one sitting.

| Round | Orion v2.1 | Paper | Diff |
|---|---|---|---|
| 1 | 23.06 | 24.89 | v2.1 +7.9% |
| 2 | 23.59 | 23.51 | tie (0.3%) |
| 3 | 23.69 | 23.65 | tie (0.17%) |
| **mean** | **23.45** | **24.02** | **2.4% apart** |

Only round 1 shows a real margin. Rounds 2 and 3 are statistical ties. Averaged out, v2.1 is
~2.4% faster than Paper — a gap so far inside this box's own established ~9% noise band that
"wins" stopped being a defensible word for it about two sentences ago. Act 7's single Paper leg
(25.95 ms/chunk) turns out to have been sitting on the slow tail of Paper's own natural
variance, which — this round — had more than double v2.1's own spread (σ=0.76 vs σ=0.34, small
sample, don't overread that specific number either).

The honest conclusion is a *better* headline than the one we almost ran with: Orion v2.1 closed
the ~53% gap from Act 7 down to **genuine statistical parity** with a real production Paper
server. Not a win. Not a loss. A tie, inside measurement noise, achieved by fixing our own
scheduler's self-inflicted overhead and touching zero lines of Mojang's actual generator code.
Parity is a claim the data can actually carry. "Beats Paper" wasn't, and we'd rather be the
document that caught its own mistake than the one that didn't.

## Act 9: The Second Wind — our own workers were slacking too

Asked, in general: what's bottlenecking us now that the scheduler is basically free? Went back
to the exact diagnostic that found the *original* wavefront problem all the way back in Act 2 —
live `top -bH` per-thread sampling, not vibes.

Aggregate CPU during a fresh champion run: **77.2% of the whole box idle.** Fine, only 4 workers
configured on an 8-core box — except five snapshots of just those four `Worker-Main` threads
told a sharper story:

| Snapshot | Sum of 4 worker threads' CPU% (max possible 400%) |
|---|---|
| 1-5 | 363.5%, 190.0%, 254.4%, 154.6%, 140.0% |

Averaging ~220 out of 400 — **the workers we did configure were themselves only ~55% busy.**
Same signature Act 2 found for the original naive solid-block fill: the dependency graph's
frontier is only ever so wide at any given instant, and no amount of extra idle silicon fixes a
frontier that's momentarily too thin to feed the workers standing by.

Tested the obvious follow-up anyway — 7 workers instead of 4, this box's real ceiling:

| Config | Total time | ms/chunk |
|---|---|---|
| v2.1, 4 workers | 148775ms | 23.25 |
| **v2.1, 7 workers** | **132949ms** | **20.77** |

**~10.6% faster** — bigger than v2's own 4→7 gain (6.3%), because v2.1 had more idle capacity
sitting around to go capture in the first place. Re-sampled under 7 workers: still bursty
(572.5%, 90.9%, 90.9%, 490.7%, 191.0% out of a possible 700%) — more capacity caught, frontier
thinness not solved, just less costly with more hands ready to catch whatever shows up.

## Act 10: Prying the Jar Open, Again — a filing cabinet made of `ArrayList`

Fair question got asked: is that thin frontier *purely* geometry, the way we assumed, or is
something MC-55596-shaped (finding #22's background-thread-order terrain bug) hiding a level
down? Only one way to find out — went and disassembled classes this project had never looked at
before: `ChunkMap`, `GenerationChunkHolder`, `ServerChunkCache`.

**The structure itself: boringly, reassuringly deterministic.** Every neighbor requirement comes
from the same static, coordinate-derived radius table Act 2 already found. No surprises there.

**But the path from "eligible" to "actually running" goes through a filing cabinet nobody
bolted down.** `ChunkMap.pendingGenerationTasks` disassembles to a **plain, unsynchronized
`java.util.ArrayList`** — `add()` from one method, a `forEach` + `clear()` from another, no lock
anywhere in sight. That should be a hazard. It isn't, because we traced *why*: every
`getChunkFuture()` call from any thread that isn't vanilla's own main thread gets funneled,
via `CompletableFuture.supplyAsync(..., mainThreadProcessor)`, onto one single-threaded
executor before it ever touches that list. Every one of our own eight dispatch threads,
routed through the same one door. Not a bug. Deliberate, and — we checked — it holds.

The consequence: a chunk can be fully, geometrically eligible and still sit in that `ArrayList`
doing nothing until the next time *something* happens to poll that one specific executor. A
real, additional latency source, stacked on top of the geometric thinness Act 9 already found —
different in kind from MC-55596, though: that bug corrupts *what* gets generated. This one only
ever delays *when* it starts. The output stays correct. The clock doesn't care that it's correct.

## Act 11: Orion v2.2 — restoring a lesson we'd already learned once

Somewhere in all this, a sharp observation landed: didn't Orion quietly *undo* one of Act 2's
own insights? The mosaic's modulus math doesn't just prove independence — within any single
phase, the included chunks form an evenly-spaced lattice across the *entire* region,
automatically. That's the same "scatter beats one contiguous blob" fix Act 2 used, just
generalized into gapless full coverage. Orion dropped the mosaic's hard barriers, correctly —
but the actual candidate order it walks is a plain row-major sweep. Early in any run, every
held chunk clusters in one corner. We'd reintroduced the exact clustering problem we'd already
fixed once, just one layer further down.

A space-filling curve (Hilbert, Z-order) was floated and rejected on the spot — those exist
specifically to *preserve* locality, the opposite of what a scatter fix needs. What actually
works: rank every residue by a 2D digit-reversal (bit-reverse the linear step index, de-interleave
into two axes) — the standard 2D generalization of a van der Corput low-discrepancy sequence.
Checked by hand before trusting it: the first four ranks land on `(0,0)`, `(0,8)`, `(8,0)`,
`(8,8)` — the four corners of the tile — and the full 256-entry map is a confirmed bijection.

| Config | Total time | MSPC p50 |
|---|---|---|
| 4w, no scatter | 148775ms | 259.82ms |
| 4w, scatter | 146021ms (-1.9%) | 144.10ms (**-44.5%**) |
| 7w, no scatter | 132949ms | 236.43ms |
| 7w, scatter | 136583ms (+2.7%) | 115.88ms (**-51.0%**) |

![Total time barely moves either way; median latency drops 45-51%](findings/orion2_2_scatter_order.png)

Genuinely mixed, reported as such rather than rounded up into another victory lap: median
per-chunk latency roughly *halved*, in both worker configs — a real, large effect. Total
wall-clock time barely moved, and possibly got a hair worse at 7 workers (one run, edge of
noise, not replicated). Scatter-ordering changes *when* any given chunk gets its turn, not the
total amount of work or the aggregate CPU ceiling — latency and throughput, it turns out, are
still two different metrics that don't have to move together, which is a lesson this project
has now been taught twice by two different schedulers. The reigning throughput champion stays
plain 7-worker v2.1, no scatter, 132949ms. v2.2 is real, it's just a tail-latency fix wearing a
version bump, and we're naming it that instead of pretending otherwise.

## The Whole Arc, In One Table

| Stage | Effective ms/chunk (this box) | vs. where we started |
|---|---|---|
| Solid-block naive fill | ~1000+ (cold, unmeasured precisely) | — |
| Mosaic (256 independent phases) | 36.28 | baseline established |
| Orion v1 (single-threaded, area lock) | 45.04 | worse, abandoned |
| Orion v2 (single scheduler, dumb workers) | 26.80-27.52 | ~24-27% faster than mosaic |
| Orion v2.1 (+ spatial index, 4 workers) | 23.25-23.98 | ~34% faster than mosaic |
| **Orion v2.1 (+ 7 workers)** | **20.77** | **~43% faster than mosaic, reigning champion** |
| Orion v2.2 (v2.1 + scatter order) | 20.77-22.81 | same throughput, ~half the median latency |
| *(for reference) Paper, interleaved same-session mean* | 24.02 | *genuine parity with v2.1 @ 4 workers, per Act 8* |

The last row is deliberately not compared against the champion row above it — the 7-worker
number has never been through Act 8's interleaved discipline against Paper, and until it has,
this document isn't going to imply a comparison it can't back up. That's the whole point of
having an Act 8 in the first place.

Every single number in that table is backed by a JFR recording, a `top -bH` snapshot, a CSV in
`findings/`, or a `javap` disassembly — nothing here is vibes. `scientific-findings.md` #1
through #35 has the full, unabridged, occasionally-wrong-and-corrected version of this story,
wrong turns and all, because a lab notebook that only records the wins isn't a lab notebook,
it's marketing.

Go make some land. Don't gloat about the Paper number until someone's run it interleaved twice.

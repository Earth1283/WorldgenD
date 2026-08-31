# WorldgenD: Findings #41-80 — Orion Deep Dives

**Continuation from [`scientific-findings-1-40.md`](scientific-findings-1-40.md).** Same methodology, same rigor, same discipline. Seed `69`, hardcoded, every run.

---

## Open questions / where you pick this up

(Imported from end of #1-40 document, still valid):

- **#18's ~53% gap to Paper/Leaf is unexplained beyond "probably the generator patches."** Thread-count is ruled out (Paper used 2 dedicated workers to WorldgenD's 4 and still won). The leading theory — Paper/Leaf's fork-level chunk-generation patches doing genuinely less work per chunk than vanilla — has never been checked against an actual source or bytecode diff the way #6/#7's claims were.
- **#31's block-sequential "beats Paper" result was retested interleaved in #32 and downgraded to genuine parity** (v2.1 23.45ms vs Paper 24.02ms mean) — the right, more defensible headline. Still open: #32 used n=3 rounds each, too thin to say much about Paper's own run-to-run variance.
- **#33 found v2.1's workers only ~55% utilized** and 7 workers recovers 10.6% — but that number was never through #32's interleaved-rerun discipline before comparing to Paper/Leaf. The natural next step: #32's recipe again with WorldgenD running v2.1 at 7 workers.
- **#35's scatter-order latency win is real and large (45-51% off MSPC p50) but total time remains a wash** — the latency/throughput decoupling is confirmed as of #40, mechanism still unexplained. Worker utilization re-sample under scatter config would show whether the latency win traces to smoother dispatch or something else.
- **#26's v2 win is a real measured champion-scale result (24-27% faster than mosaic at both worker counts) but `orion.maxinflight` was never tuned.** Default 64 is 9-16x the real worker count; capping closer to worker count would show whether v2's brutal tail latency (p99 3.5-3.6s) is inherent or unbounded-admission artifact.


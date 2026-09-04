package io.github.eath1283.worldgend

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate

// #53: wraps SurfaceRules$BiomeConditionSource.biomeNameTest. Its input space is the
// dimension's biome registry (a few dozen ResourceKeys, fixed for the run) so results
// are cached by identity instead of re-running the underlying Set.contains probe per
// column. See scientific-findings-41-80.md #53 and static-analysis-findings.md.
class MemoizingPredicate(private val delegate: Predicate<Any>) : Predicate<Any> {
    private val cache = ConcurrentHashMap<Any, Boolean>()

    override fun test(t: Any): Boolean = cache.computeIfAbsent(t, delegate::test)
}

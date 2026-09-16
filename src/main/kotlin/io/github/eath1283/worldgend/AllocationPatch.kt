package io.github.eath1283.worldgend

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import java.util.function.Supplier

// Orion v5.3 (memory-issue.md): four allocation-pressure fixes ported from a JFR
// allocation profile of Orion v5.1 (116.87 GB / 70.5s). All four replace a per-call
// allocation with a reused buffer or a cached-in-place field; none change what gets
// computed. Tracked separately from OrionPatchAgent's other patches since they touch
// four unrelated vanilla classes with no shared bytecode shape.
object AllocationPatch {
    private val transformed = ConcurrentHashMap.newKeySet<String>()

    fun markTransformed(className: String) {
        transformed.add(className)
    }

    fun requireInstalled(loader: ClassLoader, targets: Set<String>) {
        targets.forEach { Class.forName(it, false, loader) }
        check(transformed.containsAll(targets)) {
            "Orion v5.3 requires -javaagent:build/libs/orion-agent.jar -Dorion.patchAllocations=true; missing: ${targets - transformed}"
        }
    }
}

// `DensityFunctions$Ap2.fillArray`'s ADD branch does `new double[values.length]` as a
// scratch buffer for argument2's output, every call (memory-issue.md's #1 site, 10.70GB).
// A nested ADD-of-ADD tree needs more than one such buffer alive at once (the outer
// buffer is still being read while the inner node grabs its own), so this is a
// depth-indexed per-thread stack, not a single reused array. Depth resets to 0 between
// unrelated fillArray call trees since enter()/exit() bracket the whole method.
object Ap2Scratch {
    private const val MAX_DEPTH = 64
    private val depth = ThreadLocal.withInitial { 0 }
    private val slots = ThreadLocal.withInitial { arrayOfNulls<DoubleArray>(MAX_DEPTH) }
    private val maxDepthSeen = java.util.concurrent.atomic.AtomicInteger(0)

    @JvmStatic
    fun maxDepthSeen(): Int = maxDepthSeen.get()

    @JvmStatic
    fun enter() {
        val d = depth.get()
        check(d < MAX_DEPTH) { "Ap2.fillArray scratch nesting exceeded $MAX_DEPTH" }
        depth.set(d + 1)
        maxDepthSeen.updateAndGet { maxOf(it, d + 1) }
    }

    @JvmStatic
    fun exit() {
        depth.set(depth.get() - 1)
    }

    @JvmStatic
    fun checkout(len: Int): DoubleArray {
        val idx = depth.get() - 1
        val perThread = slots.get()
        var buf = perThread[idx]
        if (buf == null || buf.size < len) {
            buf = DoubleArray(len)
            perThread[idx] = buf
        }
        return buf
    }
}

// `Aquifer$NoiseBasedAquifer.computeSubstance` does `new MutableDouble(NaN)` once per
// call as an output parameter to calculatePressure (memory-issue.md's smallest site,
// 1.0GB). Not called recursively on itself, so a plain per-thread singleton (reset
// before each use) is safe, unlike Ap2Scratch. Reflection-based (commons-lang3 isn't a
// compile-time dependency of this module) mirrors this project's usual pattern for
// touching runtime-provided classes.
object MutableDoubleScratch {
    @Volatile private var ctor: Constructor<*>? = null
    @Volatile private var setValue: Method? = null
    private val perThread = ThreadLocal<Any?>()

    @JvmStatic
    fun reset(cls: Class<*>, initial: Double): Any {
        var c = ctor
        if (c == null) {
            c = cls.getConstructor(Double::class.javaPrimitiveType)
            setValue = cls.getMethod("setValue", Double::class.javaPrimitiveType)
            ctor = c
        }
        val existing = perThread.get()
        if (existing == null) {
            val fresh = c.newInstance(initial)
            perThread.set(fresh)
            return fresh!!
        }
        setValue!!.invoke(existing, initial)
        return existing
    }
}

// Backs `SurfaceRules$Context.biome`. The original does
// `this.biome = Suppliers.memoize(() -> biomeGetter.apply(pos.set(x, y, z)))` on every
// updateY call (memory-issue.md's #2 site: a fresh lambda capture plus a fresh memoizing
// wrapper per Y-level). One instance lives for the Context's whole lifetime; updateY
// calls reset() instead of allocating, and get() lazily computes+caches exactly like
// Suppliers.memoize did. pos.set(...) is invoked reflectively so this module keeps zero
// compile-time references to net.minecraft.* (see README).
class ResettableBiomeSupplier(
    private val biomeGetter: Function<Any, Any>,
    private val pos: Any,
) : Supplier<Any> {
    private val setPos: Method = pos.javaClass.getMethod(
        "set", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
    )
    private var x = 0
    private var y = 0
    private var z = 0
    private var cached: Any? = null
    private var has = false

    fun reset(x: Int, y: Int, z: Int) {
        this.x = x
        this.y = y
        this.z = z
        has = false
        cached = null
    }

    override fun get(): Any {
        if (!has) {
            val p = setPos.invoke(pos, x, y, z)
            cached = biomeGetter.apply(p)
            has = true
        }
        return cached!!
    }
}

package io.github.eath1283.worldgend

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import javassist.ClassPool
import javassist.CtClass
import javassist.LoaderClassPath
import javassist.CtNewMethod

object StructureGenRaceDetector {
    private val active = java.util.concurrent.ConcurrentHashMap<Any, MutableSet<Thread>>()
    private val violations = java.util.concurrent.atomic.AtomicLong(0)
    private val logFile = java.io.File("/tmp/orion_race_detector.log").apply { writeText("") }

    @JvmStatic
    fun enter(key: Any, label: String) {
        val threads = active.computeIfAbsent(key) { java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap()) }
        synchronized(threads) {
            threads.add(Thread.currentThread())
            if (threads.size > 1) {
                violations.incrementAndGet()
                val names = threads.joinToString(",") { it.name }
                synchronized(logFile) { logFile.appendText("VIOLATION label=$label key=$key threads=[$names]\n") }
            }
        }
    }

    @JvmStatic
    fun exit(key: Any) {
        active[key]?.let { threads -> synchronized(threads) { threads.remove(Thread.currentThread()) } }
    }

    @JvmStatic
    fun report(): String = "violations=${violations.get()}"
}

// Per-thread placeCount, keyed by PieceWeight instance identity. A real field added via
// javassist wouldn't resolve at compile-time from a DIFFERENT class's patched bytecode
// (each transform() call gets its own ClassPool); a static method on an already-compiled
// class sidesteps that, same trick patchBiomeConditionSource already uses below.
object OrionPlaceCount {
    private val perThread = ThreadLocal.withInitial { java.util.IdentityHashMap<Any, Int>() }
    @JvmStatic fun get(key: Any): Int = perThread.get()[key] ?: 0
    @JvmStatic fun set(key: Any, value: Int) { perThread.get()[key] = value }
}

// Orion v4 prerequisite, ported from C2ME-fabric (MIT). Fixes races on StrongholdPieces'
// static state and shared PieceWeight.placeCount when worker threads populate structures
// concurrently. Gated behind -Dorion.patchStructureGenState=true.
object OrionPatchAgent {
    private const val BLOCKABLE_EVENT_LOOP = "net.minecraft.util.thread.BlockableEventLoop"
    private const val SERVER_CHUNK_CACHE = "net.minecraft.server.level.ServerChunkCache"
    private const val SURFACE_RULES_BIOME_CONDITION = "net.minecraft.world.level.levelgen.SurfaceRules\$BiomeConditionSource"
    private const val STRONGHOLD_PIECES = "net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces"
    private const val STRONGHOLD_PIECE_WEIGHT = "net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces\$PieceWeight"
    private const val NETHER_FORTRESS_PIECES = "net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces"
    private const val NETHER_FORTRESS_PIECE_WEIGHT = "net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces\$PieceWeight"
    private const val DENSITY_FUNCTIONS_AP2 = "net.minecraft.world.level.levelgen.DensityFunctions\$Ap2"
    private const val CHUNK_MAP = "net.minecraft.server.level.ChunkMap"
    private const val STRUCTURE_START = "net.minecraft.world.level.levelgen.structure.StructureStart"
    private const val WORLD_GEN_REGION = "net.minecraft.server.level.WorldGenRegion"
    private const val SURFACE_RULES_CONTEXT = "net.minecraft.world.level.levelgen.SurfaceRules\$Context"
    private const val SURFACE_RULES_SEQUENCE_RULE = "net.minecraft.world.level.levelgen.SurfaceRules\$SequenceRule"
    private const val AQUIFER_NOISE_BASED = "net.minecraft.world.level.levelgen.Aquifer\$NoiseBasedAquifer"
    val ALLOCATION_PATCH_TARGETS = setOf(DENSITY_FUNCTIONS_AP2, SURFACE_RULES_CONTEXT, SURFACE_RULES_SEQUENCE_RULE, AQUIFER_NOISE_BASED)

    // Straight-to-file, not println: #23's already-documented quirk where buffered stdout
    // doesn't reliably reach the redirected log until process exit — same fix as
    // OrionV3's own diag thread uses.
    private val debugFile = java.io.File("/tmp/orion_agent_debug.log").apply { writeText("") }

    private fun debugLog(msg: String) {
        synchronized(debugFile) { debugFile.appendText("$msg\n") }
    }

    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        val patchReentrancy = System.getProperty("orion.patchReentrancy") == "true"
        val patchBiomeMemo = System.getProperty("orion.patchBiomeMemo") == "true"
        val patchStructureGenState = System.getProperty("orion.patchStructureGenState") == "true"
        val detectStructureGenRaces = System.getProperty("orion.detectStructureGenRaces") == "true"
        val patchDfc = System.getProperty("orion.patchDfc") == "true"
        val patchParallelSteps = System.getProperty("orion.patchParallelSteps") == "true"
        val patchWorldgenLight = System.getProperty("orion.patchWorldgenLight") == "true"
        val scheduler = System.getProperty("scheduler")
        val patchDensitySimd = scheduler == "orion5.1" || scheduler == "orion5.2" ||
            System.getProperty("orion.patchDensitySimd") == "true"
        val patchImprovedNoise = scheduler == "orion5.2" ||
            System.getProperty("orion.patchImprovedNoise") == "true"
        val patchAllocations = scheduler == "orion5.3" || scheduler == "orion5.4" ||
            System.getProperty("orion.patchAllocations") == "true"
        if (!patchReentrancy && !patchBiomeMemo && !patchStructureGenState && !detectStructureGenRaces && !patchDfc && !patchParallelSteps && !patchWorldgenLight && !patchDensitySimd && !patchImprovedNoise && !patchAllocations) {
            System.err.println("[OrionPatchAgent] no patch flags set, not installing (vanilla control path)")
            return
        }
        if (patchReentrancy) System.err.println("[OrionPatchAgent] will patch $BLOCKABLE_EVENT_LOOP and $SERVER_CHUNK_CACHE on load")
        if (patchBiomeMemo) System.err.println("[OrionPatchAgent] will patch $SURFACE_RULES_BIOME_CONDITION on load")
        if (patchStructureGenState) System.err.println(
            "[OrionPatchAgent] will patch $STRONGHOLD_PIECES, $STRONGHOLD_PIECE_WEIGHT, " +
                "$NETHER_FORTRESS_PIECES, $NETHER_FORTRESS_PIECE_WEIGHT on load"
        )
        if (detectStructureGenRaces) {
            System.err.println("[OrionPatchAgent] will instrument race-detection probes on $STRONGHOLD_PIECES, $STRONGHOLD_PIECE_WEIGHT")
            Runtime.getRuntime().addShutdownHook(Thread { System.err.println("[OrionPatchAgent] race detector: ${StructureGenRaceDetector.report()}") })
            inst.addTransformer(RaceDetectorTransformer())
        }
        if (patchDfc) {
            System.err.println("[OrionPatchAgent] will patch $DENSITY_FUNCTIONS_AP2 on load (DFC Stage 1, findings #57/#58)")
            Runtime.getRuntime().addShutdownHook(Thread { debugLog("DfcRuntime report: ${DfcRuntime.report()}") })
        }
        if (patchParallelSteps) {
            System.err.println("[OrionPatchAgent] will patch $CHUNK_MAP and $STRUCTURE_START on load (parallel steps, finding #62)")
            Runtime.getRuntime().addShutdownHook(Thread { debugLog("OrionParallelSteps report: ${OrionParallelSteps.report()}") })
        }
        if (patchWorldgenLight) System.err.println("[OrionPatchAgent] will patch $WORLD_GEN_REGION light reads on load (finding #65)")
        if (patchDensitySimd) System.err.println("[OrionPatchAgent] density batches: ${DensityBatch.report()}")
        if (patchImprovedNoise) System.err.println("[OrionPatchAgent] will patch ${ImprovedNoisePatch.target} on load")
        if (patchAllocations) System.err.println("[OrionPatchAgent] will patch $ALLOCATION_PATCH_TARGETS on load (allocation pressure, orion5.3, memory-issue.md)")
        inst.addTransformer(Transformer(patchReentrancy, patchBiomeMemo, patchStructureGenState, patchDfc, patchParallelSteps, patchWorldgenLight, patchDensitySimd, patchImprovedNoise, patchAllocations))
    }

    private class RaceDetectorTransformer : ClassFileTransformer {
        override fun transform(
            loader: ClassLoader?,
            className: String,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray,
        ): ByteArray? {
            val dotted = className.replace('/', '.')
            if (dotted != STRONGHOLD_PIECES && dotted != STRONGHOLD_PIECE_WEIGHT) return null
            val pool = ClassPool(false).apply {
                appendSystemPath()
                if (loader != null) appendClassPath(LoaderClassPath(loader))
            }
            return try {
                val cc = pool.makeClass(java.io.ByteArrayInputStream(classfileBuffer))
                if (dotted == STRONGHOLD_PIECES) {
                    for (m in listOf("resetPieces", "updatePieceWeight", "findAndCreatePieceFactory", "generatePieceFromSmallDoor", "generateAndAddPiece")) {
                        val method = cc.getDeclaredMethod(m)
                        method.insertBefore("""{ io.github.eath1283.worldgend.StructureGenRaceDetector.enter("stronghold-static", "$m"); }""")
                        method.insertAfter("""{ io.github.eath1283.worldgend.StructureGenRaceDetector.exit("stronghold-static"); }""", true)
                    }
                } else {
                    val doPlace = cc.getDeclaredMethod("doPlace")
                    doPlace.insertBefore("""{ io.github.eath1283.worldgend.StructureGenRaceDetector.enter(${'$'}0, "doPlace"); }""")
                    doPlace.insertAfter("""{ io.github.eath1283.worldgend.StructureGenRaceDetector.exit(${'$'}0); }""", true)
                }
                val bytes = cc.toBytecode()
                cc.detach()
                debugLog("race-detection probes installed on $dotted, ${bytes.size} bytes")
                bytes
            } catch (t: Throwable) {
                debugLog("race-detection instrumentation of $dotted FAILED: $t\n${t.stackTraceToString()}")
                null
            }
        }
    }

    private class Transformer(
        private val patchReentrancy: Boolean,
        private val patchBiomeMemo: Boolean,
        private val patchStructureGenState: Boolean,
        private val patchDfc: Boolean,
        private val patchParallelSteps: Boolean,
        private val patchWorldgenLight: Boolean,
        private val patchDensitySimd: Boolean,
        private val patchImprovedNoise: Boolean,
        private val patchAllocations: Boolean,
    ) : ClassFileTransformer {
        override fun transform(
            loader: ClassLoader?,
            className: String,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray,
        ): ByteArray? {
            val dotted = className.replace('/', '.')
            val structureGenTargets = setOf(STRONGHOLD_PIECES, STRONGHOLD_PIECE_WEIGHT, NETHER_FORTRESS_PIECES, NETHER_FORTRESS_PIECE_WEIGHT)
            val handled = (patchReentrancy && (dotted == BLOCKABLE_EVENT_LOOP || dotted == SERVER_CHUNK_CACHE)) ||
                (patchBiomeMemo && dotted == SURFACE_RULES_BIOME_CONDITION) ||
                (patchStructureGenState && dotted in structureGenTargets) ||
                (patchDfc && dotted == DENSITY_FUNCTIONS_AP2) ||
                (patchParallelSteps && (dotted == CHUNK_MAP || dotted == STRUCTURE_START)) ||
                (patchWorldgenLight && dotted == WORLD_GEN_REGION) ||
                (patchDensitySimd && dotted in DensitySimdPatch.targets) ||
                (patchImprovedNoise && dotted == ImprovedNoisePatch.target) ||
                (patchAllocations && dotted in ALLOCATION_PATCH_TARGETS)
            if (!handled) return null
            debugLog("transform() invoked for $dotted")
            return try {
                val result = if (dotted in DensitySimdPatch.targets) DensitySimdPatch.transform(loader, classfileBuffer)
                else if (dotted == ImprovedNoisePatch.target) ImprovedNoisePatch.transform(loader, classfileBuffer)
                else when (dotted) {
                    BLOCKABLE_EVENT_LOOP -> patchBlockableEventLoop(loader, classfileBuffer)
                    SERVER_CHUNK_CACHE -> patchServerChunkCache(loader, classfileBuffer)
                    SURFACE_RULES_BIOME_CONDITION -> patchBiomeConditionSource(loader, classfileBuffer)
                    STRONGHOLD_PIECES -> patchStrongholdPieces(loader, classfileBuffer)
                    STRONGHOLD_PIECE_WEIGHT -> patchPieceWeightPlaceCount(loader, classfileBuffer)
                    NETHER_FORTRESS_PIECES -> patchOuterPlaceCountUsage(loader, classfileBuffer, NETHER_FORTRESS_PIECE_WEIGHT)
                    DENSITY_FUNCTIONS_AP2 -> patchAp2(loader, classfileBuffer, patchDfc, patchAllocations)
                    SURFACE_RULES_CONTEXT -> patchSurfaceRulesContext(loader, classfileBuffer)
                    SURFACE_RULES_SEQUENCE_RULE -> patchSequenceRule(loader, classfileBuffer)
                    AQUIFER_NOISE_BASED -> patchAquiferMutableDouble(loader, classfileBuffer)
                    CHUNK_MAP -> patchChunkMapApplyStep(loader, classfileBuffer)
                    STRUCTURE_START -> patchStructureStartPlacement(loader, classfileBuffer)
                    WORLD_GEN_REGION -> patchWorldGenRegionLight(loader, classfileBuffer)
                    else -> patchPieceWeightPlaceCount(loader, classfileBuffer)
                }
                debugLog("transform() of $dotted succeeded, ${result.size} bytes")
                result
            } catch (t: Throwable) {
                debugLog("transform() of $dotted FAILED: $t\n${t.stackTraceToString()}")
                null
            }
        }

        private fun pool(loader: ClassLoader?): ClassPool {
            val pool = ClassPool(false)
            pool.appendSystemPath()
            if (loader != null) pool.appendClassPath(LoaderClassPath(loader))
            return pool
        }

        // #62: ChunkMap.applyStep runs inside the serial "worldgen" ConsecutiveExecutor and calls
        // ChunkStep.apply synchronously. Swapping that one call for OrionParallelSteps.apply
        // returns an incomplete future instead, which ChunkGenerationTask already handles
        // (it is the same path vanilla's async NOISE step takes).
        private fun patchChunkMapApplyStep(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            var matches = 0
            cc.getDeclaredMethod("applyStep").instrument(object : javassist.expr.ExprEditor() {
                override fun edit(m: javassist.expr.MethodCall) {
                    if (m.className == "net.minecraft.world.level.chunk.status.ChunkStep" && m.methodName == "apply") {
                        matches++
                        m.replace(
                            """{
                                ${'$'}_ = io.github.eath1283.worldgend.OrionParallelSteps.apply(
                                    ${'$'}0, ${'$'}1, ${'$'}2, ${'$'}3,
                                    ${'$'}3.getPos().x(), ${'$'}3.getPos().z(),
                                    ${'$'}0.targetStatus().getName(), ${'$'}0.blockStateWriteRadius());
                            }"""
                        )
                    }
                }
            })
            check(matches == 1) { "expected exactly one ChunkStep.apply call in ChunkMap.applyStep, found $matches" }
            val bytes = cc.toBytecode()
            cc.detach()
            return bytes
        }

        // #65: features read light (MushroomBlock.canSurvive's brightness < 13) from the level's
        // live light engine, which lower-key neighbors fill asynchronously after their own FEATURES.
        // Answer as an uninitialized column does (sky 15, block 0): what the decorated chunk always
        // sees for itself, and what vanilla sees whenever light lags behind features.
        private fun patchWorldGenRegionLight(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            cc.addMethod(CtNewMethod.make(
                "public int getRawBrightness(net.minecraft.core.BlockPos pos, int darkening) { return Math.max(0, 15 - darkening); }", cc))
            cc.addMethod(CtNewMethod.make(
                "public int getBrightness(net.minecraft.world.level.LightLayer layer, net.minecraft.core.BlockPos pos) " +
                    "{ return layer == net.minecraft.world.level.LightLayer.SKY ? 15 : 0; }", cc))
            val bytes = cc.toBytecode()
            cc.detach()
            return bytes
        }

        // #62: placeInChunk mutates shared piece/template state, so it takes the same global
        // structure lock OrionParallelSteps holds for structure_starts/references/spawn.
        private fun patchStructureStartPlacement(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            val placeInChunk = cc.getDeclaredMethod("placeInChunk")
            placeInChunk.insertBefore("{ io.github.eath1283.worldgend.OrionParallelSteps.lockStructures(); }")
            placeInChunk.insertAfter("{ io.github.eath1283.worldgend.OrionParallelSteps.unlockStructures(); }", true)
            val bytes = cc.toBytecode()
            cc.detach()
            return bytes
        }

        // isSameThread() gates executeBlocking()/submitAsync().join() on
        // `Thread.currentThread() == getRunningThread()`, a fixed field. Teach it to also
        // recognize "the thread currently executing doRunTask() on THIS instance" as
        // same-thread — restores the reentrancy vanilla gets for free from having exactly
        // one caller thread. A no-op wherever that's still true (all of v2.1/v2.2/vanilla).
        private fun patchBlockableEventLoop(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))

            // Reentrancy DEPTH (Integer), not a plain boolean flag saved/restored across
            // insertBefore/insertAfter via a method-level local: that first attempt
            // (addLocalVariable + insertAfter(asFinally=true)) made javassist's stackmap
            // builder choke ("conflict: *top* and java.lang.Object" — confirmed via a
            // debug run, not guessed) on both pollTask() and doRunTask(), trivial or not.
            // A counter that only ever increments/decrements by 1 needs no saved prior
            // value at all, so each injected snippet is self-contained with its own
            // block-scoped temps instead of a cross-snippet method-level slot.
            val field = javassist.CtField.make(
                "private final ThreadLocal orionReentrant = new ThreadLocal();",
                cc,
            )
            cc.addField(field)

            val isSameThread = cc.getDeclaredMethod("isSameThread")
            isSameThread.setBody(
                """{
                    if (Thread.currentThread() == getRunningThread()) return true;
                    Object v = orionReentrant.get();
                    return v != null && ((Integer) v).intValue() > 0;
                }"""
            )

            // Wrap pollTask(): it strictly encloses every doRunTask() call, including
            // nested ones through managedBlock()'s own internal pollTask() loop, so the
            // reentrancy window is the same as wrapping doRunTask() directly would give.
            val pollTask = cc.getDeclaredMethod("pollTask")
            pollTask.insertBefore(
                """{
                    Object v = orionReentrant.get();
                    int d = (v == null) ? 0 : ((Integer) v).intValue();
                    orionReentrant.set(Integer.valueOf(d + 1));
                }"""
            )
            pollTask.insertAfter(
                """{
                    int d = ((Integer) orionReentrant.get()).intValue() - 1;
                    orionReentrant.set(Integer.valueOf(d));
                }""",
                true,
            )

            val bytes = cc.toBytecode()
            if (System.getProperty("orion.patchDebugDump") == "true") {
                java.io.File("/tmp/BlockableEventLoop_patched.class").writeBytes(bytes)
            }
            cc.detach()
            return bytes
        }

        // getChunk()'s OWN inline `Thread.currentThread() != this.mainThread` branch
        // (confirmed via javap — a separate check from isSameThread(), doesn't call it)
        // decides submit-and-join vs the self-pumping managedBlock() path vanilla already
        // has for the reentrant case. orion3-poll is never literally `this.mainThread`, so
        // even its own recursive getChunk() calls (from a task doRunTask() is already
        // running on it) take the submit-and-join branch: it enqueues onto its own queue
        // and blocks on a future only it could ever service. Intercept just that one field
        // read, scoped to this one method, and substitute Thread.currentThread() for it
        // when mainThreadProcessor.isSameThread() already says we're reentrant-safe (per
        // the BlockableEventLoop patch above) — makes the comparison false, routing into
        // the correct managedBlock() branch instead. Every other read of `mainThread`
        // elsewhere in this class (real thread-confinement checks) is untouched.
        private fun patchServerChunkCache(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            val getChunk = cc.getDeclaredMethod(
                "getChunk",
                arrayOf(
                    CtClass.intType, CtClass.intType,
                    cc.classPool.get("net.minecraft.world.level.chunk.status.ChunkStatus"),
                    CtClass.booleanType,
                ),
            )
            var matches = 0
            getChunk.instrument(object : javassist.expr.ExprEditor() {
                override fun edit(f: javassist.expr.FieldAccess) {
                    if (f.isReader && f.fieldName == "mainThread") {
                        matches++
                        f.replace(
                            """{
                                ${'$'}_ = ${'$'}proceed();
                                if (mainThreadProcessor.isSameThread()) ${'$'}_ = Thread.currentThread();
                            }"""
                        )
                    }
                }
            })
            System.err.println("[OrionPatchAgent] getChunk(): patched $matches read(s) of `mainThread`")

            val bytes = cc.toBytecode()
            if (System.getProperty("orion.patchDebugDump") == "true") {
                java.io.File("/tmp/ServerChunkCache_patched.class").writeBytes(bytes)
            }
            cc.detach()
            return bytes
        }

        // biomeNameTest is assigned once in the constructor (`Set.copyOf(biomes)::contains`,
        // confirmed via javap) and never reassigned elsewhere — wrapping it right after that
        // assignment makes every later Holder.is(biomeNameTest) call (in the BiomeCondition$1
        // local class, untouched) hit the memo cache instead of the Set. Field is `private
        // final`; javassist's own bytecode writer doesn't enforce final on a second putfield
        // within the declaring class's own <init>, but strip the modifier anyway so a stricter
        // verifier can't reject it.
        private fun patchBiomeConditionSource(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))

            val field = cc.getDeclaredField("biomeNameTest")
            field.modifiers = field.modifiers and javassist.Modifier.FINAL.inv()

            val ctor = cc.declaredConstructors[0]
            ctor.insertAfter(
                """{
                    biomeNameTest = new io.github.eath1283.worldgend.MemoizingPredicate(biomeNameTest);
                }"""
            )

            val bytes = cc.toBytecode()
            if (System.getProperty("orion.patchDebugDump") == "true") {
                java.io.File("/tmp/BiomeConditionSource_patched.class").writeBytes(bytes)
            }
            cc.detach()
            return bytes
        }

        // currentPieces/totalWeight/imposedPiece are static, single-writer-assumed. Redirect
        // to ThreadLocals. Also redirects this class's own reads/writes of PieceWeight.placeCount
        // (findAndCreatePieceFactory etc touch it directly on shared instances).
        private fun patchStrongholdPieces(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))

            cc.addField(javassist.CtField.make("private static final ThreadLocal orionCurrentPieces = new ThreadLocal();", cc))
            cc.addField(javassist.CtField.make("private static final ThreadLocal orionTotalWeight = new ThreadLocal();", cc))
            cc.addField(javassist.CtField.make("private static final ThreadLocal orionImposedPiece = new ThreadLocal();", cc))

            cc.instrument(object : javassist.expr.ExprEditor() {
                override fun edit(f: javassist.expr.FieldAccess) {
                    when (f.fieldName) {
                        "currentPieces" -> f.replace(
                            if (f.isReader) """{
                                java.util.List v = (java.util.List) orionCurrentPieces.get();
                                if (v == null) { v = new java.util.ArrayList(); orionCurrentPieces.set(v); }
                                ${'$'}_ = v;
                            }"""
                            else "{ orionCurrentPieces.set(\$1); }"
                        )
                        "totalWeight" -> f.replace(
                            if (f.isReader) "{ Object v = orionTotalWeight.get(); \$_ = v == null ? 0 : ((Integer) v).intValue(); }"
                            else "{ orionTotalWeight.set(Integer.valueOf(\$1)); }"
                        )
                        "imposedPiece" -> f.replace(
                            if (f.isReader) "{ \$_ = (Class) orionImposedPiece.get(); }"
                            else "{ orionImposedPiece.set(\$1); }"
                        )
                    }
                }
            })
            redirectPlaceCount(cc, STRONGHOLD_PIECE_WEIGHT)

            val bytes = cc.toBytecode()
            cc.detach()
            return bytes
        }

        // Redirects placeCount access within this class's own methods (doPlace/isValid) to
        // OrionPlaceCount.
        private fun patchPieceWeightPlaceCount(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            redirectPlaceCount(cc, cc.name)
            val bytes = cc.toBytecode()
            cc.detach()
            return bytes
        }

        // NetherFortressPieces' own static methods touch PieceWeight.placeCount directly,
        // same as StrongholdPieces does — no static state of its own to redirect otherwise.
        private fun patchOuterPlaceCountUsage(loader: ClassLoader?, original: ByteArray, pieceWeightFqcn: String): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            redirectPlaceCount(cc, pieceWeightFqcn)
            val bytes = cc.toBytecode()
            cc.detach()
            return bytes
        }

        // DFC Stage 1 integration (findings #57/#58/#59). #59's first cut cached
        // compiled entries in a ConcurrentHashMap keyed by an allocated identity
        // wrapper — a real ~23-27% regression, because the lookup itself cost more
        // than a real Ap2 node's tiny original compute() body. This version adds two
        // real instance fields directly to Ap2 instead: a cache hit is a plain field
        // read, no allocation, no hashmap. Fields deliberately not volatile — a
        // first-access race across worker threads means at most a few redundant
        // compiles (compileFor() is a pure function of an immutable DensityFunction
        // subtree, so redoing it is wasteful, never wrong), which is cheaper than
        // forcing every access through a memory barrier. Rename-and-wrap keeps
        // vanilla's own arithmetic completely unreplicated for the fallback.
        private fun patchAp2(loader: ClassLoader?, original: ByteArray, patchDfc: Boolean, patchAllocations: Boolean): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            if (patchDfc) patchAp2Compute(cc)
            if (patchAllocations) patchAp2FillArray(cc)
            val bytes = cc.toBytecode()
            cc.detach()
            if (patchAllocations) AllocationPatch.markTransformed(DENSITY_FUNCTIONS_AP2)
            return bytes
        }

        private fun patchAp2Compute(cc: CtClass) {
            cc.addField(javassist.CtField.make("public Object orionDfcCompiled;", cc))
            cc.addField(javassist.CtField.make("public Object orionDfcLeaves;", cc))
            val compute = cc.getDeclaredMethod("compute")
            compute.name = "computeOriginal"
            val wrapper = javassist.CtNewMethod.make(
                """public double compute(net.minecraft.world.level.levelgen.DensityFunction${'$'}FunctionContext ctx) {
                    io.github.eath1283.worldgend.DfcRuntime.countEval();
                    if (orionDfcCompiled == io.github.eath1283.worldgend.DfcRuntime.FAILED_MARKER) {
                        return computeOriginal(ctx);
                    }
                    if (orionDfcCompiled != null) {
                        return ((io.github.eath1283.worldgend.DfcCompiled) orionDfcCompiled).eval((Object[]) orionDfcLeaves, ctx);
                    }
                    Object[] result = io.github.eath1283.worldgend.DfcRuntime.compileFor(this);
                    if (result == null) {
                        orionDfcCompiled = io.github.eath1283.worldgend.DfcRuntime.FAILED_MARKER;
                        return computeOriginal(ctx);
                    }
                    orionDfcCompiled = result[0];
                    orionDfcLeaves = result[1];
                    return ((io.github.eath1283.worldgend.DfcCompiled) result[0]).eval((Object[]) result[1], ctx);
                }""",
                cc,
            )
            cc.addMethod(wrapper)
        }

        // memory-issue.md #1 (10.70GB): the ADD branch's `new double[values.length]` scratch
        // buffer, replaced with Ap2Scratch's depth-indexed per-thread reuse. enter()/exit()
        // bracket the whole call (matches every branch, not just ADD, but ADD is the only one
        // that reads the depth-indexed slot); see Ap2Scratch's own comment for why a plain
        // singleton isn't safe for nested ADD-of-ADD trees.
        private fun patchAp2FillArray(cc: CtClass) {
            val fillArray = cc.getDeclaredMethod("fillArray")
            fillArray.insertBefore("{ io.github.eath1283.worldgend.Ap2Scratch.enter(); }")
            fillArray.insertAfter("{ io.github.eath1283.worldgend.Ap2Scratch.exit(); }", true)
            var matches = 0
            fillArray.instrument(object : javassist.expr.ExprEditor() {
                override fun edit(a: javassist.expr.NewArray) {
                    if (a.componentType == CtClass.doubleType) {
                        matches++
                        a.replace("{ \$_ = io.github.eath1283.worldgend.Ap2Scratch.checkout(\$1); }")
                    }
                }
            })
            check(matches == 1) { "expected exactly one new double[] in Ap2.fillArray, found $matches" }
        }

        // memory-issue.md #2 (11.38GB lambda captures + most of the 8.37GB memoizing-supplier
        // share): `updateY` did `this.biome = Suppliers.memoize(() -> biomeGetter.apply(pos.set(x,
        // y, z)))` on every call — a fresh lambda plus a fresh wrapper per Y-level. One
        // ResettableBiomeSupplier now lives for the Context's whole lifetime; updateY just resets
        // its captured coordinates. Full setBody rewrite, not a surgical expression replace: the
        // lambda's invokedynamic runs (and allocates) before Suppliers.memoize is even called, so
        // only removing the memoize() call can't stop it — the whole statement has to go.
        private fun patchSurfaceRulesContext(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            cc.addField(javassist.CtField.make(
                "private io.github.eath1283.worldgend.ResettableBiomeSupplier orionBiomeSupplier;", cc,
            ))
            val ctor = cc.declaredConstructors[0]
            ctor.insertAfter(
                """{
                    orionBiomeSupplier = new io.github.eath1283.worldgend.ResettableBiomeSupplier(biomeGetter, pos);
                }"""
            )
            // setBody() on the pre-existing method corrupts its LocalVariableTable on this
            // jar/JDK (confirmed via a ClassFormatError on the actual patched class, not
            // guessed); rename-and-add sidesteps it by leaving the original method's own
            // bytecode/attributes untouched, same trick patchAp2Compute already relies on.
            cc.getDeclaredMethod("updateY").name = "updateYOriginal"
            cc.addMethod(javassist.CtNewMethod.make(
                """protected void updateY(int argStoneDepthAbove, int argStoneDepthBelow, int argWaterHeight, int x, int y, int z) {
                    this.lastUpdateY += 1L;
                    this.orionBiomeSupplier.reset(x, y, z);
                    this.biome = this.orionBiomeSupplier;
                    this.blockY = y;
                    this.waterHeight = argWaterHeight;
                    this.stoneDepthBelow = argStoneDepthBelow;
                    this.stoneDepthAbove = argStoneDepthAbove;
                }""",
                cc,
            ))
            val bytes = cc.toBytecode()
            cc.detach()
            AllocationPatch.markTransformed(SURFACE_RULES_CONTEXT)
            return bytes
        }

        // memory-issue.md #3 (11.6GB, the hottest site — runs per block during surface
        // decoration): `for (SurfaceRule r : rules)` allocates a fresh List$Itr every tryApply
        // call. `rules` never changes after construction (it's a record component), so cache it
        // as an array once, lazily, and iterate by index. Plain field (not synchronized): worst
        // case under a first-access race is a few redundant, harmless toArray() calls, same
        // tradeoff as Ap2's own DFC cache fields make.
        private fun patchSequenceRule(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            cc.addField(javassist.CtField.make(
                "private net.minecraft.world.level.levelgen.SurfaceRules\$SurfaceRule[] orionRulesArray;", cc,
            ))
            // setBody() on a record's own method corrupts its LocalVariableTable on this
            // jar/JDK (ClassFormatError, confirmed not guessed); rename-and-add sidesteps it,
            // same trick patchAp2Compute and the Context.updateY patch above use.
            cc.getDeclaredMethod("tryApply").name = "tryApplyOriginal"
            cc.addMethod(javassist.CtNewMethod.make(
                """public net.minecraft.world.level.block.state.BlockState tryApply(int x, int y, int z) {
                    net.minecraft.world.level.levelgen.SurfaceRules${'$'}SurfaceRule[] arr = this.orionRulesArray;
                    if (arr == null) {
                        java.util.List list = this.rules;
                        arr = (net.minecraft.world.level.levelgen.SurfaceRules${'$'}SurfaceRule[])
                            list.toArray(new net.minecraft.world.level.levelgen.SurfaceRules${'$'}SurfaceRule[list.size()]);
                        this.orionRulesArray = arr;
                    }
                    for (int i = 0; i < arr.length; i++) {
                        net.minecraft.world.level.block.state.BlockState r = arr[i].tryApply(x, y, z);
                        if (r != null) return r;
                    }
                    return null;
                }""",
                cc,
            ))
            val bytes = cc.toBytecode()
            cc.detach()
            AllocationPatch.markTransformed(SURFACE_RULES_SEQUENCE_RULE)
            return bytes
        }

        // memory-issue.md #4 (1.0GB, smallest site): `computeSubstance` does
        // `new MutableDouble(NaN)` once per call purely as an output parameter to
        // calculatePressure, never recursively — a per-thread singleton (reset before use) is
        // safe here, unlike Ap2's scratch buffer.
        private fun patchAquiferMutableDouble(loader: ClassLoader?, original: ByteArray): ByteArray {
            val cc: CtClass = pool(loader).makeClass(java.io.ByteArrayInputStream(original))
            var matches = 0
            cc.getDeclaredMethod("computeSubstance").instrument(object : javassist.expr.ExprEditor() {
                override fun edit(e: javassist.expr.NewExpr) {
                    if (e.className == "org.apache.commons.lang3.mutable.MutableDouble") {
                        matches++
                        e.replace(
                            """{
                                ${'$'}_ = (org.apache.commons.lang3.mutable.MutableDouble)
                                    io.github.eath1283.worldgend.MutableDoubleScratch.reset(
                                        org.apache.commons.lang3.mutable.MutableDouble.class, ${'$'}1);
                            }"""
                        )
                    }
                }
            })
            check(matches == 1) { "expected exactly one new MutableDouble in computeSubstance, found $matches" }
            val bytes = cc.toBytecode()
            cc.detach()
            AllocationPatch.markTransformed(AQUIFER_NOISE_BASED)
            return bytes
        }

        // Rewrites every `.placeCount` access on `ownerFqcn` instances found in `cc`'s bytecode
        // to go through OrionPlaceCount, keyed by instance identity.
        private fun redirectPlaceCount(cc: CtClass, ownerFqcn: String) {
            var matches = 0
            cc.instrument(object : javassist.expr.ExprEditor() {
                override fun edit(f: javassist.expr.FieldAccess) {
                    if (f.fieldName != "placeCount") return
                    if (f.className != ownerFqcn) return
                    matches++
                    f.replace(
                        if (f.isReader) "{ \$_ = io.github.eath1283.worldgend.OrionPlaceCount.get(\$0); }"
                        else "{ io.github.eath1283.worldgend.OrionPlaceCount.set(\$0, \$1); }"
                    )
                }
            })
            debugLog("redirectPlaceCount: $ownerFqcn in ${cc.name}, $matches site(s)")
        }
    }
}

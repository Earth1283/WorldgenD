package io.github.eath1283.worldgend

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import javassist.ClassPool
import javassist.CtClass
import javassist.LoaderClassPath

// #47/#49: OrionV3's deadlock traces to BlockableEventLoop.isSameThread(), which gates
// executeBlocking()/submitAsync().join() on `Thread.currentThread() == getRunningThread()`
// — a fixed field (ServerChunkCache.mainThread etc.), not "is this thread already inside
// doRunTask() on this instance." orion3-poll's own nested calls (a task it is running via
// doRunTask() recursively needs the executor polled again) therefore take the
// async-enqueue-and-join branch instead of running inline: it blocks on a future only it
// can service, from inside the one call stack that could service it. #34's single-caller
// requirement on pendingGenerationTasks rules out just handing a second thread the
// permission to call pollTask() (tried and reverted in #48) — that's concurrent access to
// an unsynchronized ArrayList, a correctness bug, not a fix.
//
// The invariant this patch changes: isSameThread() additionally treats "the thread
// currently executing doRunTask() on THIS BlockableEventLoop instance" as same-thread.
// In vanilla (and v2.1/v2.2) exactly one physical thread ever calls doRunTask() at all,
// so this is a no-op there by construction — not just untested, structurally unreachable.
// It only changes behavior for a thread recursing into its own doRunTask() call, which
// only OrionV3's architecture can produce.
//
// Gated behind -Dorion.patchReentrancy=true so v2.1/v2.2 runs (no flag) load the
// completely unmodified class — that's the vanilla control path for correctness
// comparison, not a mode switch inside patched code.
// #53: memoizes SurfaceRules$BiomeConditionSource.biomeNameTest (a Predicate<ResourceKey<Biome>>
// derived once from the parsed surface-rule tree, config-invariant for the run) so the
// per-column Holder.is(biomeNameTest) call in the BiomeCondition$1 local class hits a cache
// instead of re-probing the backing Set every column. Patches the OUTER class's constructor,
// not the local class — the per-column call site is untouched, only the Predicate it calls
// into changes identity. Gated behind -Dorion.patchBiomeMemo=true, independent of
// orion.patchReentrancy. See scientific-findings-41-80.md #53, static-analysis-findings.md.
object OrionPatchAgent {
    private const val BLOCKABLE_EVENT_LOOP = "net.minecraft.util.thread.BlockableEventLoop"
    private const val SERVER_CHUNK_CACHE = "net.minecraft.server.level.ServerChunkCache"
    private const val SURFACE_RULES_BIOME_CONDITION = "net.minecraft.world.level.levelgen.SurfaceRules\$BiomeConditionSource"

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
        if (!patchReentrancy && !patchBiomeMemo) {
            System.err.println("[OrionPatchAgent] no patch flags set, not installing (vanilla control path)")
            return
        }
        if (patchReentrancy) System.err.println("[OrionPatchAgent] will patch $BLOCKABLE_EVENT_LOOP and $SERVER_CHUNK_CACHE on load")
        if (patchBiomeMemo) System.err.println("[OrionPatchAgent] will patch $SURFACE_RULES_BIOME_CONDITION on load")
        inst.addTransformer(Transformer(patchReentrancy, patchBiomeMemo))
    }

    private class Transformer(
        private val patchReentrancy: Boolean,
        private val patchBiomeMemo: Boolean,
    ) : ClassFileTransformer {
        override fun transform(
            loader: ClassLoader?,
            className: String,
            classBeingRedefined: Class<*>?,
            protectionDomain: ProtectionDomain?,
            classfileBuffer: ByteArray,
        ): ByteArray? {
            val dotted = className.replace('/', '.')
            val handled = (patchReentrancy && (dotted == BLOCKABLE_EVENT_LOOP || dotted == SERVER_CHUNK_CACHE)) ||
                (patchBiomeMemo && dotted == SURFACE_RULES_BIOME_CONDITION)
            if (!handled) return null
            debugLog("transform() invoked for $dotted")
            return try {
                val result = when (dotted) {
                    BLOCKABLE_EVENT_LOOP -> patchBlockableEventLoop(loader, classfileBuffer)
                    SERVER_CHUNK_CACHE -> patchServerChunkCache(loader, classfileBuffer)
                    else -> patchBiomeConditionSource(loader, classfileBuffer)
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
    }
}

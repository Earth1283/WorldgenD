package io.github.eath1283.worldgend

import javassist.ClassPool
import javassist.CtClass
import javassist.CtField
import javassist.LoaderClassPath
import javassist.expr.ExprEditor
import javassist.expr.FieldAccess
import javassist.expr.MethodCall
import java.util.concurrent.atomic.LongAdder

// Finding #76: LayerLightSectionStorage.swapSectionMap clones the whole section map on every
// light update that changed anything. Experimental, opt-in via -Dorion.lightSwap=count|skip.
//   count: vanilla behavior, but counts swaps and how many had a visible-map read since the last one
//   skip:  ceiling probe only, visible aliases updating (no snapshot); unsafe for concurrent readers, and leaving the map cache on crashed (#77)
object LightSwapPatch {
    val mode: String = System.getProperty("orion.lightSwap", "off")
    val enabled = mode != "off"
    private const val STORAGE = "net.minecraft.world.level.lighting.LayerLightSectionStorage"
    private const val SKY_STORAGE = "net.minecraft.world.level.lighting.SkyLightSectionStorage"
    val targets = setOf(STORAGE, SKY_STORAGE)

    fun report() = "mode=$mode ${LightSwapStats.report()}"

    fun transform(loader: ClassLoader?, original: ByteArray): ByteArray {
        val pool = ClassPool(false).apply {
            appendSystemPath()
            if (loader != null) appendClassPath(LoaderClassPath(loader))
        }
        val cc: CtClass = pool.makeClass(java.io.ByteArrayInputStream(original))
        if (cc.name == STORAGE) cc.addField(CtField.make("public volatile boolean orionRead;", cc))
        for (method in cc.declaredMethods) {
            val swap = cc.name == STORAGE && method.name == "swapSectionMap"
            method.instrument(object : ExprEditor() {
                override fun edit(f: FieldAccess) {
                    if (swap || !f.isReader || f.fieldName != "visibleSectionData") return
                    f.replace("{ \$0.orionRead = true; io.github.eath1283.worldgend.LightSwapStats.read(); \$_ = \$proceed(); }")
                }

                override fun edit(m: MethodCall) {
                    if (!swap || m.methodName != "copy") return
                    m.replace(
                        if (mode == "skip") "{ io.github.eath1283.worldgend.LightSwapStats.swap(false); \$_ = \$0; }"
                        else "{ \$_ = \$proceed(); io.github.eath1283.worldgend.LightSwapStats.swap(this.orionRead); this.orionRead = false; }"
                    )
                }
            })
        }
        val bytes = cc.toBytecode()
        cc.detach()
        return bytes
    }
}

object LightSwapStats {
    private val swaps = LongAdder()
    private val readEpochs = LongAdder()
    private val reads = LongAdder()

    @JvmStatic
    fun read() = reads.increment()

    @JvmStatic
    fun swap(readSinceLast: Boolean) {
        swaps.increment()
        if (readSinceLast) readEpochs.increment()
    }

    fun report() = "swaps=${swaps.sum()} epochsWithRead=${readEpochs.sum()} visibleReads=${reads.sum()}"
}

class LightSwapTransformer : java.lang.instrument.ClassFileTransformer {
    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: java.security.ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? {
        if (className.replace('/', '.') !in LightSwapPatch.targets) return null
        return try {
            LightSwapPatch.transform(loader, classfileBuffer)
        } catch (t: Throwable) {
            System.err.println("[OrionPatchAgent] light swap patch of $className FAILED: $t")
            null
        }
    }
}

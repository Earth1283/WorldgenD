package io.github.eath1283.worldgend

import javassist.ClassPool
import javassist.CtNewMethod
import javassist.LoaderClassPath
import java.util.concurrent.ConcurrentHashMap

object DensitySimdPatch {
    private const val PREFIX = "net.minecraft.world.level.levelgen.DensityFunctions\$"
    val targets = setOf("${PREFIX}Mapped", "${PREFIX}MulOrAdd", "${PREFIX}Clamp")
    private val transformed = ConcurrentHashMap.newKeySet<String>()

    fun transform(loader: ClassLoader?, original: ByteArray): ByteArray {
        val pool = ClassPool(false).apply {
            appendSystemPath()
            if (loader != null) appendClassPath(LoaderClassPath(loader))
        }
        val cc = pool.makeClass(original.inputStream())
        try {
            val operation = when (cc.name) {
                "${PREFIX}Mapped" -> "mapped(values, this.type.name())"
                "${PREFIX}MulOrAdd" -> "affine(values, this.specificType.name(), this.argument)"
                "${PREFIX}Clamp" -> "clamp(values, this.minValue, this.maxValue)"
                else -> error("Unsupported density transform: ${cc.name}")
            }
            check(cc.declaredMethods.none { it.name == "fillArray" }) {
                "${cc.name} no longer inherits fillArray; review the SIMD patch"
            }
            cc.addMethod(CtNewMethod.make("""
                public void fillArray(double[] values,
                    net.minecraft.world.level.levelgen.DensityFunction.ContextProvider provider) {
                    this.input.fillArray(values, provider);
                    io.github.eath1283.worldgend.DensityBatch.$operation;
                }
            """.trimIndent(), cc))
            val bytes = cc.toBytecode()
            transformed.add(cc.name)
            return bytes
        } finally {
            cc.detach()
        }
    }

    fun requireInstalled(loader: ClassLoader) {
        targets.forEach { Class.forName(it, false, loader) }
        check(transformed.containsAll(targets)) {
            "Orion v5.1 requires -javaagent:build/libs/orion-agent.jar; missing density patches: ${targets - transformed}"
        }
    }
}

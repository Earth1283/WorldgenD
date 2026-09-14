package io.github.eath1283.worldgend

import java.io.File
import java.net.URLClassLoader
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ImprovedNoisePatchTest {
    private val discovered = ServerRuntime.discover(File("servers"))
    private val classpath = (discovered.classpath + discovered.jar).map { it.toURI().toURL() }.toTypedArray()

    private fun patchedLoader(): URLClassLoader = object : URLClassLoader(classpath, ClassLoader.getSystemClassLoader()) {
        override fun findClass(name: String): Class<*> {
            if (name != ImprovedNoisePatch.target) return super.findClass(name)
            val original = findResource(name.replace('.', '/') + ".class").openStream().use { it.readBytes() }
            val transformed = ImprovedNoisePatch.transform(this, original)
            return defineClass(name, transformed, 0, transformed.size)
        }
    }

    private fun createNoise(loader: ClassLoader, seed: Long): Pair<Any, java.lang.reflect.Method> {
        val randomSource = loader.loadClass("net.minecraft.util.RandomSource")
        val random = randomSource.getMethod("create", Long::class.javaPrimitiveType).invoke(null, seed)
        val noiseClass = loader.loadClass(ImprovedNoisePatch.target)
        val noise = noiseClass.getConstructor(randomSource).newInstance(random)
        val sample = noiseClass.getMethod(
            "noise",
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,
        )
        return noise to sample
    }

    @Test
    fun optimizedKernelMatchesVanillaBitForBit() {
        discovered.newClassLoader().use { vanillaLoader ->
            patchedLoader().use { optimizedLoader ->
                val inputs = Random(0x5EED)
                val yScales = doubleArrayOf(0.0, 0.125, 0.25, 1.0, -0.5)
                val yMaxima = doubleArrayOf(-1.0, 0.0, 0.125, 0.5, 1.0)
                repeat(16) { seed ->
                    val (vanilla, vanillaSample) = createNoise(vanillaLoader, seed.toLong())
                    val (optimized, optimizedSample) = createNoise(optimizedLoader, seed.toLong())
                    repeat(4096) { sampleIndex ->
                        val x = inputs.nextDouble() * 60_000_000.0 - 30_000_000.0
                        val y = inputs.nextDouble() * 4096.0 - 2048.0
                        val z = inputs.nextDouble() * 60_000_000.0 - 30_000_000.0
                        val yScale = yScales[inputs.nextInt(yScales.size)]
                        val yMax = yMaxima[inputs.nextInt(yMaxima.size)]
                        val expected = vanillaSample.invoke(vanilla, x, y, z, yScale, yMax) as Double
                        val actual = optimizedSample.invoke(optimized, x, y, z, yScale, yMax) as Double
                        assertEquals(
                            expected.toBits(),
                            actual.toBits(),
                            "seed=$seed sample=$sampleIndex x=$x y=$y z=$z yScale=$yScale yMax=$yMax",
                        )
                    }
                }
            }
        }
    }
}

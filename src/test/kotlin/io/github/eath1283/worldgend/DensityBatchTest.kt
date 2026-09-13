package io.github.eath1283.worldgend

import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.Random
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class DensityBatchTest {
    private val discovered = ServerRuntime.discover(File("servers"))
    private val prefix = "net.minecraft.world.level.levelgen.DensityFunctions\$"

    private fun patchedLoader(): URLClassLoader = object : URLClassLoader(
        (discovered.classpath + discovered.jar).map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getSystemClassLoader(),
    ) {
        override fun findClass(name: String): Class<*> {
            if (name !in DensitySimdPatch.targets) return super.findClass(name)
            val connection = getResource(name.replace('.', '/') + ".class")!!.openConnection() as java.net.JarURLConnection
            val original = connection.inputStream.use { it.readBytes() }
            val bytes = DensitySimdPatch.transform(this, original)
            return defineClass(name, bytes, 0, bytes.size, java.security.CodeSource(connection.jarFileURL, connection.jarEntry.certificates))
        }
    }

    @Test
    fun patchedBatchesMatchVanillaTransformIncludingTailsAndSpecialValues() {
        patchedLoader().use { loader ->
            loader.loadClass("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null)
            loader.loadClass("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null)
            val density = loader.loadClass("net.minecraft.world.level.levelgen.DensityFunction")
            val providerClass = loader.loadClass("net.minecraft.world.level.levelgen.DensityFunction\$ContextProvider")
            val random = Random(69)
            val special = doubleArrayOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.MIN_VALUE, -Double.MIN_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE, -1.0, 1.0, Math.nextDown(1.0), Math.nextUp(-1.0))
            val source = DoubleArray(1025) { if (it < special.size) special[it] else Double.fromBits(random.nextLong()) }
            val provider = Proxy.newProxyInstance(loader, arrayOf(providerClass)) { _, method, _ ->
                error("Unexpected provider call: ${method.name}")
            }
            var fills = 0
            val input = Proxy.newProxyInstance(loader, arrayOf(density)) { _, method, args ->
                when (method.name) {
                    "fillArray" -> {
                        assertSame(provider, args!![1])
                        val destination = args[0] as DoubleArray
                        source.copyInto(destination, endIndex = destination.size)
                        fills++
                        null
                    }
                    "minValue" -> -Double.MAX_VALUE
                    "maxValue" -> Double.MAX_VALUE
                    else -> error("Unexpected input call: ${method.name}")
                }
            }
            val cases = mutableListOf<Any>()
            for (name in listOf("Mapped", "MulOrAdd")) {
                val type = loader.loadClass("$prefix$name")
                val constructor = type.declaredConstructors.single().apply { isAccessible = true }
                val enumType = loader.loadClass("$prefix$name\$Type")
                for (operation in enumType.enumConstants) {
                    if (name == "Mapped") cases.add(constructor.newInstance(operation, input, -1.0, 1.0))
                    else for (argument in listOf(-2.5, -0.0, 0.0, 0.25, Double.NaN, Double.POSITIVE_INFINITY)) {
                        cases.add(constructor.newInstance(operation, input, -1.0, 1.0, argument))
                    }
                }
            }
            val clamp = loader.loadClass("${prefix}Clamp").declaredConstructors.single().apply { isAccessible = true }
            for ((lower, upper) in listOf(-1.0 to 1.0, -0.0 to 0.0, 0.0 to -0.0, Double.NaN to 1.0, -1.0 to Double.NaN)) {
                cases.add(clamp.newInstance(input, lower, upper))
            }
            for (instance in cases) {
                val transform = instance.javaClass.getDeclaredMethod("transform", Double::class.javaPrimitiveType).apply { isAccessible = true }
                val fill = instance.javaClass.getDeclaredMethod("fillArray", DoubleArray::class.java, providerClass).apply { isAccessible = true }
                for (length in (0..17).toList() + listOf(49, 256, 257, 1025)) {
                    val before = fills
                    val actual = DoubleArray(length)
                    fill.invoke(instance, actual, provider)
                    assertEquals(before + 1, fills)
                    for (i in actual.indices) {
                        val expected = transform.invoke(instance, source[i]) as Double
                        assertEquals(expected.toBits(), actual[i].toBits(), "${instance.javaClass.simpleName} length=$length index=$i input=${source[i]}")
                    }
                }
            }
        }
    }

    @Test
    fun scalarAndVectorAgreeAcrossConcurrentIndependentBuffers() {
        val workers = Executors.newFixedThreadPool(7)
        try {
            val futures = (0..27).map { seed -> workers.submit {
                val random = Random(seed.toLong())
                for (operation in DensityBatch.Operation.entries) {
                    val expected = DoubleArray(257) { random.nextDouble() * 4.0 - 2.0 }
                    val actual = expected.copyOf()
                    DensityBatch.scalar(expected, operation, -0.25, 1.0, 0)
                    DensityVector.apply(actual, operation, -0.25, 1.0)
                    assertEquals(expected.map { it.toBits() }, actual.map { it.toBits() })
                }
            } }
            futures.forEach { it.get() }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun unsupportedOperationsFailInsteadOfChangingMeaning() {
        assertFailsWith<IllegalArgumentException> { DensityBatch.mapped(doubleArrayOf(1.0), "NEW_OPERATION") }
    }
}

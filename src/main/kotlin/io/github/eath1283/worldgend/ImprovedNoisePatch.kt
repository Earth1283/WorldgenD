package io.github.eath1283.worldgend

import javassist.ClassPool
import javassist.CtClass
import javassist.CtField
import javassist.LoaderClassPath
import java.util.concurrent.atomic.AtomicBoolean

object ImprovedNoisePatch {
    const val target = "net.minecraft.world.level.levelgen.synth.ImprovedNoise"
    private val transformed = AtomicBoolean()

    fun transform(loader: ClassLoader?, original: ByteArray): ByteArray {
        val pool = ClassPool(false).apply {
            appendSystemPath()
            if (loader != null) appendClassPath(LoaderClassPath(loader))
        }
        val noise = pool.makeClass(original.inputStream())
        try {
            noise.addField(
                CtField.make(
                    """private static final double[] ORION_GRADIENTS = new double[] {
                        1, 1, 0, 0, -1, 1, 0, 0, 1, -1, 0, 0, -1, -1, 0, 0,
                        1, 0, 1, 0, -1, 0, 1, 0, 1, 0, -1, 0, -1, 0, -1, 0,
                        0, 1, 1, 0, 0, -1, 1, 0, 0, 1, -1, 0, 0, -1, -1, 0,
                        1, 1, 0, 0, 0, -1, 1, 0, -1, 1, 0, 0, 0, -1, -1, 0
                    };""".trimIndent(),
                    noise,
                )
            )
            val parameterTypes = arrayOf(
                CtClass.intType,
                CtClass.intType,
                CtClass.intType,
                CtClass.doubleType,
                CtClass.doubleType,
                CtClass.doubleType,
                CtClass.doubleType,
            )
            val sampleAndLerp = noise.getDeclaredMethod("sampleAndLerp", parameterTypes)
            sampleAndLerp.setBody(
                """{
                    int x0 = ${'$'}1 & 255;
                    int x1 = (${'$'}1 + 1) & 255;
                    int permutationX0 = this.p[x0] & 255;
                    int permutationX1 = this.p[x1] & 255;
                    int permutationX0Y0 = this.p[(permutationX0 + ${'$'}2) & 255] & 255;
                    int permutationX0Y1 = this.p[(permutationX0 + ${'$'}2 + 1) & 255] & 255;
                    int permutationX1Y0 = this.p[(permutationX1 + ${'$'}2) & 255] & 255;
                    int permutationX1Y1 = this.p[(permutationX1 + ${'$'}2 + 1) & 255] & 255;
                    int gradient000 = (this.p[(permutationX0Y0 + ${'$'}3) & 255] & 15) << 2;
                    int gradient100 = (this.p[(permutationX1Y0 + ${'$'}3) & 255] & 15) << 2;
                    int gradient010 = (this.p[(permutationX0Y1 + ${'$'}3) & 255] & 15) << 2;
                    int gradient110 = (this.p[(permutationX1Y1 + ${'$'}3) & 255] & 15) << 2;
                    int gradient001 = (this.p[(permutationX0Y0 + ${'$'}3 + 1) & 255] & 15) << 2;
                    int gradient101 = (this.p[(permutationX1Y0 + ${'$'}3 + 1) & 255] & 15) << 2;
                    int gradient011 = (this.p[(permutationX0Y1 + ${'$'}3 + 1) & 255] & 15) << 2;
                    int gradient111 = (this.p[(permutationX1Y1 + ${'$'}3 + 1) & 255] & 15) << 2;
                    double x1Offset = ${'$'}4 - 1.0;
                    double y1Offset = ${'$'}5 - 1.0;
                    double z1Offset = ${'$'}6 - 1.0;
                    double value000 = ORION_GRADIENTS[gradient000] * ${'$'}4 + ORION_GRADIENTS[gradient000 + 1] * ${'$'}5 + ORION_GRADIENTS[gradient000 + 2] * ${'$'}6;
                    double value100 = ORION_GRADIENTS[gradient100] * x1Offset + ORION_GRADIENTS[gradient100 + 1] * ${'$'}5 + ORION_GRADIENTS[gradient100 + 2] * ${'$'}6;
                    double value010 = ORION_GRADIENTS[gradient010] * ${'$'}4 + ORION_GRADIENTS[gradient010 + 1] * y1Offset + ORION_GRADIENTS[gradient010 + 2] * ${'$'}6;
                    double value110 = ORION_GRADIENTS[gradient110] * x1Offset + ORION_GRADIENTS[gradient110 + 1] * y1Offset + ORION_GRADIENTS[gradient110 + 2] * ${'$'}6;
                    double value001 = ORION_GRADIENTS[gradient001] * ${'$'}4 + ORION_GRADIENTS[gradient001 + 1] * ${'$'}5 + ORION_GRADIENTS[gradient001 + 2] * z1Offset;
                    double value101 = ORION_GRADIENTS[gradient101] * x1Offset + ORION_GRADIENTS[gradient101 + 1] * ${'$'}5 + ORION_GRADIENTS[gradient101 + 2] * z1Offset;
                    double value011 = ORION_GRADIENTS[gradient011] * ${'$'}4 + ORION_GRADIENTS[gradient011 + 1] * y1Offset + ORION_GRADIENTS[gradient011 + 2] * z1Offset;
                    double value111 = ORION_GRADIENTS[gradient111] * x1Offset + ORION_GRADIENTS[gradient111 + 1] * y1Offset + ORION_GRADIENTS[gradient111 + 2] * z1Offset;
                    double fadeX = ${'$'}4 * ${'$'}4 * ${'$'}4 * (${'$'}4 * (${'$'}4 * 6.0 - 15.0) + 10.0);
                    double fadeY = ${'$'}7 * ${'$'}7 * ${'$'}7 * (${'$'}7 * (${'$'}7 * 6.0 - 15.0) + 10.0);
                    double fadeZ = ${'$'}6 * ${'$'}6 * ${'$'}6 * (${'$'}6 * (${'$'}6 * 6.0 - 15.0) + 10.0);
                    double x00 = value000 + fadeX * (value100 - value000);
                    double x10 = value010 + fadeX * (value110 - value010);
                    double x01 = value001 + fadeX * (value101 - value001);
                    double x11 = value011 + fadeX * (value111 - value011);
                    double xy0 = x00 + fadeY * (x10 - x00);
                    double xy1 = x01 + fadeY * (x11 - x01);
                    return xy0 + fadeZ * (xy1 - xy0);
                }""".trimIndent()
            )
            val bytes = noise.toBytecode()
            transformed.set(true)
            return bytes
        } finally {
            noise.detach()
        }
    }

    fun requireInstalled(loader: ClassLoader) {
        Class.forName(target, false, loader)
        check(transformed.get()) {
            "Orion v5.2 requires -javaagent:build/libs/orion-agent.jar and the optimized ImprovedNoise patch"
        }
    }
}

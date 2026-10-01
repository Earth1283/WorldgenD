package io.github.eath1283.worldgend

import javassist.ClassPool
import javassist.CtClass
import javassist.CtField
import javassist.CtNewMethod
import javassist.LoaderClassPath
import javassist.Modifier
import java.util.concurrent.ConcurrentHashMap

// Orion v5.7: cell-batched density fill, lazy interpolators, shared preliminary surface levels.
object DensityCellPatch {
    private const val LEVELGEN = "net.minecraft.world.level.levelgen"
    private const val DF = "$LEVELGEN.DensityFunction"
    private const val DFS = "$LEVELGEN.DensityFunctions\$"
    private const val NOISE_CHUNK = "$LEVELGEN.NoiseChunk"
    private const val INTERPOLATOR = "$NOISE_CHUNK\$NoiseInterpolator"
    private const val HELPER = "io.github.eath1283.worldgend.DensityCell"
    private const val SURFACE = "io.github.eath1283.worldgend.DensitySurfaceCache"
    private const val NODE = "io.github.eath1283.worldgend.DensityCellNode"
    private const val PROVIDER = "$DF\$ContextProvider"
    const val AP2 = "${DFS}Ap2"
    private val simdTargets = setOf("${DFS}Mapped", "${DFS}MulOrAdd", "${DFS}Clamp")
    val targets = setOf(NOISE_CHUNK, INTERPOLATOR, AP2, "${DFS}RangeChoice", "${DFS}Constant") + simdTargets
    private val sharedFields = listOf(
        "interpolating", "fillingCell", "cellWidth", "cellHeight", "inCellX", "inCellY", "inCellZ",
    )
    private val transformed = ConcurrentHashMap.newKeySet<String>()
    private val batch = System.getProperty("orion.noiseCell.batch", "true").toBoolean()
    private val lazy = System.getProperty("orion.noiseCell.lazy", "true").toBoolean()
    private val surfaceCache = System.getProperty("orion.noiseCell.surfaceCache", "true").toBoolean()
    // false: Ap2 ADD and Mapped/MulOrAdd/Clamp keep orion5.6's Ap2Scratch and DensitySimdPatch bodies
    val glue = System.getProperty("orion.noiseCell.glue", "true").toBoolean()

    fun report() = "batch=$batch lazy=$lazy surfaceCache=$surfaceCache glue=$glue ${DensitySurfaceCache.report()}"

    fun transform(loader: ClassLoader?, original: ByteArray): ByteArray {
        val pool = ClassPool(false).apply {
            appendSystemPath()
            if (loader != null) appendClassPath(LoaderClassPath(loader))
        }
        val cc = pool.makeClass(original.inputStream())
        try {
            when (cc.name) {
                NOISE_CHUNK -> patchNoiseChunk(cc)
                INTERPOLATOR -> patchInterpolator(pool, cc)
                AP2 -> patchAp2(cc)
                "${DFS}RangeChoice" -> patchRangeChoice(cc)
                "${DFS}Constant" -> addNode(cc, "return true;")
                else -> patchTransformer(cc)
            }
            val bytes = cc.toBytecode()
            transformed.add(cc.name)
            return bytes
        } finally {
            cc.detach()
        }
    }

    private fun addNode(cc: CtClass, body: String) {
        cc.addInterface(cc.classPool.get(NODE))
        cc.addMethod(CtNewMethod.make("public boolean orionCellPure() { $body }", cc))
    }

    private fun addCachedNode(cc: CtClass, expression: String) {
        cc.addField(CtField.make("private int orionPureState;", cc))
        addNode(cc, """
            int state = this.orionPureState;
            if (state == 0) {
                state = ($expression) ? 1 : 2;
                this.orionPureState = state;
            }
            return state == 1;
        """.trimIndent())
    }

    private fun patchNoiseChunk(cc: CtClass) {
        sharedFields.forEach { name ->
            val field = cc.getDeclaredField(name)
            field.modifiers = (field.modifiers and Modifier.PRIVATE.inv()) or Modifier.PUBLIC
        }
        cc.addField(CtField.make("public double orionFactorY;", cc))
        cc.addField(CtField.make("public double orionFactorX;", cc))
        cc.addField(CtField.make("public double orionFactorZ;", cc))
        cc.addField(CtField.make("public $SURFACE orionSurfaceCache;", cc))
        if (lazy) {
            cc.getDeclaredMethod("updateForY").setBody("{ this.inCellY = $1 - this.cellStartBlockY; this.orionFactorY = $2; }")
            cc.getDeclaredMethod("updateForX").setBody("{ this.inCellX = $1 - this.cellStartBlockX; this.orionFactorX = $2; }")
            cc.getDeclaredMethod("updateForZ").setBody(
                "{ this.inCellZ = $1 - this.cellStartBlockZ; this.interpolationCounter++; this.orionFactorZ = $2; }"
            )
        }
        if (surfaceCache) {
            val constructor = cc.declaredConstructors.single()
            constructor.insertBeforeBody(
                "{ if ($9 == $LEVELGEN.blending.Blender.empty()) this.orionSurfaceCache = $SURFACE.forOwner($2); }"
            )
            cc.getDeclaredMethod("computePreliminarySurfaceLevel").name = "orionComputePreliminarySurfaceLevel"
            cc.addMethod(CtNewMethod.make("""
                private int computePreliminarySurfaceLevel(long key) {
                    $SURFACE cache = this.orionSurfaceCache;
                    if (cache == null) return this.orionComputePreliminarySurfaceLevel(key);
                    int x = net.minecraft.server.level.ColumnPos.getX(key);
                    int z = net.minecraft.server.level.ColumnPos.getZ(key);
                    int level = cache.get(x, z);
                    if (level == $SURFACE.MISS) {
                        level = this.orionComputePreliminarySurfaceLevel(key);
                        cache.put(x, z, level);
                    }
                    return level;
                }
            """.trimIndent(), cc))
        }
    }

    private fun patchInterpolator(pool: ClassPool, cc: CtClass) {
        val outer = pool.get(NOISE_CHUNK)
        sharedFields.forEach { name ->
            val field = outer.getDeclaredField(name)
            field.modifiers = (field.modifiers and Modifier.PRIVATE.inv()) or Modifier.PUBLIC
        }
        if (outer.declaredFields.none { it.name == "orionFactorY" }) {
            listOf("Y", "X", "Z").forEach { outer.addField(CtField.make("public double orionFactor$it;", outer)) }
        }
        val corners = "this.noise000, this.noise100, this.noise010, this.noise110, this.noise001, this.noise101, this.noise011, this.noise111"
        addNode(cc, "return true;")
        if (batch) {
            cc.getDeclaredMethod("fillArray").setBody("""{
                $NOISE_CHUNK chunk = this.this$0;
                if (!chunk.fillingCell) {
                    this.wrapped().fillArray($1, $2);
                } else if ($2 == chunk && chunk.interpolating && $1.length == chunk.cellWidth * chunk.cellWidth * chunk.cellHeight) {
                    $HELPER.lerp3Fill($1, chunk.cellWidth, chunk.cellHeight, $corners);
                } else {
                    $2.fillAllDirectly($1, this);
                }
            }""".trimIndent())
        }
        if (lazy) {
            cc.getDeclaredMethod("compute").setBody("""{
                $NOISE_CHUNK chunk = this.this$0;
                if ($1 != chunk) return this.noiseFiller.compute($1);
                if (!chunk.interpolating) throw new IllegalStateException("Trying to sample interpolator outside the interpolation loop");
                if (chunk.fillingCell) {
                    return net.minecraft.util.Mth.lerp3(
                        (double) chunk.inCellX / (double) chunk.cellWidth,
                        (double) chunk.inCellY / (double) chunk.cellHeight,
                        (double) chunk.inCellZ / (double) chunk.cellWidth,
                        $corners);
                }
                return $HELPER.lerpYXZ(chunk.orionFactorY, chunk.orionFactorX, chunk.orionFactorZ, $corners);
            }""".trimIndent())
        }
    }

    private fun patchAp2(cc: CtClass) {
        addCachedNode(cc, "$HELPER.pure(this.argument1) && $HELPER.pure(this.argument2)")
        cc.addField(CtField.make("private int orionBatchState;", cc))
        val batched = if (!batch) "" else """
            int state = this.orionBatchState;
            if (state == 0) {
                state = $HELPER.pure(this.argument2) ? 1 : 2;
                this.orionBatchState = state;
            }
            if (${if (glue) "" else "kind != 0 && "}state == 1 && provider instanceof $NOISE_CHUNK) {
                this.argument1.fillArray(output, provider);
                if (kind == 1) {
                    if ($HELPER.anyNonZero(output)) {
                        $HELPER scratch = $HELPER.get();
                        double[] other = scratch.push(output.length);
                        this.argument2.fillArray(other, provider);
                        $HELPER.mulSkipZero(output, other);
                        scratch.pop();
                    } else {
                        $HELPER.zeroFill(output);
                    }
                } else if (kind == 2) {
                    double bound = this.argument2.minValue();
                    if ($HELPER.anyNotBelow(output, bound)) {
                        $HELPER scratch = $HELPER.get();
                        double[] other = scratch.push(output.length);
                        this.argument2.fillArray(other, provider);
                        $HELPER.minSelect(output, other, bound);
                        scratch.pop();
                    }
                } else {
                    double bound = this.argument2.maxValue();
                    if ($HELPER.anyNotAbove(output, bound)) {
                        $HELPER scratch = $HELPER.get();
                        double[] other = scratch.push(output.length);
                        this.argument2.fillArray(other, provider);
                        $HELPER.maxSelect(output, other, bound);
                        scratch.pop();
                    }
                }
                return;
            }
        """
        val add = if (!glue) "" else """
            if (kind == 0) {
                this.argument1.fillArray(output, provider);
                $HELPER scratch = $HELPER.get();
                double[] other = scratch.push(output.length);
                this.argument2.fillArray(other, provider);
                $HELPER.add(output, other);
                scratch.pop();
                return;
            }
        """
        cc.getDeclaredMethod("fillArray").name = "orionFillArrayVanilla"
        cc.addMethod(CtNewMethod.make("""
            public void fillArray(double[] output, $PROVIDER provider) {
                int kind = this.type.ordinal();
                $add
                $batched
                this.orionFillArrayVanilla(output, provider);
            }
        """.trimIndent(), cc))
        AllocationPatch.markTransformed(AP2)
    }

    private fun patchRangeChoice(cc: CtClass) {
        addCachedNode(cc, "$HELPER.pure(this.input) && $HELPER.pure(this.whenInRange) && $HELPER.pure(this.whenOutOfRange)")
        if (!batch) return
        cc.addField(CtField.make("private int orionBatchState;", cc))
        cc.getDeclaredMethod("fillArray").insertBefore("""{
            if ($2 instanceof $NOISE_CHUNK) {
                int state = this.orionBatchState;
                if (state == 0) {
                    state = ($HELPER.pure(this.whenInRange) && $HELPER.pure(this.whenOutOfRange)) ? 1 : 2;
                    this.orionBatchState = state;
                }
                if (state == 1) {
                    this.input.fillArray($1, $2);
                    int range = $HELPER.rangeClass($1, this.minInclusive, this.maxExclusive);
                    if (range == 1) {
                        this.whenInRange.fillArray($1, $2);
                    } else if (range == 2) {
                        this.whenOutOfRange.fillArray($1, $2);
                    } else {
                        $HELPER scratch = $HELPER.get();
                        double[] input = scratch.push($1.length);
                        double[] inRange = scratch.push($1.length);
                        System.arraycopy($1, 0, input, 0, $1.length);
                        this.whenInRange.fillArray(inRange, $2);
                        this.whenOutOfRange.fillArray($1, $2);
                        $HELPER.rangeSelect($1, input, inRange, this.minInclusive, this.maxExclusive);
                        scratch.pop();
                        scratch.pop();
                    }
                    return;
                }
            }
        }""".trimIndent())
    }

    private fun patchTransformer(cc: CtClass) {
        val operation = when (cc.name) {
            "${DFS}Mapped" -> "$HELPER.mapped(values, this.type.ordinal())"
            "${DFS}MulOrAdd" -> "$HELPER.affine(values, this.specificType.ordinal(), this.argument)"
            "${DFS}Clamp" -> "io.github.eath1283.worldgend.DensityBatch.clamp(values, this.minValue, this.maxValue)"
            else -> error("Unsupported density transform: ${cc.name}")
        }
        if (glue) {
            check(cc.declaredMethods.none { it.name == "fillArray" }) { "${cc.name} no longer inherits fillArray" }
            cc.addMethod(CtNewMethod.make("""
                public void fillArray(double[] values, $PROVIDER provider) {
                    this.input.fillArray(values, provider);
                    $operation;
                }
            """.trimIndent(), cc))
        }
        addCachedNode(cc, "$HELPER.pure(this.input)")
    }

    fun requireInstalled(loader: ClassLoader) {
        targets.forEach { Class.forName(it, false, loader) }
        check(transformed.containsAll(targets)) {
            "Orion v5.7 requires -javaagent:build/libs/orion-agent.jar; missing noise-cell patches: ${targets - transformed}"
        }
    }
}

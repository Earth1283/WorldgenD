package io.github.eath1283.worldgend

import javassist.ClassPool
import javassist.CtClass
import javassist.CtField
import javassist.CtNewMethod
import javassist.LoaderClassPath
import javassist.Modifier
import javassist.expr.ExprEditor
import javassist.expr.NewExpr
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

// Output-preserving patches for the non-noise chunk statuses (#75, shipped in v5.9 by #83). Each is
// on by default for orion5.9 and individually switchable with -Dorion.stage.<name>=true|false.
object StagePatch {
    private const val LEVELGEN = "net.minecraft.world.level.levelgen"
    private const val NOISE_CHUNK = "$LEVELGEN.NoiseChunk"
    private const val FLAT_CACHE = "$LEVELGEN.NoiseChunk\$FlatCache"
    private const val FLUID_STATE = "net.minecraft.world.level.material.FluidState"
    private const val BIOME_MANAGER = "net.minecraft.world.level.biome.BiomeManager"
    private const val WORLD_GEN_REGION = "net.minecraft.server.level.WorldGenRegion"
    private const val SURFACE_SYSTEM = "$LEVELGEN.SurfaceSystem"
    private const val SURFACE_COLUMN = "$LEVELGEN.SurfaceSystem\$1"
    private const val SURFACE_CONTEXT = "$LEVELGEN.SurfaceRules\$Context"
    private const val BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState"

    private val transformed = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    @JvmField val biomeFlatWraps = LongAdder()
    @JvmField val surfaceShortcuts = LongAdder()
    @JvmField val surfaceMismatches = LongAdder()
    private val surfaceVerify = System.getProperty("orion.stage.surfaceVerify") == "true"

    private fun enabled(name: String): Boolean =
        System.getProperty("orion.stage.$name")?.toBoolean() ?: (System.getProperty("scheduler") == "orion5.9")

    private val patches: Map<String, List<Pair<String, (CtClass) -> Unit>>> = mapOf(
        NOISE_CHUNK to listOf("biomeFlat" to ::patchClimateSampler),
        FLAT_CACHE to listOf("biomeFlat" to ::openFlatCache),
        FLUID_STATE to listOf("fluidEmpty" to ::patchFluidState),
        BIOME_MANAGER to listOf("biomeUniform" to ::patchBiomeManager),
        SURFACE_SYSTEM to listOf("surfaceColumn" to ::patchSurfaceSystem),
        SURFACE_COLUMN to listOf("surfaceColumn" to ::patchSurfaceColumn),
        SURFACE_CONTEXT to listOf("surfaceColumn" to ::patchSurfaceContext),
    )

    fun install(inst: Instrumentation) {
        val active = patches.mapValues { (_, list) -> list.filter { enabled(it.first) } }.filterValues { it.isNotEmpty() }
        if (active.isEmpty()) return
        System.err.println("[StagePatch] active: ${active.values.flatten().map { it.first }.distinct()}")
        inst.addTransformer(object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?, className: String, classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain?, classfileBuffer: ByteArray,
            ): ByteArray? {
                val dotted = className.replace('/', '.')
                val list = active[dotted] ?: return null
                return try {
                    val pool = ClassPool(false)
                    pool.appendSystemPath()
                    if (loader != null) pool.appendClassPath(LoaderClassPath(loader))
                    val cc = pool.makeClass(java.io.ByteArrayInputStream(classfileBuffer))
                    list.forEach { it.second(cc) }
                    val bytes = cc.toBytecode()
                    cc.detach()
                    list.forEach { transformed.add("${it.first}:${dotted.substringAfterLast('.')}") }
                    bytes
                } catch (t: Throwable) {
                    failed.add("$dotted: $t")
                    System.err.println("[StagePatch] $dotted FAILED: ${t.stackTraceToString()}")
                    null
                }
            }
        })
    }

    @JvmStatic
    fun report(): String =
        "transformed=${transformed.sorted()} failed=${failed.sorted()} biomeFlatWraps=${biomeFlatWraps.sum()} " +
        "surfaceVerify=$surfaceVerify surfaceShortcuts=${surfaceShortcuts.sum()} surfaceMismatches=${surfaceMismatches.sum()}"

    private fun openFlatCache(cc: CtClass) {
        cc.declaredConstructors.forEach { it.modifiers = Modifier.clear(it.modifiers, Modifier.PRIVATE) }
    }

    // Temperature and vegetation are shiftedNoise2d (yScale 0, zero shiftY) but, unlike the other
    // climate inputs, not flat-cached, so the biome fill recomputes them at every quart Y.
    private fun patchClimateSampler(cc: CtClass) {
        val df = "$LEVELGEN.DensityFunction"
        val shifted = "$LEVELGEN.DensityFunctions\$ShiftedNoise"
        val constant = "$LEVELGEN.DensityFunctions\$Constant"
        // The pool's copy only has to compile; the loaded class is opened by openFlatCache.
        openFlatCache(cc.classPool.get(FLAT_CACHE))
        cc.addMethod(CtNewMethod.make(
            """
            private static boolean orion${'$'}yFree($df f) {
                return f instanceof $FLAT_CACHE || f instanceof $constant;
            }
            """, cc,
        ))
        cc.addMethod(CtNewMethod.make(
            """
            private $df orion${'$'}flat($df f) {
                if (f instanceof $shifted) {
                    $shifted s = ($shifted) f;
                    $df y = s.shiftY();
                    if (s.yScale() == 0.0 && y instanceof $constant
                        && Double.doubleToRawLongBits((($constant) y).value()) == 0L
                        && orion${'$'}yFree(s.shiftX()) && orion${'$'}yFree(s.shiftZ())) {
                        io.github.eath1283.worldgend.StagePatch.biomeFlatWraps.increment();
                        return new $FLAT_CACHE(this, f, true);
                    }
                }
                return f;
            }
            """, cc,
        ))
        var hits = 0
        cc.getDeclaredMethod("cachedClimateSampler").instrument(object : ExprEditor() {
            override fun edit(e: NewExpr) {
                if (e.className != "net.minecraft.world.level.biome.Climate\$Sampler") return
                e.replace("{ ${'$'}_ = ${'$'}proceed(this.orion${'$'}flat(${'$'}1), this.orion${'$'}flat(${'$'}2), ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7); }")
                hits++
            }
        })
        check(hits == 1) { "cachedClimateSampler: expected one Climate.Sampler allocation, saw $hits" }
    }

    private fun patchFluidState(cc: CtClass) {
        cc.addField(CtField.make("private boolean orion\$empty;", cc))
        cc.declaredConstructors.forEach { it.insertAfter("{ this.orion\$empty = ${'$'}1.isEmpty(); }") }
        cc.getDeclaredMethod("isEmpty").setBody("{ return this.orion\$empty; }")
    }

    // getBiome picks the nearest of 8 quart corners by a seeded fiddle. When all 8 corners hold
    // the same biome the fiddle cannot change the answer, so one uniformity check per quart cell
    // replaces it for every block in the cell. Only for WorldGenRegion sources (one thread, one
    // step, biomes frozen), and only for cells whose corners are within 1 chunk of the center.
    private fun patchBiomeManager(cc: CtClass) {
        val holder = "net.minecraft.core.Holder"
        cc.addField(CtField.make("private long[] orion\$keys;", cc))
        cc.addField(CtField.make("private Object[] orion\$vals;", cc))
        cc.addField(CtField.make("private boolean orion\$region;", cc))
        cc.addField(CtField.make("private int orion\$cx;", cc))
        cc.addField(CtField.make("private int orion\$cz;", cc))
        cc.declaredConstructors.forEach {
            it.insertAfter(
                """{
                    if (${'$'}1 instanceof $WORLD_GEN_REGION) {
                        net.minecraft.world.level.ChunkPos c = (($WORLD_GEN_REGION) ${'$'}1).getCenter();
                        this.orion${'$'}cx = c.x();
                        this.orion${'$'}cz = c.z();
                        this.orion${'$'}region = true;
                    }
                }"""
            )
        }
        cc.addMethod(CtNewMethod.make(
            """
            private Object orion${'$'}uniform(int px, int py, int pz) {
                int x0 = (px >> 2) - this.orion${'$'}cx;
                int x1 = ((px + 1) >> 2) - this.orion${'$'}cx;
                int z0 = (pz >> 2) - this.orion${'$'}cz;
                int z1 = ((pz + 1) >> 2) - this.orion${'$'}cz;
                if (x0 < -1 || x1 > 1 || z0 < -1 || z1 > 1) return this;
                try {
                    $holder first = this.noiseBiomeSource.getNoiseBiome(px, py, pz);
                    for (int i = 1; i < 8; i++) {
                        if (this.noiseBiomeSource.getNoiseBiome(px + ((i >> 2) & 1), py + ((i >> 1) & 1), pz + (i & 1)) != first) return this;
                    }
                    return first;
                } catch (RuntimeException e) {
                    return this;
                }
            }
            """, cc,
        ))
        cc.getDeclaredMethod("getBiome").insertBefore(
            """{
                if (this.orion${'$'}region) {
                    if (this.orion${'$'}vals == null) {
                        this.orion${'$'}keys = new long[256];
                        this.orion${'$'}vals = new Object[256];
                    }
                    int px = (${'$'}1.getX() - 2) >> 2;
                    int py = (${'$'}1.getY() - 2) >> 2;
                    int pz = (${'$'}1.getZ() - 2) >> 2;
                    long key = (((long) px & 0xFFFFFFL) << 40) | (((long) pz & 0xFFFFFFL) << 16) | ((long) py & 0xFFFFL);
                    int idx = (px * -1640531535 + py * -2048144789 + pz * -1028477387) >>> 24;
                    Object v = this.orion${'$'}vals[idx];
                    if (v == null || this.orion${'$'}keys[idx] != key) {
                        v = this.orion${'$'}uniform(px, py, pz);
                        this.orion${'$'}keys[idx] = key;
                        this.orion${'$'}vals[idx] = v;
                    }
                    if (v != this) return ($holder) v;
                }
            }"""
        )
    }

    // buildSurface reads every block of a column twice through ProtoChunk.getBlockState; the
    // column object reads sections directly instead. No write happens above a pending read.
    private fun patchSurfaceColumn(cc: CtClass) {
        val section = "net.minecraft.world.level.chunk.LevelChunkSection"
        cc.addField(CtField.make("private $section[] orion\$sections;", cc))
        cc.addField(CtField.make("private int orion\$minSection;", cc))
        cc.addMethod(CtNewMethod.make(
            """
            $BLOCK_STATE orion${'$'}get(int y) {
                $section[] s = this.orion${'$'}sections;
                if (s == null) {
                    if (this.val${'$'}protoChunk.getClass() != net.minecraft.world.level.chunk.ProtoChunk.class) return this.getBlock(y);
                    this.orion${'$'}minSection = this.val${'$'}protoChunk.getMinY() >> 4;
                    s = this.val${'$'}protoChunk.getSections();
                    this.orion${'$'}sections = s;
                }
                int i = (y >> 4) - this.orion${'$'}minSection;
                if (i < 0 || i >= s.length) return this.getBlock(y);
                $section sec = s[i];
                if (sec.hasOnlyAir()) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                return sec.getBlockState(this.val${'$'}columnPos.getX() & 15, y & 15, this.val${'$'}columnPos.getZ() & 15);
            }
            """, cc,
        ))
        val heightmap = "net.minecraft.world.level.levelgen.Heightmap"
        val status = "net.minecraft.world.level.chunk.status.ChunkStatus"
        cc.addField(CtField.make("private $heightmap[] orion\$heightmaps;", cc))
        cc.addField(CtField.make("private int orion\$setMode;", cc))
        // Same steps as ProtoChunk.setBlockState before INITIALIZE_LIGHT with every
        // heightmapsAfter map already primed; any other shape takes the vanilla call.
        cc.addMethod(CtNewMethod.make(
            """
            private void orion${'$'}initSet() {
                net.minecraft.world.level.chunk.ChunkAccess chunk = this.val${'$'}protoChunk;
                this.orion${'$'}setMode = 2;
                if (chunk.getClass() != net.minecraft.world.level.chunk.ProtoChunk.class) return;
                if (chunk.getHeightAccessorForGeneration() != chunk) return;
                $status st = chunk.getPersistedStatus();
                if (st.isOrAfter($status.INITIALIZE_LIGHT)) return;
                java.util.EnumSet after = st.heightmapsAfter();
                $heightmap[] maps = new $heightmap[after.size()];
                int n = 0;
                for (java.util.Iterator it = after.iterator(); it.hasNext(); ) {
                    $heightmap${'$'}Types type = ($heightmap${'$'}Types) it.next();
                    if (!chunk.hasPrimedHeightmap(type)) return;
                    maps[n++] = chunk.getOrCreateHeightmapUnprimed(type);
                }
                this.orion${'$'}heightmaps = maps;
                this.orion${'$'}minSection = chunk.getMinY() >> 4;
                this.orion${'$'}sections = chunk.getSections();
                this.orion${'$'}setMode = 1;
            }
            """, cc,
        ))
        cc.addMethod(CtNewMethod.make(
            """
            void orion${'$'}set(int y, $BLOCK_STATE state) {
                if (this.orion${'$'}setMode == 0) this.orion${'$'}initSet();
                int i = (y >> 4) - this.orion${'$'}minSection;
                if (this.orion${'$'}setMode != 1 || i < 0 || i >= this.orion${'$'}sections.length) {
                    this.setBlock(y, state);
                    return;
                }
                $section sec = this.orion${'$'}sections[i];
                if (sec.hasOnlyAir() && state.is(net.minecraft.world.level.block.Blocks.AIR)) return;
                int lx = this.val${'$'}columnPos.getX() & 15;
                int lz = this.val${'$'}columnPos.getZ() & 15;
                sec.setBlockState(lx, y & 15, lz, state);
                $heightmap[] maps = this.orion${'$'}heightmaps;
                for (int j = 0; j < maps.length; j++) maps[j].update(lx, y, lz, state);
                if (!state.getFluidState().isEmpty()) this.val${'$'}protoChunk.markPosForPostprocessing(this.val${'$'}columnPos.setY(y));
            }
            """, cc,
        ))
    }

    // A top-level sequence of TestRules guarded by abovePreliminarySurface or a vertical gradient
    // has Y ranges where the outcome is fixed: all-null above every gradient and below the
    // preliminary surface, or a constant state at and below a gradient's trueAtAndBelow. The
    // context answers those itself and skips updateY plus the rule walk; anything else in the
    // tree disables the shortcut.
    private fun patchSurfaceContext(cc: CtClass) {
        val rules = "$LEVELGEN.SurfaceRules"
        val rule = "$rules\$SurfaceRule"
        val test = "$rules\$TestRule"
        val seq = "$rules\$SequenceRule"
        val stateRule = "$rules\$StateRule"
        val grad = "$rules\$VerticalGradientConditionSource\$1VerticalGradientCondition"
        cc.addInterface(cc.classPool.get(rule))
        cc.addField(CtField.make("private $rule orion\$rule;", cc))
        cc.addField(CtField.make("private int orion\$lo;", cc))
        cc.addField(CtField.make("private int orion\$k;", cc))
        cc.addField(CtField.make("private int[] orion\$cLo;", cc))
        cc.addField(CtField.make("private int[] orion\$cHi;", cc))
        cc.addField(CtField.make("private $BLOCK_STATE[] orion\$cState;", cc))
        cc.addField(CtField.make("private int orion\$short;", cc))
        cc.addField(CtField.make("private $BLOCK_STATE orion\$state;", cc))
        cc.addMethod(CtNewMethod.make(
            """
            public Object orion${'$'}wrap(Object r) {
                $rule top = ($rule) r;
                java.util.List list = (top instanceof $seq) ? (($seq) top).rules() : java.util.Collections.singletonList(top);
                int n = list.size();
                int lo = Integer.MIN_VALUE;
                boolean open = true;
                int k = 0;
                int[] cLo = new int[n];
                int[] cHi = new int[n];
                $BLOCK_STATE[] cState = new $BLOCK_STATE[n];
                for (int i = 0; i < n; i++) {
                    Object c = list.get(i);
                    if (!(c instanceof $test)) { open = false; break; }
                    $test t = ($test) c;
                    Object cond = t.condition();
                    if (cond == this.abovePreliminarySurface) continue;
                    if (!(cond instanceof $grad)) { open = false; break; }
                    $grad g = ($grad) cond;
                    if (g.val${'$'}ruleContext != this) { open = false; break; }
                    Object f = t.followup();
                    if (f instanceof $stateRule && (($stateRule) f).state() != null && lo <= g.val${'$'}trueAtAndBelow) {
                        cLo[k] = lo;
                        cHi[k] = g.val${'$'}trueAtAndBelow;
                        cState[k] = (($stateRule) f).state();
                        k++;
                    }
                    if (g.val${'$'}falseAtAndAbove > lo) lo = g.val${'$'}falseAtAndAbove;
                }
                this.orion${'$'}lo = open ? lo : Integer.MAX_VALUE;
                this.orion${'$'}k = k;
                this.orion${'$'}cLo = cLo;
                this.orion${'$'}cHi = cHi;
                this.orion${'$'}cState = cState;
                this.orion${'$'}rule = top;
                return this;
            }
            """, cc,
        ))
        val keepUpdate = if (surfaceVerify) "" else "return;"
        val exitLoop = if (surfaceVerify) "break;" else "return;"
        cc.addMethod(CtNewMethod.make(
            """
            public void orion${'$'}updateY(int above, int below, int water, int x, int y, int z) {
                this.orion${'$'}short = 0;
                if (y < this.getMinSurfaceLevel()) {
                    if (y >= this.orion${'$'}lo) {
                        this.orion${'$'}short = 1;
                        $keepUpdate
                    } else {
                        for (int j = 0; j < this.orion${'$'}k; j++) {
                            if (y >= this.orion${'$'}cLo[j] && y <= this.orion${'$'}cHi[j]) {
                                this.orion${'$'}short = 2;
                                this.orion${'$'}state = this.orion${'$'}cState[j];
                                
                                $exitLoop
                            }
                        }
                    }
                }
                this.updateY(above, below, water, x, y, z);
            }
            """, cc,
        ))
        val body = if (surfaceVerify) {
            """
            public $BLOCK_STATE tryApply(int x, int y, int z) {
                $BLOCK_STATE real = this.orion${'$'}rule.tryApply(x, y, z);
                int s = this.orion${'$'}short;
                if (s != 0) {
                    io.github.eath1283.worldgend.StagePatch.surfaceShortcuts.increment();
                    if (real != (s == 1 ? null : this.orion${'$'}state)) io.github.eath1283.worldgend.StagePatch.surfaceMismatches.increment();
                }
                return real;
            }
            """
        } else {
            """
            public $BLOCK_STATE tryApply(int x, int y, int z) {
                int s = this.orion${'$'}short;
                if (s == 1) return null;
                if (s == 2) return this.orion${'$'}state;
                return this.orion${'$'}rule.tryApply(x, y, z);
            }
            """
        }
        cc.addMethod(CtNewMethod.make(body, cc))
    }

    private fun patchSurfaceSystem(cc: CtClass) {
        val pool = cc.classPool
        // Pool copies only have to compile; the loaded classes get the same patches on load.
        patchSurfaceColumn(pool.get(SURFACE_COLUMN))
        patchSurfaceContext(pool.get(SURFACE_CONTEXT))
        val build = cc.declaredMethods.single { it.name == "buildSurface" }
        var reads = 0
        var wraps = 0
        var updates = 0
        var writes = 0
        val fastSet = enabled("surfaceSet")
        build.instrument(object : ExprEditor() {
            override fun edit(m: javassist.expr.MethodCall) {
                when {
                    m.methodName == "getBlock" && m.className == "net.minecraft.world.level.chunk.BlockColumn" -> {
                        m.replace("{ ${'$'}_ = (($SURFACE_COLUMN) ${'$'}0).orion${'$'}get(${'$'}1); }")
                        reads++
                    }
                    m.methodName == "apply" && m.className == "$LEVELGEN.SurfaceRules\$RuleSource" -> {
                        m.replace("{ ${'$'}_ = (($SURFACE_CONTEXT) ${'$'}1).orion${'$'}wrap(${'$'}proceed(${'$'}${'$'})); }")
                        wraps++
                    }
                    fastSet && m.methodName == "setBlock" && m.className == "net.minecraft.world.level.chunk.BlockColumn" -> {
                        m.replace("{ (($SURFACE_COLUMN) ${'$'}0).orion${'$'}set(${'$'}1, ${'$'}2); }")
                        writes++
                    }
                    m.methodName == "updateY" && m.className == SURFACE_CONTEXT -> {
                        m.replace("{ ${'$'}0.orion${'$'}updateY(${'$'}${'$'}); }")
                        updates++
                    }
                }
            }
        })
        check(reads == 2 && wraps == 1 && updates == 1 && writes == (if (fastSet) 1 else 0)) {
            "buildSurface shape changed: reads=$reads wraps=$wraps updates=$updates writes=$writes"
        }
    }
}

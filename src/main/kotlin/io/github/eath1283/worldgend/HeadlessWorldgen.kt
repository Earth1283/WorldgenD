package io.github.eath1283.worldgend

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.Proxy as NetProxy
import java.nio.file.Path
import java.util.Collections
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.BooleanSupplier

// ChunkPyramid.GENERATION_PYRAMID's addRequirement() calls are all radius 1
// except STRUCTURE_STARTS, which is 8 — confirmed straight out of the jar's
// bytecode (bipush 8), not assumed. Two chunks whose (x, z) both share the
// same residue mod MOSAIC_N are at least MOSAIC_N apart on some axis, so any
// N > 8 makes them provably independent — no shared dependency, ever. Filling
// a solid area phase-by-phase (one residue class per phase) turns "let the
// scheduler figure out the wavefront" into "there is no wavefront": every
// chunk in a phase is generatable the instant it's submitted.
private const val MOSAIC_N = 16

fun main() {
    // #56: Minecraft's log bootstrap makes System.err unreliable after startup, so an
    // uncaught exception's default printStackTrace(System.err) can render as a bare
    // "Exception in thread main" with the trace itself silently lost. Write it to a file too.
    Thread.setDefaultUncaughtExceptionHandler { _, t ->
        File("main_crash.txt").writeText(t.stackTraceToString())
    }
    // Self-reported, not inferred: which collector actually loaded, straight from the
    // JVM's own MXBeans, so a GC experiment's flags can be confirmed the same way
    // -Dmax.bg.threads got confirmed in #13 — by asking the running JVM, not the flag.
    val gcNames = ManagementFactory.getGarbageCollectorMXBeans().joinToString { it.name }
    println("Active GC(s): $gcNames")

    // Experiment (see scientific-findings.md #21): vanilla only ever expects ONE thread
    // to call managedBlock() and drain MinecraftServer's task queue — normally "the server
    // thread," here whichever thread happens to run main(). Nothing in the public API stops
    // a second thread from calling the same protected managedBlock() on the same instance
    // concurrently; vanilla just never does it. pumpThreads > 1 tries it anyway.
    val pumpThreads = System.getProperty("pump.threads", "1").toInt()
    println("Pump threads: $pumpThreads (managedBlock() drainers per phase barrier)")

    // MC-55596 control knobs (see scientific-findings.md #22): describe.all logs every
    // chunk instead of just phase 0/last, and mosaic.tile shrinks the run for fast iteration.
    val mosaicTile = System.getProperty("mosaic.tile", "6").toInt()
    val describeAll = System.getProperty("describe.all", "false").toBoolean()
    val pumpDebug = System.getProperty("pump.debug", "false").toBoolean()

    val schedulerMode = System.getProperty("scheduler", "mosaic")
    val saveWorld = System.getProperty("saveworld", "false").toBoolean()
    val reportProgress = System.getProperty("worldgen.progress", "false").toBoolean()
    val progressFile = System.getProperty("worldgen.progressfile")?.let { path ->
        File(path).apply {
            parentFile?.mkdirs()
            writeText("")
        }
    }
    println(
        if (saveWorld) "World saving enabled: chunks will be flushed after generation timing completes."
        else "World saving disabled (default): benchmark behavior is unchanged."
    )
    val orionMaxInFlight = System.getProperty("orion.maxinflight", "64").toInt()
    val orionLockRadius = System.getProperty("orion.lockradius", Orion.DEPENDENCY_RADIUS.toString()).toInt()
    val orionTelemetry = System.getProperty("orion.telemetry", "false").toBoolean()
    val orionDispatchThreads = System.getProperty("orion.dispatchthreads", "1").toInt()
    val orionWaitCeilingMs = System.getProperty("orion.waitceilingms", "10").toLong()

    val serversDir = File(System.getProperty("user.dir"), "servers")
    val callTelemetry = if (System.getProperty("call.telemetry", "false").toBoolean())
        File(serversDir.parentFile, "call_telemetry.log").apply { writeText("") } else null
    val discovered = ServerRuntime.discover(serversDir)
    println("Hammering ${discovered.jar} (${discovered.classpath.size} bundled libraries)")

    val loader = discovered.newClassLoader()
    if (schedulerMode == "orion5.1" || schedulerMode == "orion5.2") DensitySimdPatch.requireInstalled(loader)
    if (schedulerMode == "orion5.2") ImprovedNoisePatch.requireInstalled(loader)
    if (schedulerMode == "orion5.3") AllocationPatch.requireInstalled(loader, OrionPatchAgent.ALLOCATION_PATCH_TARGETS)
    val mc = Mc(loader)

    mc.method(mc.c("net.minecraft.SharedConstants"), "tryDetectVersion").call(null)
    mc.method(mc.c("net.minecraft.server.Bootstrap"), "bootStrap").call(null)

    // Direct stress test, not a real-generation timing gamble: resetPieces() is the exact
    // method that reassigns StrongholdPieces' racy static state (currentPieces/totalWeight/
    // imposedPiece), needs nothing but Bootstrap.bootStrap() having run, and can be hammered
    // millions of times a second from N threads — real chunk generation only ever triggers
    // it once per stronghold, making timing-based detection near-impossible (see the three
    // failed real-generation attempts this session). A genuine race here surfaces as an
    // actual exception (ArrayIndexOutOfBounds/ConcurrentModification/NPE), not a maybe.
    if (schedulerMode == "stress-stronghold") {
        val cStrongholdPieces = mc.c("net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces")
        val resetPieces = mc.publicMethod(cStrongholdPieces, "resetPieces")
        val threads = System.getProperty("stress.threads", "8").toInt()
        val iterations = System.getProperty("stress.iterations", "200000").toLong()
        val inCritical = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = java.util.concurrent.atomic.AtomicInteger(0)
        val exceptions = java.util.concurrent.atomic.AtomicLong(0)
        val samples = java.util.Collections.synchronizedList(mutableListOf<String>())
        val workers = (1..threads).map {
            Thread {
                var i = 0L
                while (i < iterations) {
                    val cur = inCritical.incrementAndGet()
                    maxConcurrent.updateAndGet { m -> maxOf(m, cur) }
                    try {
                        resetPieces.call(null)
                    } catch (t: Throwable) {
                        exceptions.incrementAndGet()
                        val real = t.cause ?: t
                        if (samples.size < 8) samples.add("${real.javaClass.name}: ${real.message}")
                    } finally {
                        inCritical.decrementAndGet()
                    }
                    i++
                }
            }
        }
        val start = System.nanoTime()
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        File(serversDir.parentFile, "stress_result.txt").writeText(
            "stress-stronghold: threads=$threads iterations=$iterations totalCalls=${threads * iterations} " +
                "maxConcurrent=${maxConcurrent.get()} exceptions=${exceptions.get()} elapsedMs=$elapsedMs\n" +
                "samples: ${samples.joinToString(" | ")}\n"
        )
        return
    }

    // DFC Stage 1 proof-of-mechanism (see scientific-findings-41-80.md #56's follow-up
    // section / Dfc.kt): does compiling a DensityFunction tree into one flat method
    // actually beat vanilla's own polymorphic compute() dispatch, on this jar and JDK?
    // Builds a synthetic tree from real DensityFunctions factories, not a toy — only
    // needs Bootstrap.bootStrap(), same reasoning as stress-stronghold above.
    if (schedulerMode == "dfc-bench") try {
        val cDensityFunctions = mc.c("net.minecraft.world.level.levelgen.DensityFunctions")
        val cDensityFunction = mc.c("net.minecraft.world.level.levelgen.DensityFunction")
        val cFunctionContext = mc.c("net.minecraft.world.level.levelgen.DensityFunction\$FunctionContext")
        val constant = mc.publicMethod(cDensityFunctions, "constant", Double::class.javaPrimitiveType!!)
        val add = mc.publicMethod(cDensityFunctions, "add", cDensityFunction, cDensityFunction)
        val mul = mc.publicMethod(cDensityFunctions, "mul", cDensityFunction, cDensityFunction)
        val yClampedGradient = mc.publicMethod(
            cDensityFunctions, "yClampedGradient",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!,
        )
        val realLeaves = System.getProperty("dfc.realleaves", "false").toBoolean()

        val depth = System.getProperty("dfc.depth", "12").toInt()
        val iterations = System.getProperty("dfc.iterations", "20000000").toLong()

        val ctx = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(cFunctionContext), InvocationHandler { _, method, margs ->
            when (method.name) {
                "blockX" -> 100
                "blockY" -> 64
                "blockZ" -> 100
                else -> defaultInvoke(method, margs)
            }
        })

        // Vanilla's own add()/mul() fold two Constant operands at construction time
        // (confirmed: an all-constant tree collapsed to a single leaf, per a first
        // pass of this benchmark). Non-foldable leaves keep the tree's real depth.
        // Two leaf kinds: Proxy stand-ins (first pass — InvocationHandler dispatch
        // dilutes the glue-removal signal we're actually trying to measure) vs real
        // yClampedGradient instances (concrete vanilla class, real virtual call, no
        // reflection tax on either the vanilla or compiled path) — dfc.realleaves
        // picks which, so both numbers are on record.
        fun newLeaf(seed: Int): Any = if (realLeaves) {
            yClampedGradient.call(null, 0, 256, seed * 0.01, seed * 0.01 + 1.0)!!
        } else {
            java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(cDensityFunction), InvocationHandler { proxy, method, margs ->
                when (method.name) {
                    "compute" -> {
                        val x = mc.publicMethod(cFunctionContext, "blockX").call(margs!![0]) as Int
                        ((x + seed) % 7) * 0.1
                    }
                    "minValue" -> -1.0
                    "maxValue" -> 1.0
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === margs?.get(0)
                    "toString" -> "TestLeaf$seed"
                    else -> defaultInvoke(method, margs)
                }
            })
        }

        // A lopsided tree of alternating add/mul over depth+1 leaves.
        fun buildTree(d: Int): Any {
            var acc = newLeaf(d)
            for (i in 0 until d) {
                val leaf = newLeaf(i)
                acc = if (i % 2 == 0) add.call(null, acc, leaf)!! else mul.call(null, acc, leaf)!!
            }
            return acc
        }
        val tree = buildTree(depth)

        val converter = DfcConverter(mc)
        val ast = converter.toAst(tree)
        val compiled = DfcCompilerGen.compile(loader, ast)
        val leavesArray = converter.leaves.toTypedArray()

        val computeMethod = mc.publicMethod(cDensityFunction, "compute", cFunctionContext)

        // Warmup both paths equally before timing either.
        var sink = 0.0
        repeat(200_000) {
            sink += computeMethod.call(tree, ctx) as Double
            sink += compiled.eval(leavesArray, ctx)
        }

        val vanillaStart = System.nanoTime()
        var i = 0L
        while (i < iterations) { sink += computeMethod.call(tree, ctx) as Double; i++ }
        val vanillaMs = (System.nanoTime() - vanillaStart) / 1_000_000

        val compiledStart = System.nanoTime()
        i = 0L
        while (i < iterations) { sink += compiled.eval(leavesArray, ctx); i++ }
        val compiledMs = (System.nanoTime() - compiledStart) / 1_000_000

        File(serversDir.parentFile, "dfc_bench_result.txt").writeText(
            "dfc-bench: depth=$depth iterations=$iterations leaves=${leavesArray.size} realLeaves=$realLeaves sink=$sink\n" +
                "vanilla computeMs=$vanillaMs (${vanillaMs.toDouble() / iterations * 1_000_000}ns/call)\n" +
                "compiled evalMs=$compiledMs (${compiledMs.toDouble() / iterations * 1_000_000}ns/call)\n" +
                "speedup=${vanillaMs.toDouble() / compiledMs}x\n"
        )
        return
    } catch (t: Throwable) {
        File(serversDir.parentFile, "dfc_bench_result.txt").writeText("THREW: ${t.stackTraceToString()}\n")
        return
    }

    // A fresh .run every launch: createNewWorldData() always builds new world
    // data regardless of what's on disk, but a stale region file from a prior
    // (different-seed) run would still get loaded back instead of regenerated,
    // silently defeating the configured seed below.
    val runDir = File(serversDir, ".run").apply { deleteRecursively(); mkdirs() }

    // #55: STRUCTURE_STARTS is the sole radius-8 requirement in ChunkPyramid
    // (#7) -- every other requirement is radius 1. Toggle to test whether
    // disabling structure search collapses the dependency radius and relieves
    // the scarcity #50/#51/#54 pinned as the real ceiling.
    val generateStructures = System.getProperty("worldgen.generateStructures", "true").toBoolean()
    val worldSeed = System.getProperty("worldgen.seed", "69").toLong()
    println("World seed: $worldSeed")
    val propertiesFile = File(runDir, "server.properties")
        .apply { writeText("level-seed=$worldSeed\ngenerate-structures=$generateStructures\n") }
    val cDedicatedServerSettings = mc.c("net.minecraft.server.dedicated.DedicatedServerSettings")
    val dedicatedServerSettings = mc.new(
        cDedicatedServerSettings, arrayOf(Path::class.java), arrayOf(propertiesFile.toPath())
    )

    val cDirectoryValidator = mc.c("net.minecraft.world.level.validation.DirectoryValidator")
    val cLevelStorageSource = mc.c("net.minecraft.world.level.storage.LevelStorageSource")
    val validator = mc.method(cLevelStorageSource, "parseValidator", Path::class.java)
        .call(null, File(runDir, "symlinks.txt").toPath())

    val cServerPacksSource = mc.c("net.minecraft.server.packs.repository.ServerPacksSource")
    val serverPacksSource = mc.new(cServerPacksSource, arrayOf(cDirectoryValidator), arrayOf(validator))

    val cRepositorySource = mc.c("net.minecraft.server.packs.repository.RepositorySource")
    val repoSources = java.lang.reflect.Array.newInstance(cRepositorySource, 1)
    java.lang.reflect.Array.set(repoSources, 0, serverPacksSource)
    val cPackRepository = mc.c("net.minecraft.server.packs.repository.PackRepository")
    val packRepository = mc.ctor(cPackRepository, repoSources.javaClass).newInstance(repoSources)

    val cWorldDataConfiguration = mc.c("net.minecraft.world.level.WorldDataConfiguration")
    val defaultDataConfig = mc.staticField(cWorldDataConfiguration, "DEFAULT")

    val cPackConfig = mc.c("net.minecraft.server.WorldLoader\$PackConfig")
    val packConfig = mc.new(
        cPackConfig,
        arrayOf(cPackRepository, cWorldDataConfiguration, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
        arrayOf(packRepository, defaultDataConfig, false, false)
    )

    val cCommandSelection = mc.c("net.minecraft.commands.Commands\$CommandSelection")
    val commandSelection = mc.staticField(cCommandSelection, "DEDICATED")

    val cPermissionSet = mc.c("net.minecraft.server.permissions.PermissionSet")
    val allPermissions = mc.staticField(cPermissionSet, "ALL_PERMISSIONS")

    val cInitConfig = mc.c("net.minecraft.server.WorldLoader\$InitConfig")
    val initConfig = mc.new(
        cInitConfig, arrayOf(cPackConfig, cCommandSelection, cPermissionSet),
        arrayOf(packConfig, commandSelection, allPermissions)
    )

    val cMain = mc.c("net.minecraft.server.Main")
    val cDataLoadContext = mc.c("net.minecraft.server.WorldLoader\$DataLoadContext")
    val cRegistry = mc.c("net.minecraft.core.Registry")
    val cRegistries = mc.c("net.minecraft.core.registries.Registries")
    val levelStemKey = mc.staticField(cRegistries, "LEVEL_STEM")
    val cRegistryAccess = mc.c("net.minecraft.core.RegistryAccess")
    val createNewWorldData = mc.method(
        cMain, "createNewWorldData", cDedicatedServerSettings, cDataLoadContext, cRegistry,
        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!
    )
    val lookupOrThrowRegistry = mc.methodByReturn(cRegistryAccess, "lookupOrThrow", 1, cRegistry)
    val datapackDimensions = mc.publicMethod(cDataLoadContext, "datapackDimensions")

    val cWorldDataSupplier = mc.c("net.minecraft.server.WorldLoader\$WorldDataSupplier")
    val worldDataSupplier = Proxy.newProxyInstance(loader, arrayOf(cWorldDataSupplier), InvocationHandler { _, method, args ->
        if (method.name != "get") return@InvocationHandler defaultInvoke(method, args)
        val dataLoadContext = args[0]
        val dimensions = datapackDimensions.call(dataLoadContext)
        val levelStemRegistry = lookupOrThrowRegistry.call(dimensions, levelStemKey)
        createNewWorldData.call(null, dedicatedServerSettings, dataLoadContext, levelStemRegistry, false, false)
    })

    val cWorldStem = mc.c("net.minecraft.server.WorldStem")
    val cCloseableResourceManager = mc.c("net.minecraft.server.packs.resources.CloseableResourceManager")
    val cReloadableServerResources = mc.c("net.minecraft.server.ReloadableServerResources")
    val cLayeredRegistryAccess = mc.c("net.minecraft.core.LayeredRegistryAccess")
    val cWorldDataAndGenSettings = mc.c("net.minecraft.world.level.storage.LevelDataAndDimensions\$WorldDataAndGenSettings")
    val worldStemCtor = mc.ctor(cWorldStem, cCloseableResourceManager, cReloadableServerResources, cLayeredRegistryAccess, cWorldDataAndGenSettings)

    val cResultFactory = mc.c("net.minecraft.server.WorldLoader\$ResultFactory")
    val resultFactory = Proxy.newProxyInstance(loader, arrayOf(cResultFactory), InvocationHandler { _, method, args ->
        if (method.name != "create") return@InvocationHandler defaultInvoke(method, args)
        worldStemCtor.newInstance(args[0], args[1], args[2], args[3])
    })

    val directExecutor = Executor { it.run() }
    val cWorldLoader = mc.c("net.minecraft.server.WorldLoader")
    val loadMethod = mc.method(cWorldLoader, "load", cInitConfig, cWorldDataSupplier, cResultFactory, Executor::class.java, Executor::class.java)
    @Suppress("UNCHECKED_CAST")
    val worldStem = (loadMethod.call(null, initConfig, worldDataSupplier, resultFactory, directExecutor, directExecutor) as CompletableFuture<Any?>).join()

    val worldDataAndGenSettings = mc.publicMethod(cWorldStem, "worldDataAndGenSettings").call(worldStem)
    val worldData = mc.publicMethod(cWorldDataAndGenSettings, "data").call(worldDataAndGenSettings)

    val levelStorageSource = mc.method(cLevelStorageSource, "createDefault", Path::class.java)
        .call(null, runDir.toPath())
    val cLevelStorageAccess = mc.c("net.minecraft.world.level.storage.LevelStorageSource\$LevelStorageAccess")
    val levelStorageAccess = mc.publicMethod(cLevelStorageSource, "createAccess", String::class.java)
        .call(levelStorageSource, "headless")
    val cWorldData = mc.c("net.minecraft.world.level.storage.WorldData")
    mc.publicMethod(cLevelStorageAccess, "saveDataTag", cWorldData).call(levelStorageAccess, worldData)

    val cYggdrasil = mc.c("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService")
    val yggdrasil = mc.new(cYggdrasil, arrayOf(java.net.Proxy::class.java), arrayOf(NetProxy.NO_PROXY))
    val cServices = mc.c("net.minecraft.server.Services")
    val services = mc.method(cServices, "create", cYggdrasil, File::class.java).call(null, yggdrasil, runDir)

    val dataFixer = mc.method(mc.c("net.minecraft.util.datafix.DataFixers"), "getDataFixer").call(null)

    val cDedicatedServer = mc.c("net.minecraft.server.dedicated.DedicatedServer")
    val dedicatedServer = mc.new(
        cDedicatedServer,
        arrayOf(Thread::class.java, cLevelStorageAccess, cPackRepository, cWorldStem, Optional::class.java, cDedicatedServerSettings, mc.c("com.mojang.datafixers.DataFixer"), cServices),
        arrayOf(Thread.currentThread(), levelStorageAccess, packRepository, worldStem, Optional.empty<Any>(), dedicatedServerSettings, dataFixer, services)
    )

    val cMinecraftServer = mc.c("net.minecraft.server.MinecraftServer")
    val saveAllChunks = mc.publicMethod(
        cMinecraftServer, "saveAllChunks",
        Boolean::class.javaPrimitiveType!!,
        Boolean::class.javaPrimitiveType!!,
        Boolean::class.javaPrimitiveType!!,
    )

    fun saveWorldIfRequested() {
        if (!saveWorld) return
        println("Generation timing complete; saving world to ${File(runDir, "headless")} (blocking flush)...")
        val saveStart = System.nanoTime()
        saveAllChunks.call(dedicatedServer, false, true, false)
        val saveMs = (System.nanoTime() - saveStart) / 1_000_000
        println("World save complete in ${saveMs}ms (excluded from generation timing).")
    }

    // ServerLevel's constructor reads getPlayerList().getViewDistance(); normally
    // DedicatedServer.initServer() sets that up, which we never call, so we wire
    // a bare-minimum PlayerList in by hand before touching loadLevel().
    val registries = mc.publicMethod(cWorldStem, "registries").call(worldStem)
    val cPlayerDataStorage = mc.c("net.minecraft.world.level.storage.PlayerDataStorage")
    val playerDataStorage = mc.new(cPlayerDataStorage, arrayOf(cLevelStorageAccess, mc.c("com.mojang.datafixers.DataFixer")), arrayOf(levelStorageAccess, dataFixer))
    val cPlayerList = mc.c("net.minecraft.server.players.PlayerList")
    val cDedicatedPlayerList = mc.c("net.minecraft.server.dedicated.DedicatedPlayerList")
    val playerList = mc.new(
        cDedicatedPlayerList, arrayOf(cDedicatedServer, cLayeredRegistryAccess, cPlayerDataStorage),
        arrayOf(dedicatedServer, registries, playerDataStorage)
    )
    mc.publicMethod(cMinecraftServer, "setPlayerList", cPlayerList).call(dedicatedServer, playerList)
    mc.publicMethod(cPlayerList, "setViewDistance", Int::class.javaPrimitiveType!!).call(playerList, 10)

    println("Constructed DedicatedServer without run()/initServer() — calling loadLevel() directly.")
    mc.method(cMinecraftServer, "loadLevel").call(dedicatedServer)

    val overworld = mc.publicMethod(cMinecraftServer, "overworld").call(dedicatedServer)!!
    val cServerLevel = mc.c("net.minecraft.server.level.ServerLevel")
    val cServerChunkCache = mc.c("net.minecraft.server.level.ServerChunkCache")
    val chunkSource = mc.methodByReturn(cServerLevel, "getChunkSource", 0, cServerChunkCache).call(overworld)!!

    val cChunkStatus = mc.c("net.minecraft.world.level.chunk.status.ChunkStatus")
    val fullStatus = mc.staticField(cChunkStatus, "FULL")
    val getChunkFuture = mc.method(
        cServerChunkCache, "getChunkFuture",
        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, cChunkStatus, Boolean::class.javaPrimitiveType!!
    )
    val managedBlock = mc.publicMethod(cMinecraftServer, "managedBlock", BooleanSupplier::class.java)

    val cChunkAccess = mc.c("net.minecraft.world.level.chunk.ChunkAccess")
    val getHeight = mc.publicMethod(cChunkAccess, "getHeight", mc.c("net.minecraft.world.level.levelgen.Heightmap\$Types"), Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    val getNoiseBiome = mc.publicMethod(cChunkAccess, "getNoiseBiome", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    val worldSurface = mc.staticField(mc.c("net.minecraft.world.level.levelgen.Heightmap\$Types"), "WORLD_SURFACE")
    val unwrapKey = mc.publicMethod(mc.c("net.minecraft.core.Holder"), "unwrapKey")
    val identifier = mc.publicMethod(mc.c("net.minecraft.resources.ResourceKey"), "identifier")

    data class Pending(val cx: Int, val cz: Int, val future: CompletableFuture<Any?>)

    fun describe(chunk: Any, cx: Int, cz: Int): String {
        val sampleX = (cx shl 4) + 8
        val sampleZ = (cz shl 4) + 8
        val height = getHeight.call(chunk, worldSurface, sampleX, sampleZ) as Int
        val biomeHolder = getNoiseBiome.call(chunk, sampleX shr 2, height shr 2, sampleZ shr 2)!!
        @Suppress("UNCHECKED_CAST")
        val biomeKey = (unwrapKey.call(biomeHolder) as Optional<Any?>).orElse(null)
        val biomeName = biomeKey?.let { identifier.call(it).toString() } ?: "?"
        return "[$cx,$cz] height=$height biome=$biomeName"
    }

    val histogramFile = System.getProperty("describe.histogramfile")?.let { File(it).apply { writeText("") } }
    val getSections = mc.publicMethod(cChunkAccess, "getSections")
    val snapshot = histogramFile?.let {
        BlockStateSnapshot(mc.publicMethod(mc.c("net.minecraft.world.level.chunk.LevelChunkSection"),
            "getBlockState", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!))
    }

    fun recordHistogram(chunkResult: Any, cx: Int, cz: Int) {
        val out = histogramFile ?: return
        val chunk = mc.publicMethod(chunkResult.javaClass, "orElse", Any::class.java).call(chunkResult, null)!!
        val sections = getSections.call(chunk) as Array<*>
        val line = "$cx $cz ${snapshot!!.describe(sections)}\n"
        synchronized(out) { out.appendText(line) }
    }

    val mosaicSide = MOSAIC_N * mosaicTile
    val base = -mosaicSide / 2

    // One-off lookup so task #3 can target a chunk that actually exercises the structure-gen
    // patches, instead of guessing a mosaic region large enough to reach a stronghold's ring.
    if (schedulerMode == "locate") {
        val structureLocationArg = System.getProperty("locate.structure", "minecraft:stronghold")
        val cIdentifier = mc.c("net.minecraft.resources.Identifier")
        val identifierOf = mc.publicMethod(cIdentifier, "parse", String::class.java).call(null, structureLocationArg)!!
        val cRegistries = mc.c("net.minecraft.core.registries.Registries")
        val structureKey = mc.staticField(cRegistries, "STRUCTURE")
        val cRegistryAccess = mc.c("net.minecraft.core.RegistryAccess")
        val registryAccess = mc.publicMethod(cMinecraftServer, "registryAccess").call(dedicatedServer)!!
        val cRegistry = mc.c("net.minecraft.core.Registry")
        val lookupOrThrow = mc.methodByReturn(cRegistryAccess, "lookupOrThrow", 1, cRegistry)
        val structureRegistry = lookupOrThrow.call(registryAccess, structureKey)!!
        @Suppress("UNCHECKED_CAST")
        val holderOpt = mc.publicMethod(cRegistry, "get", cIdentifier).call(structureRegistry, identifierOf) as Optional<Any?>
        val holder = holderOpt.orElseThrow { IllegalStateException("no such structure: $structureLocationArg") }!!
        val cHolderSet = mc.c("net.minecraft.core.HolderSet")
        val holderSet = mc.publicMethod(cHolderSet, "direct", java.util.List::class.java).call(null, java.util.List.of(holder))!!

        val cChunkGenerator = mc.c("net.minecraft.world.level.chunk.ChunkGenerator")
        val generator = mc.publicMethod(cServerChunkCache, "getGenerator").call(chunkSource)!!
        val cBlockPos = mc.c("net.minecraft.core.BlockPos")
        val originX = System.getProperty("locate.originx", "0").toInt()
        val originZ = System.getProperty("locate.originz", "0").toInt()
        val origin = mc.new(cBlockPos, arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!), arrayOf(originX, 0, originZ))
        val cHolderSetIface = mc.c("net.minecraft.core.HolderSet")
        val findNearestMapStructure = mc.publicMethod(
            cChunkGenerator, "findNearestMapStructure", cServerLevel, cHolderSetIface, cBlockPos,
            Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
        )
        val searchRadiusChunks = System.getProperty("locate.radius", "100").toInt()
        val resultFile = File(serversDir.parentFile, "locate_result.txt")
        val result = findNearestMapStructure.call(generator, overworld, holderSet, origin, searchRadiusChunks, false)
        // File, not println: console output after Bootstrap.bootStrap() is unreliable here
        // (same symptom as #23's Gradle relay issue, root cause still not isolated).
        if (result == null) {
            resultFile.writeText("locate: no $structureLocationArg found within $searchRadiusChunks chunks of origin\n")
        } else {
            val cPair = mc.c("com.mojang.datafixers.util.Pair")
            val found = mc.publicMethod(cPair, "getFirst").call(result)!!
            val getX = mc.publicMethod(cBlockPos, "getX")
            val getY = mc.publicMethod(cBlockPos, "getY")
            val getZ = mc.publicMethod(cBlockPos, "getZ")
            val x = getX.call(found) as Int
            val y = getY.call(found) as Int
            val z = getZ.call(found) as Int
            resultFile.writeText("locate: $structureLocationArg at block ($x, $y, $z) -> chunk (${x shr 4}, ${z shr 4})\n")
        }
        return
    }

    if (schedulerMode == "probe") {
        val getChunkFutureMainThread = mc.method(
            cServerChunkCache, "getChunkFutureMainThread",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, cChunkStatus, Boolean::class.javaPrimitiveType!!
        )
        val probeCoords = (0 until 5).flatMap { i -> (0 until 5).map { j -> (base + i) to (base + j) } }
        val lines = mutableListOf<String>()
        for ((cx, cz) in probeCoords) {
            val t0 = System.nanoTime()
            @Suppress("UNCHECKED_CAST")
            val future = getChunkFutureMainThread.call(chunkSource, cx, cz, fullStatus, true) as CompletableFuture<Any?>
            val registerMs = (System.nanoTime() - t0) / 1_000_000.0
            managedBlock.call(dedicatedServer, BooleanSupplier { future.isDone })
            future.join()
            val totalMs = (System.nanoTime() - t0) / 1_000_000.0
            lines.add("[$cx,$cz] register=${"%.3f".format(registerMs)}ms total=${"%.3f".format(totalMs)}ms wait=${"%.3f".format(totalMs - registerMs)}ms")
        }
        File(serversDir.parentFile, "probe_result.txt").writeText(lines.joinToString("\n"))
        saveWorldIfRequested()
        return
    }

    if (schedulerMode == "orion") {
        val telemetryFile = if (orionTelemetry) File(serversDir.parentFile, "orion_telemetry.log").apply { writeText("") } else null
        val pollTask = if (orionDispatchThreads > 1) mc.method(mc.c("net.minecraft.util.thread.BlockableEventLoop"), "pollTask") else null
        val mainThreadProcessor = if (orionDispatchThreads > 1) mc.field(cServerChunkCache, "mainThreadProcessor", chunkSource) else null
        val orion = Orion(
            mc, dedicatedServer, chunkSource, getChunkFuture, fullStatus!!, managedBlock,
            orionMaxInFlight, orionLockRadius, telemetryFile, pollTask, mainThreadProcessor, orionDispatchThreads,
        )
        val target = (base until base + mosaicSide).flatMap { cx -> (base until base + mosaicSide).map { cz -> cx to cz } }
        println("Orion-filling a ${mosaicSide}x$mosaicSide block (${target.size} chunks), max $orionMaxInFlight in flight, lock radius ${Orion.DEPENDENCY_RADIUS}.")

        File(serversDir.parentFile, "orion_result.txt").writeText("orion fill() starting, target=${target.size}\n")
        val overallStart = System.nanoTime()
        val result = try {
            orion.fill(target) { cx, cz, success, chunkResult, error ->
                if (!success) {
                    println("[$cx,$cz] FAILED: $error")
                } else if (describeAll) {
                    val chunk = mc.publicMethod(chunkResult!!.javaClass, "orElse", Any::class.java).call(chunkResult, null)!!
                    println(describe(chunk, cx, cz))
                }
            }
        } catch (t: Throwable) {
            File(serversDir.parentFile, "orion_result.txt").writeText("THREW: ${t.stackTraceToString()}\n")
            throw t
        }
        val totalMs = (System.nanoTime() - overallStart) / 1_000_000
        File(serversDir.parentFile, "orion_result.txt").writeText(
            "ok=${result.ok} failed=${result.failed} totalMs=$totalMs overlapViolations=${orion.overlapViolations.get()}\n${mspcSummary(orion.chunkMspc)}\n"
        )

        println("Done: ${result.ok} chunks generated, ${result.failed} failed in ${totalMs}ms. No network, no RCON, no tick loop ever ran.")
        println(mspcSummary(orion.chunkMspc))
        saveWorldIfRequested()
        return
    }

    if (schedulerMode == "orion2") {
        val telemetryFile = if (orionTelemetry) File(serversDir.parentFile, "orion_telemetry.log").apply { writeText("") } else null
        val pollTask = mc.method(mc.c("net.minecraft.util.thread.BlockableEventLoop"), "pollTask")
        val mainThreadProcessor = mc.field(cServerChunkCache, "mainThreadProcessor", chunkSource)!!
        val orion = OrionV2(
            mc, dedicatedServer, chunkSource, getChunkFuture, fullStatus!!, pollTask, mainThreadProcessor,
            orionDispatchThreads, orionMaxInFlight, orionLockRadius, telemetryFile,
        )
        val target = (base until base + mosaicSide).flatMap { cx -> (base until base + mosaicSide).map { cz -> cx to cz } }
        println("Orion v2-filling a ${mosaicSide}x$mosaicSide block (${target.size} chunks), $orionDispatchThreads workers, max $orionMaxInFlight in flight, lock radius $orionLockRadius.")

        File(serversDir.parentFile, "orion_result.txt").writeText("orion v2 fill() starting, target=${target.size}\n")
        val overallStart = System.nanoTime()
        val result = try {
            orion.fill(target) { cx, cz, success, chunkResult, error ->
                if (!success) {
                    println("[$cx,$cz] FAILED: $error")
                } else if (describeAll) {
                    val chunk = mc.publicMethod(chunkResult!!.javaClass, "orElse", Any::class.java).call(chunkResult, null)!!
                    println(describe(chunk, cx, cz))
                }
            }
        } catch (t: Throwable) {
            File(serversDir.parentFile, "orion_result.txt").writeText("THREW: ${t.stackTraceToString()}\n")
            throw t
        }
        val totalMs = (System.nanoTime() - overallStart) / 1_000_000
        File(serversDir.parentFile, "orion_result.txt").writeText(
            "scheduler=orion2 ok=${result.ok} failed=${result.failed} totalMs=$totalMs\n${mspcSummary(orion.chunkMspc)}\n"
        )

        println("Done: ${result.ok} chunks generated, ${result.failed} failed in ${totalMs}ms. No network, no RCON, no tick loop ever ran.")
        println(mspcSummary(orion.chunkMspc))
        saveWorldIfRequested()
        return
    }

    if (schedulerMode == "orion2.1") {
        val telemetryFile = if (orionTelemetry) File(serversDir.parentFile, "orion_telemetry.log").apply { writeText("") } else null
        val pollTask = mc.method(mc.c("net.minecraft.util.thread.BlockableEventLoop"), "pollTask")
        val mainThreadProcessor = mc.field(cServerChunkCache, "mainThreadProcessor", chunkSource)!!
        val orion = OrionV2_1(
            mc, dedicatedServer, chunkSource, getChunkFuture, fullStatus!!, pollTask, mainThreadProcessor,
            orionDispatchThreads, orionMaxInFlight, orionLockRadius, telemetryFile,
        )
        val target = (base until base + mosaicSide).flatMap { cx -> (base until base + mosaicSide).map { cz -> cx to cz } }
        println(
            "Orion v2.1-filling a ${mosaicSide}x$mosaicSide block (${target.size} chunks), $orionDispatchThreads workers, " +
                "max $orionMaxInFlight in flight, lock radius $orionLockRadius."
        )

        File(serversDir.parentFile, "orion_result.txt").writeText("orion v2.1 fill() starting, target=${target.size}\n")
        val overallStart = System.nanoTime()
        val result = try {
            orion.fill(target) { cx, cz, success, chunkResult, error ->
                if (!success) {
                    println("[$cx,$cz] FAILED: $error")
                } else if (describeAll) {
                    val chunk = mc.publicMethod(chunkResult!!.javaClass, "orElse", Any::class.java).call(chunkResult, null)!!
                    println(describe(chunk, cx, cz))
                }
            }
        } catch (t: Throwable) {
            File(serversDir.parentFile, "orion_result.txt").writeText("THREW: ${t.stackTraceToString()}\n")
            throw t
        }
        val totalMs = (System.nanoTime() - overallStart) / 1_000_000
        File(serversDir.parentFile, "orion_result.txt").writeText(
            "scheduler=orion2.1 ok=${result.ok} failed=${result.failed} totalMs=$totalMs\n${mspcSummary(orion.chunkMspc)}\n"
        )

        println("Done: ${result.ok} chunks generated, ${result.failed} failed in ${totalMs}ms. No network, no RCON, no tick loop ever ran.")
        println(mspcSummary(orion.chunkMspc))
        saveWorldIfRequested()
        return
    }

    if (schedulerMode == "orion2.2") {
        val telemetryFile = if (orionTelemetry) File(serversDir.parentFile, "orion_telemetry.log").apply { writeText("") } else null
        val pollTask = mc.method(mc.c("net.minecraft.util.thread.BlockableEventLoop"), "pollTask")
        val mainThreadProcessor = mc.field(cServerChunkCache, "mainThreadProcessor", chunkSource)!!
        val orion = OrionV2_2(
            mc, dedicatedServer, chunkSource, getChunkFuture, fullStatus!!, pollTask, mainThreadProcessor,
            orionDispatchThreads, orionMaxInFlight, orionLockRadius, telemetryFile,
        )
        val target = (base until base + mosaicSide).flatMap { cx -> (base until base + mosaicSide).map { cz -> cx to cz } }
        println(
            "Orion v2.2-filling a ${mosaicSide}x$mosaicSide block (${target.size} chunks), $orionDispatchThreads workers, " +
                "max $orionMaxInFlight in flight, lock radius $orionLockRadius, scatter order."
        )

        File(serversDir.parentFile, "orion_result.txt").writeText("orion v2.2 fill() starting, target=${target.size}\n")
        val overallStart = System.nanoTime()
        val result = try {
            orion.fill(target) { cx, cz, success, chunkResult, error ->
                if (!success) {
                    println("[$cx,$cz] FAILED: $error")
                } else if (describeAll) {
                    val chunk = mc.publicMethod(chunkResult!!.javaClass, "orElse", Any::class.java).call(chunkResult, null)!!
                    println(describe(chunk, cx, cz))
                }
            }
        } catch (t: Throwable) {
            File(serversDir.parentFile, "orion_result.txt").writeText("THREW: ${t.stackTraceToString()}\n")
            throw t
        }
        val totalMs = (System.nanoTime() - overallStart) / 1_000_000
        File(serversDir.parentFile, "orion_result.txt").writeText(
            "scheduler=orion2.2 ok=${result.ok} failed=${result.failed} totalMs=$totalMs\n${mspcSummary(orion.chunkMspc)}\n"
        )

        println("Done: ${result.ok} chunks generated, ${result.failed} failed in ${totalMs}ms. No network, no RCON, no tick loop ever ran.")
        println(mspcSummary(orion.chunkMspc))
        saveWorldIfRequested()
        return
    }

    if (schedulerMode == "orion3" || schedulerMode == "orion4") {
        // orion4 is orion3's scheduling code plus the structure-gen thread-safety patch
        // (OrionPatchAgent) — it's the flag combo, not different scheduling logic, so it
        // fails fast rather than silently running as orion3 without its defining patch.
        // DFC (-Dorion.patchDfc=true) is deliberately NOT required here: finding #59
        // measured it as a real ~23-27% champion-scale REGRESSION (231184ms/eMSPC 25.08
        // vs orion4-without-DFC's 187663ms/20.36), root-caused via DfcRuntime's own
        // compile/eval counters — a 99.95% cache hit rate rules out recompilation, so
        // the cost is the per-call cache lookup (IdentityKey allocation + ConcurrentHashMap
        // lookup) itself exceeding what a real Ap2 node's tiny original compute() body
        // ever cost. Usable standalone for anyone who wants to reproduce or improve it.
        if (schedulerMode == "orion4") {
            require(System.getProperty("orion.patchReentrancy") == "true") {
                "orion4 requires -Dorion.patchReentrancy=true (see OrionPatchAgent)"
            }
            require(System.getProperty("orion.patchStructureGenState") == "true") {
                "orion4 requires -Dorion.patchStructureGenState=true (see OrionPatchAgent)"
            }
        }
        val telemetryFile = if (orionTelemetry) File(serversDir.parentFile, "orion_telemetry.log").apply { writeText("") } else null
        val pollTask = mc.method(mc.c("net.minecraft.util.thread.BlockableEventLoop"), "pollTask")
        val mainThreadProcessor = mc.field(cServerChunkCache, "mainThreadProcessor", chunkSource)!!
        val orion = OrionV3(
            mc, dedicatedServer, chunkSource, getChunkFuture, fullStatus!!, pollTask, mainThreadProcessor,
            orionDispatchThreads, orionMaxInFlight, orionLockRadius, telemetryFile, orionWaitCeilingMs,
        )
        // Independent of `base`'s origin-centering (other modes' SOP baselines depend on that) —
        // task #3 needs to target a real stronghold, not spawn.
        val centerX = System.getProperty("mosaic.centerx", "0").toInt()
        val centerZ = System.getProperty("mosaic.centerz", "0").toInt()
        val baseX = centerX - mosaicSide / 2
        val baseZ = centerZ - mosaicSide / 2
        var target = (baseX until baseX + mosaicSide).flatMap { cx -> (baseZ until baseZ + mosaicSide).map { cz -> cx to cz } }
        // Optional second disjoint region in the SAME fill() call — needed to get two
        // different structure-start events (e.g. two strongholds) racing on real worker
        // threads at once; one region alone can only ever trigger one at a time.
        val centerX2 = System.getProperty("mosaic.centerx2")?.toInt()
        val centerZ2 = System.getProperty("mosaic.centerz2")?.toInt()
        if (centerX2 != null && centerZ2 != null) {
            val baseX2 = centerX2 - mosaicSide / 2
            val baseZ2 = centerZ2 - mosaicSide / 2
            val target2 = (baseX2 until baseX2 + mosaicSide).flatMap { cx -> (baseZ2 until baseZ2 + mosaicSide).map { cz -> cx to cz } }
            // Interleaved, not concatenated: the scheduler admits off a FIFO by list order,
            // so appending region 2 after region 1 would let region 1 finish first — no
            // actual wall-clock overlap between the two structure-start events.
            target = target.zip(target2).flatMap { (a, b) -> listOf(a, b) }
        }
        println(
            "Orion ${if (schedulerMode == "orion4") "v4" else "v3"}-filling a ${mosaicSide}x$mosaicSide block (${target.size} chunks), " +
                "$orionDispatchThreads workers, max $orionMaxInFlight in flight, lock radius $orionLockRadius, multi-threaded admission."
        )

        File(serversDir.parentFile, "orion_result.txt").writeText("orion ${schedulerMode.removePrefix("orion")} fill() starting, target=${target.size}\n")
        val overallStart = System.nanoTime()
        val result = try {
            orion.fill(target) { cx, cz, success, chunkResult, error ->
                if (!success) {
                    println("[$cx,$cz] FAILED: $error")
                } else {
                    recordHistogram(chunkResult!!, cx, cz)
                    if (describeAll) {
                        val chunk = mc.publicMethod(chunkResult.javaClass, "orElse", Any::class.java).call(chunkResult, null)!!
                        println(describe(chunk, cx, cz))
                    }
                }
            }
        } catch (t: Throwable) {
            File(serversDir.parentFile, "orion_result.txt").writeText("THREW: ${t.stackTraceToString()}\n")
            throw t
        }
        val totalMs = (System.nanoTime() - overallStart) / 1_000_000
        File(serversDir.parentFile, "orion_result.txt").writeText(
            "scheduler=$schedulerMode ok=${result.ok} failed=${result.failed} totalMs=$totalMs\n${mspcSummary(orion.chunkMspc)}\n"
        )

        println("Done: ${result.ok} chunks generated, ${result.failed} failed in ${totalMs}ms. No network, no RCON, no tick loop ever ran.")
        println(mspcSummary(orion.chunkMspc))
        saveWorldIfRequested()
        return
    }

    if (schedulerMode == "orion5" || schedulerMode == "orion5.1" || schedulerMode == "orion5.2" || schedulerMode == "orion5.3") {
        for (flag in listOf("orion.patchReentrancy", "orion.patchStructureGenState", "orion.patchParallelSteps")) {
            require(System.getProperty(flag) == "true") { "$schedulerMode requires -D$flag=true (see OrionPatchAgent)" }
        }
        val pollTask = mc.method(mc.c("net.minecraft.util.thread.BlockableEventLoop"), "pollTask")
        val mainThreadProcessor = mc.field(cServerChunkCache, "mainThreadProcessor", chunkSource)!!
        val backgroundExecutor = mc.publicMethod(mc.c("net.minecraft.util.Util"), "backgroundExecutor").call(null)!!
        val backgroundPool = mc.publicMethod(backgroundExecutor.javaClass, "service").call(backgroundExecutor) as java.util.concurrent.ForkJoinPool
        fun poolReport() = "backgroundPool parallelism=${backgroundPool.parallelism} poolSize=${backgroundPool.poolSize}"
        val orion = OrionV5(mc, dedicatedServer, chunkSource, getChunkFuture, fullStatus!!, pollTask, mainThreadProcessor, orionMaxInFlight)
        // #65: shifting the target lets two runs overlap partially, to test position-only determinism.
        val shift = Integer.getInteger("orion.targetShift", 0)
        val lo = base + shift
        val hi = base + shift + mosaicSide - 1
        val target = (lo..hi).flatMap { cx -> (lo..hi).map { cz -> cx to cz } }
        val evictChunks = System.getProperty("orion.evictChunks") == "true"
        val featureOrderMode = System.getProperty("orion.deterministicFeatures")
        // Verified together at tile 2 with -Dorion.targetShift=100: 0/1024 block-position
        // hashes differ vs. the same run without eviction, with 640/1024 chunks actually
        // evicted mid-run -- deterministicFeatures's extra neighbor-only calls (outside
        // `target`) never touch an evicted chunk's ticket/holder, so they don't interact.
        val evictor = if (evictChunks) ChunkEvictor(mc, chunkSource, lo, hi, mosaicSide) else null
        if (featureOrderMode != null) {
            val featuresStatus = mc.staticField(cChunkStatus, "FEATURES")
            // Target chunks need LIGHT, which pulls FEATURES on their 1-ring; nothing further out runs it.
            val bounds = when (featureOrderMode) {
                "region" -> intArrayOf(lo - 1, lo - 1, hi + 1, hi + 1)
                "closure" -> null
                else -> error("orion.deterministicFeatures must be region or closure, got $featureOrderMode")
            }
            OrionParallelSteps.orderFeatures(OrionParallelSteps.FeatureOrder(bounds) { x, z ->
                getChunkFuture.invoke(chunkSource, x, z, featuresStatus, true) as CompletableFuture<*>
            })
            val progressFile = File(serversDir.parentFile, "orion_progress.txt")
            Thread({
                while (true) {
                    Thread.sleep(20_000)
                    progressFile.appendText("${OrionParallelSteps.report()}\n  stuck: ${OrionParallelSteps.stuckSample(8)}\n")
                }
            }, "orion5-progress").apply { isDaemon = true; start() }
        }
        println("Orion v${schedulerMode.removePrefix("orion")}-filling a ${mosaicSide}x$mosaicSide block (${target.size} chunks), max $orionMaxInFlight in flight, parallel steps.")

        val resultFile = File(serversDir.parentFile, "orion_result.txt")
        resultFile.writeText("$schedulerMode fill() starting, target=${target.size}\n${poolReport()}\n")
        val overallStart = System.nanoTime()
        val result = try {
            orion.fill(
                target,
                onComplete = { cx, cz, success, chunkResult, error ->
                    if (success) recordHistogram(chunkResult!!, cx, cz) else println("[$cx,$cz] FAILED: $error")
                    evictor?.markComplete(cx, cz)
                },
                onProgress = { completed, total, elapsedMs ->
                    val progress = "WGD_PROGRESS completed=$completed total=$total elapsedMs=$elapsedMs"
                    if (reportProgress) println(progress)
                    if (progressFile != null) synchronized(progressFile) { progressFile.appendText("$progress\n") }
                },
            )
        } catch (t: Throwable) {
            resultFile.writeText("THREW: ${t.stackTraceToString()}\n")
            throw t
        }
        evictor?.flush()
        val totalMs = (System.nanoTime() - overallStart) / 1_000_000
        resultFile.writeText(
            "scheduler=$schedulerMode ok=${result.ok} failed=${result.failed} totalMs=$totalMs\n${mspcSummary(orion.chunkMspc)}\n" +
                "parallelSteps ${OrionParallelSteps.report()}\n${poolReport()}\n" +
                (if (schedulerMode == "orion5.1" || schedulerMode == "orion5.2") "densitySimd ${DensityBatch.report()}\n" else "") +
                (if (schedulerMode == "orion5.3") "ap2ScratchMaxDepth ${Ap2Scratch.maxDepthSeen()}\n" else "") +
                (if (evictor != null) {
                    "chunkEvictor ${evictor.report()}\n" +
                        "chunkEvictorGcCheck ${evictor.verifyReclaimed()}\n" +
                        "chunkEvictorRetainerPath ${evictor.findFirstRetainerPath()}\n"
                } else "")
        )
        println("Done: ${result.ok} chunks generated, ${result.failed} failed in ${totalMs}ms.")
        saveWorldIfRequested()
        return
    }

    val phaseCount = MOSAIC_N * MOSAIC_N
    println("Mosaic-filling a ${mosaicSide}x$mosaicSide block across $phaseCount independence-guaranteed phases (mod $MOSAIC_N).")

    var ok = 0
    var failed = 0
    var fastestPhaseMs = Long.MAX_VALUE
    var slowestPhaseMs = 0L
    // MSPC (milliseconds per chunk): submission-to-completion latency of one chunk's
    // getChunkFuture, sampled per chunk rather than averaged per phase. whenComplete()
    // fires on whichever thread actually finishes the future, so this list is written
    // from many worker threads concurrently — hence the synchronized wrapper.
    val chunkMspc = Collections.synchronizedList(mutableListOf<Double>())
    val overallStart = System.nanoTime()
    for (phase in 0 until phaseCount) {
        val residueX = phase % MOSAIC_N
        val residueZ = phase / MOSAIC_N
        val phaseCoords = (0 until mosaicTile).flatMap { i ->
            (0 until mosaicTile).map { j -> (base + residueX + i * MOSAIC_N) to (base + residueZ + j * MOSAIC_N) }
        }

        val phaseStart = System.nanoTime()
        val pending = phaseCoords.map { (cx, cz) ->
            val submitNanos = System.nanoTime()
            @Suppress("UNCHECKED_CAST")
            val future = getChunkFuture.call(chunkSource, cx, cz, fullStatus, true) as CompletableFuture<Any?>
            if (callTelemetry != null) {
                val callMs = (System.nanoTime() - submitNanos) / 1_000_000.0
                synchronized(callTelemetry) { callTelemetry.appendText("CALL $cx,$cz took=${callMs}ms\n") }
            }
            future.whenComplete { _, _ -> chunkMspc.add((System.nanoTime() - submitNanos) / 1_000_000.0) }
            Pending(cx, cz, future)
        }
        val phaseDone = CompletableFuture.allOf(*pending.map { it.future }.toTypedArray())
        val pumpCondition = BooleanSupplier { phaseDone.isDone }
        if (pumpThreads > 1) {
            val extraPumpers = (1 until pumpThreads).map { i ->
                Thread({
                    val t0 = System.nanoTime()
                    if (pumpDebug) println("pump-$i phase=$phase START +${(t0 - phaseStart) / 1_000_000}ms isDoneAlready=${pumpCondition.getAsBoolean()}")
                    try {
                        managedBlock.call(dedicatedServer, pumpCondition)
                    } catch (t: Throwable) {
                        println("pump-$i phase=$phase THREW: $t")
                        t.printStackTrace(System.out)
                    }
                    if (pumpDebug) println("pump-$i phase=$phase END after ${(System.nanoTime() - t0) / 1_000_000}ms, total-since-phase-start=${(System.nanoTime() - phaseStart) / 1_000_000}ms")
                }, "pump-$i").apply { isDaemon = true; start() }
            }
            if (pumpDebug) println("main phase=$phase pumpers launched +${(System.nanoTime() - phaseStart) / 1_000_000}ms")
            managedBlock.call(dedicatedServer, pumpCondition)
            extraPumpers.forEach { it.join() }
        } else {
            managedBlock.call(dedicatedServer, pumpCondition)
        }
        phaseDone.join()
        val phaseMs = (System.nanoTime() - phaseStart) / 1_000_000
        fastestPhaseMs = minOf(fastestPhaseMs, phaseMs)
        slowestPhaseMs = maxOf(slowestPhaseMs, phaseMs)

        for ((cx, cz, future) in pending) {
            val result = future.join()!!
            val isSuccess = mc.publicMethodCached(result.javaClass, "isSuccess").call(result) as Boolean
            if (!isSuccess) {
                failed++
                println("[$cx,$cz] FAILED: ${mc.publicMethodCached(result.javaClass, "getError").call(result)}")
                continue
            }
            ok++
            if (describeAll || phase == 0 || phase == phaseCount - 1) {
                val chunk = mc.publicMethod(result.javaClass, "orElse", Any::class.java).call(result, null)!!
                println(describe(chunk, cx, cz))
            }
        }
        if (phase % 32 == 0 || phase == phaseCount - 1) {
            println("phase $phase/${phaseCount - 1}: ${pending.size} chunks in ${phaseMs}ms")
        }
    }
    val totalMs = (System.nanoTime() - overallStart) / 1_000_000

    println(
        "Done: $ok chunks generated, $failed failed in ${totalMs}ms across $phaseCount phases " +
            "(fastest=${fastestPhaseMs}ms, slowest=${slowestPhaseMs}ms). No network, no RCON, no tick loop ever ran."
    )
    println(mspcSummary(chunkMspc))
    File(serversDir.parentFile, "mosaic_result.txt").writeText(
        "ok=$ok failed=$failed totalMs=$totalMs\n${mspcSummary(chunkMspc)}\n"
    )
    saveWorldIfRequested()
}


private fun defaultInvoke(method: java.lang.reflect.Method, args: Array<Any?>?): Any? = when (method.name) {
    "toString" -> "WorldgenD proxy for ${method.declaringClass}"
    "hashCode" -> System.identityHashCode(args)
    "equals" -> false
    else -> null
}

// Linear-interpolation percentile (numpy's default "linear" method): rank = p/100 * (n-1),
// then interpolate between the two bracketing samples. Matches what most stats tooling
// reports for "p50"/"p99" so MSPC numbers here are comparable elsewhere without translation.
private fun percentile(sorted: DoubleArray, p: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val rank = p / 100.0 * (sorted.size - 1)
    val lo = rank.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    val frac = rank - lo
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}

private fun mspcSummary(samplesMs: List<Double>): String {
    if (samplesMs.isEmpty()) return "MSPC (ms/chunk): no samples"
    val sorted = samplesMs.toDoubleArray().also { it.sort() }
    fun at(p: Double) = "%.2f".format(percentile(sorted, p))
    return "MSPC (ms/chunk, n=${sorted.size}): min=${at(0.0)} p1=${at(1.0)} p25=${at(25.0)} " +
        "p50=${at(50.0)} p75=${at(75.0)} p99=${at(99.0)} max=${at(100.0)}"
}

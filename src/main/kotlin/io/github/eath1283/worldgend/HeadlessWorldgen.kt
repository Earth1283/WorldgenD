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
private const val MOSAIC_TILE = 5
private const val MOSAIC_SIDE = MOSAIC_N * MOSAIC_TILE

fun main() {
    // Self-reported, not inferred: which collector actually loaded, straight from the
    // JVM's own MXBeans, so a GC experiment's flags can be confirmed the same way
    // -Dmax.bg.threads got confirmed in #13 — by asking the running JVM, not the flag.
    val gcNames = ManagementFactory.getGarbageCollectorMXBeans().joinToString { it.name }
    println("Active GC(s): $gcNames")

    val serversDir = File(System.getProperty("user.dir"), "servers")
    val discovered = ServerRuntime.discover(serversDir)
    println("Hammering ${discovered.jar} (${discovered.classpath.size} bundled libraries)")

    val loader = discovered.newClassLoader()
    val mc = Mc(loader)

    mc.method(mc.c("net.minecraft.SharedConstants"), "tryDetectVersion").call(null)
    mc.method(mc.c("net.minecraft.server.Bootstrap"), "bootStrap").call(null)

    // A fresh .run every launch: createNewWorldData() always builds new world
    // data regardless of what's on disk, but a stale region file from a prior
    // (different-seed) run would still get loaded back instead of regenerated,
    // silently defeating the pinned seed below.
    val runDir = File(serversDir, ".run").apply { deleteRecursively(); mkdirs() }

    val propertiesFile = File(runDir, "server.properties").apply { writeText("level-seed=69\n") }
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

    val base = -MOSAIC_SIDE / 2
    val phaseCount = MOSAIC_N * MOSAIC_N
    println("Mosaic-filling a ${MOSAIC_SIDE}x$MOSAIC_SIDE block across $phaseCount independence-guaranteed phases (mod $MOSAIC_N).")

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
        val phaseCoords = (0 until MOSAIC_TILE).flatMap { i ->
            (0 until MOSAIC_TILE).map { j -> (base + residueX + i * MOSAIC_N) to (base + residueZ + j * MOSAIC_N) }
        }

        val phaseStart = System.nanoTime()
        val pending = phaseCoords.map { (cx, cz) ->
            val submitNanos = System.nanoTime()
            @Suppress("UNCHECKED_CAST")
            val future = getChunkFuture.call(chunkSource, cx, cz, fullStatus, true) as CompletableFuture<Any?>
            future.whenComplete { _, _ -> chunkMspc.add((System.nanoTime() - submitNanos) / 1_000_000.0) }
            Pending(cx, cz, future)
        }
        val phaseDone = CompletableFuture.allOf(*pending.map { it.future }.toTypedArray())
        managedBlock.call(dedicatedServer, BooleanSupplier { phaseDone.isDone })
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
            if (phase == 0 || phase == phaseCount - 1) {
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

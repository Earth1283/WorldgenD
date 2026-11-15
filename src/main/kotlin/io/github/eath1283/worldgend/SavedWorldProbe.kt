package io.github.eath1283.worldgend

import java.io.File
import java.nio.file.Path

object SavedWorldProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 7) { "Expected serversDir worldDir minX minZ side dataVersion reportFile" }
        val report = File(args[6])
        try {
            inspect(File(args[0]), File(args[1]), args[2].toInt(), args[3].toInt(), args[4].toInt(), args[5].toInt(), report)
        } catch (t: Throwable) {
            report.writeText("FAILED\n${t.stackTraceToString()}")
            throw t
        }
    }

    private fun inspect(servers: File, world: File, minX: Int, minZ: Int, side: Int, dataVersion: Int, report: File) {
        require(side > 0)
        ServerRuntime.discover(servers).newClassLoader().use { loader ->
            val mc = Mc(loader)
            mc.method(mc.c("net.minecraft.SharedConstants"), "tryDetectVersion").call(null)
            mc.method(mc.c("net.minecraft.server.Bootstrap"), "bootStrap").call(null)
            val infoClass = mc.c("net.minecraft.world.level.chunk.storage.RegionStorageInfo")
            val dimension = mc.staticField(mc.c("net.minecraft.world.level.Level"), "OVERWORLD")
            val info = mc.new(infoClass,
                arrayOf(String::class.java, mc.c("net.minecraft.resources.ResourceKey"), String::class.java),
                arrayOf("headless", dimension, "chunk"))
            val storageClass = mc.c("net.minecraft.world.level.chunk.storage.RegionFileStorage")
            val regionDir = File(world, "dimensions/minecraft/overworld/region")
            require(regionDir.isDirectory) { "Missing region directory: $regionDir" }
            val storage = mc.new(storageClass,
                arrayOf(infoClass, Path::class.java, Boolean::class.javaPrimitiveType!!),
                arrayOf(info, regionDir.toPath(), false))
            val posClass = mc.c("net.minecraft.world.level.ChunkPos")
            val posConstructor = mc.ctor(posClass, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            val read = mc.publicMethod(storageClass, "read", posClass)
            val tagClass = mc.c("net.minecraft.nbt.CompoundTag")
            val getInt = mc.publicMethod(tagClass, "getIntOr", String::class.java, Int::class.javaPrimitiveType!!)
            val getString = mc.publicMethod(tagClass, "getStringOr", String::class.java, String::class.java)
            val getList = mc.publicMethod(tagClass, "getListOrEmpty", String::class.java)
            val getCompound = mc.publicMethod(tagClass, "getCompound", String::class.java)
            val listClass = mc.c("net.minecraft.nbt.ListTag")
            val listSize = mc.publicMethod(listClass, "size")
            val listCompound = mc.publicMethod(listClass, "getCompoundOrEmpty", Int::class.javaPrimitiveType!!)
            var checked = 0
            (storage as AutoCloseable).use {
                for (x in minX until minX + side) for (z in minZ until minZ + side) {
                    val tag = read.call(storage, posConstructor.newInstance(x, z)) ?: error("Missing saved chunk $x,$z")
                    check(getInt.call(tag, "xPos", Int.MIN_VALUE) == x && getInt.call(tag, "zPos", Int.MIN_VALUE) == z) {
                        "Saved coordinates differ at $x,$z"
                    }
                    check(getInt.call(tag, "DataVersion", -1) == dataVersion) { "Wrong data version at $x,$z" }
                    check(getString.call(tag, "Status", "") == "minecraft:full") { "Incomplete chunk $x,$z" }
                    val sections = getList.call(tag, "sections")!!
                    val size = listSize.call(sections) as Int
                    var blockSections = 0
                    var biomeSections = 0
                    for (i in 0 until size) {
                        val section = listCompound.call(sections, i)!!
                        if ((getCompound.call(section, "block_states") as java.util.Optional<*>).isPresent) blockSections++
                        if ((getCompound.call(section, "biomes") as java.util.Optional<*>).isPresent) biomeSections++
                    }
                    check(blockSections > 0 && biomeSections > 0) { "Missing blocks or biomes at $x,$z" }
                    checked++
                }
            }
            report.writeText("ok=$checked failed=0 dataVersion=$dataVersion\n")
        }
    }
}

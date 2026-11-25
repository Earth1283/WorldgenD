package io.github.eath1283.worldgend

import java.io.File
import java.net.Proxy
import java.util.Optional

object ServerBootstrap {
    fun services(mc: Mc, runDir: File): Any {
        val servicesClass = mc.c("net.minecraft.server.Services")
        val create = servicesClass.declaredMethods.single {
            it.name == "create" && it.parameterCount == 2 && it.parameterTypes[1] == File::class.java
        }.apply { isAccessible = true }
        val authClass = create.parameterTypes[0]
        val auth = when (authClass.name) {
            "com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService" ->
                mc.new(authClass, arrayOf(Proxy::class.java), arrayOf(Proxy.NO_PROXY))
            "com.mojang.authlib.services.MinecraftServicesDiscoveryService" ->
                mc.publicMethod(authClass, "create", Proxy::class.java).call(null, Proxy.NO_PROXY)
            else -> error("Unsupported Services.create authentication API: ${authClass.name}")
        }
        return create.call(null, auth, runDir)!!
    }

    fun dedicatedServer(
        mc: Mc,
        levelStorageAccess: Any?,
        packRepository: Any?,
        worldStem: Any?,
        dedicatedServerSettings: Any?,
        dataFixer: Any?,
        services: Any,
    ): Any {
        val serverClass = mc.c("net.minecraft.server.dedicated.DedicatedServer")
        val types = mutableListOf(
            Thread::class.java,
            mc.c("net.minecraft.world.level.storage.LevelStorageSource\$LevelStorageAccess"),
            mc.c("net.minecraft.server.packs.repository.PackRepository"),
            mc.c("net.minecraft.server.WorldStem"),
            Optional::class.java,
            mc.c("net.minecraft.server.dedicated.DedicatedServerSettings"),
            mc.c("com.mojang.datafixers.DataFixer"),
            mc.c("net.minecraft.server.Services"),
        )
        val args = mutableListOf(
            Thread.currentThread(), levelStorageAccess, packRepository, worldStem,
            Optional.empty<Any>(), dedicatedServerSettings, dataFixer, services,
        )
        val legacyConstructor = serverClass.declaredConstructors.any {
            it.parameterTypes.contentEquals(types.toTypedArray())
        }
        val notifications = if (legacyConstructor) null else {
            val managementClass = mc.c("net.minecraft.server.jsonrpc.ManagementServer")
            val notificationClass = mc.c("net.minecraft.server.notifications.NotificationManager")
            types.add(managementClass)
            types.add(notificationClass)
            val manager = mc.new(notificationClass, emptyArray(), emptyArray())
            // The headless server has no management listener.
            args.add(null)
            args.add(manager)
            manager
        }
        val server = mc.new(serverClass, types.toTypedArray(), args.toTypedArray())
        if (notifications != null) {
            mc.publicMethod(notifications.javaClass, "setServer", serverClass).call(notifications, server)
        }
        return server
    }
}

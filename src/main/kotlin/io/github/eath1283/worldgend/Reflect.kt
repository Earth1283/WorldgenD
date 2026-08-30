package io.github.eath1283.worldgend

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class Mc(private val loader: ClassLoader) {
    fun c(name: String): Class<*> = Class.forName(name, false, loader)

    fun ctor(cls: Class<*>, vararg params: Class<*>): Constructor<*> =
        cls.getDeclaredConstructor(*params).apply { isAccessible = true }

    fun new(cls: Class<*>, params: Array<Class<*>>, args: Array<Any?>): Any =
        ctor(cls, *params).newInstance(*args)!!

    fun method(cls: Class<*>, name: String, vararg params: Class<*>): Method =
        cls.getDeclaredMethod(name, *params).apply { isAccessible = true }

    fun publicMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method =
        cls.getMethod(name, *params).apply { isAccessible = true }

    // Bridge methods from covariant-return interface overrides make plain
    // getDeclaredMethod(name, ResourceKey) ambiguous at the erasure level;
    // picking by return type sidesteps that instead of guessing which bridge wins.
    fun methodByReturn(cls: Class<*>, name: String, paramCount: Int, returnAssignableTo: Class<*>): Method =
        cls.methods.first {
            it.name == name && it.parameterCount == paramCount && returnAssignableTo.isAssignableFrom(it.returnType)
        }.apply { isAccessible = true }

    fun staticField(cls: Class<*>, name: String): Any? =
        cls.getField(name).apply { isAccessible = true }.get(null)

    fun field(cls: Class<*>, name: String, target: Any): Any? =
        cls.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private val methodCache = ConcurrentHashMap<Pair<Class<*>, String>, Method>()
    fun publicMethodCached(cls: Class<*>, name: String, vararg params: Class<*>): Method =
        methodCache.getOrPut(cls to name) { publicMethod(cls, name, *params) }
}

fun Method.call(target: Any?, vararg args: Any?): Any? = invoke(target, *args)

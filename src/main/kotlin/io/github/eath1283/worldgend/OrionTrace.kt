package io.github.eath1283.worldgend

import javassist.ClassPool
import javassist.CtClass
import javassist.LoaderClassPath
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

// Critical-path study: per-step timestamps, serial-lane busy time, 100 ms utilization samples,
// and opt-in CPU injection (-Dorion.inject=noise:2000 adds 2000 us of thread CPU per noise step).
object OrionTrace {
    private val path: String? = System.getProperty("orion.trace")
    @JvmField val enabled = path != null
    private const val BIN_NANOS = 100_000_000L
    private const val MAX_BINS = 12_000

    val statuses = listOf(
        "empty", "structure_starts", "structure_references", "biomes", "noise", "surface", "carvers",
        "features", "initialize_light", "light", "spawn", "full",
    )
    private val injectNanos = LongArray(statuses.size).also { arr ->
        System.getProperty("orion.inject")?.split(',')?.filter { it.isNotBlank() }?.forEach { spec ->
            val (name, micros) = spec.split(':')
            arr[statuses.indexOf(name).also { require(it >= 0) { "unknown status $name" } }] = micros.toLong() * 1000
        }
    }
    val injectSpec: String = System.getProperty("orion.inject", "")

    @Volatile private var origin = 0L
    private val steps = ConcurrentLinkedQueue<LongArray>()
    private val targets = ConcurrentHashMap<Long, LongArray>()

    // [x, z, dispatch, complete] per requested FULL target
    fun dispatched(x: Int, z: Int) {
        if (origin != 0L) targets[(x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)] = longArrayOf(x.toLong(), z.toLong(), System.nanoTime(), 0L)
    }

    fun completed(x: Int, z: Int) {
        targets[(x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)]?.let { it[3] = System.nanoTime() }
    }
    private val laneBins = ConcurrentHashMap<String, AtomicLongArray>()
    private val structureHoldBins = AtomicLongArray(MAX_BINS)
    private val structureWaitNanos = AtomicLong()
    private var structureAcquired = 0L

    val submitted = AtomicLong()
    val ready = AtomicLong()
    val started = AtomicLong()
    val ended = AtomicLong()

    @JvmStatic fun now() = if (enabled) System.nanoTime() else 0L
    fun statusIndex(name: String) = statuses.indexOf(name)

    fun spin(status: Int) {
        val nanos = if (status >= 0) injectNanos[status] else 0L
        if (nanos == 0L) return
        val bean = ManagementFactory.getThreadMXBean()
        val end = bean.currentThreadCpuTime + nanos
        while (bean.currentThreadCpuTime < end) Thread.onSpinWait()
    }

    // [status, x, z, submit, ready, start, locked, bodyEnd, end]
    fun step(record: LongArray) { if (origin != 0L) steps.add(record) }

    @JvmStatic
    fun lane(name: String, startNanos: Long) {
        if (origin == 0L) return
        addInterval(laneBins.computeIfAbsent(name) { AtomicLongArray(MAX_BINS) }, startNanos, System.nanoTime())
    }

    @JvmStatic fun structureWaited(sinceNanos: Long) {
        val acquired = System.nanoTime()
        structureWaitNanos.addAndGet(acquired - sinceNanos)
        structureAcquired = acquired
    }

    @JvmStatic fun structureReleasing() {
        if (origin != 0L) addInterval(structureHoldBins, structureAcquired, System.nanoTime())
    }

    private fun addInterval(bins: AtomicLongArray, from: Long, to: Long) {
        var t = maxOf(from, origin)
        while (t < to) {
            val bin = ((t - origin) / BIN_NANOS).toInt()
            if (bin >= MAX_BINS) return
            val binEnd = origin + (bin + 1) * BIN_NANOS
            val upTo = minOf(to, binEnd)
            bins.addAndGet(bin, upTo - t)
            t = upTo
        }
    }

    private class Sampler(private val executor: () -> Executor?) : Thread("orion-trace-sampler") {
        private var pool: ForkJoinPool? = null
        @Volatile var running = true
        val rows = ArrayList<String>()
        private val threads = ManagementFactory.getThreadMXBean()
        private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        private val lastCpu = HashMap<Long, Long>()

        init { isDaemon = true }

        private fun procStat(): LongArray {
            val parts = File("/proc/stat").useLines { it.first() }.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
            // total, idle+iowait, steal
            return longArrayOf(parts.sum(), parts[3] + parts[4], parts.getOrElse(7) { 0L })
        }

        override fun run() {
            var lastProcess = os.processCpuTime
            var lastStat = procStat()
            var next = origin + BIN_NANOS
            while (running) {
                val sleep = next - System.nanoTime()
                if (sleep > 0) java.util.concurrent.locks.LockSupport.parkNanos(sleep)
                if (pool == null) pool = executor()?.let { runCatching { it.javaClass.getMethod("service").invoke(it) as? ForkJoinPool }.getOrNull() }
                val pool = pool
                val groups = LongArray(4)
                for (info in threads.getThreadInfo(threads.allThreadIds)) {
                    if (info == null) continue
                    val cpu = threads.getThreadCpuTime(info.threadId)
                    if (cpu < 0) continue
                    val delta = cpu - (lastCpu.put(info.threadId, cpu) ?: cpu)
                    val name = info.threadName
                    groups[when {
                        name.startsWith("Worker-Main") -> 0
                        name == "orion5-poll" -> 1
                        name.startsWith("orion-trace") -> 3
                        else -> 2
                    }] += delta
                }
                val process = os.processCpuTime
                val stat = procStat()
                rows += listOf(
                    (next - origin) / 1_000_000, groups[0], groups[1], groups[2], process - lastProcess,
                    stat[0] - lastStat[0], stat[1] - lastStat[1], stat[2] - lastStat[2],
                    pool?.queuedSubmissionCount ?: -1, pool?.queuedTaskCount ?: -1, pool?.activeThreadCount ?: -1, pool?.runningThreadCount ?: -1,
                    submitted.get() - ready.get(), ready.get() - started.get(), started.get() - ended.get(),
                ).joinToString(",")
                lastProcess = process
                lastStat = stat
                next += BIN_NANOS
            }
        }
    }

    private var sampler: Sampler? = null

    var summary = ""
        private set

    fun begin(executor: () -> Executor?) {
        if (!enabled) return
        origin = System.nanoTime()
        sampler = Sampler(executor).also { it.start() }
    }

    fun end() {
        if (!enabled || origin == 0L) return
        val endNanos = System.nanoTime()
        val s = sampler!!
        s.running = false
        s.join()
        val base = path!!
        File("$base.steps.csv").bufferedWriter().use { out ->
            out.write("status,x,z,submit_us,ready_us,start_us,locked_us,body_end_us,end_us\n")
            for (r in steps) {
                out.write(statuses[r[0].toInt()]); out.write(","); out.write("${r[1]},${r[2]}")
                for (i in 3 until r.size) out.write("," + (if (r[i] == 0L) "" else ((r[i] - origin) / 1000).toString()))
                out.write("\n")
            }
        }
        File("$base.targets.csv").bufferedWriter().use { out ->
            out.write("x,z,dispatch_us,complete_us\n")
            for (t in targets.values) out.write("${t[0]},${t[1]},${(t[2] - origin) / 1000},${if (t[3] == 0L) "" else (t[3] - origin) / 1000}\n")
        }
        File("$base.samples.csv").writeText(
            "t_ms,pool_cpu_ns,poll_cpu_ns,other_java_cpu_ns,process_cpu_ns,host_ticks,host_idle_ticks,host_steal_ticks," +
                "fjp_queued_submissions,fjp_queued_tasks,fjp_active,fjp_running,area_waiting,exec_waiting,steps_running\n" +
                s.rows.joinToString("\n") + "\n"
        )
        val bins = ((endNanos - origin) / BIN_NANOS + 1).toInt().coerceAtMost(MAX_BINS)
        val lanes = laneBins.keys.sorted()
        File("$base.lanes.csv").bufferedWriter().use { out ->
            out.write("t_ms," + (lanes + "structure_lock").joinToString(",") + "\n")
            for (b in 0 until bins) {
                out.write("${b * 100}," + lanes.joinToString(",") { laneBins.getValue(it)[b].toString() } + ",${structureHoldBins[b]}\n")
            }
        }
        val wallNanos = (endNanos - origin).toDouble()
        fun busy(arr: AtomicLongArray) = (0 until bins).sumOf { arr[it] }
        summary = "trace inject=${injectSpec.ifEmpty { "none" }} steps=${steps.size} " +
            lanes.joinToString(" ") { "lane.$it=%.3f".format(busy(laneBins.getValue(it)) / wallNanos) } +
            " structureLock=%.3f structureWaitS=%.2f".format(busy(structureHoldBins) / wallNanos, structureWaitNanos.get() / 1e9)
    }

    const val LANE_TARGET = "net.minecraft.util.thread.AbstractConsecutiveExecutor"

    fun transformLane(loader: ClassLoader?, original: ByteArray): ByteArray {
        val pool = ClassPool(false).apply {
            appendSystemPath()
            if (loader != null) appendClassPath(LoaderClassPath(loader))
        }
        val cc: CtClass = pool.makeClass(java.io.ByteArrayInputStream(original))
        val run = cc.getDeclaredMethod("run")
        // A field, not a local: javassist's addLocalVariable breaks the finally frame (VerifyError). Each lane runs serially.
        cc.addField(javassist.CtField.make("public long orionT0;", cc))
        run.insertBefore("this.orionT0 = System.nanoTime();")
        run.insertAfter("io.github.eath1283.worldgend.OrionTrace.lane(this.name, this.orionT0);", true)
        val bytes = cc.toBytecode()
        cc.detach()
        return bytes
    }
}

class OrionTraceTransformer : java.lang.instrument.ClassFileTransformer {
    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: java.security.ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? {
        if (className.replace('/', '.') != OrionTrace.LANE_TARGET) return null
        return try {
            OrionTrace.transformLane(loader, classfileBuffer)
        } catch (t: Throwable) {
            System.err.println("[OrionPatchAgent] lane trace patch FAILED: $t")
            null
        }
    }
}

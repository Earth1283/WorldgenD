package io.github.eath1283.worldgend

import java.io.File

class OrionTelemetry(file: File, private val startNanos: Long) : AutoCloseable {
    private val writer = file.bufferedWriter()
    private var bufferedEvents = 0

    @Synchronized
    fun record(event: String, cx: Int, cz: Int, inFlight: Int, completed: Int) {
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        writer.write("$elapsedMs $event $cx,$cz inFlight=$inFlight completed=$completed\n")
        if (++bufferedEvents == 64) {
            writer.flush()
            bufferedEvents = 0
        }
    }

    @Synchronized
    override fun close() {
        writer.close()
    }
}

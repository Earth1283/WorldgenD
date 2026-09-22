package io.github.eath1283.worldgend

import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrionV5FailureTest {
    class Poller {
        fun pollTask() = false
    }

    class ChunkResult {
        fun isSuccess() = true
    }

    class ChunkSource(private val failSubmission: Boolean) {
        fun getChunkFuture(x: Int, z: Int, status: Any, create: Boolean): CompletableFuture<ChunkResult> {
            if (failSubmission) error("submission failed")
            return CompletableFuture.completedFuture(ChunkResult())
        }
    }

    private fun scheduler(failSubmission: Boolean, telemetryFile: File? = null): OrionV5 {
        val source = ChunkSource(failSubmission)
        val poller = Poller()
        return OrionV5(
            Mc(javaClass.classLoader), poller, source,
            ChunkSource::class.java.getMethod("getChunkFuture", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Any::class.java, Boolean::class.javaPrimitiveType),
            Any(), Poller::class.java.getMethod("pollTask"), poller, 2, telemetryFile,
        )
    }

    @Test
    fun callbackFailureStopsFill() {
        val failure = assertFailsWith<IllegalStateException> {
            scheduler(false).fill(listOf(0 to 0), onComplete = { _, _, _, _, _ -> error("callback failed") })
        }
        assertContains(failure.cause.toString(), "callback failed")
    }

    @Test
    fun submissionFailureStopsFill() {
        val failure = assertFailsWith<IllegalStateException> {
            scheduler(true).fill(listOf(0 to 0))
        }
        assertContains(failure.cause.toString(), "submission failed")
    }

    @Test
    fun telemetryRecordsDispatchAndCompletion() {
        val file = File.createTempFile("orion-telemetry", ".log")
        try {
            val result = scheduler(false, file).fill(listOf(0 to 0, 1 to 1))
            val lines = file.readLines()
            assertEquals(2, result.ok)
            assertEquals(4, lines.size)
            assertEquals(2, lines.count { " DISPATCH " in it })
            assertEquals(2, lines.count { " COMPLETE " in it })
            assertContains(lines.last(), "inFlight=0 completed=2")
        } finally {
            file.delete()
        }
    }
}

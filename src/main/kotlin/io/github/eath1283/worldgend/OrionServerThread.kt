package io.github.eath1283.worldgend

/**
 * The poll thread is the only caller of both server loops, so it counts as their thread even
 * outside pollTask(). Without this, an entity unloaded from onPoll() that calls getChunk()
 * queues a task onto the poll thread's own loop and waits on it forever.
 */
object OrionServerThread {
    @Volatile private var thread: Thread? = null

    @JvmStatic fun isCurrent(): Boolean = Thread.currentThread() === thread

    fun claim(enabled: Boolean) {
        if (enabled) thread = Thread.currentThread()
    }

    fun release() {
        if (thread === Thread.currentThread()) thread = null
    }
}

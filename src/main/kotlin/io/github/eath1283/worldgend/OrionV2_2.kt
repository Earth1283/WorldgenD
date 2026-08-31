package io.github.eath1283.worldgend

import java.io.File
import java.lang.reflect.Method

class OrionV2_2(
    mc: Mc,
    dedicatedServer: Any,
    chunkSource: Any,
    getChunkFuture: Method,
    fullStatus: Any,
    pollTask: Method,
    mainThreadProcessor: Any,
    dispatchThreads: Int,
    maxInFlight: Int,
    lockRadius: Int = Orion.DEPENDENCY_RADIUS,
    telemetry: File? = null,
) {
    companion object {
        private const val SCATTER_MODULUS = 16

        private val scatterRank: Map<Pair<Int, Int>, Int> = run {
            val map = HashMap<Pair<Int, Int>, Int>()
            for (i in 0 until SCATTER_MODULUS * SCATTER_MODULUS) {
                var reversed = 0
                var value = i
                repeat(8) {
                    reversed = (reversed shl 1) or (value and 1)
                    value = value shr 1
                }

                var rx = 0
                var rz = 0
                for (bit in 0 until 4) {
                    if ((reversed shr (2 * bit)) and 1 == 1) rx = rx or (1 shl bit)
                    if ((reversed shr (2 * bit + 1)) and 1 == 1) rz = rz or (1 shl bit)
                }
                map[rx to rz] = i
            }
            map
        }

        internal fun scatterSort(coords: List<Pair<Int, Int>>): List<Pair<Int, Int>> =
            coords.sortedBy { (cx, cz) ->
                scatterRank.getValue(
                    Math.floorMod(cx, SCATTER_MODULUS) to Math.floorMod(cz, SCATTER_MODULUS)
                )
            }
    }

    data class Result(val ok: Int, val failed: Int, val totalMs: Long)

    private val scheduler = OrionV2_1(
        mc, dedicatedServer, chunkSource, getChunkFuture, fullStatus, pollTask, mainThreadProcessor,
        dispatchThreads, maxInFlight, lockRadius, telemetry,
    )

    val chunkMspc = scheduler.chunkMspc

    fun fill(
        coords: List<Pair<Int, Int>>,
        onComplete: (cx: Int, cz: Int, success: Boolean, result: Any?, error: Any?) -> Unit = { _, _, _, _, _ -> },
    ): Result {
        val result = scheduler.fill(scatterSort(coords), onComplete)
        return Result(result.ok, result.failed, result.totalMs)
    }
}

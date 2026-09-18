package io.github.eath1283.worldgend

import javassist.CtClass
import javassist.CtField
import java.util.concurrent.atomic.AtomicBoolean

// v5.5: WorldGenRegion.getChunk re-resolves the neighbor through GenerationChunkHolder's
// AtomicReferenceArray + CompletableFuture.getNow on every block/biome read (~5% of worker
// samples in #69's profile). The holder a region sees for a slot can only change on FULL
// promotion, which needs this chunk's step to finish first for every chunk in its write radius,
// so memoizing per region instance is safe. The slot also records the highest status already
// validated, so a request for a later status still takes vanilla's path (and its crash report).
object RegionChunkMemo {
    const val RADIUS = 2
    private const val SIDE = 2 * RADIUS + 1
    private const val TARGET = "net.minecraft.server.level.WorldGenRegion"
    private val installed = AtomicBoolean()

    fun requireInstalled(loader: ClassLoader) {
        Class.forName(TARGET, false, loader)
        check(installed.get()) { "Orion v5.5 requires -javaagent:build/libs/orion-agent.jar (WorldGenRegion chunk memo missing)" }
    }

    fun patch(cc: CtClass) {
        val chunk = "net.minecraft.world.level.chunk.ChunkAccess"
        cc.addField(CtField.make("private $chunk[] orion\$memo;", cc))
        cc.addField(CtField.make("private int[] orion\$memoStatus;", cc))
        cc.addField(CtField.make("private int orion\$cx;", cc))
        cc.addField(CtField.make("private int orion\$cz;", cc))
        val pool = cc.classPool
        val getChunk = cc.getDeclaredMethod(
            "getChunk",
            arrayOf(CtClass.intType, CtClass.intType, pool.get("net.minecraft.world.level.chunk.status.ChunkStatus"), CtClass.booleanType),
        )
        // insertAfter first: it instruments every return, and the memo hit's early return must skip it.
        getChunk.insertAfter(
            """{
                if (${'$'}_ != null) {
                    if (this.orion${'$'}memo == null) {
                        net.minecraft.world.level.ChunkPos p = this.center.getPos();
                        this.orion${'$'}cx = p.x();
                        this.orion${'$'}cz = p.z();
                        this.orion${'$'}memoStatus = new int[$SIDE * $SIDE];
                        this.orion${'$'}memo = new $chunk[$SIDE * $SIDE];
                    }
                    int dx = ${'$'}1 - this.orion${'$'}cx + $RADIUS;
                    int dz = ${'$'}2 - this.orion${'$'}cz + $RADIUS;
                    if (dx >= 0 && dx < $SIDE && dz >= 0 && dz < $SIDE) {
                        int i = dx * $SIDE + dz;
                        int s = ${'$'}3.getIndex();
                        if (this.orion${'$'}memo[i] != ${'$'}_) {
                            this.orion${'$'}memo[i] = ${'$'}_;
                            this.orion${'$'}memoStatus[i] = s;
                        } else if (s > this.orion${'$'}memoStatus[i]) {
                            this.orion${'$'}memoStatus[i] = s;
                        }
                    }
                }
            }"""
        )
        getChunk.insertBefore(
            """{
                $chunk[] memo = this.orion${'$'}memo;
                if (memo != null) {
                    int dx = ${'$'}1 - this.orion${'$'}cx + $RADIUS;
                    int dz = ${'$'}2 - this.orion${'$'}cz + $RADIUS;
                    if (dx >= 0 && dx < $SIDE && dz >= 0 && dz < $SIDE) {
                        int i = dx * $SIDE + dz;
                        $chunk hit = memo[i];
                        if (hit != null && ${'$'}3.getIndex() <= this.orion${'$'}memoStatus[i]) return hit;
                    }
                }
            }"""
        )
        installed.set(true)
    }
}

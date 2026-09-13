package io.github.eath1283.worldgend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BlockStateSnapshotTest {
    class Section {
        val blocks = Array(4096) { "Block{minecraft:air}" }
        fun getBlockState(x: Int, y: Int, z: Int): String = blocks[(y * 16 + z) * 16 + x]
    }

    @Test
    fun hashesPositionsAndCountsEveryBlockInsteadOfPaletteEntries() {
        val snapshot = BlockStateSnapshot(Section::class.java.getMethod("getBlockState",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
        val section = Section()
        section.blocks[0] = "Block{minecraft:stone}"
        val first = snapshot.describe(arrayOf(section))
        assertEquals("minecraft:air=4095,minecraft:stone=1", first.substringAfter(' '))
        assertEquals(first, snapshot.describe(arrayOf(section)))
        section.blocks[0] = "Block{minecraft:air}"
        section.blocks[1] = "Block{minecraft:stone}"
        val moved = snapshot.describe(arrayOf(section))
        assertEquals(first.substringAfter(' '), moved.substringAfter(' '))
        assertNotEquals(first.substringBefore(' '), moved.substringBefore(' '))
        section.blocks[1] = "Block{minecraft:stone}[axis=x]"
        val propertyX = snapshot.describe(arrayOf(section))
        section.blocks[1] = "Block{minecraft:stone}[axis=y]"
        val propertyY = snapshot.describe(arrayOf(section))
        assertEquals(propertyX.substringAfter(' '), propertyY.substringAfter(' '))
        assertNotEquals(propertyX.substringBefore(' '), propertyY.substringBefore(' '))
        val duplicated = snapshot.describe(arrayOf(section, section))
        assertEquals("minecraft:air=8190,minecraft:stone=2", duplicated.substringAfter(' '))
    }
}

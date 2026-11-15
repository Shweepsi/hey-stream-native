package io.github.shweepsi.heystream.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferSizeTest {
    private val mib = 1024 * 1024
    private val gib = 1024L * mib

    @Test
    fun shieldGetsHalfItsHeap() {
        // Mesuré : tas de 192 Mo, ~2,9 Go de mémoire.
        assertEquals(96 * mib, BufferSize.bytesFor(192, 3_089_108_992L, lowRam = false))
    }

    @Test
    fun aTwoGigabyteBoxStaysAt64Megabytes() {
        assertEquals(64 * mib, BufferSize.bytesFor(192, 2 * gib, lowRam = false))
        assertEquals(64 * mib, BufferSize.bytesFor(256, 4 * gib, lowRam = true))
    }

    @Test
    fun aSmallHeapLowersTheCap() {
        assertEquals(48 * mib, BufferSize.bytesFor(96, 2 * gib, lowRam = false))
    }

    @Test
    fun theCapStaysBetween32And128Megabytes() {
        assertEquals(32 * mib, BufferSize.bytesFor(16, 2 * gib, lowRam = false))
        assertEquals(128 * mib, BufferSize.bytesFor(512, 8 * gib, lowRam = false))
    }

    /** Media3 refuse des durées incohérentes ; le zapping garde un démarrage court. */
    @Test
    fun durationsAreCoherent() {
        assertTrue(BufferSize.START_BUFFER_MS <= BufferSize.REBUFFER_MS)
        assertTrue(BufferSize.REBUFFER_MS <= BufferSize.MIN_BUFFER_MS)
        assertTrue(BufferSize.MIN_BUFFER_MS <= BufferSize.MAX_BUFFER_MS)
        assertEquals(1_000, BufferSize.START_BUFFER_MS)
    }
}

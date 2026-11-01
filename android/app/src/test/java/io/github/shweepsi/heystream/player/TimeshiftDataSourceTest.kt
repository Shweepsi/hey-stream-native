package io.github.shweepsi.heystream.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lecture du tampon du différé, sur de vrais fichiers et un index simulé. */
@RunWith(RobolectricTestRunner::class)
class TimeshiftDataSourceTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "hs-timeshift-${System.nanoTime()}").apply { mkdirs() }

    /** Index simulé : fichiers ajoutés par le test, comme la copie du cœur. */
    private inner class Buffer : TimeshiftFiles {
        val files = ConcurrentHashMap<Long, TimeshiftFile>()

        @Volatile var ended = false

        fun add(seq: Long, run: Long, bytes: ByteArray, complete: Boolean = true) {
            val file = File(dir, "%08d.ts".format(seq)).apply { writeBytes(bytes) }
            files[seq] = TimeshiftFile(seq, run, file.path, complete)
        }

        fun append(seq: Long, bytes: ByteArray, complete: Boolean) {
            val file = files.getValue(seq)
            File(file.path).appendBytes(bytes)
            files[seq] = file.copy(complete = complete)
        }

        fun trim(seq: Long) {
            files.remove(seq)?.let { File(it.path).delete() }
        }

        override fun file(seq: Long) = files[seq]

        override fun oldestSeq() = files.keys.minOrNull()

        override fun finished() = ended
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun bytes(from: Int, count: Int) = ByteArray(count) { (from + it).toByte() }

    private fun readAll(source: TimeshiftDataSource, position: Long = 0): ByteArray {
        source.open(DataSpec.Builder().setUri(Uri.fromFile(dir)).setPosition(position).build())
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(7)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        source.close()
        return out.toByteArray()
    }

    @Test
    fun theFilesOfARunAreReadAsOneStreamUntilTheRunEnds() {
        val buffer = Buffer()
        buffer.add(0, run = 1, bytes(0, 10))
        buffer.add(1, run = 1, bytes(10, 10))
        buffer.add(2, run = 1, bytes(20, 5))
        // Reconnexion : nouvelle série, ouverte à part par le lecteur.
        buffer.add(3, run = 2, bytes(100, 10))
        val seen = mutableListOf<Long>()
        val read = readAll(TimeshiftDataSource(buffer, startSeq = 0, startOffset = 3) { seen += it })
        assertArrayEquals(bytes(3, 22), read)
        assertEquals(listOf(0L, 1L, 2L), seen)
    }

    @Test
    fun aReopenedSourceSkipsWhatWasAlreadyRead() {
        val buffer = Buffer()
        buffer.add(0, run = 1, bytes(0, 10))
        buffer.add(1, run = 1, bytes(10, 10))
        val read = readAll(TimeshiftDataSource(buffer, startSeq = 0, startOffset = 2), position = 12)
        assertArrayEquals(bytes(14, 6), read)
    }

    @Test
    fun atTheLiveEdgeTheReadWaitsForTheNextData() {
        val buffer = Buffer()
        buffer.add(0, run = 1, bytes(0, 4), complete = false)
        val writer = thread {
            Thread.sleep(150)
            buffer.append(0, bytes(4, 4), complete = true)
            buffer.add(1, run = 1, bytes(8, 4), complete = false)
            Thread.sleep(150)
            buffer.append(1, bytes(12, 4), complete = true)
            buffer.ended = true
        }
        val read = readAll(TimeshiftDataSource(buffer, startSeq = 0, startOffset = 0))
        writer.join()
        assertArrayEquals(bytes(0, 16), read)
    }

    @Test
    fun aTrimmedStartIsReported() {
        val buffer = Buffer()
        buffer.add(0, run = 1, bytes(0, 4))
        buffer.add(1, run = 1, bytes(4, 4))
        buffer.trim(0)
        val source = TimeshiftDataSource(buffer, startSeq = 0, startOffset = 0)
        val error = runCatching { source.open(DataSpec(Uri.fromFile(dir))) }.exceptionOrNull()
        assertTrue(error is TimeshiftTrimmedException)
    }
}

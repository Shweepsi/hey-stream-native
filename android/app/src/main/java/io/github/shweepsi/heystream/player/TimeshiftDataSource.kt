package io.github.shweepsi.heystream.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** Un fichier du tampon du différé, tel que la lecture en a besoin. */
data class TimeshiftFile(
    val seq: Long,
    val run: Long,
    val path: String,
    val complete: Boolean,
    /** Réception du premier et du dernier octet (secondes Unix du tampon). */
    val start: Double = 0.0,
    val end: Double = 0.0,
)

/** Les fichiers du tampon (le cœur ; remplaçable dans les tests). */
interface TimeshiftFiles {
    /** Fichier [seq], `null` s'il est effacé ou pas encore commencé. */
    fun file(seq: Long): TimeshiftFile?

    /** Plus ancien fichier encore gardé. */
    fun oldestSeq(): Long?

    /** La copie est finie : plus rien ne s'ajoute. */
    fun finished(): Boolean
}

/** Le fichier du tampon a été effacé avant d'être lu (pause trop longue). */
class TimeshiftTrimmedException : IOException("fichier du différé effacé")

/**
 * Lecture du tampon du différé : les fichiers d'une même **série** (une
 * connexion) bout à bout, comme un seul flux, à partir de l'octet
 * [startOffset] du fichier [startSeq]. Au bout du dernier fichier en cours
 * d'écriture, la lecture attend la suite (direct) ; la fin de la série
 * (reconnexion : l'horloge du flux repart) termine la lecture, et le lecteur
 * ouvre la série suivante à part.
 *
 * [onFile] reçoit chaque fichier lu (position affichée, série suivante).
 * Media3 interrompt le fil de chargement pour arrêter : l'attente s'arrête
 * aussitôt.
 */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(
    private val files: TimeshiftFiles,
    private val startSeq: Long,
    private val startOffset: Long,
    private val onFile: (Long) -> Unit = {},
) : BaseDataSource(false) {
    private var handle: RandomAccessFile? = null
    private var current: TimeshiftFile? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        // Media3 peut rouvrir plus loin (après une erreur) : on avance d'autant.
        var seq = startSeq
        var offset = startOffset
        var skip = dataSpec.position
        var file = files.file(seq) ?: throw trimmedOrMissing(seq)
        while (skip > 0 && file.complete) {
            val length = File(file.path).length()
            val left = (length - offset).coerceAtLeast(0)
            if (skip < left) break
            skip -= left
            val next = files.file(seq + 1)?.takeIf { it.run == file.run } ?: break
            seq += 1
            offset = 0
            file = next
        }
        openFile(file, offset + skip)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    private fun openFile(file: TimeshiftFile, offset: Long) {
        handle?.close()
        val raf = try {
            RandomAccessFile(File(file.path), "r")
        } catch (_: java.io.FileNotFoundException) {
            throw TimeshiftTrimmedException()
        }
        raf.seek(offset)
        handle = raf
        current = file
        onFile(file.seq)
    }

    private fun trimmedOrMissing(seq: Long): IOException {
        val oldest = files.oldestSeq()
        return if (oldest != null && seq < oldest) TimeshiftTrimmedException() else IOException("fichier du différé absent")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val raf = handle ?: return C.RESULT_END_OF_INPUT
            val file = current ?: return C.RESULT_END_OF_INPUT
            val read = raf.read(buffer, offset, length)
            if (read > 0) {
                bytesTransferred(read)
                return read
            }
            // Fin du fichier : fini (état lu avant de relire) ou encore en écriture.
            val now = files.file(file.seq)
            val complete = now?.complete ?: true
            if (complete) {
                val last = raf.read(buffer, offset, length)
                if (last > 0) {
                    bytesTransferred(last)
                    return last
                }
                val next = files.file(file.seq + 1)
                // Fichier fini sans suivant de la même série : fin de la série.
                if (next == null || next.run != file.run) return C.RESULT_END_OF_INPUT
                openFile(next, 0)
                continue
            }
            if (files.finished()) {
                val last = raf.read(buffer, offset, length)
                if (last > 0) {
                    bytesTransferred(last)
                    return last
                }
                return C.RESULT_END_OF_INPUT
            }
            try {
                Thread.sleep(WAIT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            handle?.close()
        } finally {
            handle = null
            current = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val files: TimeshiftFiles,
        private val startSeq: Long,
        private val startOffset: Long,
        private val onFile: (Long) -> Unit = {},
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(files, startSeq, startOffset, onFile)
    }

    private companion object {
        /** Attente de nouvelles données au direct. */
        const val WAIT_MS = 100L
    }
}

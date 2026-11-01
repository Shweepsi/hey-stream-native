package io.github.shweepsi.heystream.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Lecture d'un enregistrement **en cours d'écriture** : en fin de fichier,
 * la lecture attend la suite tant que [growing] est vrai, puis s'arrête à
 * la vraie fin. La pause garde la place (le fichier continue de grandir) :
 * c'est un différé, sans seconde connexion au fournisseur.
 *
 * [startOffset] : octet de départ (près de la fin pour suivre le direct,
 * plus tôt pour revenir en arrière) ; le flux TS se resynchronise seul.
 *
 * Media3 interrompt le fil de chargement pour arrêter une lecture : l'attente
 * s'arrête alors aussitôt.
 */
@OptIn(UnstableApi::class)
class GrowingFileDataSource(
    private val growing: () -> Boolean,
    private val startOffset: Long,
) : BaseDataSource(false) {
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw java.io.FileNotFoundException()
        val handle = RandomAccessFile(File(path), "r")
        handle.seek(startOffset + dataSpec.position)
        file = handle
        opened = true
        transferStarted(dataSpec)
        return if (growing()) C.LENGTH_UNSET.toLong() else (handle.length() - handle.filePointer).coerceAtLeast(0)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val handle = file ?: return C.RESULT_END_OF_INPUT
        while (true) {
            val read = handle.read(buffer, offset, length)
            if (read > 0) {
                bytesTransferred(read)
                return read
            }
            if (!growing()) {
                // Dernières données écrites entre la lecture et la fin de la copie.
                val last = handle.read(buffer, offset, length)
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
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val growing: () -> Boolean, private val startOffset: Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = GrowingFileDataSource(growing, startOffset)
    }

    private companion object {
        /** Attente de nouvelles données en fin de fichier. */
        const val WAIT_MS = 250L
    }
}

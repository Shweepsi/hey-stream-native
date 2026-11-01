package io.github.shweepsi.heystream.recording

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import io.github.shweepsi.heystream.core.VolumeSpace
import io.github.shweepsi.heystream.core.volumeSpace
import java.io.File

/**
 * Disque où enregistrer : un dossier **privé à l'app** sur un volume
 * (`docs/DECISIONS.md`, « Fichiers privés à l'app ») : lisible seulement
 * dans Hey Stream, effacé à la désinstallation.
 */
data class RecordingVolume(
    /** Dossier des enregistrements sur ce volume. */
    val path: String,
    /** Nom du volume donné par Android (« Clé USB SanDisk ») ; `null` pour la mémoire interne. */
    val label: String?,
    val removable: Boolean,
    /** Taille et place libre ; `null` si Android ne répond pas. */
    val space: VolumeSpace?,
)

object RecordingVolumes {
    private const val FOLDER = "Recordings"

    /**
     * Volumes qui offrent un dossier privé à l'app, mémoire interne en tête.
     * Beaucoup d'appareils n'en offrent pas sur une clé USB (seulement sur
     * une carte SD ou un disque « adopté ») : elle n'apparaît alors pas.
     */
    fun list(context: Context): List<RecordingVolume> {
        val storage = context.getSystemService(StorageManager::class.java)
        val dirs = runCatching { context.getExternalFilesDirs(null).toList() }.getOrDefault(emptyList())
            .filterNotNull()
            .filter { runCatching { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }.getOrDefault(false) }
        val volumes = dirs.map { dir ->
            val volume = runCatching { storage?.getStorageVolume(dir) }.getOrNull()
            val folder = File(dir, FOLDER)
            RecordingVolume(
                path = folder.absolutePath,
                label = volume?.takeIf { !it.isPrimary }?.getDescription(context),
                removable = volume?.isRemovable ?: false,
                space = volumeSpace(dir.absolutePath),
            )
        }
        // Aucun volume externe monté (rare) : la mémoire interne de l'app.
        return volumes.ifEmpty {
            val folder = File(context.filesDir, FOLDER)
            listOf(RecordingVolume(folder.absolutePath, label = null, removable = false, space = volumeSpace(context.filesDir.absolutePath)))
        }
    }
}

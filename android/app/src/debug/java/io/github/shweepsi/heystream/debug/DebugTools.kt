package io.github.shweepsi.heystream.debug

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.CaptureSummary
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.Lucide
import io.github.shweepsi.heystream.ui.toUiText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outils de l'APK debug, absents de l'APK release (voir `src/release`). */
object DebugTools {
    /** Bloc ajouté en bas de Réglages › Catalogue. */
    val catalogSettings: (@Composable () -> Unit)? = { CaptureTool() }
}

private sealed interface CaptureState {
    data object Idle : CaptureState

    data object Running : CaptureState

    /** [download] : nom de la copie dans Téléchargements, `null` si elle a échoué. */
    data class Done(val file: File, val summary: CaptureSummary, val download: String?) : CaptureState

    data class Failed(val error: Throwable) : CaptureState
}

/**
 * Capture des réponses du panneau (cœur : `capture`), pour les réponses
 * simulées des tests. Le fichier est partagé (Drive, e-mail…) et copié dans
 * Téléchargements, faute d'app de partage sur la Mi Box.
 */
@Composable
private fun CaptureTool() {
    val session = appGraph().session
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CaptureState>(CaptureState.Idle) }

    Text(
        stringResource(R.string.debug_capture_title),
        style = Hs.type.itemTitle,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.debug_capture_intro), style = Hs.type.body, color = Hs.colors.textSecondary)
    HsButton(
        text = stringResource(if (state == CaptureState.Running) R.string.debug_capture_running else R.string.debug_capture_start),
        onClick = {
            if (state == CaptureState.Running) return@HsButton
            state = CaptureState.Running
            scope.launch {
                state = try {
                    val file = captureFile(context)
                    val summary = session.withCore { it.capture(file.absolutePath) }
                    CaptureState.Done(file, summary, withContext(Dispatchers.IO) { copyToDownloads(context, file) })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    CaptureState.Failed(failure)
                }
            }
        },
        icon = Lucide.RefreshCw,
    )
    when (val current = state) {
        is CaptureState.Done -> {
            Text(
                stringResource(
                    R.string.debug_capture_done,
                    current.summary.requests.toInt(),
                    current.summary.failed.toInt(),
                    Formatter.formatShortFileSize(context, current.summary.bytes.toLong()),
                    current.download ?: "-",
                ),
                style = Hs.type.body,
            )
            HsButton(
                text = stringResource(R.string.debug_capture_share),
                onClick = { share(context, current.file) },
                style = ButtonStyle.Primary,
            )
        }
        is CaptureState.Failed -> Text(
            stringResource(R.string.debug_capture_failed, current.error.toUiText().resolve()),
            style = Hs.type.body,
            color = Hs.colors.textSecondary,
        )
        else -> {}
    }
}

/** Fichier neuf dans `cache/capture/`, les captures précédentes effacées. */
private suspend fun captureFile(context: Context): File = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "capture").apply {
        deleteRecursively()
        mkdirs()
    }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
    File(dir, "hey-stream-capture-$stamp.json")
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.capture", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.debug_capture_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Copie dans Téléchargements (sans permission depuis Android 10) ; nom du fichier, ou `null`. */
private fun copyToDownloads(context: Context, file: File): String? = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    file.name
}.getOrNull()

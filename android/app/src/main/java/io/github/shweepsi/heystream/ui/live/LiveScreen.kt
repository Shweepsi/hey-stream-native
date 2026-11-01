package io.github.shweepsi.heystream.ui.live

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.recording.RecordingScheduler
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.shweepsi.heystream.player.VideoSurface
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.ParentalPinDialog
import io.github.shweepsi.heystream.ui.components.rememberNow
import io.github.shweepsi.heystream.ui.theme.Hs

/**
 * Direct : catégories | chaînes | aperçu vidéo et programme. [initialChannelId]
 * (depuis la recherche) ouvre cette chaîne directement en plein écran.
 */
@Composable
fun LiveScreen(initialChannelId: Long?, onBack: () -> Unit, onOpenRecording: (Long) -> Unit = {}) {
    val graph = appGraph()
    val player = graph.player
    val context = LocalContext.current
    val viewModel = viewModel {
        LiveViewModel(
            graph.session,
            graph.player,
            initialChannelId,
            recordings = graph.recordings,
            canScheduleExact = { RecordingScheduler.canScheduleExact(context.applicationContext) },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openRecording by viewModel.openRecording.collectAsStateWithLifecycle()
    LaunchedEffect(openRecording) {
        openRecording?.let {
            viewModel.onRecordingOpened()
            onOpenRecording(it)
        }
    }
    // Notifications (Android 13+) : demandées à la première programmation.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val channels = viewModel.channels.collectAsLazyPagingItems()
    val now by rememberNow()

    // Quitter le direct ferme le flux (une seule connexion par abonnement).
    val leave = remember(viewModel, onBack) {
        {
            viewModel.onLeave()
            onBack()
        }
    }
    BackHandler {
        if (viewModel.state.value.fullscreen) viewModel.onExitFullscreen() else leave()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onLeave() }
    }
    LaunchedEffect(viewModel) { viewModel.resumeIfStopped() }
    if (!Hs.isTv) HideSystemBars(state.fullscreen)

    // Écran allumé seulement pendant la lecture (la mise en veille reste possible sans flux).
    val keepScreenOn = state.playback.let { it is LivePlayback.Playing || it is LivePlayback.Reconnecting }
    LiveContent(
        state = state,
        channels = channels,
        nowSecs = now / 1000,
        actions = viewModel,
        onBack = leave,
        video = { modifier -> VideoSurface(player.player, modifier, keepScreenOn = keepScreenOn) },
    )

    state.recordDialog?.let { RecordDialog(it, viewModel) }
    state.recordingOptions?.let { RecordingOptionsDialog(it, viewModel) }
    val scheduledOnce = state.message?.id == R.string.record_scheduled
    LaunchedEffect(scheduledOnce) {
        if (scheduledOnce && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    if (state.alarmPrompt) {
        ExactAlarmDialog(
            onOpen = {
                viewModel.onAlarmPromptDone()
                openExactAlarmSettings(context)
            },
            onDismiss = viewModel::onAlarmPromptDone,
        )
    }

    state.pinRequest?.let { request ->
        key(request) {
            ParentalPinDialog(
                purpose = request.purpose,
                onSuccess = viewModel::onPinSuccess,
                onDismiss = viewModel::onPinDismiss,
            )
        }
    }
}

/** Réglage « Alarmes et rappels » de l'app (Android 12+). */
fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Plein écran au doigt : barres du système masquées (un glissement les montre). */
@Composable
private fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = view.context.findActivity()?.window
        if (!hidden || window == null) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

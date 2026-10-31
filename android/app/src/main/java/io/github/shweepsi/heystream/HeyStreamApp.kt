package io.github.shweepsi.heystream

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.shweepsi.heystream.data.AppUpdater
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.data.CrashReports
import io.github.shweepsi.heystream.data.CredentialStore
import io.github.shweepsi.heystream.data.RefreshWorker
import io.github.shweepsi.heystream.data.Telemetry
import io.github.shweepsi.heystream.data.TelemetryWorker
import io.github.shweepsi.heystream.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

class HeyStreamApp : Application(), SingletonImageLoader.Factory {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Le cœur de l'abonnement (un seul pour toute l'app). */
    val session: CoreSession by lazy { CoreSession(this, CredentialStore(this), scope) }

    /** Le lecteur vidéo (un seul pour toute l'app : jamais deux flux). */
    val player: PlayerController by lazy { PlayerController(this) }

    /** Mises à jour de l'app depuis les releases GitHub. */
    val updater: AppUpdater by lazy { AppUpdater(this, scope) }

    override fun onCreate() {
        super.onCreate()
        CrashReports.start(this)
        Telemetry.start(this)
        RefreshWorker.schedule(this)
        TelemetryWorker.schedule(this)
    }

    /**
     * Images du fournisseur : cache mémoire plafonné à 48 Mo et disque à
     * 150 Mo (docs/MATERIEL.md), images à la taille affichée.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "HeyStream/${BuildConfig.VERSION_NAME}").build())
            }
            .build()
        return ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(48L * 1024 * 1024).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}

package io.github.shweepsi.heystream.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.shweepsi.heystream.HeyStreamApp
import io.github.shweepsi.heystream.core.HeyStreamException
import java.util.concurrent.TimeUnit

/**
 * Rafraîchissement quotidien du catalogue en arrière-plan (spec : une fois
 * par jour). Le cœur ne retélécharge que les sections de plus de 24 h.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val session = (applicationContext as HeyStreamApp).session
        session.start()
        if (session.state.value != SessionState.Ready) return Result.success()
        return try {
            session.refreshNow(force = false)
            Result.success()
        } catch (_: HeyStreamException.Network) {
            Result.retry()
        } catch (_: Throwable) {
            Result.success()
        }
    }

    companion object {
        private const val NAME = "catalog-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

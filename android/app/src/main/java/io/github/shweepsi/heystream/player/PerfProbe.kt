package io.github.shweepsi.heystream.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Request
import okhttp3.Response

/**
 * Mesures de l'ouverture d'un flux dans le journal Android, pour les essais
 * sur appareil : `adb shell setprop log.tag.HSPerf D`. Des durées seulement,
 * depuis la demande d'ouverture : jamais d'adresse, de chaîne ni d'identifiant.
 * Coupé (par défaut), rien n'est écrit.
 */
@OptIn(UnstableApi::class)
internal class PerfProbe {
    @Volatile private var enabled = false

    @Volatile private var t0 = 0L

    @Volatile private var seq = 0

    @Volatile private var firstBytes = false

    /** Nouvelle ouverture : le chronomètre repart. */
    fun open() {
        enabled = Log.isLoggable(TAG, Log.DEBUG)
        if (!enabled) return
        seq += 1
        firstBytes = false
        t0 = SystemClock.elapsedRealtime()
        Log.d(TAG, "zap $seq +0 open")
    }

    fun mark(event: String) {
        if (enabled && t0 != 0L) Log.d(TAG, "zap $seq +${SystemClock.elapsedRealtime() - t0} $event")
    }

    /** Requête HTTP : début, en-têtes reçus (redirection comprise), premiers octets. */
    val transfer = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (isNetwork) mark("http_init")
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (isNetwork) mark("http_start")
        }

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork && !firstBytes) {
                firstBytes = true
                mark("http_first_bytes")
            }
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    /**
     * Essai OkHttp : chaque saut (redirection du panneau, puis serveur de flux),
     * numéroté ; connexion neuve ou réutilisée. Codes HTTP et durées seulement.
     */
    fun okHttpEvents(): EventListener = object : EventListener() {
        private var hop = 0

        override fun callStart(call: Call) {
            hop = 0
            mark("ok_call")
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = mark("ok_dns h$hop")

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = mark("ok_connect h$hop")

        override fun secureConnectEnd(call: Call, handshake: Handshake?) = mark("ok_tls h$hop")

        override fun connectionAcquired(call: Call, connection: Connection) = mark("ok_conn h$hop")

        override fun requestHeadersEnd(call: Call, request: Request) = mark("ok_req h$hop")

        override fun responseHeadersEnd(call: Call, response: Response) {
            mark("ok_resp h$hop code=${response.code}")
            hop += 1
        }
    }

    companion object {
        const val TAG = "HSPerf"

        /**
         * Variante d'essai, lue à la création du lecteur :
         * `adb shell setprop log.tag.<nom> D`, puis relancer l'app.
         */
        fun flag(name: String): Boolean = Log.isLoggable(name, Log.DEBUG)
    }
}

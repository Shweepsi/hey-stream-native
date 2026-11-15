package io.github.shweepsi.heystream.player

import android.app.ActivityManager
import android.content.Context

/**
 * Plafond du tampon de lecture, selon l'appareil.
 *
 * Le tampon de Media3 vit dans le tas Java, limité par appareil
 * (`memoryClass`, 192 Mo sur une Shield). Sans plafond, un film 4K y prenait
 * 161 Mo, au bord du manque de mémoire. Le plafond l'emporte sur les durées
 * de tampon (réglage par défaut de Media3) : à débit élevé, on garde moins de
 * secondes plutôt que de risquer un plantage.
 */
object BufferSize {
    /** Durées de tampon, en ms (voir `DefaultLoadControl.Builder.setBufferDurationsMs`). */
    data class Durations(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int)

    /** Réglage de Media3 d'origine : zapping rapide, tampon généreux (Wi-Fi). */
    val STANDARD = Durations(minMs = 15_000, maxMs = 50_000, startMs = 1_000, rebufferMs = 2_500)

    /**
     * Petits appareils (TV modestes) : après une coupure, la lecture attendait 2,5 s de tampon puis
     * recalait aussitôt (une coupure de 3 s toutes les 40 s sur une TV Philips). Reprise à 8 s,
     * chargement relancé plus tôt (30 s) ; le démarrage reste à 1 s (zapping inchangé).
     */
    val SMALL_DEVICE = Durations(minMs = 30_000, maxMs = 60_000, startMs = 1_000, rebufferMs = 8_000)

    private const val MIB = 1024 * 1024

    /** Jamais moins : ~25 s d'un flux HD à 10 Mbit/s. */
    const val MIN_BYTES = 32 * MIB

    /** Box modeste (≤ 2,5 Go de mémoire ou « low RAM ») : Mi Box S 2. */
    const val SMALL_DEVICE_BYTES = 64 * MIB

    /** Jamais plus, même avec un grand tas. */
    const val MAX_BYTES = 128 * MIB

    private const val SMALL_DEVICE_RAM = 5L * 512 * MIB // 2,5 Go

    /**
     * La moitié du tas de l'app, plafonnée à 64 Mo sur une box modeste, et
     * bornée entre 32 et 128 Mo. Exemples : Shield (tas 192 Mo, 3 Go) → 96 Mo,
     * soit ~13 s d'une 4K à 60 Mbit/s ; box de 2 Go → 64 Mo au plus.
     */
    /** Petit appareil : peu de mémoire vive, ou déclaré « low RAM » par Android. */
    fun isSmall(totalRamBytes: Long, lowRam: Boolean): Boolean = lowRam || totalRamBytes <= SMALL_DEVICE_RAM

    fun durationsFor(totalRamBytes: Long, lowRam: Boolean): Durations =
        if (isSmall(totalRamBytes, lowRam)) SMALL_DEVICE else STANDARD

    fun bytesFor(memoryClassMb: Int, totalRamBytes: Long, lowRam: Boolean): Int {
        val half = memoryClassMb.toLong() * MIB / 2
        val small = isSmall(totalRamBytes, lowRam)
        val capped = if (small) minOf(half, SMALL_DEVICE_BYTES.toLong()) else half
        return capped.coerceIn(MIN_BYTES.toLong(), MAX_BYTES.toLong()).toInt()
    }

    fun bytesFor(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return bytesFor(am.memoryClass, info.totalMem, am.isLowRamDevice)
    }

    fun durationsFor(context: Context): Durations {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return durationsFor(info.totalMem, am.isLowRamDevice)
    }
}

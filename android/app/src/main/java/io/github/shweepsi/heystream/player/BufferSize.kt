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
    /** Tampon sous lequel le chargement reprend, en ms. */
    const val MIN_BUFFER_MS = 30_000

    /** Tampon au-delà duquel le chargement s'arrête, en ms. */
    const val MAX_BUFFER_MS = 60_000

    /** Tampon qui lance la lecture au démarrage (zapping rapide), en ms. */
    const val START_BUFFER_MS = 1_000

    /**
     * Tampon exigé pour reprendre après une coupure, en ms. 2,5 s ne tenaient pas : sur une
     * connexion juste, la lecture repartait puis recalait aussitôt (une coupure de 3 s toutes
     * les 40 s sur une TV Philips). Attendre plus longtemps donne moins de coupures, plus calmes.
     */
    const val REBUFFER_MS = 8_000

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
    fun bytesFor(memoryClassMb: Int, totalRamBytes: Long, lowRam: Boolean): Int {
        val half = memoryClassMb.toLong() * MIB / 2
        val small = lowRam || totalRamBytes <= SMALL_DEVICE_RAM
        val capped = if (small) minOf(half, SMALL_DEVICE_BYTES.toLong()) else half
        return capped.coerceIn(MIN_BYTES.toLong(), MAX_BYTES.toLong()).toInt()
    }

    fun bytesFor(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return bytesFor(am.memoryClass, info.totalMem, am.isLowRamDevice)
    }
}

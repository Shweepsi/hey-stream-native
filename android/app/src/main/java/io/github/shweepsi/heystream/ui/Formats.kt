package io.github.shweepsi.heystream.ui

import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Formats de dates et de durées, selon la langue de l'app. Le cœur ne
 * produit aucun texte : tout ce qui s'affiche se met en forme ici ou dans
 * les ressources.
 */
object Formats {
    /**
     * Nom d'un film, d'une série ou d'une chaîne sans le préfixe de langue que
     * les fournisseurs ajoutent (« FR - », « |FR| », « VOSTFR: »). Les noms de
     * catégories ne passent pas par ici. Rien n'est retiré si le nom ne
     * contient que le préfixe.
     */
    fun displayName(raw: String): String {
        val stripped = LANGUAGE_PREFIX.replaceFirst(raw, "").trim()
        return stripped.ifEmpty { raw.trim() }
    }

    private val LANGUAGE_PREFIX = Regex(
        """^\s*(?:\|\s*[A-Za-z]{2,6}\s*\|\s*|(?:FR|EN|UK|US|BE|CH|CA|QC|DE|ES|IT|PT|NL|AR|TR|VF|VO|VOST|VOSTFR|MULTI)\s*[-:|]\s+)""",
    )

    /** Heure (« 21:47 », « 9:47 PM »). */
    fun time(epochMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** Heure d'un programme, à partir de secondes Unix. */
    fun timeOfSeconds(epochSecs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        time(epochSecs * 1000, locale, zone)

    /** Jour et date sans l'année (« mercredi 28 octobre »). */
    fun dayAndDate(epochMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }

    /** Date complète (« 14 mars 2027 »), à partir de secondes Unix. */
    fun longDate(epochSecs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
            .format(Instant.ofEpochSecond(epochSecs).atZone(zone))

    /** Jours entre la date de [epochSecs] et celle de [nowSecs] (0 : aujourd'hui, 1 : hier). */
    fun daysAgo(epochSecs: Long, nowSecs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        java.time.temporal.ChronoUnit.DAYS.between(
            Instant.ofEpochSecond(epochSecs).atZone(zone).toLocalDate(),
            Instant.ofEpochSecond(nowSecs).atZone(zone).toLocalDate(),
        )

    /** Position de lecture (« 1:07:20 », « 7:05 »). */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** Heures et minutes d'une durée en secondes, arrondie à la minute. */
    fun hoursMinutes(durationSecs: Long): Pair<Long, Long> {
        val minutes = (durationSecs.coerceAtLeast(0) + 30) / 60
        return minutes / 60 to minutes % 60
    }

    /** Minutes restantes, arrondies au-dessus (au moins 1). */
    fun remainingMinutes(positionMs: Long, durationMs: Long): Long =
        ((durationMs - positionMs).coerceAtLeast(0) + 59_999) / 60_000

    /** Avancement d'un programme ou d'une lecture, entre 0 et 1. */
    fun fraction(position: Long, duration: Long): Float =
        if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
}

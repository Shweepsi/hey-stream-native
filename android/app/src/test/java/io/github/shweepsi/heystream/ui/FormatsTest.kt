package io.github.shweepsi.heystream.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.AccountInfo
import io.github.shweepsi.heystream.ui.components.initials
import io.github.shweepsi.heystream.ui.home.Subscription
import io.github.shweepsi.heystream.ui.home.subscriptionOf
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormatsTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun playbackPositions() {
        assertEquals("0:00", Formats.clock(0))
        assertEquals("7:05", Formats.clock(425_000))
        assertEquals("1:07:20", Formats.clock(4_040_000))
        assertEquals("0:00", Formats.clock(-5))
    }

    @Test
    fun durationsRoundToTheMinute() {
        assertEquals(1L to 52L, Formats.hoursMinutes(6_720))
        assertEquals(0L to 45L, Formats.hoursMinutes(2_690))
        assertEquals(2L to 0L, Formats.hoursMinutes(7_190))
    }

    @Test
    fun remainingMinutesRoundUp() {
        assertEquals(45L, Formats.remainingMinutes(4_040_000, 6_740_000))
        assertEquals(1L, Formats.remainingMinutes(9_000, 10_000))
        assertEquals(0L, Formats.remainingMinutes(10_000, 10_000))
    }

    @Test
    fun fractionStaysWithinBounds() {
        assertEquals(0.25f, Formats.fraction(25, 100))
        assertEquals(1f, Formats.fraction(150, 100))
        assertEquals(0f, Formats.fraction(10, 0))
    }

    @Test
    fun datesFollowTheLanguage() {
        val march14 = 1_805_014_800L // 14 mars 2027, 10 h à Paris
        assertEquals("14 mars 2027", Formats.longDate(march14, Locale.FRANCE, paris))
        assertEquals("March 14, 2027", Formats.longDate(march14, Locale.US, paris))
        assertEquals("10:00", Formats.timeOfSeconds(march14, Locale.FRANCE, paris))
        assertEquals("dimanche 14 mars", Formats.dayAndDate(march14 * 1000, Locale.FRANCE, paris))
    }

    @Test
    fun subscriptionWording() {
        fun account(expires: Long?) = AccountInfo(expires, 1, 0, 0)
        assertNull(subscriptionOf(null, 100))
        assertEquals(Subscription.Unlimited, subscriptionOf(account(null), 100))
        assertEquals(Subscription.ValidUntil(200), subscriptionOf(account(200), 100))
        assertEquals(Subscription.Expired(100), subscriptionOf(account(100), 100))
    }

    @Test
    fun channelInitials() {
        assertEquals("IM", initials("Info Monde"))
        assertEquals("F2", initials("France 2"))
        assertEquals("FC", initials("|FR| Canal"))
        assertEquals("", initials(""))
    }

    @Test
    fun daysAgoCountsCalendarDaysInTheLocalZone() {
        // 29 octobre 2026, 0:30 et 23:30 à Paris (UTC+1).
        val earlyMorning = 1_793_230_200L
        val lateEvening = earlyMorning + 23 * 3600
        assertEquals(0L, Formats.daysAgo(earlyMorning, lateEvening, paris))
        // 28 octobre 23:30 → 29 octobre 0:30 : hier, une heure plus tôt.
        assertEquals(1L, Formats.daysAgo(earlyMorning - 3600, earlyMorning, paris))
        assertEquals(3L, Formats.daysAgo(earlyMorning - 3 * 86_400, earlyMorning, paris))
    }

    @Test
    fun displayNameDropsTheProviderLanguagePrefix() {
        assertEquals("To Philly with Love (2026) FHD", Formats.displayName("FR - To Philly with Love (2026) FHD"))
        assertEquals("canal+ FOOT UHD", Formats.displayName("|FR| canal+ FOOT UHD"))
        assertEquals("CANAL+ SPORT", Formats.displayName("|FR|  CANAL+ SPORT"))
        assertEquals("Dune", Formats.displayName("VOSTFR: Dune"))
        assertEquals("Gravity (2013)", Formats.displayName("FR -4K Gravity (2013)"))
        assertEquals("Apollo 13", Formats.displayName("FR -4KL Apollo 13"))
        assertEquals("Argo", Formats.displayName("FR - 4K Argo"))
        assertEquals("IT - Chapitre 2", Formats.displayName("IT - Chapitre 2"))
        assertEquals("The Crown (2016)", Formats.displayName("The Crown (2016) FR"))
        assertEquals("Bad Blood (2017)", Formats.displayName("Bad Blood (2017)_fr"))
        assertEquals("When They See Us (2019)", Formats.displayName("When They See Us (2019)-fr"))
        assertEquals("Affection (2026) MULTI", Formats.displayName("FR - Affection (2026) MULTI"))
        // Titres sans préfixe de langue : inchangés.
        assertEquals("OSS 117 - Le Caire", Formats.displayName("OSS 117 - Le Caire"))
        assertEquals("France 2", Formats.displayName("France 2"))
        assertEquals("FR -", Formats.displayName("FR -"))
    }
}

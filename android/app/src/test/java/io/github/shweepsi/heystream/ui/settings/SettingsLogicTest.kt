package io.github.shweepsi.heystream.ui.settings

import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.AccountInfo
import io.github.shweepsi.heystream.core.CategoryEntry
import io.github.shweepsi.heystream.core.HeyStreamException
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.core.SectionRefresh
import io.github.shweepsi.heystream.data.RefreshState
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.home.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    @Test
    fun languageFromAppCompatTags() {
        assertEquals(LanguageChoice.System, LanguageChoice.of(""))
        assertEquals(LanguageChoice.French, LanguageChoice.of("fr"))
        assertEquals(LanguageChoice.French, LanguageChoice.of("fr-FR"))
        assertEquals(LanguageChoice.English, LanguageChoice.of("en-US,fr"))
        assertEquals(LanguageChoice.System, LanguageChoice.of("de"))
        assertEquals("", LanguageChoice.System.tags)
    }

    @Test
    fun sectionOutcomes() {
        assertEquals(RefreshOutcome.Updated, refresh(refreshed = true, items = 3_330u).toResult().outcome)
        assertEquals(3_330L, refresh(refreshed = true, items = 3_330u).toResult().items)
        assertEquals(RefreshOutcome.KeptCache, refresh(keptCache = true).toResult().outcome)
        assertEquals(RefreshOutcome.Failed, refresh(failed = true).toResult().outcome)
        assertEquals(RefreshOutcome.UpToDate, refresh().toResult().outcome)
    }

    @Test
    fun catalogResultOnlyAfterAManualUpdate() {
        val done = RefreshState.Done(listOf(refresh(refreshed = true, items = 12u)))
        assertEquals(CatalogUi(running = true), catalogUiOf(RefreshState.Running, requested = false))
        assertEquals(CatalogUi(), catalogUiOf(done, requested = false))
        assertEquals(listOf(SectionResult(Section.LIVE, RefreshOutcome.Updated, 12)), catalogUiOf(done, requested = true).results)
        assertEquals(CatalogUi(), catalogUiOf(RefreshState.Failed(HeyStreamException.Network()), requested = false))
        assertEquals(UiText(R.string.error_network), catalogUiOf(RefreshState.Failed(HeyStreamException.Network()), requested = true).error)
        assertEquals(CatalogUi(), catalogUiOf(RefreshState.Idle, requested = true))
    }

    @Test
    fun accountLikeTheHomeScreen() {
        val now = 1_800_000_000L
        assertEquals(AccountUi(), accountUiOf(null, now))
        val unlimited = accountUiOf(AccountInfo(expiresAt = null, maxConnections = 1, activeConnections = 0, checkedAt = now), now)
        assertEquals(Subscription.Unlimited, unlimited.subscription)
        assertEquals(1L, unlimited.maxConnections)
        val expired = accountUiOf(AccountInfo(expiresAt = now - 10, maxConnections = 0, activeConnections = null, checkedAt = now), now)
        assertTrue(expired.subscription is Subscription.Expired)
        assertNull(expired.maxConnections)
        val valid = accountUiOf(AccountInfo(expiresAt = now + 10, maxConnections = null, activeConnections = null, checkedAt = now), now)
        assertEquals(Subscription.ValidUntil(now + 10), valid.subscription)
    }

    @Test
    fun categoryToggleKeepsLockAndHidden() {
        val toggle = CategoryEntry(id = "9", name = "Adultes", isAdult = true, locked = true, hidden = false).toToggle()
        assertEquals(CategoryToggle("9", "Adultes", hidden = false, locked = true), toggle)
    }

    private fun refresh(refreshed: Boolean = false, keptCache: Boolean = false, failed: Boolean = false, items: ULong = 0u) =
        SectionRefresh(section = Section.LIVE, refreshed = refreshed, keptCache = keptCache, failed = failed, items = items)
}

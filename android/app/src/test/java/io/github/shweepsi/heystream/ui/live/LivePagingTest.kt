package io.github.shweepsi.heystream.ui.live

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.data.ParentalFakeCore
import io.github.shweepsi.heystream.data.fakeSession
import io.github.shweepsi.heystream.player.PlayerController
import io.github.shweepsi.heystream.player.runMainUntil
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** Chaque catégorie de Direct s'ouvre avec la vraie configuration de pagination (sauts compris). */
@RunWith(AndroidJUnit4::class)
class LivePagingTest {
    private val core = ParentalFakeCore().apply { add(Section.LIVE, 1L, "Info continue") }
    private val session = fakeSession(core)
    private val controller = PlayerController(RuntimeEnvironment.getApplication())

    @After
    fun release() {
        controller.release()
    }

    private fun open(key: CategoryKey) {
        val viewModel = LiveViewModel(session, controller, initialChannelId = null)
        runMainUntil { viewModel.state.value.categories is CategoriesUi.Ready }
        viewModel.onSelectCategory(LiveCategory(key, name = null, count = null))
        var done = false
        var error: Throwable? = null
        viewModel.viewModelScope.launch {
            error = runCatching { viewModel.channels.first() }.exceptionOrNull()
            done = true
        }
        runMainUntil { done }
        viewModel.viewModelScope.cancel()
        assertNull(error)
    }

    @Test
    fun recentsOpen() = open(CategoryKey.Recents)

    @Test
    fun favoritesOpen() = open(CategoryKey.Favorites)

    @Test
    fun providerCategoryOpens() = open(CategoryKey.Provider("tous"))
}

package io.github.shweepsi.heystream.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.theme.HeyStreamTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** Télécommande et doigt sur le contenu du direct (fausses données, actions enregistrées). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LiveInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = object : LiveActions {
        override fun onChannelClick(channel: CatalogItem) {
            calls += "click ${channel.id}"
        }

        override fun onToggleFavorite(channel: CatalogItem) {
            calls += "favorite ${channel.id}"
        }

        override fun onSelectCategory(category: LiveCategory) {
            calls += "category ${category.key.listKey}"
        }

        override fun onFullscreenKey(ok: Boolean) {
            calls += if (ok) "fullscreen ok" else "fullscreen info"
        }

        override fun onVideoClick() {
            calls += "video"
        }

        override fun onReloadCategories() {
            calls += "reload"
        }
    }

    private fun channel(id: Long, name: String) = CatalogItem(
        section = Section.LIVE,
        id = id,
        name = name,
        image = null,
        categoryId = "news",
        extension = null,
        epgChannelId = null,
        rating = null,
        plot = null,
        number = 100 + id,
        progress = null,
        watched = false,
    )

    private val channels = listOf(channel(1, "Info Monde"), channel(2, "Info Continu"), channel(3, "Éco Direct"))

    private val state = LiveUiState(
        categories = CategoriesUi.Ready(liveCategories(emptyList(), emptyMap(), favorites = 3, recents = 0) + LiveCategory(CategoryKey.Provider("news"), "Information", 3)),
        selected = CategoryKey.Provider("news"),
        preview = LivePreview(id = 1, name = "Info Monde"),
    )

    private fun show(device: Device, state: LiveUiState) {
        RuntimeEnvironment.setQualifiers(device.qualifiers)
        compose.setContent {
            val flow = remember { flowOf(PagingData.from(channels, sourceLoadStates = Loaded)) }
            val content = @Composable {
                HeyStreamTheme(isTv = device.isTv) {
                    LiveContent(
                        state = state,
                        channels = flow.collectAsLazyPagingItems(),
                        nowSecs = 0,
                        actions = actions,
                        onBack = {},
                        video = { modifier -> Box(modifier) },
                    )
                }
            }
            if (device.isTv) {
                CompositionLocalProvider(LocalInputModeManager provides KeyboardInput, content = content)
            } else {
                content()
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun remoteStartsOnTheFirstChannel() {
        show(Device.Tv, state)
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("click 1", "click 2"), calls)
    }

    @Test
    fun menuKeyTogglesTheFavorite() {
        show(Device.Tv, state)
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.Menu) }
        assertEquals(listOf("favorite 1"), calls)
    }

    @Test
    fun fullscreenNeverZapsAndShowsTheBanner() {
        show(Device.Tv, state.copy(fullscreen = true))
        val focused = compose.onNode(isFocused())
        focused.performKeyInput { pressKey(Key.DirectionDown) }
        focused.performKeyInput { pressKey(Key.DirectionRight) }
        focused.performKeyInput { pressKey(Key.DirectionUp) }
        focused.performKeyInput { pressKey(Key.DirectionCenter) }
        // Ni changement de chaîne ni de catégorie : seulement le bandeau, puis OK.
        assertEquals(listOf("fullscreen info", "fullscreen info", "fullscreen ok"), calls)
    }

    @Test
    fun menuAndSpaceAreIgnoredInFullscreen() {
        show(Device.Tv, state.copy(fullscreen = true))
        val focused = compose.onNode(isFocused())
        // Ni favori ni lecture pour la ligne cachée derrière la vidéo.
        focused.performKeyInput { pressKey(Key.Menu) }
        focused.performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun channelFromSearchHasTheFocusBehindTheFullscreen() {
        // Chaîne venue de la recherche : Retour quitte le plein écran sur elle, pas sur la première.
        show(Device.Tv, state.copy(preview = LivePreview(id = 3, name = "Éco Direct"), fullscreen = true))
        compose.onNode(isFocused()).assert(hasText("Éco Direct"))
    }

    @Test
    fun okOnTheFocusedChannelInPreviewGoesFullscreen() {
        show(Device.Tv, state.copy(preview = LivePreview(id = 3, name = "Éco Direct")))
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("click 3"), calls)
    }

    @Test
    fun failedCategoriesFocusTryAgain() {
        show(Device.Tv, LiveUiState(categories = CategoriesUi.Failed(UiText(R.string.error_storage))))
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("reload"), calls)
    }

    @Test
    fun touchTapPreviewsAndLongPressTogglesFavorite() {
        show(Device.Tablet, state)
        compose.onNodeWithText("Info Continu").performClick()
        compose.onNodeWithText("Éco Direct").performTouchInput { longClick() }
        assertEquals(listOf("click 2", "favorite 3"), calls)
    }

    private companion object {
        val Loaded = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = true),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )

        val KeyboardInput = object : InputModeManager {
            override val inputMode = InputMode.Keyboard

            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }
    }
}

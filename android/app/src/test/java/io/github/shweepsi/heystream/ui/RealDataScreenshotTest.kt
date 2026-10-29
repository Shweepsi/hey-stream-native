package io.github.shweepsi.heystream.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import io.github.shweepsi.heystream.core.CatalogItem
import io.github.shweepsi.heystream.core.CategoryEntry
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.detail.DetailHeader
import io.github.shweepsi.heystream.ui.detail.EpisodeRow
import io.github.shweepsi.heystream.ui.detail.PlayTarget
import io.github.shweepsi.heystream.ui.detail.SeriesDetailContent
import io.github.shweepsi.heystream.ui.detail.SeriesDetailState
import io.github.shweepsi.heystream.ui.live.CategoriesUi
import io.github.shweepsi.heystream.ui.live.LiveActions
import io.github.shweepsi.heystream.ui.live.LiveContent
import io.github.shweepsi.heystream.ui.live.LivePlayback
import io.github.shweepsi.heystream.ui.live.LiveUiState
import io.github.shweepsi.heystream.ui.live.liveCategories
import io.github.shweepsi.heystream.ui.vod.PosterUi
import io.github.shweepsi.heystream.ui.vod.VodCategory
import io.github.shweepsi.heystream.ui.vod.VodContent
import io.github.shweepsi.heystream.ui.vod.VodUiState
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import io.github.shweepsi.heystream.ui.live.CategoryKey as LiveKey
import io.github.shweepsi.heystream.ui.vod.CategoryKey as VodKey

/**
 * Captures avec de vraies listes de fournisseur (`real_catalog.json`, anonymisé :
 * ni identifiants, ni adresse du panneau). Les affiches ne sont pas dans le
 * dépôt : avec `HEYSTREAM_POSTERS=<dossier>` (fichiers nommés comme dans
 * l'URL TMDB), elles sont chargées depuis ce dossier, sans réseau.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealDataScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val catalog: JSONObject = JSONObject(
        checkNotNull(javaClass.classLoader?.getResource("real_catalog.json")).readText(),
    )

    private val posters: File? = System.getenv("HEYSTREAM_POSTERS")?.let(::File)?.takeIf { it.isDirectory }

    /** Affiche locale (même nom de fichier que l'URL), sinon une image transparente. */
    private val previewHandler = AsyncImagePreviewHandler { request ->
        val name = request.data.toString().substringAfterLast('/')
        val bitmap = posters?.resolve(name)?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        (bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
    }

    private fun shot(name: String, device: Device, content: @Composable () -> Unit) = compose.capture(name, device, locale = "fr") {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides previewHandler,
        ) { content() }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

    private fun JSONObject.text(key: String): String? = optString(key).takeIf { !isNull(key) && it.isNotEmpty() }

    private fun posters(key: String): List<PosterUi> = catalog.getJSONObject(key).getJSONArray("items").objects().map {
        PosterUi(id = it.getLong("id"), title = it.getString("name"), image = it.text("image"), progress = null, watched = false)
    }

    /** Catégories du fournisseur ; celle ouverte porte le nombre de titres relevé. */
    private fun vodState(section: Section, names: List<String>, selected: String, count: Long) = VodUiState(
        section = section,
        loading = false,
        categories = names.mapIndexed { index, name ->
            VodCategory(VodKey.Provider("$index"), name, if (name == selected) count else null)
        },
        selected = VodKey.Provider("${names.indexOf(selected)}"),
    )

    @Composable
    private fun Vod(state: VodUiState, items: List<PosterUi>) {
        val paged = remember { flowOf(PagingData.from(items, Loaded)) }.collectAsLazyPagingItems()
        VodContent(state, paged, {}, {}, {}, {}, {})
    }

    private fun movies(device: Device) {
        val movies = catalog.getJSONObject("movies")
        val state = vodState(Section.MOVIES, movies.getJSONArray("categories").strings(), movies.getString("selected"), movies.getLong("count"))
        shot("real_movies", device) { Vod(state, posters("movies")) }
    }

    private fun series(device: Device) {
        val series = catalog.getJSONObject("series_netflix")
        val state = vodState(Section.SERIES, catalog.getJSONArray("series_categories").strings(), series.getString("selected"), series.getLong("count"))
        shot("real_series", device) { Vod(state, posters("series_netflix")) }
    }

    /** Fiche d'une vraie série ; épisodes tels que le fournisseur les nomme (« S01E01 »). */
    private fun seriesDetail(device: Device) {
        val item = catalog.getJSONObject("series_netflix").getJSONArray("items").objects().first()
        val episodes = (1L..7L).map { EpisodeRow(id = it, season = 1, number = it, title = null, durationSecs = 57 * 60L, progress = null, watched = false) }
        val state = SeriesDetailState(
            header = DetailHeader(
                title = item.getString("name"),
                image = item.text("image"),
                plot = item.text("plot"),
                year = item.text("year"),
                rating = item.text("rating"),
            ),
            loading = false,
            episodesLoading = false,
            seasons = listOf(1L, 2L, 3L, 4L),
            selectedSeason = 1,
            episodes = episodes,
            playTarget = PlayTarget(episodeId = 1, season = 1, number = 1, resume = false),
        )
        shot("real_series_detail", device) { SeriesDetailContent(state, { _, _ -> }, {}, {}, {}, {}, {}, {}) }
    }

    private fun live(device: Device) {
        val live = catalog.getJSONObject("live")
        val names = live.getJSONArray("categories").strings()
        val selected = live.getString("selected")
        val channels = live.getJSONArray("channels").objects()
        val entries = names.mapIndexed { index, name -> CategoryEntry("$index", name, isAdult = false, locked = false, hidden = false) }
        val state = LiveUiState(
            categories = CategoriesUi.Ready(liveCategories(entries, mapOf("${names.indexOf(selected)}" to channels.size.toLong()), favorites = 0, recents = 0)),
            selected = LiveKey.Provider("${names.indexOf(selected)}"),
            playback = LivePlayback.Playing,
        )
        val items = channels.map {
            CatalogItem(
                section = Section.LIVE, id = it.getLong("id"), name = it.getString("name"), image = null,
                categoryId = "${names.indexOf(selected)}", extension = null, epgChannelId = null, rating = null,
                plot = null, number = null, progress = null, watched = false,
            )
        }
        shot("real_live", device) {
            val paged = remember { flowOf(PagingData.from(items, Loaded)) }.collectAsLazyPagingItems()
            LiveContent(
                state = state,
                channels = paged,
                nowSecs = 0,
                actions = object : LiveActions {},
                onBack = {},
                video = { modifier -> Box(modifier.background(Color(0xFF2B3542))) },
            )
        }
    }

    @Test fun moviesPhone() = movies(Device.Phone)

    @Test fun moviesTablet() = movies(Device.Tablet)

    @Test fun seriesPhone() = series(Device.Phone)

    @Test fun seriesTablet() = series(Device.Tablet)

    @Test fun seriesDetailPhone() = seriesDetail(Device.Phone)

    @Test fun seriesDetailTablet() = seriesDetail(Device.Tablet)

    @Test fun livePhone() = live(Device.Phone)

    @Test fun liveTablet() = live(Device.Tablet)

    @Test fun moviesTv() = movies(Device.Tv)

    @Test fun seriesTv() = series(Device.Tv)

    @Test fun seriesDetailTv() = seriesDetail(Device.Tv)

    @Test fun liveTv() = live(Device.Tv)

    private companion object {
        val Loaded = LoadStates(
            refresh = LoadState.NotLoading(endOfPaginationReached = true),
            prepend = LoadState.NotLoading(endOfPaginationReached = true),
            append = LoadState.NotLoading(endOfPaginationReached = true),
        )
    }
}

package io.github.shweepsi.heystream.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.core.Section
import io.github.shweepsi.heystream.ui.Device
import io.github.shweepsi.heystream.ui.capture
import io.github.shweepsi.heystream.ui.home.Subscription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val categories = CategoriesUi(
        section = Section.MOVIES,
        items = listOf(
            CategoryToggle("1", "Nouveautés", hidden = false, locked = false),
            CategoryToggle("2", "Action", hidden = false, locked = false),
            CategoryToggle("3", "Comédie", hidden = true, locked = false),
            CategoryToggle("4", "Drame", hidden = false, locked = false),
            CategoryToggle("5", "Animation", hidden = false, locked = false),
            CategoryToggle("6", "Documentaire", hidden = true, locked = false),
            CategoryToggle("7", "Films en version originale sous-titrée", hidden = false, locked = false),
            CategoryToggle("8", "Adultes XXX", hidden = false, locked = true),
            CategoryToggle("9", "Classiques", hidden = false, locked = false),
            CategoryToggle("10", "Westerns", hidden = false, locked = false),
            CategoryToggle("11", "Science-fiction", hidden = false, locked = false),
        ),
    )

    private val state = SettingsState(
        categories = categories,
        parental = ParentalUi(loaded = true, hasPin = true, unlocked = true),
        catalog = CatalogUi(
            results = listOf(
                SectionResult(Section.LIVE, RefreshOutcome.Updated, 3_330),
                SectionResult(Section.MOVIES, RefreshOutcome.KeptCache, 0),
                SectionResult(Section.SERIES, RefreshOutcome.Failed, 0),
            ),
        ),
        account = AccountUi(Subscription.ValidUntil(1_805_000_000), maxConnections = 1),
        coreVersion = "0.1.0",
    )

    private val actions = object : SettingsActions {}

    private fun settings(name: String, device: Device, section: SettingsSection, language: LanguageChoice = LanguageChoice.System) =
        compose.capture("settings_$name", device) {
            SettingsContent(state.copy(section = section), language, appVersion = "0.1.0", actions = actions)
        }

    @Test fun hiddenCategoriesTv() = settings("hidden", Device.Tv, SettingsSection.HiddenCategories)

    @Test fun parentalTablet() = settings("parental", Device.Tablet, SettingsSection.Parental)

    @Test fun hiddenCategoriesPhone() = settings("hidden", Device.Phone, SettingsSection.HiddenCategories)

    @Test fun subscriptionPhone() = settings("subscription", Device.Phone, SettingsSection.Subscription)

    @Test fun parentalTv() = settings("parental", Device.Tv, SettingsSection.Parental)

    @Test fun languageTv() = settings("language", Device.Tv, SettingsSection.Language, LanguageChoice.French)

    @Test fun catalogTv() = settings("catalog", Device.Tv, SettingsSection.Catalog)

    @Test fun subscriptionTv() = settings("subscription", Device.Tv, SettingsSection.Subscription)

    @Test fun aboutTv() = settings("about", Device.Tv, SettingsSection.About)

    @Test fun aboutPhone() = settings("about", Device.Phone, SettingsSection.About)

    @Test fun catalogTabletDark() = settings("catalog", Device.TabletDark, SettingsSection.Catalog)

    private fun settingsFrench(name: String, device: Device, section: SettingsSection) =
        compose.captureFrench("settings_$name", device) {
            SettingsContent(state.copy(section = section), LanguageChoice.French, appVersion = "0.1.0", actions = actions)
        }

    @Test fun hiddenCategoriesTvFrench() = settingsFrench("hidden", Device.Tv, SettingsSection.HiddenCategories)

    @Test fun parentalTabletFrench() = settingsFrench("parental", Device.Tablet, SettingsSection.Parental)

    @Test fun hiddenCategoriesPhoneFrench() = settingsFrench("hidden", Device.Phone, SettingsSection.HiddenCategories)

    @Test fun catalogTvFrench() = settingsFrench("catalog", Device.Tv, SettingsSection.Catalog)

    @Test fun subscriptionTvFrench() = settingsFrench("subscription", Device.Tv, SettingsSection.Subscription)

    @Test fun aboutTabletFrench() = settingsFrench("about", Device.Tablet, SettingsSection.About)

    /** Deuxième mise à jour moins d'une minute après la première. */
    private val notRetried = state.copy(
        section = SettingsSection.Catalog,
        catalog = CatalogUi(
            results = listOf(
                SectionResult(Section.LIVE, RefreshOutcome.UpToDate, 0),
                SectionResult(Section.MOVIES, RefreshOutcome.UpToDate, 0),
                SectionResult(Section.SERIES, RefreshOutcome.UpToDate, 0),
            ),
        ),
    )

    @Test fun catalogNotRetriedPhoneFrench() = compose.captureFrench("settings_catalog_again", Device.Phone) {
        SettingsContent(notRetried, LanguageChoice.French, appVersion = "0.1.0", actions = actions)
    }

    @Test fun signOutTv() = compose.capture("settings_sign_out", Device.Tv) {
        SignOutPanel(busy = false, onConfirm = {}, onDismiss = {})
    }

    @Test fun signOutPhoneFrench() = compose.captureFrench("settings_sign_out", Device.Phone) {
        SignOutPanel(busy = false, onConfirm = {}, onDismiss = {})
    }

    @Test fun licenseTablet() = compose.capture("settings_license", Device.Tablet) {
        LicensePanel(
            title = "Lucide (icônes)",
            subtitle = "Licence ISC",
            text = "ISC License\n\nCopyright (c) Lucide Icons and Contributors\n\nPermission to use, copy, modify…",
            onDismiss = {},
        )
    }
}

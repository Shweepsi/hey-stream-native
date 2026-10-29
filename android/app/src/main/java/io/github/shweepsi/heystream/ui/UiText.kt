package io.github.shweepsi.heystream.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.HeyStreamException
import java.util.Locale

/** Langue de l'app (celle choisie dans les réglages), pour les dates et heures. */
@Composable
@ReadOnlyComposable
// La configuration a toujours au moins une langue ; elle change avec le réglage de langue.
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Texte à afficher, choisi hors d'un composable (ViewModel) et traduit à l'affichage. */
@Immutable
data class UiText(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    @Composable
    fun resolve(): String = stringResource(id, *args.toTypedArray())

    fun resolve(context: Context): String = context.getString(id, *args.toTypedArray())
}

/**
 * Message d'une erreur du cœur. Le cœur ne produit aucun texte affiché : ses
 * erreurs sont typées et traduites ici. Jamais d'URL ni d'identifiant.
 */
fun Throwable.toUiText(): UiText = when (this) {
    is HeyStreamException.InvalidServerUrl -> UiText(R.string.error_invalid_server_url)
    is HeyStreamException.MissingCredentials -> UiText(R.string.error_missing_credentials)
    is HeyStreamException.Unauthorized -> UiText(R.string.error_unauthorized)
    is HeyStreamException.AccountInactive -> UiText(R.string.error_account_inactive)
    is HeyStreamException.NotFound -> UiText(R.string.error_not_found)
    is HeyStreamException.Server -> UiText(R.string.error_server, listOf(status.toInt()))
    is HeyStreamException.UnexpectedStatus -> UiText(R.string.error_unexpected_status, listOf(status.toInt()))
    is HeyStreamException.InvalidResponse -> UiText(R.string.error_invalid_response)
    is HeyStreamException.Network -> UiText(R.string.error_network)
    is HeyStreamException.Storage -> UiText(R.string.error_storage)
    is HeyStreamException.ParentalLock -> UiText(R.string.error_parental_lock)
    is HeyStreamException.InvalidPin -> UiText(R.string.error_invalid_pin)
    is HeyStreamException.WrongPin -> UiText(R.string.error_wrong_pin)
    is HeyStreamException.TooManyAttempts -> UiText(R.string.error_too_many_attempts, listOf(retryInSecs.coerceAtLeast(1)))
    is HeyStreamException.SignedOut -> UiText(R.string.error_signed_out)
    else -> UiText(R.string.error_unknown)
}

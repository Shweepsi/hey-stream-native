package io.github.shweepsi.heystream.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.core.AccountInfo
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.ui.Formats
import io.github.shweepsi.heystream.ui.currentLocale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Ligne « abonnement » de l'accueil. */
sealed interface Subscription {
    /** Compte illimité. */
    data object Unlimited : Subscription

    data class ValidUntil(val epochSecs: Long) : Subscription

    data class Expired(val epochSecs: Long) : Subscription
}

/**
 * Abonnement d'après la dernière connexion réussie (connu hors ligne) ;
 * `null` tant qu'il n'a jamais été vérifié sur l'appareil.
 */
fun subscriptionOf(info: AccountInfo?, nowSecs: Long): Subscription? {
    info ?: return null
    val expires = info.expiresAt ?: return Subscription.Unlimited
    return if (expires <= nowSecs) Subscription.Expired(expires) else Subscription.ValidUntil(expires)
}

/** Expiré, ou expire dans moins de [ALERT_DAYS] jours : affiché en rouge. */
fun Subscription.needsAttention(nowSecs: Long = System.currentTimeMillis() / 1000): Boolean = when (this) {
    Subscription.Unlimited -> false
    is Subscription.Expired -> true
    is Subscription.ValidUntil -> epochSecs - nowSecs < ALERT_DAYS * 86_400
}

private const val ALERT_DAYS = 7L

@Composable
fun Subscription.text(): String {
    val locale = currentLocale()
    return when (this) {
        Subscription.Unlimited -> stringResource(R.string.subscription_unlimited)
        is Subscription.ValidUntil -> stringResource(R.string.subscription_valid_until, Formats.longDate(epochSecs, locale))
        is Subscription.Expired -> stringResource(R.string.subscription_expired, Formats.longDate(epochSecs, locale))
    }
}

class HomeViewModel(session: CoreSession) : ViewModel() {
    val subscription: StateFlow<Subscription?> = session.account
        .map { subscriptionOf(it, System.currentTimeMillis() / 1000) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

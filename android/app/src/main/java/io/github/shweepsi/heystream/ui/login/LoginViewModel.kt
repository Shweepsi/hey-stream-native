package io.github.shweepsi.heystream.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.data.CoreSession
import io.github.shweepsi.heystream.ui.UiText
import io.github.shweepsi.heystream.ui.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: UiText? = null,
    val signedIn: Boolean = false,
) {
    override fun toString(): String = "LoginState(busy=$busy, error=$error, signedIn=$signedIn)"
}

class LoginViewModel(private val session: CoreSession) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onServerChange(value: String) = _state.update { it.copy(server = value, error = null) }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    /** Vérification immédiate auprès du fournisseur ; message clair en cas d'échec. */
    fun signIn() {
        val current = _state.value
        if (current.busy) return
        if (current.server.isBlank()) {
            _state.update { it.copy(error = UiText(R.string.error_missing_server)) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                session.signIn(current.server, current.username, current.password)
                _state.update { it.copy(busy = false, password = "", signedIn = true) }
            } catch (error: Throwable) {
                _state.update { it.copy(busy = false, error = error.toUiText()) }
            }
        }
    }
}

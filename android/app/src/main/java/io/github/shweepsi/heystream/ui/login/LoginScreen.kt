package io.github.shweepsi.heystream.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.shweepsi.heystream.R
import io.github.shweepsi.heystream.ui.appGraph
import io.github.shweepsi.heystream.ui.components.ButtonStyle
import io.github.shweepsi.heystream.ui.components.HsButton
import io.github.shweepsi.heystream.ui.components.Logo
import io.github.shweepsi.heystream.ui.theme.Hs
import io.github.shweepsi.heystream.ui.theme.HsColors

/**
 * Premier lancement : serveur, identifiant, mot de passe (spec, rien
 * d'autre). [editing] : « Modifier l'adresse du serveur » depuis les
 * réglages, formulaire prérempli ; avec le même identifiant, favoris,
 * reprises, « vus » et code sont gardés, et rien n'est effacé si la
 * vérification échoue.
 */
/** Repères des champs, pour le test de bout en bout (UiAutomator). */
object LoginTags {
    const val SERVER = "login_server"
    const val USERNAME = "login_username"
    const val PASSWORD = "login_password"
}

@Composable
fun LoginScreen(onSignedIn: () -> Unit, editing: Boolean = false, onBack: (() -> Unit)? = null) {
    val session = appGraph().session
    val viewModel = viewModel { LoginViewModel(session, editing) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }
    // Retour ignoré pendant « Vérification… » : la vérification ne peut plus
    // être annulée (réussie, elle remplace l'abonnement), l'écran attend son
    // résultat.
    if (onBack != null) BackHandler { if (!state.busy) onBack() }
    LoginContent(
        state = state,
        onServerChange = viewModel::onServerChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::signIn,
    )
}

@Composable
fun LoginContent(
    state: LoginState,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val dimens = Hs.dimens
    val focus = LocalFocusManager.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Hs.colors.background)
            .imePadding(),
    ) {
        // Téléphone en paysage : formulaire resserré, pour que le bouton tienne
        // à l'écran sans défiler.
        val low = !dimens.isTv && maxHeight < 420.dp
        val gap = if (low) 10.dp else dimens.gap
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = dimens.screenHorizontal * 2, vertical = if (low) 8.dp else dimens.screenVertical),
            horizontalArrangement = Arrangement.spacedBy(dimens.gapLarge * 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.gap)) {
                Logo(size = if (dimens.isTv) 40.dp else 44.dp)
                Text(stringResource(if (state.editing) R.string.login_edit_title else R.string.login_title), style = Hs.type.title)
                Text(
                    stringResource(if (state.editing) R.string.login_edit_intro else R.string.login_intro),
                    style = Hs.type.body,
                    color = Hs.colors.textSecondary,
                )
                Text(stringResource(R.string.login_privacy), style = Hs.type.small, color = Hs.colors.textSecondary)
            }
            Column(
                Modifier
                    .weight(1f)
                    .widthIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                LoginField(
                    label = stringResource(R.string.login_server),
                    value = state.server,
                    onValueChange = onServerChange,
                    placeholder = stringResource(R.string.login_server_hint),
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.focusRequester(first).testTag(LoginTags.SERVER),
                    onNext = { focus.moveFocus(FocusDirection.Down) },
                )
                LoginField(
                    label = stringResource(R.string.login_username),
                    value = state.username,
                    onValueChange = onUsernameChange,
                    keyboardType = KeyboardType.Text,
                    modifier = Modifier.testTag(LoginTags.USERNAME),
                    onNext = { focus.moveFocus(FocusDirection.Down) },
                )
                LoginField(
                    label = stringResource(R.string.login_password),
                    value = state.password,
                    onValueChange = onPasswordChange,
                    keyboardType = KeyboardType.Password,
                    password = true,
                    imeAction = ImeAction.Done,
                    modifier = Modifier.testTag(LoginTags.PASSWORD),
                    onNext = onSubmit,
                )
                // Place réservée au message (rien ne bouge quand il apparaît),
                // sauf sur un écran bas.
                val error = state.error?.resolve()
                if (error != null || !low) {
                    Text(
                        text = error ?: " ",
                        style = Hs.type.body,
                        color = Hs.colors.text,
                    )
                }
                HsButton(
                    text = stringResource(if (state.busy) R.string.login_checking else R.string.login_submit),
                    onClick = onSubmit,
                    // Toujours activable (un second appui est ignoré pendant la
                    // vérification) : le focus reste sur le bouton.
                    style = ButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    val dimens = Hs.dimens
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dimens.radius)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Hs.type.small, color = Hs.colors.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Hs.type.body.copy(color = Hs.colors.text),
            cursorBrush = SolidColor(Hs.colors.text),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .fillMaxWidth()
                .heightIn(min = dimens.minTouch + 4.dp)
                .border(dimens.focusBorder, if (focused) HsColors.Focus else Hs.colors.surface2, shape)
                .background(Hs.colors.surface2, shape),
            decorationBox = { field ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = Hs.type.body, color = Hs.colors.textSecondary, maxLines = 1)
                    }
                    field()
                }
            },
        )
    }
}

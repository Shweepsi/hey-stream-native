package io.github.shweepsi.heystream.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** Premier lancement : serveur, identifiant, mot de passe (spec, rien d'autre). */
@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val session = appGraph().session
    val viewModel = viewModel { LoginViewModel(session) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }
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
    Row(
        Modifier
            .fillMaxSize()
            .background(Hs.colors.background)
            .imePadding()
            .padding(horizontal = dimens.screenHorizontal * 2, vertical = dimens.screenVertical),
        horizontalArrangement = Arrangement.spacedBy(dimens.gapLarge * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.gap)) {
            Logo(size = if (dimens.isTv) 40.dp else 44.dp)
            Text(stringResource(R.string.login_title), style = Hs.type.title)
            Text(stringResource(R.string.login_intro), style = Hs.type.body, color = Hs.colors.textSecondary)
            Text(stringResource(R.string.login_privacy), style = Hs.type.small, color = Hs.colors.textSecondary)
        }
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimens.gap),
        ) {
            LoginField(
                label = stringResource(R.string.login_server),
                value = state.server,
                onValueChange = onServerChange,
                placeholder = stringResource(R.string.login_server_hint),
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.focusRequester(first),
                onNext = { focus.moveFocus(FocusDirection.Down) },
            )
            LoginField(
                label = stringResource(R.string.login_username),
                value = state.username,
                onValueChange = onUsernameChange,
                keyboardType = KeyboardType.Text,
                onNext = { focus.moveFocus(FocusDirection.Down) },
            )
            LoginField(
                label = stringResource(R.string.login_password),
                value = state.password,
                onValueChange = onPasswordChange,
                keyboardType = KeyboardType.Password,
                password = true,
                imeAction = ImeAction.Done,
                onNext = onSubmit,
            )
            Text(
                text = state.error?.resolve() ?: " ",
                style = Hs.type.body,
                color = Hs.colors.text,
            )
            HsButton(
                text = stringResource(if (state.busy) R.string.login_checking else R.string.login_submit),
                onClick = onSubmit,
                enabled = !state.busy,
                style = ButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
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

package io.github.shweepsi.heystream.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shweepsi.heystream.ui.home.HomeContent
import io.github.shweepsi.heystream.ui.home.Subscription
import io.github.shweepsi.heystream.ui.login.LoginContent
import io.github.shweepsi.heystream.ui.login.LoginState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun home(device: Device, subscription: Subscription?) = compose.capture("home", device) {
        HomeContent(subscription, {}, {}, {}, {}, {})
    }

    @Test fun homeTv() = home(Device.Tv, Subscription.ValidUntil(1_805_000_000))

    @Test fun homeTablet() = home(Device.Tablet, Subscription.Expired(1_760_000_000))

    @Test fun homePhone() = home(Device.Phone, Subscription.Unlimited)

    @Test fun loginTv() = compose.capture("login", Device.Tv) {
        LoginContent(LoginState(server = "http://exemple.com:8080"), {}, {}, {}, {})
    }

    @Test fun loginTablet() = compose.capture("login", Device.Tablet) {
        LoginContent(LoginState(error = UiText(io.github.shweepsi.heystream.R.string.error_unauthorized)), {}, {}, {}, {})
    }
}

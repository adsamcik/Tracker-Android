package com.adsamcik.tracker.app.activity

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import com.adsamcik.tracker.feature.dashboard.api.navigation.Dashboard
import com.adsamcik.tracker.feature.game.api.navigation.Game
import com.adsamcik.tracker.feature.statistics.api.navigation.Stats
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityDeepNavigationTest {

    @Test
    fun `parses explicit supported target`() {
        val intent = Intent().putExtra(
            MainActivityCompose.EXTRA_NAVIGATE_TO,
            MainActivityCompose.TARGET_IMPEXP,
        )

        parseDeepNavigationRequest(intent)?.target shouldBe MainActivityCompose.TARGET_IMPEXP
    }

    @Test
    fun `parses legacy open game extra`() {
        val intent = Intent().putExtra(MainActivityCompose.LEGACY_EXTRA_OPEN_GAME, true)

        parseDeepNavigationRequest(intent)?.target shouldBe MainActivityCompose.TARGET_GAME
    }

    @Test
    fun `ignores unsupported target`() {
        val intent = Intent().putExtra(MainActivityCompose.EXTRA_NAVIGATE_TO, "unknown")

        parseDeepNavigationRequest(intent) shouldBe null
    }

    @Test
    fun `old payload extras are ignored rather than exposed as supported contract`() {
        val intent = Intent()
            .putExtra(MainActivityCompose.EXTRA_NAVIGATE_TO, MainActivityCompose.TARGET_DASHBOARD)
            .putExtra("challenge_id", 42L)
            .putExtra("scroll_to", "goals")

        parseDeepNavigationRequest(intent)?.target shouldBe MainActivityCompose.TARGET_DASHBOARD
    }

    @Test
    fun `selected tab matches deep navigation target family`() {
        selectedTabForDeepNavigationTarget(MainActivityCompose.TARGET_GAME) shouldBe Game
        selectedTabForDeepNavigationTarget(MainActivityCompose.TARGET_STATS) shouldBe Stats
        selectedTabForDeepNavigationTarget(MainActivityCompose.TARGET_IMPEXP) shouldBe Dashboard
        selectedTabForDeepNavigationTarget(MainActivityCompose.TARGET_SETTINGS) shouldBe Dashboard
    }

    @Test
    fun `does not replay restored request already recorded as handled`() {
        val requestId = "handled-request"
        val savedStateHandle = SavedStateHandle(
            mapOf(
                MainActivityViewModel.KEY_DEEP_NAV_TARGET to MainActivityCompose.TARGET_GAME,
                MainActivityViewModel.KEY_DEEP_NAV_REQUEST_ID to requestId,
                MainActivityViewModel.KEY_LAST_HANDLED_DEEP_NAV_REQUEST_ID to requestId,
            ),
        )

        MainActivityViewModel(savedStateHandle).deepNavigationRequest.value shouldBe null
    }

    @Test
    fun `handles different request IDs with the same target independently`() {
        val viewModel = MainActivityViewModel(SavedStateHandle())
        val first = DeepNavigationRequest(MainActivityCompose.TARGET_GAME, "request-1")
        val second = DeepNavigationRequest(MainActivityCompose.TARGET_GAME, "request-2")

        viewModel.setDeepNavigationRequest(first)
        viewModel.consumeDeepNavigationRequest(first.requestId) shouldBe true
        viewModel.setDeepNavigationRequest(second)

        viewModel.consumeDeepNavigationRequest(second.requestId) shouldBe true
    }
}



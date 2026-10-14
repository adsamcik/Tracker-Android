package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.SessionExportChoices
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Authored interaction scenarios only; rendering and execution remain NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionExportChoicesComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `available sources start selected and user exclusions reach one export`() {
        var exported: Set<HistorySource>? = null
        val choices = SessionExportChoices(session(), setOf(HistorySource.STEPS, HistorySource.WIFI), setOf(HistorySource.CELL))
        compose.setContent { MaterialTheme { SessionExportChoicesDialog(choices, {}, { exported = it }) } }
        compose.onNodeWithText(text(HistorySource.STEPS.labelResource)).assertIsOn()
        compose.onNodeWithText(text(HistorySource.WIFI.labelResource)).assertIsOn().performClick()
        compose.onNodeWithText(text(HistorySource.WIFI.labelResource)).assertIsOff()
        compose.onNodeWithText(text(HistorySource.CELL.labelResource) + " · " + text(R.string.history_value_unavailable)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.organization_export)).onLast().performClick()
        exported shouldBe setOf(HistorySource.STEPS)
    }

    @Test fun `empty selection disables export without discarding the choices`() {
        var exported = false
        compose.setContent { MaterialTheme { SessionExportChoicesDialog(
            SessionExportChoices(session(), setOf(HistorySource.LOCATION), emptySet()), {}, { exported = true }) } }
        compose.onNodeWithText(text(HistorySource.LOCATION.labelResource)).performClick()
        compose.onAllNodesWithText(text(R.string.organization_export)).onLast().assertIsNotEnabled()
        exported shouldBe false
    }

    private fun session() = OrganizedSession(OrganizationSessionId.Annotation("local"), 1, 0, "Walk",
        null, null, "UTC", 100, 200, false, false)
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}

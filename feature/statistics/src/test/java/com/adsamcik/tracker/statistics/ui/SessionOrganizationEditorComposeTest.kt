package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.SessionEditorTarget
import com.adsamcik.tracker.stats.api.repository.OrganizationActivityLabel
import com.adsamcik.tracker.stats.api.repository.OrganizationInterval
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizedSession
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionOrganizationEditorComposeTest {
	@get:Rule val compose = createComposeRule()
	@Test fun `uncertain recording name save leaves missing bounds unchanged`() {
		var saved = false
		var interval: OrganizationInterval? = OrganizationInterval(1, 2)
		val session = session().copy(toMs = null, timeUncertain = true)
		compose.setContent { MaterialTheme { SessionOrganizationEditor(target(session), emptyList(), false, null,
			{}, {}, { change, _, _, _ -> saved = true; interval = change }, {}) } }
		compose.onNodeWithText(text(R.string.organization_name)).performTextReplacement("New name")
		compose.onNodeWithText(text(R.string.organization_save)).performClick()
		saved shouldBe true
		interval shouldBe null
	}
	@Test fun `active editor permits metadata but disables time and grouping removal`() {
		compose.setContent { MaterialTheme { SessionOrganizationEditor(target(session().copy(isActive = true)),
			emptyList(), false, null, {}, {}, { _, _, _, _ -> }, {}) } }
		compose.onNodeWithText(text(R.string.organization_start)).performScrollTo()
		compose.onAllNodesWithText(text(R.string.organization_date))[0].assertIsNotEnabled()
		compose.onNodeWithText(text(R.string.organization_remove)).performScrollTo().assertIsNotEnabled()
	}
	@Test fun `cancel creates no saved group and removal confirmation promises data preservation`() {
		var saved = false
		var removed = false
		var dismissed = false
		compose.setContent { MaterialTheme { SessionOrganizationEditor(target(session()), emptyList(), false, null,
			{ dismissed = true }, {}, { _, _, _, _ -> saved = true }, { removed = true }) } }
		compose.onNodeWithText(text(R.string.organization_remove)).performScrollTo().performClick()
		compose.onNodeWithText(text(R.string.organization_remove_body)).assertIsDisplayed()
		removed shouldBe false
		// Cancel only the confirmation: no operation is dispatched.
		compose.onAllNodesWithText(text(R.string.organization_cancel)).onLast().performClick()
		removed shouldBe false
		compose.onNodeWithText(text(R.string.organization_cancel)).performClick()
		dismissed shouldBe true
		saved shouldBe false
	}
	@Test fun `clearing activity label does not continue displaying its old name`() {
		compose.setContent { MaterialTheme { SessionOrganizationEditor(target(session().copy(activityLabelId = 1, activityLabelName = "Hike")),
			listOf(OrganizationActivityLabel(1, "Hike")), false, null, {}, {}, { _, _, _, _ -> }, {}) } }
		compose.onNodeWithText(text(R.string.organization_label) + ": Hike").performClick()
		compose.onNodeWithText(text(R.string.organization_no_label)).performClick()
		compose.onNodeWithText(text(R.string.organization_label) + ": " + text(R.string.organization_no_label)).assertIsDisplayed()
	}
	private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
	private fun session() = OrganizedSession(OrganizationSessionId.Recording("logical:fixture"), 0, 0,
		null, null, null, "UTC", 100, 200, false, false)
	private fun target(session: OrganizedSession) = SessionEditorTarget(session, 0, OrganizationInterval(100, 200), "UTC")
}

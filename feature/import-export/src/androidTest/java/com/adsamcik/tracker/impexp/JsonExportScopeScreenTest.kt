package com.adsamcik.tracker.impexp

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.impexp.exporter.JsonExporter
import com.adsamcik.tracker.impexp.exporter.activity.ImportExportComposeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonExportScopeScreenTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val launchIntent = Intent(context, ImportExportComposeActivity::class.java).apply {
		putExtra(ImportExportComposeActivity.EXPORTER_KEY, JsonExporter::class.java)
	}

	@get:Rule
	val composeRule = createAndroidComposeRule(ActivityScenarioRule<ImportExportComposeActivity>(launchIntent))

	@Test
	fun completeOverlappingSessionsAreDisclosedBeforeSaving() {
		composeRule.onNodeWithText(
			context.getString(R.string.export_json_whole_session_scope),
			useUnmergedTree = true,
		).assertIsDisplayed()
	}
}

package com.adsamcik.tracker.app.tracebox

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.R
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiStrings
import dev.tracebox.ui.compose.TraceboxPrimaryAction
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerTraceboxUiTest {
    @Test
    fun `casual share is primary and technical controls begin collapsed`() {
        val strings = TraceboxDiagnosticsUiStrings(
            title = R.string.settings_tracebox_title,
            description = R.string.settings_tracebox_root_summary,
        )
        val configuration = TrackerTraceboxUi.configuration(strings)

        configuration.strings shouldBe strings
        configuration.showHeading shouldBe false
        configuration.primaryAction shouldBe TraceboxPrimaryAction.SHARE
        configuration.packageActions.upload shouldBe false
        configuration.packageActions.share shouldBe true
        configuration.packageActions.save shouldBe true
        configuration.packageActions.deleteAllData shouldBe true
        configuration.advancedControls.visible shouldBe true
        configuration.advancedControls.initiallyExpanded shouldBe false
        configuration.advancedControls.resetToDefaults shouldBe true
        configuration.defaultPolicy shouldBe TrackerTraceboxRuntime.defaultPolicy
    }

    @Test
    fun `diagnostics resources resolve in a supported non-English locale`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val english = context.localized("en-US")
        val czech = context.localized("cs-CZ")
        val strings = TrackerTraceboxUi.configuration().strings

        czech.getString(strings.title) shouldNotBe english.getString(strings.title)
        czech.getString(strings.description) shouldNotBe english.getString(strings.description)
        czech.getString(strings.privacyNotice).isNotBlank() shouldBe true
        czech.resources.getResourceName(strings.savedBytes).isNotBlank() shouldBe true
    }

    private fun Context.localized(languageTag: String): Context {
        val localized = Configuration(resources.configuration)
        localized.setLocale(Locale.forLanguageTag(languageTag))
        return createConfigurationContext(localized)
    }
}

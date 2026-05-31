package com.adsamcik.tracker.game.ui.achievement

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.data.achievement.AchievementFormatting
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for R8 finding `r8-humanize-masks-i18n-debt`.
 *
 * `AchievementFormatting` (in `:stats-data`) is the project's canonical
 * title/description renderer for achievement catalog entries. Every entry
 * in [AchievementCatalog] MUST have:
 *
 *  - a `MetricKey` branch in `AchievementFormatting.formatTitle`
 *  - a `MetricKey` branch in `AchievementFormatting.formatDescription`
 *  - a non-blank rendered title (means the format string referenced by that
 *    branch actually exists in `stats-data/res/values/achievement_format_strings.xml`)
 *  - a non-blank rendered description (same — verifies `ach_desc_*` keys)
 *
 * Without this guard, adding a new `MetricKey` to the catalog (or removing a
 * string resource) silently produces empty achievement titles on the
 * Game / Dashboard surfaces. The per-id `achievement_${id}_title` resources
 * used by the legacy dashboard fallback are intentionally NOT defined — the
 * catalog uses metric-based format strings instead.
 *
 * Test lives in `:game` (which depends on both `:stats-data` and `:sutils`)
 * because Robolectric's resource merge picks up `:sutils` strings via the
 * `:game` manifest chain — the `formatDistance` extension used inside
 * `AchievementFormatting.formatTitle` needs them. The code under test lives
 * in `:stats-data`; this is purely a test-resource-availability constraint.
 *
 * This test runs the WHOLE catalog through both formatters; failure listing
 * tells you exactly which catalog entry needs a strings.xml entry or
 * formatter branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AchievementCatalogStringCoverageTest {

	private val context: android.content.Context = ApplicationProvider.getApplicationContext()

	@Test
	fun `every catalog entry renders a non-blank title via AchievementFormatting`() {
		val empties = AchievementCatalog.definitions.mapNotNull { def ->
			val title = runCatching { AchievementFormatting.formatTitle(context, def) }
				.getOrElse { e -> "<threw: ${e.javaClass.simpleName}: ${e.message}>" }
			if (title.isBlank() || title.startsWith("<threw:")) def.id to title else null
		}
		check(empties.isEmpty()) {
			val lines = empties.joinToString("\n") { (id, t) -> "  - $id: $t" }
			"AchievementFormatting.formatTitle produced ${empties.size} blank or throwing titles:\n$lines"
		}
	}

	@Test
	fun `every catalog entry renders a non-blank description via AchievementFormatting`() {
		val empties = AchievementCatalog.definitions.mapNotNull { def ->
			val desc = runCatching { AchievementFormatting.formatDescription(context, def) }
				.getOrElse { e -> "<threw: ${e.javaClass.simpleName}: ${e.message}>" }
			if (desc.isBlank() || desc.startsWith("<threw:")) def.id to desc else null
		}
		check(empties.isEmpty()) {
			val lines = empties.joinToString("\n") { (id, t) -> "  - $id: $t" }
			"AchievementFormatting.formatDescription produced ${empties.size} blank or throwing descriptions:\n$lines"
		}
	}

	@Test
	fun `rendered titles never leak raw resource keys`() {
		// Catches the historical Dashboard bug where the user saw
		// "achievement_sessions_total_1_title" instead of "Sessions Total 1".
		// If a future change wires AchievementFormatting through getIdentifier
		// + fallback (instead of explicit when branches) this guard fires.
		val leaks = AchievementCatalog.definitions.mapNotNull { def ->
			val title = AchievementFormatting.formatTitle(context, def)
			if (title.contains("achievement_") || title == def.nameRes) def.id to title else null
		}
		check(leaks.isEmpty()) {
			val lines = leaks.joinToString("\n") { (id, t) -> "  - $id: $t" }
			"AchievementFormatting leaked raw resource keys for ${leaks.size} entries:\n$lines"
		}
	}

	@Test
	fun `rendered descriptions never leak raw resource keys`() {
		val leaks = AchievementCatalog.definitions.mapNotNull { def ->
			val desc = AchievementFormatting.formatDescription(context, def)
			if (desc.contains("achievement_") || desc == def.descriptionRes) def.id to desc else null
		}
		check(leaks.isEmpty()) {
			val lines = leaks.joinToString("\n") { (id, t) -> "  - $id: $t" }
			"AchievementFormatting leaked raw resource keys for ${leaks.size} entries:\n$lines"
		}
	}
}

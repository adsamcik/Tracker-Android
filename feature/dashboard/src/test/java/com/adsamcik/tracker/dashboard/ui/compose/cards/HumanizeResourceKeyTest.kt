package com.adsamcik.tracker.dashboard.ui.compose.cards

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("humanizeResourceKey - achievement title fallback when no string resource exists")
class HumanizeResourceKeyTest {

	@Test
	fun `strips achievement_ prefix and _title suffix and title-cases tokens`() {
		humanizeResourceKey("achievement_sessions_total_1_title") shouldBe "Sessions Total 1"
	}

	@Test
	fun `also handles _desc suffix`() {
		humanizeResourceKey("achievement_total_distance_5000_desc") shouldBe "Total Distance 5000"
	}

	@Test
	fun `also handles _description suffix`() {
		humanizeResourceKey("achievement_active_days_7_description") shouldBe "Active Days 7"
	}

	@Test
	fun `unrelated keys still get title-cased (no false stripping)`() {
		humanizeResourceKey("some_other_key") shouldBe "Some Other Key"
	}

	@Test
	fun `keys without achievement prefix still get title-cased`() {
		humanizeResourceKey("max_session_duration_3600000_title") shouldBe "Max Session Duration 3600000"
	}

	@Test
	fun `empty string returns empty string`() {
		humanizeResourceKey("") shouldBe ""
	}

	@Test
	fun `degenerate key with only prefix gets title-cased remainder`() {
		// "achievement_title" -> strip "achievement_" -> "title" -> "Title".
		// Suffix-strip only matches "_title" (with separator), so "title"
		// alone survives the strip pass and becomes a single capitalized word.
		humanizeResourceKey("achievement_title") shouldBe "Title"
	}

	@Test
	fun `consecutive underscores are collapsed`() {
		humanizeResourceKey("achievement_a__b_title") shouldBe "A B"
	}

	@Test
	fun `single-word id is title-cased`() {
		humanizeResourceKey("achievement_walker_title") shouldBe "Walker"
	}

	@Test
	fun `does not double-strip suffix that appears in the middle`() {
		// "title" inside the body should not be touched -- only suffix.
		humanizeResourceKey("achievement_title_holder_title") shouldBe "Title Holder"
	}
}

package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.R
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveSessionWidgetPresentationTest {
	@Test
	fun `raw positive session Steps are absent while independent stats remain`() {
		val stats = buildActiveSessionStats(
			context = ApplicationProvider.getApplicationContext<Context>(),
			session = TrackerSessionSnapshot(
				id = 1L,
				start = 1_000L,
				distanceInM = 1_500f,
				steps = 9_999,
				collections = 12,
			),
			nowMillis = 61_000L,
		)

		stats.map(ActiveSessionStat::labelRes) shouldContainExactly listOf(
			R.string.widget_session_distance,
			R.string.widget_session_duration,
			R.string.widget_collections,
		)
		(R.string.widget_session_steps in stats.map(ActiveSessionStat::labelRes)) shouldBe false
		stats.last().value shouldBe "12"
	}
}

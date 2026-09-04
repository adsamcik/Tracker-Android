package com.adsamcik.tracker.app.widget.glance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.R
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryCapture
import com.adsamcik.tracker.stats.api.repository.HistoryCaptureRevision
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SessionHistory
import com.adsamcik.tracker.stats.api.repository.SessionHistoryQuery
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
			qualifiedSteps = null,
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

	@Test
	fun `complete qualified positive and covered zero Steps are rendered`() {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val session = TrackerSessionSnapshot(id = 7L, start = 1_000L, steps = 99_999)

		listOf(1_234L, 0L).forEach { steps ->
			val stats = buildActiveSessionStats(
				context = context,
				session = session,
				qualifiedSteps = steps,
				nowMillis = 61_000L,
			)

			stats.map(ActiveSessionStat::labelRes) shouldContainExactly listOf(
				R.string.widget_session_distance,
				R.string.widget_session_duration,
				R.string.widget_session_steps,
				R.string.widget_collections,
			)
			stats.first { it.labelRes == R.string.widget_session_steps }.value shouldBe
				WidgetFormatters.formatSteps(steps)
		}
	}

	@Test
	fun `exact segment complete history exposes its qualified value`() {
		completeQuery(segmentId = 7L, count = 1_234L)
			.completeStepsForSegment(expectedSegmentId = 7L) shouldBe 1_234L
		completeQuery(segmentId = 7L, count = 0L)
			.completeStepsForSegment(expectedSegmentId = 7L) shouldBe 0L
	}

	@Test
	fun `segment mismatch withholds otherwise complete Steps`() {
		completeQuery(segmentId = 8L, count = 1_234L)
			.completeStepsForSegment(expectedSegmentId = 7L) shouldBe null
	}

	@Test
	fun `partial unqualified Steps are withheld`() {
		query(
			segmentId = 7L,
			qualified = false,
			steps = StepsHistory(
				count = 123L,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.RECORDED,
				productState = HistoryProductState.PARTIAL,
				coverage = StepsHistoryCoverage.PARTIAL,
				causes = setOf(StepsHistoryCause.CAPTURE_PARTIAL),
			),
		).completeStepsForSegment(expectedSegmentId = 7L) shouldBe null
	}

	@Test
	fun `complete numeric history without qualified Steps source is withheld`() {
		query(
			segmentId = 7L,
			qualified = false,
			steps = StepsHistory(
				count = 123L,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.RECORDED,
				productState = HistoryProductState.READY,
				coverage = StepsHistoryCoverage.COMPLETE,
			),
		).completeStepsForSegment(expectedSegmentId = 7L) shouldBe null
	}

	@Test
	fun `materializing Steps are withheld`() {
		query(
			segmentId = 7L,
			qualified = false,
			steps = StepsHistory(
				count = null,
				availability = HistoryAvailability.AVAILABLE,
				evidence = HistoryEvidence.STARTING,
				productState = HistoryProductState.MATERIALIZING,
				coverage = StepsHistoryCoverage.NONE,
				causes = setOf(StepsHistoryCause.MATERIALIZATION_BEHIND),
			),
		).completeStepsForSegment(expectedSegmentId = 7L) shouldBe null
	}

	@Test
	fun `qualified widget read uses exact segment identity`() = runTest {
		val repository = mockk<TrackingHistoryRepository>()
		every { repository.observeSession(7L) } returns flowOf(
			completeQuery(segmentId = 7L, count = 1_234L),
		)

		readQualifiedWidgetSteps(
			repository = repository,
			session = TrackerSessionSnapshot(id = 7L),
		) shouldBe 1_234L
		verify(exactly = 1) { repository.observeSession(7L) }
	}

	@Test
	fun `qualified widget read preserves cancellation`() = runTest {
		val repository = mockk<TrackingHistoryRepository>()
		every { repository.observeSession(7L) } returns flow {
			throw CancellationException("widget stopped")
		}

		val failure = runCatching {
			readQualifiedWidgetSteps(
				repository = repository,
				session = TrackerSessionSnapshot(id = 7L),
			)
		}.exceptionOrNull()

		failure.shouldBeInstanceOf<CancellationException>()
	}

	private fun completeQuery(segmentId: Long, count: Long): SessionHistoryQuery = query(
		segmentId = segmentId,
		qualified = true,
		steps = StepsHistory(
			count = count,
			availability = HistoryAvailability.AVAILABLE,
			evidence = if (count == 0L) HistoryEvidence.ACTIVE else HistoryEvidence.RECORDED,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		),
	)

	private fun query(
		segmentId: Long,
		qualified: Boolean,
		steps: StepsHistory,
	): SessionHistoryQuery = SessionHistoryQuery.Found(
		SessionHistory(
			segmentId = segmentId,
			capture = HistoryCapture.Exact(
				listOf(
					HistoryCaptureRevision(
						revision = 1L,
						effectiveAt = EpochMs(1_000L),
						capturedSources = setOf(HistorySource.STEPS),
						controlSources = emptySet(),
					),
				),
			),
			qualifiedSources = if (qualified) setOf(HistorySource.STEPS) else emptySet(),
			steps = steps,
		),
	)
}

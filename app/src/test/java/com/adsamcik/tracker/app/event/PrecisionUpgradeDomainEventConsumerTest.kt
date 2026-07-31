package com.adsamcik.tracker.app.event

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.repository.UnconsumedEvent
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
import com.adsamcik.tracker.app.test.FakePreferencesHelper
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.adsamcik.tracker.shared.preferences.R as PrefR

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PrecisionUpgradeDomainEventConsumerTest {

	private lateinit var context: Context
	private lateinit var prefs: Preferences
	private val repository: DomainEventRepository = mockk(relaxed = true)
	private lateinit var consumer: PrecisionUpgradeDomainEventConsumer

	private fun sessionEndedEvent(sessionId: Long = 1L, timestampMs: Long = 1000L) =
		DomainEvent.SessionEnded(
			timestampMs = EpochMs(timestampMs),
			processorId = "test",
			sessionId = sessionId,
			totalDistance = DistanceM(0f),
			totalSteps = StepCount(0),
			duration = DurationMs(0L),
		)

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		prefs = FakePreferencesHelper.setup()

		FakePreferencesHelper.registerKeyMapping(
			PrefR.string.settings_approximate_session_count_key,
			"approximateSessionCount",
		)
		FakePreferencesHelper.registerKeyMapping(
			PrefR.string.settings_should_show_precision_upgrade_key,
			"shouldShowPrecisionUpgrade",
		)
		FakePreferencesHelper.registerKeyMapping(
			PrefR.string.settings_precision_upgrade_dismissed_key,
			"precisionUpgradeDismissed",
		)
		FakePreferencesHelper.registerKeyMapping(
			PrefR.string.settings_location_precision_key,
			"locationPrecisionMode",
		)

		FakePreferencesHelper.data[PrefR.string.settings_location_precision_key] = "APPROXIMATE"
		FakePreferencesHelper.stringKeyData["locationPrecisionMode"] = "APPROXIMATE"

		prefs.edit {
			setInt(PrefR.string.settings_approximate_session_count_key, 0)
			setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, false)
			setBoolean(PrefR.string.settings_precision_upgrade_dismissed_key, false)
			setString(PrefR.string.settings_location_precision_key, "APPROXIMATE")
		}

		consumer = PrecisionUpgradeDomainEventConsumer(repository, context, prefs)
	}

	@After
	fun tearDown() {
		FakePreferencesHelper.tearDown()
		unmockkAll()
	}

	@Test
	fun `no-op when no unconsumed events`() = runTest {
		coEvery {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returns emptyList()
		consumer.processUnconsumed()
		coVerify {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		}
	}

	@Test
	fun `increments counter for SessionEnded in APPROXIMATE mode`() = runTest {
		val event = sessionEndedEvent()
		coEvery {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(UnconsumedEvent(event, 1L)), emptyList())
		consumer.processUnconsumed()

		val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
		count shouldBe 1
	}

	@Test
	fun `sets prompt flag after threshold of 2 sessions`() = runTest {
		coEvery {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(
			listOf(UnconsumedEvent(sessionEndedEvent(1L, 1000L), 1L)),
			listOf(UnconsumedEvent(sessionEndedEvent(2L, 2000L), 2L)),
			emptyList(),
		)
		consumer.processUnconsumed()

		val shouldShow = prefs.getBooleanRes(
			PrefR.string.settings_should_show_precision_upgrade_key,
			false,
		)
		shouldShow shouldBe true
	}

	@Test
	fun `skips counter if user dismissed prompt`() = runTest {
		prefs.edit {
			setBoolean(PrefR.string.settings_precision_upgrade_dismissed_key, true)
		}
		FakePreferencesHelper.stringKeyData["precisionUpgradeDismissed"] = true

		coEvery {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(UnconsumedEvent(sessionEndedEvent(), 1L)), emptyList())
		consumer.processUnconsumed()

		val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
		count shouldBe 0
	}

	@Test
	fun `skips counter if user already in PRECISE mode`() = runTest {
		FakePreferencesHelper.data[PrefR.string.settings_location_precision_key] = "PRECISE"
		FakePreferencesHelper.stringKeyData["locationPrecisionMode"] = "PRECISE"

		coEvery {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(UnconsumedEvent(sessionEndedEvent(), 1L)), emptyList())
		consumer.processUnconsumed()

		val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
		count shouldBe 0
	}

	@Test
	fun `marks events consumed after processing`() = runTest {
		val event = sessionEndedEvent(timestampMs = 5000L)
		coEvery {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(UnconsumedEvent(event, 10L)), emptyList())
		consumer.processUnconsumed()

		coVerify {
			repository.markBatchConsumed(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				upToTimestamp = EpochMs(5000L),
				upToEventId = 10L,
			)
		}
	}

	@Test
	fun `marks each event in a batch immediately after processing it`() = runTest {
		val first = UnconsumedEvent(sessionEndedEvent(sessionId = 1L, timestampMs = 1000L), 1L)
		val second = UnconsumedEvent(sessionEndedEvent(sessionId = 2L, timestampMs = 2000L), 2L)
		coEvery {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(first, second), emptyList())

		consumer.processUnconsumed()

		coVerifyOrder {
			repository.markBatchConsumed(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				EpochMs(1000L),
				1L,
			)
			repository.markBatchConsumed(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				EpochMs(2000L),
				2L,
			)
		}
	}

	@Test
	fun `marks each drained batch separately`() = runTest {
		coEvery {
			repository.getUnconsumedBatchWithIds(
				consumerId = PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(
			listOf(UnconsumedEvent(sessionEndedEvent(sessionId = 1L, timestampMs = 1000L), 1L)),
			listOf(UnconsumedEvent(sessionEndedEvent(sessionId = 2L, timestampMs = 2000L), 2L)),
			emptyList(),
		)

		consumer.processUnconsumed()

		coVerifyOrder {
			repository.markBatchConsumed(PrecisionUpgradeDomainEventConsumer.CONSUMER_ID, EpochMs(1000L), 1L)
			repository.markBatchConsumed(PrecisionUpgradeDomainEventConsumer.CONSUMER_ID, EpochMs(2000L), 2L)
		}
	}
}

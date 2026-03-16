package com.adsamcik.tracker.app.event

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
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
import io.mockk.mockkObject
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

		mockkObject(Logger)
		io.mockk.every { Logger.log(any()) } returns Unit

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
			repository.getUnconsumedBatch(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returns emptyList()
		consumer.processUnconsumed()
		// With empty list, markConsumed is never reached — verify only getUnconsumed was called
		coVerify {
			repository.getUnconsumedBatch(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		}
	}

	@Test
	fun `increments counter for SessionEnded in APPROXIMATE mode`() = runTest {
		coEvery {
			repository.getUnconsumedBatch(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(sessionEndedEvent()), emptyList())
		consumer.processUnconsumed()

		val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
		count shouldBe 1
	}

	@Test
	fun `sets prompt flag after threshold of 2 sessions`() = runTest {
		coEvery {
			repository.getUnconsumedBatch(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(
			listOf(sessionEndedEvent(1L, 1000L)),
			listOf(sessionEndedEvent(2L, 2000L)),
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
			repository.getUnconsumedBatch(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(sessionEndedEvent()), emptyList())
		consumer.processUnconsumed()

		val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
		count shouldBe 0
	}

	@Test
	fun `skips counter if user already in PRECISE mode`() = runTest {
		FakePreferencesHelper.data[PrefR.string.settings_location_precision_key] = "PRECISE"
		FakePreferencesHelper.stringKeyData["locationPrecisionMode"] = "PRECISE"

		coEvery {
			repository.getUnconsumedBatch(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(sessionEndedEvent()), emptyList())
		consumer.processUnconsumed()

		val count = prefs.getIntRes(PrefR.string.settings_approximate_session_count_key, 0)
		count shouldBe 0
	}

	@Test
	fun `marks events consumed after processing`() = runTest {
		val event = sessionEndedEvent(timestampMs = 5000L)
		coEvery {
			repository.getUnconsumedBatch(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(listOf(event), emptyList())
		consumer.processUnconsumed()

		coVerify {
			repository.markConsumed(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				EpochMs(5000L),
			)
		}
	}

	@Test
	fun `marks each drained batch separately`() = runTest {
		coEvery {
			repository.getUnconsumedBatch(
				PrecisionUpgradeDomainEventConsumer.CONSUMER_ID,
				DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
		} returnsMany listOf(
			listOf(sessionEndedEvent(sessionId = 1L, timestampMs = 1000L)),
			listOf(sessionEndedEvent(sessionId = 2L, timestampMs = 2000L)),
			emptyList(),
		)

		consumer.processUnconsumed()

		coVerifyOrder {
			repository.markConsumed(PrecisionUpgradeDomainEventConsumer.CONSUMER_ID, EpochMs(1000L))
			repository.markConsumed(PrecisionUpgradeDomainEventConsumer.CONSUMER_ID, EpochMs(2000L))
		}
	}
}

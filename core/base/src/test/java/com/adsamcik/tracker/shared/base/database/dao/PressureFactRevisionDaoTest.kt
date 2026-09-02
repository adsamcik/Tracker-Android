package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureFactRevisionDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: PressureFactRevisionDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.pressureFactRevisionDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun insertReplayLatestCountAndFullClearRemainExact() = runTest {
		val first = revision(semanticRevision = 1L, admissionOrdinal = 10L)
		val second = revision(semanticRevision = 2L, admissionOrdinal = 11L)

		dao.insert(first) shouldBe 1L
		dao.insert(first) shouldBe -1L
		dao.replay(
			first.writerProjectionId,
			first.writerProjectionVersion,
			first.logicalFactId,
			first.semanticRevision,
			first.mutationId,
			first.sourceAdmissionOrdinal,
		) shouldBe first
		dao.insert(second) shouldBe 2L
		dao.latest(first.writerProjectionId, first.writerProjectionVersion, first.logicalFactId) shouldBe second
		dao.count() shouldBe 2L

		dao.deleteAll()

		dao.count() shouldBe 0L
		dao.latest(first.writerProjectionId, first.writerProjectionVersion, first.logicalFactId) shouldBe null
	}

	@Test
	fun replayRequiresAllUniqueIdentitiesToResolveToOneExactRow() = runTest {
		val stored = revision(semanticRevision = 1L, admissionOrdinal = 10L)
		dao.insert(stored) shouldBe 1L
		val mutationCollision = revision(semanticRevision = 2L, admissionOrdinal = 11L).copy(
			mutationId = stored.mutationId,
		)
		dao.insert(mutationCollision) shouldBe -1L
		dao.replay(
			mutationCollision.writerProjectionId,
			mutationCollision.writerProjectionVersion,
			mutationCollision.logicalFactId,
			mutationCollision.semanticRevision,
			mutationCollision.mutationId,
			mutationCollision.sourceAdmissionOrdinal,
		) shouldBe null

		val admissionCollision = revision(semanticRevision = 2L, admissionOrdinal = 10L)
		dao.insert(admissionCollision) shouldBe -1L
		dao.replay(
			admissionCollision.writerProjectionId,
			admissionCollision.writerProjectionVersion,
			admissionCollision.logicalFactId,
			admissionCollision.semanticRevision,
			admissionCollision.mutationId,
			admissionCollision.sourceAdmissionOrdinal,
		) shouldBe null
		dao.count() shouldBe 1L
	}

	@Test
	fun entityRejectsLegacyOrInternallyContradictoryPressureFacts() {
		shouldThrow<IllegalArgumentException> { revision().copy(payloadVersion = 3) }
		shouldThrow<IllegalArgumentException> {
			revision().copy(writerBindingGeneration = 2L)
		}
		shouldThrow<IllegalArgumentException> {
			revision().copy(lastProviderSequence = 5L)
		}
		shouldThrow<IllegalArgumentException> {
			revision().copy(qualification = PressureFactRevisionEntity.QUALIFICATION_PARTIAL)
		}
		shouldThrow<IllegalArgumentException> {
			revision().copy(closureKind = PressureFactRevisionEntity.CLOSURE_SOURCE_BOUNDARY)
		}
		shouldThrow<IllegalArgumentException> {
			revision().copy(intervalStartTimeMs = 851L)
		}
	}

	private fun revision(
		semanticRevision: Long = 1L,
		admissionOrdinal: Long = 10L,
	) = PressureFactRevisionEntity(
		logicalFactId = LOGICAL_FACT_ID,
		semanticRevision = semanticRevision,
		mutationId = "pressure-mutation-$semanticRevision",
		sourceEventId = "pressure-event-$admissionOrdinal",
		sourceAdmissionOrdinal = admissionOrdinal,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
		intervalStartTimeMs = 850L,
		intervalEndTimeMs = 1_000L,
		windowStartElapsedRealtimeNanos = 1_000_000_000L,
		windowEndElapsedRealtimeNanos = 1_150_000_000L,
		clockDomainId = "boot-1",
		wallTimeUncertaintyMs = 25L,
		sampleCount = 4,
		meanHectopascals = 1_001.5,
		sumSquaredDeviations = 5.0,
		minimumHectopascals = 1_000f,
		maximumHectopascals = 1_003f,
		firstProviderSequence = 1L,
		lastProviderSequence = 4L,
		firstHectopascals = 1_000f,
		lastHectopascals = 1_003f,
		slopeHectopascalsPerSecond = 20.0,
		rSquared = 1.0,
		sensorAccuracy = PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH,
		effectiveSamplePeriodMicros = 50_000,
		effectiveMaximumReportLatencyMicros = 200_000,
		targetWindowDurationNanos = 200_000_000L,
		expectedSampleCount = 4,
		maximumInterSampleGapNanos = 50_000_000L,
		closureKind = PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED,
		qualification = PressureFactRevisionEntity.QUALIFICATION_COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		logicalTrackingId = "tracking-1",
		serviceRunId = "run-1",
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 1L,
		effectChecksum = "pressure-effect-$semanticRevision",
		appliedAtMs = 1_000L,
	)

	private companion object {
		const val LOGICAL_FACT_ID = "pressure-session-facts:window-1"
	}
}

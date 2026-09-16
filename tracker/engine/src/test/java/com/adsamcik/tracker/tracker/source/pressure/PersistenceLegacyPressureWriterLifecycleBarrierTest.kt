package com.adsamcik.tracker.tracker.source.pressure

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierBlockedReason
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierResult
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierToken
import com.adsamcik.tracker.stats.api.repository.PressureSourceEraseBarrierVerification
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.pipeline.persistence.PersistenceProcessor
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseSettlement
import com.adsamcik.tracker.tracker.source.runtime.PressureProviderEraseVerification
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistenceLegacyPressureWriterLifecycleBarrierTest {
	private lateinit var database: AppDatabase
	private lateinit var persistence: PersistenceProcessor
	private lateinit var lifecycleLease: ExclusiveTrackingPersistenceLifecycleLease
	private lateinit var subject: PersistenceLegacyPressureWriterLifecycleBarrier

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure()
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
				owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
				ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
				updatedAtMs = 1L,
			),
		)
		persistence = mockk()
		every { persistence.isPipelineActiveForPersistenceLifecycle() } returns false
		coEvery { persistence.drainOrphanedSignals() } returns true
		lifecycleLease = ExclusiveTrackingPersistenceLifecycleLease()
		subject = PersistenceLegacyPressureWriterLifecycleBarrier(
			database,
			persistence,
			lifecycleLease,
		)
	}

	@Test
	fun `Pressure establishment waits for the live persistence lifecycle`() = runTest {
		val live = lifecycleLease.acquireLivePipeline()
		var providerCalled = false
		val establishing = async {
			subject.establish(0L) {
				providerCalled = true
				PressureProviderEraseSettlement.NoLocalProvider
			}
		}

		yield()
		providerCalled shouldBe false
		live.release()
		establishing.await() shouldBe PressureSourceEraseBarrierResult.NoLocalProvider(
			PressureSourceEraseBarrierToken(
				collectedDataEpoch = 0L,
				providerRegistrationGeneration = null,
				legacyWriteFenceGeneration =
					SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
			),
		)
		providerCalled shouldBe true
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `empty pending lane establishes exact durable fence after provider settlement`() = runTest {
		val result = subject.establish(0L) {
			PressureProviderEraseSettlement.Settled(7L)
		}
		val expectedToken = PressureSourceEraseBarrierToken(
			collectedDataEpoch = 0L,
			providerRegistrationGeneration = 7L,
			legacyWriteFenceGeneration =
				SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
		)

		result shouldBe PressureSourceEraseBarrierResult.Established(
			expectedToken,
		)
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.ownerGeneration shouldBe
			SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION
		coVerify(exactly = 1) { persistence.drainOrphanedSignals() }
		subject.verifySettled(expectedToken) {
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Verified
	}

	@Test
	fun `verification rejects token mismatch before provider reauthentication`() = runTest {
		var providerCalled = false

		subject.verifySettled(
			PressureSourceEraseBarrierToken(0L, 7L, 99L),
			verifyProvider = {
				providerCalled = true
				PressureProviderEraseVerification.Verified
			},
		) shouldBe PressureSourceEraseBarrierVerification.Blocked(
			PressureSourceEraseBarrierBlockedReason.STALE_LIFECYCLE,
		)

		providerCalled shouldBe false
	}

	@Test
	fun `direct Pressure demand appearing after fence blocks erase verification`() = runTest {
		val token = when (val established = subject.establish(0L) {
			PressureProviderEraseSettlement.NoLocalProvider
		}) {
			is PressureSourceEraseBarrierResult.NoLocalProvider -> established.token
			else -> error("Expected an established no-provider Pressure fence")
		}
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = "pressure-stale-demand",
					consumerId = "pressure-test",
					sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					qosCode = 0,
					maximumAgeMs = 1_000L,
					desiredLatencyMs = 1_000L,
					requestedBootId = "boot-test",
					requestedElapsedRealtimeNanos = 1L,
					requestedAtMs = 1L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)
		var providerCalled = false

		subject.verifySettled(token) {
			providerCalled = true
			PressureProviderEraseVerification.Verified
		} shouldBe PressureSourceEraseBarrierVerification.Blocked(
			PressureSourceEraseBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
		)

		providerCalled shouldBe false
		database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		)?.ownerGeneration shouldBe
			SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION
	}
}

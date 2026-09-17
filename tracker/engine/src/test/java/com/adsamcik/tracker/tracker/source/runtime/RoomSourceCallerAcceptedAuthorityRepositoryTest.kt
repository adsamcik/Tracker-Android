package com.adsamcik.tracker.tracker.source.runtime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEffectChecksum
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerRequest
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSourceCallerAcceptedAuthorityRepositoryTest {
	private lateinit var database: AppDatabase
	private lateinit var repository: RoomSourceCallerAcceptedAuthorityRepository

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		repository = RoomSourceCallerAcceptedAuthorityRepository(database)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `normalized accepted authority survives recreation and retires exactly`() = runTest {
		val reference = SourceCallerReplayReference("room-authority")
		repository.insertIfAbsent(reference, authority(), createdAtMs = 100L) shouldBe true
		RoomSourceCallerAcceptedAuthorityRepository(database).load(reference)
			.shouldBeInstanceOf<StoredSourceCallerAuthorityLoadResult.Available>()
			.authority shouldBe authority()
		repository.insertIfAbsent(reference, authority(), createdAtMs = 101L) shouldBe false

		repository.retire(reference, "SESSION_TERMINAL", 200L) shouldBe true
		repository.load(reference) shouldBe StoredSourceCallerAuthorityLoadResult.Retired
		repository.retire(reference, "SESSION_TERMINAL", 201L) shouldBe true
	}

	@Test
	fun `tamper and unknown format never reconstruct accepted authority`() = runTest {
		val reference = SourceCallerReplayReference("tampered-authority")
		repository.insertIfAbsent(reference, authority(), createdAtMs = 100L) shouldBe true
		val row = database.sourceCallerAuthorityDao().rows(reference.value).single()
		database.sourceCallerAuthorityDao().update(
			listOf(row.copy(ownerCasToken = "tampered")),
		) shouldBe 1
		repository.load(reference) shouldBe StoredSourceCallerAuthorityLoadResult.Corrupt

		val forgedReference = SourceCallerReplayReference("forged-reference")
		database.sourceCallerAuthorityDao().insert(
			listOf(row.copy(reference = forgedReference.value)),
		)
		repository.load(forgedReference) shouldBe StoredSourceCallerAuthorityLoadResult.Corrupt

		val futureReference = SourceCallerReplayReference("future-authority")
		database.sourceCallerAuthorityDao().insert(
			SourceCallerAcceptedAuthorityEffectChecksum.seal(
				listOf(row.copy(
					reference = futureReference.value,
					formatVersion = SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION + 1,
					effectChecksum = "pending",
				)),
			),
		)
		repository.load(futureReference) shouldBe StoredSourceCallerAuthorityLoadResult.Corrupt
	}

	@Test
	fun `authority insertion rolls back with its owning lifecycle transaction`() = runTest {
		val reference = SourceCallerReplayReference("rolled-back-authority")
		runCatching {
			database.withTransaction {
				repository.insertIfAbsent(reference, authority(), createdAtMs = 100L) shouldBe true
				error("rollback")
			}
		}

		repository.load(reference) shouldBe StoredSourceCallerAuthorityLoadResult.Missing
	}

	@Test
	fun `retired authority cannot be replayed by reference`() = runTest {
		val identity = authority().permittedDemandIdentities.single()
		val guard = ExactSourceCallerGuard(
			SourceCallerAuthoritySnapshotReader {
				SourceCallerAuthoritySnapshot(
					setOf(identity),
					TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
				)
			},
			repository,
		)
		val accepted = guard.accept(
			SourceCallerRequest.ManualSessionStart(
				setOf(TrackingSource.LOCATION),
				requireNotNull(identity.manifestIdentity),
				setOf(identity),
			),
		).shouldBeInstanceOf<SourceCallerGuardResult.Permitted>()
		repository.retire(accepted.receipt.reference, "TERMINAL", 200L) shouldBe true

		val replay = guard.accept(
			SourceCallerRequest.Replay(
				SourceCallerReplayKind.PROCESS_RECOVERY,
				accepted.receipt.reference,
				TrackingPurpose.SESSION_CAPTURE,
				setOf(identity),
			),
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()
		replay.rejection.reason shouldBe SourceCallerRejectionReason.REPLAY_AUTHORITY_RETIRED
	}

	@Test
	fun `retired authority pruning is bounded and leaves active references`() = runTest {
		val first = SourceCallerReplayReference("retired-first")
		val second = SourceCallerReplayReference("retired-second")
		val active = SourceCallerReplayReference("still-active")
		listOf(first, second, active).forEach { reference ->
			repository.insertIfAbsent(reference, authority(), createdAtMs = 100L) shouldBe true
		}
		repository.retire(first, "TERMINAL", 200L) shouldBe true
		repository.retire(second, "TERMINAL", 300L) shouldBe true

		repository.pruneRetired(retiredBeforeOrAtMs = 250L, limit = 1) shouldBe 1
		repository.load(first) shouldBe StoredSourceCallerAuthorityLoadResult.Missing
		repository.load(second) shouldBe StoredSourceCallerAuthorityLoadResult.Retired
		repository.load(active).shouldBeInstanceOf<StoredSourceCallerAuthorityLoadResult.Available>()
		repository.delete(active) shouldBe true
		repository.load(active) shouldBe StoredSourceCallerAuthorityLoadResult.Missing
	}

	@Test
	fun `real dispatcher separates redelivery recovery and policy reconfiguration authority`() = runTest {
		val originalManifest = SourceCallerManifestIdentity("logical-recovery", 1L)
		var snapshot = SourceCallerAuthoritySnapshot(
			setOf(captureIdentity(originalManifest)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val guard = ExactSourceCallerGuard(
			SourceCallerAuthoritySnapshotReader { snapshot },
			repository,
		)
		val dispatcher = GuardedSourceCallerDemandDispatcher(
			database = database,
			authorityReader = CurrentSourceCallerAuthorityProvider { snapshot },
			guard = guard,
			sourceBroker = SourceBroker(database),
			authorityRepository = repository,
		)
		val original = dispatcher.dispatchSession(
			sessionRequest(originalManifest, SessionStartOrigin.MANUAL_FOREGROUND_START),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()
		val originalDemands = database.sourceBrokerDao()
			.demandHistory("session:${originalManifest.logicalTrackingId}")

		dispatcher.replayPreparedSession(
			originalManifest,
			original.receipt.reference,
			SourceCallerReplayKind.ACTIVE_REDELIVERY,
		).shouldBeInstanceOf<SourceCallerGuardResult.Permitted>()
		database.sourceBrokerDao().demandHistory("session:${originalManifest.logicalTrackingId}") shouldBe
			originalDemands

		val replacementManifest = SourceCallerManifestIdentity("logical-recovery", 2L)
		snapshot = SourceCallerAuthoritySnapshot(
			setOf(captureIdentity(replacementManifest)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val replacement = dispatcher.dispatchSession(
			sessionRequest(replacementManifest, SessionStartOrigin.RECOVERY),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()

		(replacement.receipt.reference == original.receipt.reference) shouldBe false
		repository.load(original.receipt.reference) shouldBe
			StoredSourceCallerAuthorityLoadResult.Retired
		repository.load(replacement.receipt.reference)
			.shouldBeInstanceOf<StoredSourceCallerAuthorityLoadResult.Available>()

		val policyManifest = SourceCallerManifestIdentity("logical-recovery", 3L)
		snapshot = SourceCallerAuthoritySnapshot(
			setOf(captureIdentity(policyManifest)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val policyReplacement = dispatcher.dispatchSession(
			sessionRequest(policyManifest, SessionStartOrigin.POLICY_RECONCILIATION),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()
		repository.load(replacement.receipt.reference)
			.shouldBeInstanceOf<StoredSourceCallerAuthorityLoadResult.Available>()

		database.withTransaction {
			SourceBroker(database).retireSupersededSessionAuthoritiesInTransaction(
				logicalTrackingId = policyManifest.logicalTrackingId,
				currentReference = policyReplacement.receipt.reference,
				expectedSupersededReference = replacement.receipt.reference,
				wallTimeMs = 500L,
			)
		} shouldBe true
		repository.load(replacement.receipt.reference) shouldBe
			StoredSourceCallerAuthorityLoadResult.Retired
	}

	private fun sessionRequest(
		manifestIdentity: SourceCallerManifestIdentity,
		startOrigin: SessionStartOrigin,
	) = SessionSourceDemandDispatchRequest(
		manifest = SessionManifestVersionEntity(
			logicalTrackingId = manifestIdentity.logicalTrackingId,
			manifestRevision = manifestIdentity.manifestRevision,
			serviceRunId = if (manifestIdentity.manifestRevision == 1L) {
				"service-run-1"
			} else {
				"service-run-2"
			},
			sessionMode = SessionMode.MANUAL.name,
			sourcePolicyRevision = 3L,
			acquisitionPlanRevision = manifestIdentity.manifestRevision,
			rolloutRevision = 6L,
			startOrigin = startOrigin.name,
			effectiveBootId = "boot-1",
			effectiveElapsedRealtimeNanos = 100L + manifestIdentity.manifestRevision,
			effectiveWallTimeMs = 200L + manifestIdentity.manifestRevision,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "test-checksum-${manifestIdentity.manifestRevision}",
		),
		bindings = listOf(
			SessionManifestSourceEntity(
				logicalTrackingId = manifestIdentity.logicalTrackingId,
				manifestRevision = manifestIdentity.manifestRevision,
				sourceKind = TrackingSource.LOCATION.stableCode,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				consentEpoch = 4L,
				persistenceEligible = true,
				qosCode = 0,
			),
		),
		sessionMode = SessionMode.MANUAL,
		startOrigin = startOrigin,
		mutation = if (manifestIdentity.manifestRevision == 1L) {
			SessionDemandMutation.STAGE_UNTIL_FOREGROUND
		} else {
			SessionDemandMutation.REPLACE_ACTIVE
		},
		lifecycleLeaseGeneration = 7L,
		bootId = "boot-1",
		elapsedRealtimeNanos = 100L + manifestIdentity.manifestRevision,
		wallTimeMs = 200L + manifestIdentity.manifestRevision,
	)

	private fun captureIdentity(
		manifestIdentity: SourceCallerManifestIdentity,
	) = SourceCallerDemandIdentity(
		purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
			sourcePurpose =
				TrackingSource.LOCATION.forPurpose(TrackingPurpose.SESSION_CAPTURE),
			policyRevision = 3L,
			consentEpoch = 4L,
			collectedDataEpoch = 5L,
			rolloutRevision = 6L,
			executionRevision = 7L,
			ownerCasToken = "owner",
		),
		manifestIdentity = manifestIdentity,
	)

	private fun authority() = StoredSourceCallerAuthority(
		origin = StoredSourceCallerOrigin.MANUAL,
		purpose = TrackingPurpose.SESSION_CAPTURE,
		permittedDemandIdentities = setOf(
			SourceCallerDemandIdentity(
				purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
					sourcePurpose =
						TrackingSource.LOCATION.forPurpose(TrackingPurpose.SESSION_CAPTURE),
					policyRevision = 3L,
					consentEpoch = 4L,
					collectedDataEpoch = 5L,
					rolloutRevision = 6L,
					executionRevision = 7L,
					ownerCasToken = "owner",
				),
				manifestIdentity = SourceCallerManifestIdentity("logical", 8L),
			),
		),
	)
}

package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GuardedSourceCallerDemandDispatcherTest {
	@Test
	fun `fresh dispatch reads strict current manifest authority`() = runTest {
		val snapshot = SourceCallerAuthoritySnapshot(
			setOf(capture(TrackingSource.LOCATION)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		var currentReads = 0
		var replayReads = 0
		val provider = object : CurrentSourceCallerAuthorityProvider {
			override suspend fun readCurrentManifest(
				identity: SourceCallerManifestIdentity,
			): SourceCallerAuthoritySnapshot {
				currentReads++
				return snapshot
			}

			override suspend fun readReplayManifest(
				identity: SourceCallerManifestIdentity,
				replayKind: SourceCallerReplayKind,
			): SourceCallerAuthoritySnapshot {
				replayReads++
				return snapshot
			}
		}
		val repository = InMemorySourceCallerAuthorityRepository()
		val dispatcher = GuardedSourceCallerDemandDispatcher(
			database = mockk(relaxed = true),
			authorityReader = provider,
			guard = ExactSourceCallerGuard(
				SourceCallerAuthoritySnapshotReader { snapshot },
				repository,
			),
			sourceBroker = brokerReturning(listOf(demand(TrackingSource.LOCATION))),
			authorityRepository = repository,
		)

		dispatcher.dispatchSession(
			sessionRequest(bindings = listOf(binding(TrackingSource.LOCATION))),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()

		currentReads shouldBe 1
		replayReads shouldBe 0
	}

	@Test
	fun `redelivery dispatch reads replay-specific manifest authority`() = runTest {
		val snapshot = SourceCallerAuthoritySnapshot(
			setOf(capture(TrackingSource.LOCATION)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		var replayKindRead: SourceCallerReplayKind? = null
		val provider = object : CurrentSourceCallerAuthorityProvider {
			override suspend fun readCurrentManifest(
				identity: SourceCallerManifestIdentity,
			): SourceCallerAuthoritySnapshot = snapshot

			override suspend fun readReplayManifest(
				identity: SourceCallerManifestIdentity,
				replayKind: SourceCallerReplayKind,
			): SourceCallerAuthoritySnapshot {
				replayKindRead = replayKind
				return snapshot
			}
		}
		val repository = InMemorySourceCallerAuthorityRepository()
		val dispatcher = GuardedSourceCallerDemandDispatcher(
			database = mockk(relaxed = true),
			authorityReader = provider,
			guard = ExactSourceCallerGuard(
				SourceCallerAuthoritySnapshotReader { snapshot },
				repository,
			),
			sourceBroker = brokerReturning(listOf(demand(TrackingSource.LOCATION))),
			authorityRepository = repository,
		)
		val accepted = dispatcher.dispatchSession(
			sessionRequest(bindings = listOf(binding(TrackingSource.LOCATION))),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()

		dispatcher.replayPreparedSession(
			MANIFEST,
			accepted.receipt.reference,
			SourceCallerReplayKind.ACTIVE_REDELIVERY,
		).shouldBeInstanceOf<SourceCallerGuardResult.Permitted>()

		replayKindRead shouldBe SourceCallerReplayKind.ACTIVE_REDELIVERY
	}

	@Test
	fun `recovery authentication rejects a stale exact owner before descriptor adoption`() = runTest {
		var snapshot = SourceCallerAuthoritySnapshot(
			setOf(capture(TrackingSource.LOCATION)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val provider = object : CurrentSourceCallerAuthorityProvider {
			override suspend fun readCurrentManifest(
				identity: SourceCallerManifestIdentity,
			): SourceCallerAuthoritySnapshot = snapshot

			override suspend fun readReplayManifest(
				identity: SourceCallerManifestIdentity,
				replayKind: SourceCallerReplayKind,
			): SourceCallerAuthoritySnapshot = snapshot
		}
		val repository = InMemorySourceCallerAuthorityRepository()
		val dispatcher = GuardedSourceCallerDemandDispatcher(
			database = mockk(relaxed = true),
			authorityReader = provider,
			guard = ExactSourceCallerGuard(
				SourceCallerAuthoritySnapshotReader { snapshot },
				repository,
			),
			sourceBroker = brokerReturning(listOf(demand(TrackingSource.LOCATION))),
			authorityRepository = repository,
		)
		val accepted = dispatcher.dispatchSession(
			sessionRequest(bindings = listOf(binding(TrackingSource.LOCATION))),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()
		snapshot = snapshot.copy(
			currentDemandIdentities = setOf(
				capture(TrackingSource.LOCATION).copy(
					purposeLeaseIdentity = capture(TrackingSource.LOCATION)
						.purposeLeaseIdentity
						.copy(ownerCasToken = "replacement-owner"),
				),
			),
		)

		val rejected = dispatcher.authenticatePreparedSession(
			MANIFEST,
			accepted.receipt.reference,
			SourceCallerReplayKind.PROCESS_RECOVERY,
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()

		rejected.rejection.reason shouldBe SourceCallerRejectionReason.STALE_OWNER_CAS_TOKEN
	}

	@Test
	fun `manual multisource dispatch stages exactly the guard-permitted identities`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val cell = capture(TrackingSource.CELL)
		val snapshot = SourceCallerAuthoritySnapshot(
			setOf(location, cell),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val broker = brokerReturning(
			listOf(
				demand(TrackingSource.LOCATION),
				demand(TrackingSource.CELL),
			),
		)
		val dispatcher = dispatcher(snapshot, broker)

		val result = dispatcher.dispatchSession(
			sessionRequest(
				bindings = listOf(
					binding(TrackingSource.LOCATION),
					binding(TrackingSource.CELL),
				),
			),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()

		result.receipt.permittedDemandIdentities shouldBe setOf(location, cell)
		coVerify(exactly = 1) {
			broker.stageSessionDemandsInTransaction(
				MANIFEST.logicalTrackingId,
				any(),
				BOOT_ID,
				ELAPSED,
				WALL,
			)
		}
	}

	@Test
	fun `manual dispatch rejects a hidden current control demand before broker mutation`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val control = control()
		val broker = brokerReturning(listOf(demand(TrackingSource.LOCATION)))
		val dispatcher = dispatcher(
			SourceCallerAuthoritySnapshot(
				setOf(location, control),
				TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
			),
			broker,
		)

		val result = dispatcher.dispatchSession(
			sessionRequest(
				bindings = listOf(
					binding(TrackingSource.LOCATION),
					binding(TrackingSource.ACTIVITY, purpose = "CONTROL"),
				),
			),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Rejected>()

		result.rejection.reason shouldBe SourceCallerRejectionReason.UNDECLARED_DEMAND
		coVerify(exactly = 0) {
			broker.stageSessionDemandsInTransaction(any(), any(), any(), any(), any())
		}
	}

	@Test
	fun `authority-only refresh issues a receipt without mutating broker demand`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val broker = brokerReturning(listOf(demand(TrackingSource.LOCATION)))
		val dispatcher = dispatcher(
			SourceCallerAuthoritySnapshot(
				setOf(location),
				TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
			),
			broker,
		)

		dispatcher.dispatchSession(
			sessionRequest(
				bindings = listOf(binding(TrackingSource.LOCATION)),
			).copy(mutation = SessionDemandMutation.AUTHORITY_ONLY),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()

		coVerify(exactly = 0) {
			broker.stageSessionDemandsInTransaction(any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) {
			broker.replaceSessionDemandsInTransaction(
				any(),
				any(),
				any(),
				any(),
				any(),
				any(),
			)
		}
	}

	@Test
	fun `automatic dispatch fails closed when Activity control readiness is missing`() = runTest {
		val location = capture(TrackingSource.LOCATION)
		val broker = brokerReturning(emptyList())
		val dispatcher = dispatcher(
			SourceCallerAuthoritySnapshot(
				setOf(location),
				TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
			),
			broker,
		)

		val result = dispatcher.dispatchSession(
			sessionRequest(
				sessionMode = SessionMode.AUTOMATIC,
				startOrigin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
				bindings = listOf(
					binding(TrackingSource.LOCATION),
					binding(TrackingSource.ACTIVITY, purpose = "CONTROL"),
				),
			),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Rejected>()

		result.rejection.reason shouldBe SourceCallerRejectionReason.AUTOMATIC_CONTROL_UNAVAILABLE
		coVerify(exactly = 0) {
			broker.stageSessionDemandsInTransaction(any(), any(), any(), any(), any())
		}
	}

	@Test
	fun `foreground replay rejects authority escalation without touching broker`() = runTest {
		var snapshot = SourceCallerAuthoritySnapshot(
			setOf(capture(TrackingSource.LOCATION)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val repository = InMemorySourceCallerAuthorityRepository()
		val guard = ExactSourceCallerGuard(
			authorityReader = SourceCallerAuthoritySnapshotReader { snapshot },
			authorityRepository = repository,
		)
		val broker = brokerReturning(listOf(demand(TrackingSource.LOCATION)))
		val dispatcher = GuardedSourceCallerDemandDispatcher(
			database = mockk(relaxed = true),
			authorityReader = CurrentSourceCallerAuthorityProvider { snapshot },
			guard = guard,
			sourceBroker = broker,
			authorityRepository = repository,
		)
		val accepted = dispatcher.dispatchSession(
			sessionRequest(bindings = listOf(binding(TrackingSource.LOCATION))),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()

		snapshot = SourceCallerAuthoritySnapshot(
			setOf(
				capture(TrackingSource.LOCATION).copy(
					purposeLeaseIdentity = capture(TrackingSource.LOCATION)
						.purposeLeaseIdentity
						.copy(executionRevision = EXECUTION_REVISION + 1L),
				),
			),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val replay = dispatcher.replayPreparedSession(
			MANIFEST,
			accepted.receipt.reference,
			SourceCallerReplayKind.FOREGROUND_SERVICE_DELIVERY,
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()

		replay.rejection.reason shouldBe SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION
	}

	@Test
	fun `prepared activation requires the exact current accepted authority`() = runTest {
		var snapshot = SourceCallerAuthoritySnapshot(
			setOf(capture(TrackingSource.LOCATION)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val repository = InMemorySourceCallerAuthorityRepository()
		val broker = brokerReturning(listOf(demand(TrackingSource.LOCATION)))
		val dispatcher = GuardedSourceCallerDemandDispatcher(
			database = mockk(relaxed = true),
			authorityReader = CurrentSourceCallerAuthorityProvider { snapshot },
			guard = ExactSourceCallerGuard(
				SourceCallerAuthoritySnapshotReader { snapshot },
				repository,
			),
			sourceBroker = broker,
			authorityRepository = repository,
		)
		val accepted = dispatcher.dispatchSession(
			sessionRequest(bindings = listOf(binding(TrackingSource.LOCATION))),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()
		val demand = demand(TrackingSource.LOCATION).copy(
			sourceCallerAuthorityReference = accepted.receipt.reference.value,
		)

		dispatcher.permitsActivation(
			accepted.receipt.reference,
			MANIFEST,
			listOf(demand),
		) shouldBe true

		snapshot = SourceCallerAuthoritySnapshot(
			setOf(
				capture(TrackingSource.LOCATION).copy(
					purposeLeaseIdentity = capture(TrackingSource.LOCATION)
						.purposeLeaseIdentity.copy(consentEpoch = CONSENT_EPOCH + 1L),
				),
			),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		dispatcher.permitsActivation(
			accepted.receipt.reference,
			MANIFEST,
			listOf(demand),
		) shouldBe false
	}

	@Test
	fun `recovery cannot replace the accepted manifest identity`() = runTest {
		var snapshot = SourceCallerAuthoritySnapshot(
			setOf(capture(TrackingSource.LOCATION)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val repository = InMemorySourceCallerAuthorityRepository()
		val guard = ExactSourceCallerGuard(
			authorityReader = SourceCallerAuthoritySnapshotReader { snapshot },
			authorityRepository = repository,
		)
		val broker = brokerReturning(listOf(demand(TrackingSource.LOCATION)))
		val dispatcher = GuardedSourceCallerDemandDispatcher(
			database = mockk(relaxed = true),
			authorityReader = CurrentSourceCallerAuthorityProvider { snapshot },
			guard = guard,
			sourceBroker = broker,
			authorityRepository = repository,
		)
		val accepted = dispatcher.dispatchSession(
			sessionRequest(bindings = listOf(binding(TrackingSource.LOCATION))),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()
		val replacementManifest = SourceCallerManifestIdentity(
			MANIFEST.logicalTrackingId,
			MANIFEST.manifestRevision + 1L,
		)
		snapshot = SourceCallerAuthoritySnapshot(
			setOf(
				capture(TrackingSource.LOCATION).copy(
					manifestIdentity = replacementManifest,
				),
			),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)

		val replay = dispatcher.replayPreparedSession(
			replacementManifest,
			accepted.receipt.reference,
			SourceCallerReplayKind.PROCESS_RECOVERY,
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()

		replay.rejection.reason shouldBe SourceCallerRejectionReason.REPLAY_AUTHORITY_ESCALATION
	}

	@Test
	fun `policy reconciliation cannot enter the replay path`() = runTest {
		val dispatcher = dispatcher(
			SourceCallerAuthoritySnapshot(
				setOf(capture(TrackingSource.LOCATION)),
				TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
			),
			brokerReturning(emptyList()),
		)

		dispatcher.replayPreparedSession(
			MANIFEST,
			SourceCallerReplayReference("old-reference"),
			SourceCallerReplayKind.POLICY_RECONCILIATION,
		).shouldBeInstanceOf<SourceCallerGuardResult.Rejected>()
			.rejection.reason shouldBe
			SourceCallerRejectionReason.REPLAY_KIND_REQUIRES_FRESH_ACCEPTANCE
	}

	@Test
	fun `new recovery manifest receives a new acceptance after exact prior replay`() = runTest {
		var snapshot = SourceCallerAuthoritySnapshot(
			setOf(capture(TrackingSource.LOCATION)),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val repository = InMemorySourceCallerAuthorityRepository()
		val guard = ExactSourceCallerGuard(
			authorityReader = SourceCallerAuthoritySnapshotReader { snapshot },
			authorityRepository = repository,
		)
		val broker = brokerReturning(listOf(demand(TrackingSource.LOCATION)))
		val dispatcher = GuardedSourceCallerDemandDispatcher(
			database = mockk(relaxed = true),
			authorityReader = CurrentSourceCallerAuthorityProvider { snapshot },
			guard = guard,
			sourceBroker = broker,
			authorityRepository = repository,
		)
		val original = dispatcher.dispatchSession(
			sessionRequest(bindings = listOf(binding(TrackingSource.LOCATION))),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()
		dispatcher.replayPreparedSession(
			MANIFEST,
			original.receipt.reference,
			SourceCallerReplayKind.PROCESS_RECOVERY,
		).shouldBeInstanceOf<SourceCallerGuardResult.Permitted>()

		val replacementManifest = SourceCallerManifestIdentity(
			MANIFEST.logicalTrackingId,
			MANIFEST.manifestRevision + 1L,
		)
		snapshot = SourceCallerAuthoritySnapshot(
			setOf(
				capture(TrackingSource.LOCATION).copy(
					manifestIdentity = replacementManifest,
				),
			),
			TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
		)
		val replacement = dispatcher.dispatchSession(
			sessionRequest(
				manifest = manifest(
					manifestIdentity = replacementManifest,
					startOrigin = SessionStartOrigin.RECOVERY,
				),
				startOrigin = SessionStartOrigin.RECOVERY,
				bindings = listOf(
					binding(
						TrackingSource.LOCATION,
						manifestIdentity = replacementManifest,
					),
				),
			),
		).shouldBeInstanceOf<SessionSourceDemandDispatchResult.Permitted>()

		(replacement.receipt.reference == original.receipt.reference) shouldBe false
	}

	private fun dispatcher(
		snapshot: SourceCallerAuthoritySnapshot,
		broker: SourceBroker,
	): GuardedSourceCallerDemandDispatcher {
		val repository = InMemorySourceCallerAuthorityRepository()
		return GuardedSourceCallerDemandDispatcher(
			database = mockk(relaxed = true),
			authorityReader = CurrentSourceCallerAuthorityProvider { snapshot },
			guard = ExactSourceCallerGuard(
				authorityReader = SourceCallerAuthoritySnapshotReader { snapshot },
				authorityRepository = repository,
			),
			sourceBroker = broker,
			authorityRepository = repository,
		)
	}

	private fun brokerReturning(demands: List<SourceDemandEntity>): SourceBroker =
		mockk<SourceBroker> {
			every {
				buildSessionDemands(
					any(),
					any(),
					any(),
					any(),
					any(),
					any(),
					any(),
					any(),
					any(),
					any(),
				)
			} answers {
				demands.map { demand ->
					demand.copy(
						logicalTrackingId = arg(0),
						serviceRunId = arg(1),
						manifestRevision = arg(2),
						lifecycleLeaseGeneration = arg(3),
						sourcePolicyRevision = arg(4),
						requestedBootId = arg(6),
						requestedElapsedRealtimeNanos = arg(7),
						requestedAtMs = arg(8),
						sourceCallerAuthorityReference = arg(9),
					)
				}
			}
			coEvery {
				stageSessionDemandsInTransaction(any(), any(), any(), any(), any())
			} returns Unit
		}

	private fun sessionRequest(
		sessionMode: SessionMode = SessionMode.MANUAL,
		startOrigin: SessionStartOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START,
		manifest: SessionManifestVersionEntity = manifest(sessionMode, startOrigin),
		bindings: List<SessionManifestSourceEntity>,
	) = SessionSourceDemandDispatchRequest(
		manifest = manifest,
		bindings = bindings,
		sessionMode = sessionMode,
		startOrigin = startOrigin,
		mutation = SessionDemandMutation.STAGE_UNTIL_FOREGROUND,
		lifecycleLeaseGeneration = EXECUTION_REVISION,
		bootId = BOOT_ID,
		elapsedRealtimeNanos = ELAPSED,
		wallTimeMs = WALL,
	)

	private fun manifest(
		sessionMode: SessionMode = SessionMode.MANUAL,
		startOrigin: SessionStartOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START,
		manifestIdentity: SourceCallerManifestIdentity = MANIFEST,
	) = SessionManifestVersionEntity(
		logicalTrackingId = manifestIdentity.logicalTrackingId,
		manifestRevision = manifestIdentity.manifestRevision,
		serviceRunId = "service-run",
		sessionMode = sessionMode.name,
		sourcePolicyRevision = POLICY_REVISION,
		acquisitionPlanRevision = 1L,
		rolloutRevision = ROLLOUT_REVISION,
		startOrigin = startOrigin.name,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = ELAPSED,
		effectiveWallTimeMs = WALL,
		zoneId = "UTC",
		automationEpoch = null,
		changeReason = "TEST",
		manifestChecksum = "checksum",
	)

	private fun binding(
		source: TrackingSource,
		purpose: String = SourceBrokerPurpose.SESSION_CAPTURE,
		manifestIdentity: SourceCallerManifestIdentity = MANIFEST,
	) = SessionManifestSourceEntity(
		logicalTrackingId = manifestIdentity.logicalTrackingId,
		manifestRevision = manifestIdentity.manifestRevision,
		sourceKind = source.stableCode,
		purpose = purpose,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = purpose == SourceBrokerPurpose.SESSION_CAPTURE,
		qosCode = 0,
	)

	private fun capture(source: TrackingSource) = SourceCallerDemandIdentity(
		purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
			sourcePurpose = source.forPurpose(TrackingPurpose.SESSION_CAPTURE),
			policyRevision = POLICY_REVISION,
			consentEpoch = CONSENT_EPOCH,
			collectedDataEpoch = 0L,
			rolloutRevision = ROLLOUT_REVISION,
			executionRevision = EXECUTION_REVISION,
			ownerCasToken = "session-owner",
		),
		manifestIdentity = MANIFEST,
	)

	private fun control() = SourceCallerDemandIdentity(
		purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
			sourcePurpose = TrackingSource.ACTIVITY.forPurpose(TrackingPurpose.CONTROL),
			policyRevision = POLICY_REVISION,
			consentEpoch = CONSENT_EPOCH,
			collectedDataEpoch = 0L,
			rolloutRevision = ROLLOUT_REVISION,
			executionRevision = EXECUTION_REVISION,
			ownerCasToken = "control-owner",
		),
		manifestIdentity = null,
	)

	private fun demand(source: TrackingSource) = SourceDemandEntity(
		demandId = "demand-${source.name.lowercase()}",
		consumerId = "session:${MANIFEST.logicalTrackingId}",
		sourceKind = source.stableCode,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = MANIFEST.logicalTrackingId,
		serviceRunId = "service-run",
		manifestRevision = MANIFEST.manifestRevision,
		lifecycleLeaseGeneration = EXECUTION_REVISION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = 0,
		maximumAgeMs = 0L,
		desiredLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = ELAPSED,
		requestedAtMs = WALL,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private class InMemorySourceCallerAuthorityRepository :
		SourceCallerAcceptedAuthorityRepository {
		private val persisted =
			mutableMapOf<SourceCallerReplayReference, StoredSourceCallerAuthority>()

		override suspend fun insertIfAbsent(
			reference: SourceCallerReplayReference,
			authority: StoredSourceCallerAuthority,
			createdAtMs: Long,
		): Boolean = persisted.putIfAbsent(reference, authority) == null

		override suspend fun load(
			reference: SourceCallerReplayReference,
		): StoredSourceCallerAuthorityLoadResult = persisted[reference]?.let {
			StoredSourceCallerAuthorityLoadResult.Available(it)
		} ?: StoredSourceCallerAuthorityLoadResult.Missing

		override suspend fun retire(
			reference: SourceCallerReplayReference,
			reason: String,
			retiredAtMs: Long,
		): Boolean = persisted.remove(reference) != null

		override suspend fun delete(reference: SourceCallerReplayReference): Boolean =
			persisted.remove(reference) != null

		override suspend fun pruneRetired(
			retiredBeforeOrAtMs: Long,
			limit: Int,
		): Int = 0
	}

	private companion object {
		val MANIFEST = SourceCallerManifestIdentity("logical-session", 1L)
		const val POLICY_REVISION = 11L
		const val CONSENT_EPOCH = 7L
		const val ROLLOUT_REVISION = 13L
		const val EXECUTION_REVISION = 17L
		const val BOOT_ID = "boot-1"
		const val ELAPSED = 100L
		const val WALL = 200L
	}
}

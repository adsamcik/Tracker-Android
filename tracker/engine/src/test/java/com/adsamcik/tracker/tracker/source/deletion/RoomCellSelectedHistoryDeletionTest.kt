package com.adsamcik.tracker.tracker.source.deletion

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCell
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCellRequest
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCellResult
import com.adsamcik.tracker.shared.base.database.DeletedCapturedCellSelectionAuthentication
import com.adsamcik.tracker.shared.base.database.authenticateDeletedCapturedCellSelectionInTransaction
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.CellHistoryDeletionBlockedReason
import com.adsamcik.tracker.stats.api.repository.CellHistoryDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistoryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistoryResult
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomCellSelectedHistoryDeletionTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(
			ApplicationProvider.getApplicationContext<Application>(),
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `imported action forwards exact authenticated selection epoch and request time`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
		var received: DeleteSelectedImportedCellRequest? = null
		val service = service(
			DeleteSelectedImportedCell { request ->
				received = request
				DeleteSelectedImportedCellResult.Deleted(2, 3, 4)
			},
		)
		val selection = importedSelection()

		service.delete(DeleteCellHistoryRequest(selection, 900L)) shouldBe
			DeleteCellHistoryResult.Deleted(1, 3, 4)
		received shouldBe DeleteSelectedImportedCellRequest(
			identity = com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity(
				selection.identity.value,
			),
			expectedImportRevision = selection.importRevision,
			expectedContentChecksum =
			com.adsamcik.tracker.shared.base.database.PortableCellDigest(
				selection.contentChecksum.value,
			),
			expectedCollectedDataEpoch = 7L,
			deletedAtMs = 900L,
		)
	}

	@Test
	fun `local unknown selection is not found without deleting any source data`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))

		service().delete(
			DeleteCellHistoryRequest(
				LocalCellHistorySelection(LocalCellHistoryIdentity("f".repeat(64))),
				900L,
			),
		) shouldBe DeleteCellHistoryResult.NotFound

		database.sourceDeletionFenceDao().countAll() shouldBe 0L
		database.cellCapturedFactDao().revisionCount() shouldBe 0L
	}

	@Test
	fun `positive local identity owner with no runs is invalid replacement scope`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
		val logicalId = "known-without-runs"
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL",
				clockDomainId = "boot",
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				cutoffAtMs = 2L,
				cutoffElapsedNanos = 2L,
				completedAtMs = 2L,
				finalAdmissionOrdinal = 0L,
				failureCode = null,
			),
		)
		val selection = LocalCellHistorySelection(
			LocalCellHistoryIdentity(
				com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity.derive(
					com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind.LOGICAL_ENTRY,
					logicalId,
				).value,
			),
		)

		service().delete(DeleteCellHistoryRequest(selection, 900L)) shouldBe
			DeleteCellHistoryResult.Unverifiable(
				CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
			)
	}

	@Test
	fun `local opaque lookup cap is explicit unavailable rather than false not found`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
		repeat(4_097) { index ->
			database.sourceSessionDao().insertSession(
				LogicalTrackingSessionEntity(
					logicalTrackingId = "local-$index",
					state = "FINALIZED",
					lifecycleRevision = 1L,
					desiredPlanRevision = 1L,
					rolloutRevision = 1L,
					startOrigin = "MANUAL",
					clockDomainId = "boot",
					startedAtMs = 1L,
					startedElapsedNanos = 1L,
					cutoffAtMs = 2L,
					cutoffElapsedNanos = 2L,
					completedAtMs = 2L,
					finalAdmissionOrdinal = 0L,
					failureCode = null,
				),
			)
		}

		service().delete(
			DeleteCellHistoryRequest(
				LocalCellHistorySelection(LocalCellHistoryIdentity("f".repeat(64))),
				900L,
			),
		) shouldBe DeleteCellHistoryResult.Unverifiable(
			CellHistoryDeletionUnverifiableReason.SELECTION_LOOKUP_BUDGET_EXCEEDED,
		)
	}

	@Test
	fun `active exact Cell owner blocks without using presentation quiescence as deletion proof`() =
		runTest {
			val selection = seedLocalScope(
				sessionState = "ACTIVE",
				runState = "ACTIVE",
				completedAtMs = null,
				captureSources = listOf(SourceDestinationOwnerEntity.SOURCE_CELL),
			)

			service().delete(DeleteCellHistoryRequest(selection, 900L)) shouldBe
				DeleteCellHistoryResult.Blocked(CellHistoryDeletionBlockedReason.ACTIVE_CAPTURE)
			database.sourceDeletionFenceDao().countAll() shouldBe 0L
			database.sessionSegmentDao().getById(1L)?.logicalTrackingId shouldBe LOGICAL_ID
		}

	@Test
	fun `mixed captured source membership blocks before any Cell payload or presentation mutation`() =
		runTest {
			val selection = seedLocalScope(
				sessionState = "FINALIZED",
				runState = "FINALIZED",
				completedAtMs = 200L,
				captureSources = listOf(
					SourceDestinationOwnerEntity.SOURCE_CELL,
					SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				),
			)

			service().delete(DeleteCellHistoryRequest(selection, 900L)) shouldBe
				DeleteCellHistoryResult.Blocked(
					CellHistoryDeletionBlockedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
				)
			database.sourceDeletionFenceDao().countAll() shouldBe 0L
			database.sessionSegmentDao().getById(1L)?.logicalTrackingId shouldBe LOGICAL_ID
		}

	@Test
	fun `terminal exact factless Cell session deletes presentation and exact scopes idempotently`() =
		runTest {
			val selection = seedLocalScope(
				sessionState = "FINALIZED",
				runState = "FINALIZED",
				completedAtMs = 200L,
				captureSources = listOf(SourceDestinationOwnerEntity.SOURCE_CELL),
			)
			installFactlessCellAuthority()
			val service = service(ownsLane = true)

			service.delete(DeleteCellHistoryRequest(selection, 900L)) shouldBe
				DeleteCellHistoryResult.Deleted(1, 1, 0)
			database.sessionSegmentDao().getById(1L) shouldBe null
			database.sourceDeletionFenceDao().countAll() shouldBe 1L
			database.cellCapturedFactDao().deletionGenerationCount() shouldBe 1L
			database.cellCapturedFactDao().entryDeletionReceipt(LOGICAL_ID)
				?.deletedAtMs shouldBe 900L
			database.cellCapturedFactDao().deletedRuns(LOGICAL_ID, 2).size shouldBe 1
			database.withTransaction {
				database.authenticateDeletedCapturedCellSelectionInTransaction(
					LOGICAL_ID,
					com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity(
						selection.identity.value,
					),
				)
			} shouldBe DeletedCapturedCellSelectionAuthentication.Exact(
				requireNotNull(database.cellCapturedFactDao().entryDeletionReceipt(LOGICAL_ID)),
			)
			service.delete(DeleteCellHistoryRequest(selection, 0L)) shouldBe
				DeleteCellHistoryResult.Blocked(CellHistoryDeletionBlockedReason.STALE_REQUEST)
			service.delete(DeleteCellHistoryRequest(selection, 899L)) shouldBe
				DeleteCellHistoryResult.Blocked(CellHistoryDeletionBlockedReason.STALE_REQUEST)
			service.delete(DeleteCellHistoryRequest(selection, 900L)) shouldBe
				DeleteCellHistoryResult.AlreadyDeleted
			service.delete(DeleteCellHistoryRequest(selection, 901L)) shouldBe
				DeleteCellHistoryResult.AlreadyDeleted
			database.withTransaction {
				database.authenticateDeletedCapturedCellSelectionInTransaction(
					LOGICAL_ID,
					com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity(
						selection.identity.value,
					),
				)
			} shouldBe DeletedCapturedCellSelectionAuthentication.Exact(
				requireNotNull(database.cellCapturedFactDao().entryDeletionReceipt(LOGICAL_ID)),
			)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_deletion_fence SET fence_generation = 2",
			)
			database.withTransaction {
				database.authenticateDeletedCapturedCellSelectionInTransaction(
					LOGICAL_ID,
					com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity(
						selection.identity.value,
					),
				)
			} shouldBe DeletedCapturedCellSelectionAuthentication.Unverifiable
		}

	@Test
	fun `imported action cancellation propagates without mutation typing`() = runTest {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
		val service = service(DeleteSelectedImportedCell {
			throw CancellationException("cancel imported deletion")
		})

		shouldThrow<CancellationException> {
			service.delete(DeleteCellHistoryRequest(importedSelection(), 900L))
		}
	}

	private fun service(
		imported: DeleteSelectedImportedCell = DeleteSelectedImportedCell {
			DeleteSelectedImportedCellResult.NotFound
		},
		ownsLane: Boolean = false,
	) = RoomCellSelectedHistoryDeletion(
		database = database,
		importedDeletion = imported,
		laneExecutionAuthority = SourceProductLaneExecutionAuthority { ownsLane },
		ioDispatcher = Dispatchers.Unconfined,
	)

	private fun importedSelection() = ImportedCellHistorySelection(
		ImportedCellHistoryIdentity("1".repeat(64)),
		2L,
		ImportedCellHistoryDigest("2".repeat(64)),
	)

	private suspend fun seedLocalScope(
		sessionState: String,
		runState: String,
		completedAtMs: Long?,
		captureSources: List<Int>,
	): LocalCellHistorySelection {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
		val segment = SessionSegment(
			id = 1L,
			startTimeMs = 100L,
			endTimeMs = 200L,
			distanceM = 0f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 0,
			source = SegmentSource.USER_CREATED,
			inferenceVersion = null,
			createdAt = 200L,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
		)
		database.sessionSegmentDao().insert(segment)
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = sessionState,
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = "boot",
				startedAtMs = 100L,
				startedElapsedNanos = 100L,
				cutoffAtMs = completedAtMs,
				cutoffElapsedNanos = completedAtMs,
				completedAtMs = completedAtMs,
				finalAdmissionOrdinal = 0L,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				currentServiceRunId = if (completedAtMs == null) RUN_ID else null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = runState,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 100L,
				startedElapsedNanos = 100L,
				completedAtMs = completedAtMs,
				completionReason = completedAtMs?.let { "USER_STOP" },
				bootId = "boot",
				leaseGeneration = 1L,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "delivery",
				startCommandGeneration = 1L,
				preparedManifestRevision = 1L,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = 100L,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = 1L,
				presentationAcknowledgement =
				SourceServiceRunEntity.PRESENTATION_QUIESCED,
				presentationAcknowledgedAtMs = completedAtMs ?: 200L,
			),
		)
		val sources = captureSources.mapIndexed { index, sourceKind ->
			if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
				SessionManifestSourceEntity(
					logicalTrackingId = LOGICAL_ID,
					manifestRevision = 1L,
					sourceKind = sourceKind,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					consentEpoch = 1L + index,
					persistenceEligible = true,
					qosCode = 2,
					outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
					writerOwner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
					writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					writerProjectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
					writerProjectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
					writerBindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
				)
			} else {
				SessionManifestSourceEntity(
					logicalTrackingId = LOGICAL_ID,
					manifestRevision = 1L,
					sourceKind = sourceKind,
					purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					consentEpoch = 1L + index,
					persistenceEligible = true,
					qosCode = 2,
					outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
					writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
					writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
					writerProjectionVersion =
						SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
					writerBindingGeneration =
						SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
				)
			}
		}
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = 1L,
			serviceRunId = RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = 1L,
			acquisitionPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = 100L,
			effectiveWallTimeMs = 100L,
			zoneId = "UTC",
			automationEpoch = null,
			changeReason = "TEST",
			manifestChecksum = "pending",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, sources)),
		)
		database.sourceSessionDao().insertManifestSources(sources)
		return LocalCellHistorySelection(
			LocalCellHistoryIdentity(
				com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity.derive(
					com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind.LOGICAL_ENTRY,
					LOGICAL_ID,
				).value,
			),
		)
	}

	private suspend fun installFactlessCellAuthority() {
		database.sourcePolicyDao().insertPolicies(listOf(
			SourcePolicyEntity(
				policyRevision = 1L,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
				enabled = true,
				qosCode = 2,
				locationMinTimeSeconds = null,
				locationMinDistanceMeters = null,
				locationRequiredAccuracyMeters = null,
				capturePersistenceEligible = true,
				controlPersistenceEligible = false,
				ambientPersistenceEligible = false,
				captureConsentEpoch = 1L,
				controlConsentEpoch = null,
				ambientConsentEpoch = null,
				effectiveBootId = "boot",
				effectiveElapsedRealtimeNanos = 100L,
				effectiveWallTimeMs = 100L,
				changeReason = "TEST",
			),
		))
		database.sourcePolicyDao().insertConsentEpochs(listOf(
			SourceConsentEpochEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				epoch = 1L,
				eligible = true,
				persistenceEligible = true,
				policyRevision = 1L,
				effectiveBootId = "boot",
				effectiveElapsedRealtimeNanos = 100L,
				effectiveWallTimeMs = 100L,
				changeReason = "TEST",
			),
		))
		val payload = ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(1)
				output.writeUTF("CELL")
				output.writeLong(1L)
				output.writeUTF("OBSERVE_CHANGES")
				output.writeLong(60_000L)
				output.writeLong(1_000L)
				output.writeInt(0)
				output.writeLong(1_000L)
				output.writeLong(60_000L)
				output.writeDouble(2.0)
			}
			bytes.toByteArray()
		}
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = 1L,
				planId = "cell-plan",
				createdAtMs = 100L,
				status = "EFFECTIVE",
				sourcePolicyRevision = 1L,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(listOf(
			SourceDesiredPlanEntity(
				1L,
				SourceDestinationOwnerEntity.SOURCE_CELL,
				1,
				payload,
				sha256(payload),
			),
		))
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
				bindingGeneration = SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION,
				projectionId = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				projectionVersion = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				captureModeMask = 1L,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				activatedRolloutRevision = 1L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				captureAdmissionCutoffOrdinal = null,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				terminalDisposition = null,
				terminalAtMs = null,
				installedAtMs = 100L,
				updatedAtMs = 100L,
			),
		)
		database.sourceSessionDao().saveCompleteness(
			SourceSessionCompletenessEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
				sourceInstanceId = "unavailable-cell",
				registrationGeneration = 0L,
				lastAdmissionOrdinal = null,
				lastSourceSequence = null,
				appDrainComplete = true,
				providerCoverage = "PROVIDER_COMPLETENESS_UNOBSERVABLE",
				stopStatus = "PROVIDER_FAILED",
				unresolvedSequenceStart = null,
				unresolvedSequenceEnd = null,
				updatedAtMs = 200L,
			),
		)
	}

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it) }

	private companion object {
		const val LOGICAL_ID = "cell-selected-logical"
		const val RUN_ID = "cell-selected-run"
	}
}

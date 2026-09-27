package com.adsamcik.tracker.tracker.source.lifecycle

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.persistCapturedRadioFactRetentionProofs
import com.adsamcik.tracker.shared.base.database.persistPressureFactRetentionAuthority
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.stats.api.repository.RecordingProofState
import com.adsamcik.tracker.stats.api.repository.SourceEvidenceIdentity
import com.adsamcik.tracker.stats.api.repository.SourceProofCompleteness
import com.adsamcik.tracker.stats.api.repository.SourceProofCoverage
import com.adsamcik.tracker.stats.api.repository.SourceProofKey
import com.adsamcik.tracker.stats.api.repository.SourceProofLineage
import com.adsamcik.tracker.stats.api.repository.SourceProofReason
import com.adsamcik.tracker.stats.api.repository.SourceProofResult
import com.adsamcik.tracker.stats.api.repository.SourceProofScope
import com.adsamcik.tracker.stats.api.repository.SourceProofStage
import com.adsamcik.tracker.tracker.source.activity.ActivityCapturedWalAdmissionAdapter
import com.adsamcik.tracker.tracker.source.cell.CellWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.location.LocationWalQualificationAdapter
import com.adsamcik.tracker.tracker.source.wifi.WifiWalQualificationAdapter
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceEventRetentionProofLifecycleTest {
	private lateinit var database: AppDatabase
	private lateinit var factory: DefaultSourceLifecycleProofAdapterFactory
	private lateinit var cellQualifier: CellWalQualificationAdapter

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = 7L))
		cellQualifier = mockk(relaxed = true)
		factory = DefaultSourceLifecycleProofAdapterFactory(
			database = database,
			payloadCodec = mockk<SourcePayloadCodec>(relaxed = true),
			planCodec = mockk<SourcePlanCodec>(relaxed = true),
			locationQualifier = mockk<LocationWalQualificationAdapter>(relaxed = true),
			activityQualifier = mockk<ActivityCapturedWalAdmissionAdapter>(relaxed = true),
			wifiQualifier = mockk<WifiWalQualificationAdapter>(relaxed = true),
			cellQualifier = cellQualifier,
			bootClockDomainProvider = BootClockDomainProvider { "boot-current" },
		)
	}

	@Test
	fun `Cell selected deletion fence is terminal before missing segment authority is rebuilt`() =
		runTest {
			database.sourceDeletionFenceDao().insertIfAbsent(
				SourceDeletionFenceEntity.createLogicalServiceRun(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = "logical",
					serviceRunId = "run",
					fenceGeneration = 1L,
					collectedDataEpoch = 7L,
					deletedAtMs = 100L,
				),
			)

			factory.create(TrackingSource.CELL).recording(key(TrackingSource.CELL)) shouldBe
				SourceProofResult.Deleted(
					SourceProofStage.RECORDING,
					SourceProofReason.DELETED,
				)
		}

	@Test
	fun `Cell deletion between recording and materialization is terminal before missing fact`() =
		runTest {
			database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(
				cellWal(),
			)
			database.sourceDeletionFenceDao().insertIfAbsent(
				SourceDeletionFenceEntity.createLogicalServiceRun(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = "logical",
					serviceRunId = "run",
					fenceGeneration = 1L,
					collectedDataEpoch = 7L,
					deletedAtMs = 100L,
				),
			)

			factory.create(TrackingSource.CELL).materialized(
				key(TrackingSource.CELL),
				recording(),
			) shouldBe SourceProofResult.Deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.DELETED,
				recording().lineage,
			)
		}

	@Test
	fun `all source materialization fallbacks honor an exact run deletion fence`() = runTest {
		TrackingSource.entries.forEach { source ->
			database.sourceDeletionFenceDao().insertIfAbsent(
				SourceDeletionFenceEntity.createLogicalServiceRun(
					sourceKind = source.stableCode(),
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = "logical",
					serviceRunId = "run",
					fenceGeneration = 1L,
					collectedDataEpoch = 7L,
					deletedAtMs = 100L,
				),
			)

			factory.create(source).materialized(key(source), recording()) shouldBe
				SourceProofResult.Deleted(
					SourceProofStage.MATERIALIZED,
					SourceProofReason.DELETED,
					recording().lineage,
				)
		}
	}

	@Test
	fun `unfenced Cell materialization miss remains pending`() = runTest {
		database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(cellWal())

		factory.create(TrackingSource.CELL).materialized(
			key(TrackingSource.CELL),
			recording(),
		) shouldBe SourceProofResult.Pending(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.MATERIALIZATION_PENDING,
			recording().lineage,
		)
	}

	@Test
	fun `Cell dependency retention proof is terminal before fact deletion becomes visible`() =
		runTest {
			database.sourceEvidenceStateDao().updateLifecycle(7L, 100L, 200L)
			val proof = capturedRadioRetentionProof(SourceDestinationOwnerEntity.SOURCE_CELL)
			database.capturedRadioFactRetentionProofDao().insert(proof)

			factory.create(TrackingSource.CELL).materialized(
				key(TrackingSource.CELL),
				recording(),
			) shouldBe SourceProofResult.Deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.RETENTION_EXPIRED,
				recording().lineage,
			)
			factory.create(TrackingSource.WIFI).materialized(
				key(TrackingSource.WIFI),
				recording(),
			) shouldBe SourceProofResult.Pending(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.MATERIALIZATION_PENDING,
				recording().lineage,
			)
		}

	@Test
	fun `corrupt Cell dependency retention proof fails materialization closed`() = runTest {
		database.sourceEvidenceStateDao().updateLifecycle(7L, 100L, 200L)
		database.capturedRadioFactRetentionProofDao().insert(
			capturedRadioRetentionProof(SourceDestinationOwnerEntity.SOURCE_CELL)
				.copy(effectChecksum = "corrupt"),
		)

		factory.create(TrackingSource.CELL).materialized(
			key(TrackingSource.CELL),
			recording(),
		) shouldBe SourceProofResult.Failed(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.INTEGRITY_FAILURE,
			recording().lineage,
		)
	}

	@Test
	fun `captured radio retention proof replay is exact and conflicting replay is rejected`() =
		runTest {
			val proof = capturedRadioRetentionProof(SourceDestinationOwnerEntity.SOURCE_CELL)

			database.persistCapturedRadioFactRetentionProofs(listOf(proof))
			database.persistCapturedRadioFactRetentionProofs(listOf(proof))
			database.capturedRadioFactRetentionProofDao().countAll() shouldBe 1L

			val conflicting = proof.copy(
				retainedAtMs = proof.retainedAtMs + 1L,
				effectChecksum = "",
			).let { changed ->
				changed.copy(effectChecksum = changed.calculatedEffectChecksum())
			}
			shouldThrow<IllegalStateException> {
				database.persistCapturedRadioFactRetentionProofs(listOf(conflicting))
			}
		}

	@Test
	fun `three revision Pressure retention resolves intermediate WAL above floor at every lifecycle stage`() =
		runTest {
			database.sourceEvidenceStateDao().updateLifecycle(
				epoch = 7L,
				retainedFromMs = 1_500L,
				updatedAtMs = 2_000L,
			)
			val lineage = retainedPressureLineage()
			database.persistPressureFactRetentionAuthority(
				revisions = lineage,
				retainedFromMs = 1_500L,
				retainedAtMs = 2_000L,
			)
			val adapter = factory.create(TrackingSource.PRESSURE)

			adapter.recording(key(TrackingSource.PRESSURE)) shouldBe
				SourceProofResult.Deleted(
					SourceProofStage.RECORDING,
					SourceProofReason.RETENTION_EXPIRED,
				)
			database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(
				pressureWalRetainedCarrier(),
			)
			adapter.recording(key(TrackingSource.PRESSURE)) shouldBe
				SourceProofResult.Deleted(
					SourceProofStage.RECORDING,
					SourceProofReason.RETENTION_EXPIRED,
				)
			adapter.materialized(
				key(TrackingSource.PRESSURE),
				recording(),
			) shouldBe SourceProofResult.Deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.RETENTION_EXPIRED,
				recording().lineage,
			)
			val intermediate = lineage[1]
			val intermediateKey = key(
				source = TrackingSource.PRESSURE,
				evidenceIdentity = intermediate.sourceEventId,
			)
			val intermediateRecording = recording(
				evidenceIdentity = intermediate.sourceEventId,
				sourceAdmissionOrdinal = intermediate.sourceAdmissionOrdinal,
			)
			database.sourceEventWalDao().insertAbortingOnUnexpectedConflict(
				pressureWalRetainedCarrier().copy(
					admissionOrdinal = intermediate.sourceAdmissionOrdinal,
					eventId = intermediate.sourceEventId,
					acquiredAtMs = intermediate.intervalEndTimeMs,
					createdAtMs = intermediate.intervalEndTimeMs,
					wallTimeMs = intermediate.intervalStartTimeMs,
					receivedWallTimeMs = intermediate.intervalEndTimeMs,
					integrityIdentity = "",
				).let { wal ->
					wal.copy(integrityIdentity = wal.calculatedIntegrityIdentity())
				},
			)
			adapter.recording(intermediateKey) shouldBe SourceProofResult.Deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.RETENTION_EXPIRED,
			)
			adapter.materialized(
				intermediateKey,
				intermediateRecording,
			) shouldBe SourceProofResult.Deleted(
				SourceProofStage.MATERIALIZED,
				SourceProofReason.RETENTION_EXPIRED,
				intermediateRecording.lineage,
			)
		}

	@Test
	fun `live Pressure admission collision fails recording and materialization closed`() = runTest {
		database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 2_000L)
		val lineage = retainedPressureLineage()
		database.pressureFactRevisionDao().insert(
			retainedPressureFact().copy(
				logicalFactId =
					"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:live-collision",
				mutationId =
					"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:live-collision:1",
				sourceEventId = "live-pressure-collision",
				sourceAdmissionOrdinal = lineage.first().sourceAdmissionOrdinal,
			),
		)
		database.persistPressureFactRetentionAuthority(lineage, 1_500L, 2_000L)
		val adapter = factory.create(TrackingSource.PRESSURE)

		adapter.recording(key(TrackingSource.PRESSURE)) shouldBe SourceProofResult.Failed(
			SourceProofStage.RECORDING,
			SourceProofReason.INTEGRITY_FAILURE,
		)
		adapter.materialized(
			key(TrackingSource.PRESSURE),
			recording(),
		) shouldBe SourceProofResult.Failed(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.INTEGRITY_FAILURE,
			recording().lineage,
		)
	}

	@Test
	fun `corrupt Pressure lineage retention authority fails before materialization pending`() = runTest {
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = 7L,
			retainedFromMs = 1_500L,
			updatedAtMs = 2_000L,
		)
		database.persistPressureFactRetentionAuthority(
			revisions = retainedPressureLineage(),
			retainedFromMs = 1_500L,
			retainedAtMs = 2_000L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE pressure_fact_retention_authority SET effect_checksum = 'corrupt'",
		)

		factory.create(TrackingSource.PRESSURE).materialized(
			key(TrackingSource.PRESSURE),
			recording(),
		) shouldBe SourceProofResult.Failed(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.INTEGRITY_FAILURE,
			recording().lineage,
		)
	}

	@Test
	fun `missing intermediate Pressure revision authority fails closed`() = runTest {
		database.sourceEvidenceStateDao().updateLifecycle(7L, 1_500L, 2_000L)
		val lineage = retainedPressureLineage()
		database.persistPressureFactRetentionAuthority(lineage, 1_500L, 2_000L)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM pressure_fact_retention_revision_authority WHERE semantic_revision = 2",
		)
		val intermediate = lineage[1]
		val intermediateKey = key(
			TrackingSource.PRESSURE,
			evidenceIdentity = intermediate.sourceEventId,
		)
		val intermediateRecording = recording(
			evidenceIdentity = intermediate.sourceEventId,
			sourceAdmissionOrdinal = intermediate.sourceAdmissionOrdinal,
		)
		factory.create(TrackingSource.PRESSURE).recording(intermediateKey) shouldBe
			SourceProofResult.Failed(
				SourceProofStage.RECORDING,
				SourceProofReason.INTEGRITY_FAILURE,
			)

		factory.create(TrackingSource.PRESSURE).materialized(
			intermediateKey,
			intermediateRecording,
		) shouldBe SourceProofResult.Failed(
			SourceProofStage.MATERIALIZED,
			SourceProofReason.INTEGRITY_FAILURE,
			intermediateRecording.lineage,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `missing WAL remains terminal retention deletion only for its exact source and epoch`() =
		runTest {
			val receipt = receipt()
			database.sourceEventRetentionProofDao().insertAll(listOf(receipt))

			factory.create(TrackingSource.PRESSURE).recording(key(TrackingSource.PRESSURE)) shouldBe
				SourceProofResult.Deleted(
					SourceProofStage.RECORDING,
					SourceProofReason.RETENTION_EXPIRED,
				)
			factory.create(TrackingSource.STEPS).recording(key(TrackingSource.STEPS)) shouldBe
				SourceProofResult.Pending(
					SourceProofStage.RECORDING,
					SourceProofReason.EVIDENCE_NOT_FOUND,
				)
			factory.create(TrackingSource.PRESSURE).recording(
				key(TrackingSource.PRESSURE, collectedDataEpoch = 8L),
			) shouldBe SourceProofResult.Unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		}

	@Test
	fun `retention receipt requires exact policy consent and run scope`() = runTest {
		database.sourceEventRetentionProofDao().insertAll(listOf(receipt()))
		val adapter = factory.create(TrackingSource.PRESSURE)

		listOf(
			key(TrackingSource.PRESSURE).copy(sourcePolicyRevision = 2L),
			key(TrackingSource.PRESSURE).copy(captureConsentEpoch = 2L),
			key(TrackingSource.PRESSURE).copy(scope = SourceProofScope.Run("logical", "other-run")),
		).forEach { mismatched ->
			adapter.recording(mismatched) shouldBe SourceProofResult.Unavailable(
				SourceProofStage.RECORDING,
				SourceProofReason.IMMUTABLE_AUTHORITY_MISSING,
			)
		}
	}

	@Test
	fun `run deletion takes precedence over an older retention receipt`() = runTest {
		database.sourceEventRetentionProofDao().insertAll(listOf(receipt()))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_retention_proof SET effect_checksum = ? " +
				"WHERE source_kind = ? AND event_id = ?",
			arrayOf(
				"0".repeat(64),
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				EVENT_ID,
			),
		)
		database.sourceDeletionFenceDao().insertIfAbsent(
			SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "logical",
				serviceRunId = "run",
				fenceGeneration = 1L,
				collectedDataEpoch = 7L,
				deletedAtMs = 100L,
			),
		)

		factory.create(TrackingSource.PRESSURE).recording(key(TrackingSource.PRESSURE)) shouldBe
			SourceProofResult.Deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.DELETED,
			)
	}

	@Test
	fun `global deletion high water takes precedence over an older retention receipt`() = runTest {
		database.sourceEventRetentionProofDao().insertAll(listOf(receipt()))
		database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 7L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 41L,
			updatedAtMs = 100L,
		)

		factory.create(TrackingSource.PRESSURE).recording(key(TrackingSource.PRESSURE)) shouldBe
			SourceProofResult.Deleted(
				SourceProofStage.RECORDING,
				SourceProofReason.DELETED,
			)
	}

	@Test
	fun `corrupt retention proof cannot classify missing WAL as retained`() = runTest {
		val receipt = receipt()
		database.sourceEventRetentionProofDao().insertAll(listOf(receipt))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_retention_proof SET effect_checksum = ? " +
				"WHERE source_kind = ? AND event_id = ?",
			arrayOf(
				"0".repeat(64),
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				EVENT_ID,
			),
		)

		factory.create(TrackingSource.PRESSURE).recording(key(TrackingSource.PRESSURE)) shouldBe
			SourceProofResult.Failed(
				SourceProofStage.RECORDING,
				SourceProofReason.INTEGRITY_FAILURE,
			)
	}

	@Test
	fun `corrupt lowered retention ordinal fails integrity before global deletion`() = runTest {
		database.sourceEventRetentionProofDao().insertAll(listOf(receipt()))
		database.sourceEvidenceStateDao().updateAfterFullDeletion(
			epoch = 7L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = 40L,
			updatedAtMs = 100L,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_retention_proof SET source_admission_ordinal = 1 " +
				"WHERE source_kind = ? AND event_id = ?",
			arrayOf(
				SourceDestinationOwnerEntity.SOURCE_PRESSURE,
				EVENT_ID,
			),
		)

		factory.create(TrackingSource.PRESSURE).recording(key(TrackingSource.PRESSURE)) shouldBe
			SourceProofResult.Failed(
				SourceProofStage.RECORDING,
				SourceProofReason.INTEGRITY_FAILURE,
			)
	}

	private fun receipt(): SourceEventRetentionProofEntity {
		val payload = byteArrayOf(1)
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = 41L,
			eventId = EVENT_ID,
			providerDedupKey = "retained-pressure",
			logicalTrackingId = "logical",
			serviceRunId = "run",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			sourceInstanceId = "pressure-provider",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "a".repeat(64),
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "b".repeat(64),
			sourceSequence = 41L,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "boot-old",
			observedElapsedNanos = 1L,
			receivedElapsedNanos = 2L,
			wallTimeMs = 10L,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 7L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 10L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = payload,
			payloadChecksum = "",
			createdAtMs = 10L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return SourceEventRetentionProofEntity.createFromWal(
			checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity()),
		)
	}

	private fun capturedRadioRetentionProof(
		sourceKind: Int,
	): CapturedRadioFactRetentionProofEntity {
		val projectionId = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
		} else {
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID
		}
		val projectionVersion = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
		} else {
			SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION
		}
		val bindingGeneration = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
			SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION
		} else {
			SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION
		}
		val logicalFactId = "1".repeat(64)
		val mutationId = if (sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL) {
			com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
				.mutationId(logicalFactId, 1L)
		} else {
			com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
				.mutationId(logicalFactId, 1L)
		}
		val unsigned = CapturedRadioFactRetentionProofEntity(
			sourceKind = sourceKind,
			writerProjectionId = projectionId,
			writerProjectionVersion = projectionVersion,
			writerBindingGeneration = bindingGeneration,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = mutationId,
			factEffectChecksum = "3".repeat(64),
			sourceEventId = EVENT_ID,
			sourceAdmissionOrdinal = 41L,
			walIntegrityIdentity = "4".repeat(64),
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			collectedDataEpoch = 7L,
			logicalTrackingId = "logical",
			serviceRunId = "run",
			scopeDeletionGeneration = 0L,
			logicalRunScopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind,
				SourceBrokerPurpose.SESSION_CAPTURE,
				"logical",
				"run",
			),
			cursorRevision = 1L,
			cursorMutationId = mutationId,
			cursorEffectChecksum = "3".repeat(64),
			cursorSourceAdmissionOrdinal = 41L,
			cursorUpdatedAtMs = 50L,
			aggregateOwnerLogicalFactId = "5".repeat(64),
			aggregateOwnerSemanticRevision = 1L,
			aggregateOwnerSourceEventId = "owner-event",
			aggregateOwnerSourceAdmissionOrdinal = 40L,
			aggregateOwnerWalIntegrityIdentity = "6".repeat(64),
			aggregateOwnerEffectChecksum = "7".repeat(64),
			aggregateOwnerCursorRevision = 1L,
			retainedFromMs = 100L,
			retainedAtMs = 200L,
			reason = CapturedRadioFactRetentionProofEntity.REASON_DEPENDENCY_CLOSURE,
			effectChecksum = "",
		)
		return unsigned.copy(effectChecksum = unsigned.calculatedEffectChecksum())
	}

	private fun key(
		source: TrackingSource,
		collectedDataEpoch: Long = 7L,
		evidenceIdentity: String = EVENT_ID,
	) = SourceProofKey(
		source = source,
		purpose = TrackingPurpose.SESSION_CAPTURE,
		evidenceIdentity = SourceEvidenceIdentity(evidenceIdentity),
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = collectedDataEpoch,
		scope = SourceProofScope.Run("logical", "run"),
	)

	private fun recording(
		evidenceIdentity: String = EVENT_ID,
		sourceAdmissionOrdinal: Long = 41L,
	) = RecordingProofState(
		lineage = SourceProofLineage(
			evidenceIdentity = SourceEvidenceIdentity(evidenceIdentity),
			sourceAdmissionOrdinal = sourceAdmissionOrdinal,
			sourceEvidenceRevision = 0L,
		),
		coverage = SourceProofCoverage.ProviderInterval(10L, 20L),
		completeness = SourceProofCompleteness.COMPLETE,
	)

	private fun retainedPressureFact() = PressureFactRevisionEntity(
		logicalFactId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:$EVENT_ID",
		semanticRevision = 1L,
		mutationId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:$EVENT_ID:1",
		sourceEventId = EVENT_ID,
		sourceAdmissionOrdinal = 41L,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
		payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
		intervalStartTimeMs = 1_000L,
		intervalEndTimeMs = 1_150L,
		windowStartElapsedRealtimeNanos = 10L,
		windowEndElapsedRealtimeNanos = 150_000_010L,
		clockDomainId = "boot-old",
		wallTimeUncertaintyMs = 1L,
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
		sourceQualityConfidence = null,
		logicalTrackingId = "logical",
		serviceRunId = "run",
		purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
		manifestRevision = 1L,
		sourcePolicyRevision = 1L,
		captureConsentEpoch = 1L,
		collectedDataEpoch = 7L,
		effectChecksum = "lineage-effect",
		appliedAtMs = 1_150L,
	)

	private fun retainedPressureLineage(): List<PressureFactRevisionEntity> {
		val trigger = retainedPressureFact()
		return listOf(
			trigger,
			trigger.copy(
				semanticRevision = 2L,
				mutationId = "${trigger.logicalFactId}:2",
				sourceEventId = "pressure-intermediate",
				sourceAdmissionOrdinal = 42L,
				intervalStartTimeMs = 1_600L,
				intervalEndTimeMs = 1_750L,
				windowStartElapsedRealtimeNanos = 600_000_010L,
				windowEndElapsedRealtimeNanos = 750_000_010L,
				firstProviderSequence = 5L,
				lastProviderSequence = 8L,
				effectChecksum = "intermediate-lineage-effect",
				appliedAtMs = 1_750L,
			),
			trigger.copy(
				semanticRevision = 3L,
				mutationId = "${trigger.logicalFactId}:3",
				sourceEventId = "pressure-current",
				sourceAdmissionOrdinal = 43L,
				intervalStartTimeMs = 1_800L,
				intervalEndTimeMs = 1_950L,
				windowStartElapsedRealtimeNanos = 800_000_010L,
				windowEndElapsedRealtimeNanos = 950_000_010L,
				firstProviderSequence = 9L,
				lastProviderSequence = 12L,
				effectChecksum = "current-lineage-effect",
				appliedAtMs = 1_950L,
			),
		)
	}

	private fun cellWal(): SourceEventWalEntity {
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = 41L,
			eventId = EVENT_ID,
			providerDedupKey = null,
			logicalTrackingId = "logical",
			serviceRunId = "run",
			sourceKind = SourceDestinationOwnerEntity.SOURCE_CELL,
			sourceInstanceId = "cell-provider",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "cell-config",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = 1L,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = "boot-old",
			observedElapsedNanos = 20L,
			observedIntervalStartNanos = 10L,
			receivedElapsedNanos = 21L,
			receivedWallTimeMs = 101L,
			wallTimeMs = 100L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 7L,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 100L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = byteArrayOf(1),
			payloadChecksum = "",
			createdAtMs = 101L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
	}

	private fun pressureWalRetainedCarrier(): SourceEventWalEntity {
		val unsigned = cellWal().copy(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			sourceInstanceId = "pressure-provider",
			integrityIdentity = "",
		)
		return unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
	}

	private fun TrackingSource.stableCode(): Int = when (this) {
		TrackingSource.LOCATION -> SourceDestinationOwnerEntity.SOURCE_LOCATION
		TrackingSource.ACTIVITY -> SourceDestinationOwnerEntity.SOURCE_ACTIVITY
		TrackingSource.STEPS -> SourceDestinationOwnerEntity.SOURCE_STEPS
		TrackingSource.PRESSURE -> SourceDestinationOwnerEntity.SOURCE_PRESSURE
		TrackingSource.WIFI -> SourceDestinationOwnerEntity.SOURCE_WIFI
		TrackingSource.CELL -> SourceDestinationOwnerEntity.SOURCE_CELL
	}

	private companion object {
		const val EVENT_ID = "retained-source-event"
	}
}

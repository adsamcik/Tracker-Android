package com.adsamcik.tracker.tracker.source.cell

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.cellProviderDeliveryIdentity
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellWalQualificationAdapterTest {
	private lateinit var database: AppDatabase
	private val payloadCodec = DefaultSourcePayloadCodec()
	private val planCodec = SourcePlanCodec()
	private lateinit var subject: CellWalQualificationAdapter
	private lateinit var writer: CellCapturedFactWriter

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		subject = CellWalQualificationAdapter(database, payloadCodec, planCodec)
		writer = CellCapturedFactWriter(database, subject)
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `exact cell-only WAL and durable authority produce identity-free fact`() = runTest {
		installValidFixture()

		val evaluated = assertIs<CellWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		val fact = assertIs<CellCapturedFactClassification.FreshChanged>(evaluated.classification).fact

		assertEquals(setOf(SourceKind.CELL), fact.authority.capturedSources)
		assertEquals(emptySet(), fact.authority.controlSources)
		assertEquals(RUN_ID, fact.authority.serviceRunId.value)
		assertEquals(SEGMENT_ID, fact.authority.sessionSegmentId)
		assertEquals(PLAN_REVISION, fact.authority.configurationRevision)
		assertEquals(SOURCE_SEQUENCE, fact.evidenceBinding.sourceSequence)
		assertEquals(CellSubscriptionCompleteness.UNKNOWN, fact.coverage.subscriptionCompleteness)
		assertEquals(null, fact.coverage.expectedSubscriptionCount)
		assertEquals(null, fact.coverage.observedSubscriptionCount)
		assertEquals(2, fact.aggregate.observationCount)
		val expectedDay = Instant.ofEpochMilli(OBSERVED_WALL_MS)
			.atZone(ZoneId.of(ZONE_ID)).toLocalDate().toEpochDay()
		assertEquals(expectedDay, fact.authority.structuralEpochDay)
		assertEquals(1L, database.sourceEventWalDao().countAll())
	}

	@Test
	fun `real producer unit and positive source sequence are mandatory`() = runTest {
		installValidFixture(sourceSequence = 0L)
		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `canonical WAL payload and provider delivery identity are recomputed`() = runTest {
		installValidFixture(deliveryIdentityOverride = "a".repeat(64))
		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)

		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = X'00' WHERE event_id = ?",
			arrayOf(EVENT_ID.value),
		)
		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `canonical WAL decoder rejects validly resigned trailing bytes`() = runTest {
		installValidFixture()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val withTrailingByte = original.copy(payload = original.payload + byteArrayOf(0))
		val withChecksum = withTrailingByte.copy(
			payloadChecksum = withTrailingByte.calculatedPayloadChecksum(),
		)
		val resigned = withChecksum.copy(
			integrityIdentity = withChecksum.calculatedIntegrityIdentity(),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET payload = ?, payload_checksum = ?, " +
				"integrity_identity = ? WHERE event_id = ?",
			arrayOf(
				resigned.payload,
				resigned.payloadChecksum,
				resigned.integrityIdentity,
				EVENT_ID.value,
			),
		)

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.PAYLOAD_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `one-unit producer rejects a second WAL sibling`() = runTest {
		installValidFixture()
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val unsignedSibling = original.copy(
			admissionOrdinal = 0L,
			eventId = "cell-event-sibling",
			deliveryUnitIndex = 1,
			deliveryUnitCount = 2,
			sourceSequence = SOURCE_SEQUENCE + 1L,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val sibling = unsignedSibling.copy(
			integrityIdentity = unsignedSibling.calculatedIntegrityIdentity(),
		)
		assertEquals(2L, database.sourceEventWalDao().insertIgnoringDuplicate(sibling))

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `authorization successor is exact despite interleaved source-global revisions`() = runTest {
		installValidFixture()
		database.sourceBrokerDao().insertRegistration(
			registration(generation = 2L, sourceInstance = "other-cell-instance"),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
				registrationGeneration = 2L,
				authorizationRevision = 2L,
				demands = listOf(demand()),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS + 100L,
				effectiveWallTimeMs = OBSERVED_WALL_MS + 1L,
			),
		)
		database.sourceBrokerDao().insertAuthorizations(
			SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
				registrationGeneration = REGISTRATION_GENERATION,
				authorizationRevision = 3L,
				demands = emptyList(),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = OBSERVED_END_NANOS + 200L,
				effectiveWallTimeMs = OBSERVED_WALL_MS + 1L,
			),
		)

		val evaluated = assertIs<CellWalAdapterResult.Evaluated>(subject.qualify(EVENT_ID))
		assertIs<CellCapturedFactClassification.FreshChanged>(evaluated.classification)
	}

	@Test
	fun `retention evaluates the oldest provider wall uncertainty bound`() = runTest {
		val providerSpanMs = (OBSERVED_END_NANOS - OBSERVED_START_NANOS) / 1_000_000L
		val earliestPossibleWall = OBSERVED_WALL_MS - providerSpanMs - WALL_UNCERTAINTY_MS
		installValidFixture(
			evidenceState = SourceEvidenceState(retainedFromMs = earliestPossibleWall + 1L),
		)

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.BEFORE_RETENTION_FLOOR),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `provider wall uncertainty spanning civil days is not assigned to one day`() = runTest {
		installValidFixture(
			zoneId = "UTC",
			observedWallMs = 86_400_000L,
		)

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.STRUCTURAL_DAY_UNVERIFIABLE),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `reverse run segment binding is mandatory`() = runTest {
		installValidFixture(segmentRunId = "other-run")

		assertEquals(
			CellWalAdapterResult.Rejected(CellWalAdapterRejection.SEGMENT_MISMATCH),
			subject.qualify(EVENT_ID),
		)
	}

	@Test
	fun `dormant candidate writer atomically appends fact cursor and evidence revision`() = runTest {
		installValidFixture(candidateWriter = true)
		val beforeEvidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val cursor = requireNotNull(
			database.cellCapturedFactDao().cursor(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				applied.logicalFactId,
			),
		)
		val fact = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				applied.logicalFactId,
				applied.semanticRevision,
			),
		)

		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(fact.semanticRevision, cursor.latestSemanticRevision)
		assertEquals(fact.mutationId, cursor.latestMutationId)
		assertEquals(fact.effectChecksum, cursor.latestEffectChecksum)
		assertEquals(0L, fact.scopeDeletionGeneration)
		assertEquals("UNKNOWN", fact.subscriptionCompleteness)
		assertEquals(beforeEvidenceRevision + 1L, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `fact backed history discovery finds a Cell only zero sample segment`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))

		val candidates = database.cellCapturedFactDao().logicalHistoryCandidatePage(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			10,
			null,
			null,
		)

		assertEquals(1, candidates.size)
		assertEquals(SEGMENT_ID, candidates.single().segment.id)
		assertEquals(0, candidates.single().segment.sampleCount)
	}

	@Test
	fun `exact WAL replay is a zero mutation idempotent result`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		val replay = assertIs<CellCapturedWriteResult.Unchanged>(writer.write(EVENT_ID))

		assertEquals(first.logicalFactId, replay.logicalFactId)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(evidenceRevision, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `finite immutable owner permits one coverage only reuse`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val beforeEvidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)

		val second = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		val coverage = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				second.logicalFactId,
				second.semanticRevision,
			),
		)

		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, coverage.factKind)
		assertEquals(first.logicalFactId, coverage.aggregateOwnerLogicalFactId)
		assertEquals(1L, coverage.aggregateOwnerSemanticRevision)
		assertEquals(null, coverage.observationCount)
		assertEquals(2L, database.cellCapturedFactDao().revisionCount())
		assertEquals(beforeEvidenceRevision + 1L, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `historical open dependent self materializes after its owner settles`() = runTest {
		installValidFixture(candidateWriter = true, openAuthority = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)

		val second = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		rewriteAsHistoricalCoverageOnly(second.logicalFactId, first.logicalFactId)
		val historicalDependent = fact(second.logicalFactId, second.semanticRevision)
		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY, historicalDependent.factKind)
		assertEquals(first.logicalFactId, historicalDependent.aggregateOwnerLogicalFactId)
		assertEquals(1L, historicalDependent.aggregateOwnerSemanticRevision)

		settleFixtureAuthority()

		val settledOwner = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		assertEquals(first.logicalFactId, settledOwner.logicalFactId)
		assertEquals(2L, settledOwner.semanticRevision)
		val settledDependent = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		val settledDependentFact = fact(
			settledDependent.logicalFactId,
			settledDependent.semanticRevision,
		)
		assertEquals(2L, settledDependent.semanticRevision)
		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE, settledDependentFact.factKind)
		assertEquals(null, settledDependentFact.aggregateOwnerLogicalFactId)
		assertEquals(null, settledDependentFact.aggregateOwnerSemanticRevision)
	}

	@Test
	fun `same retained WAL can append an exact semantic correction`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		rewriteLatestAggregateAsHistorical(first.logicalFactId)

		val corrected = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val revision = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				corrected.logicalFactId,
				corrected.semanticRevision,
			),
		)

		assertEquals(first.logicalFactId, corrected.logicalFactId)
		assertEquals(2L, revision.semanticRevision)
		assertEquals(1L, revision.supersedesSemanticRevision)
		assertEquals(1, revision.registeredObservationCount)
		assertEquals(SOURCE_SEQUENCE, revision.sourceSequence)
		assertEquals(2L, database.cellCapturedFactDao().revisionCount())
	}

	@Test
	fun `same WAL closes formerly open authority bounds after exact settlement`() = runTest {
		installValidFixture(candidateWriter = true, openAuthority = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val open = fact(first.logicalFactId, 1L)
		assertEquals(Long.MAX_VALUE, open.providerAcceptanceEndNanos)
		assertEquals(Long.MAX_VALUE, open.authorizationEffectEndNanos)
		assertEquals(Long.MAX_VALUE, open.sessionRunEffectEndNanos)
		assertEquals(Long.MAX_VALUE, open.deletionEffectEndNanos)

		settleFixtureAuthority()

		val corrected = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val settled = fact(corrected.logicalFactId, corrected.semanticRevision)
		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE, settled.factKind)
		assertEquals(REGISTRATION_END_NANOS, settled.providerAcceptanceEndNanos)
		assertEquals(REGISTRATION_END_NANOS, settled.authorizationEffectEndNanos)
		assertEquals(REGISTRATION_END_NANOS, settled.consentEffectEndNanos)
		assertEquals(REGISTRATION_END_NANOS, settled.sessionRunEffectEndNanos)
		assertEquals(SESSION_END_NANOS, settled.deletionEffectEndNanos)
	}

	@Test
	fun `finite historical bounds cannot move during correction`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET retired_elapsed_realtime_nanos = ? " +
				"WHERE source_kind = ? AND registration_generation = ?",
			arrayOf(REGISTRATION_END_NANOS + 1L, CELL_SOURCE, REGISTRATION_GENERATION),
		)

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH),
			writer.write(EVENT_ID),
		)
	}

	@Test
	fun `retained callback materializes aggregate when prior owner falls below retention`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET retained_from_ms = ? WHERE id = 1",
			arrayOf(OBSERVED_WALL_MS),
		)

		val second = assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		val retained = fact(second.logicalFactId, second.semanticRevision)

		assertEquals(CellCapturedFactRevisionEntity.FACT_KIND_AGGREGATE, retained.factKind)
		assertEquals(null, retained.aggregateOwnerLogicalFactId)
		assertEquals(2, retained.observationCount)
	}

	@Test
	fun `coverage replay authenticates its exact owner through a later owner revision`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(SECOND_EVENT_ID))
		pointCursorAt(syntheticRevision(fact(first.logicalFactId, 1L), 2L))

		assertIs<CellCapturedWriteResult.Unchanged>(writer.write(SECOND_EVENT_ID))
	}

	@Test
	fun `discontinuous prior lineage fails closed before reuse`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		pointCursorAt(syntheticRevision(fact(first.logicalFactId, 1L), 3L))
		val shifted = observations().map { observation ->
			observation.copy(
				providerTimestampNanos = requireNotNull(observation.providerTimestampNanos) +
					SECOND_DELIVERY_SHIFT_NANOS,
			)
		}
		insertAdditionalWal(SECOND_EVENT_ID, shifted, SOURCE_SEQUENCE + 1L)

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH),
			writer.write(SECOND_EVENT_ID),
		)
	}

	@Test
	fun `lineage beyond source local maximum fails closed`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		var current = fact(first.logicalFactId, 1L)
		for (revision in 2L..257L) {
			current = syntheticRevision(current, revision)
			assertEquals(true, database.cellCapturedFactDao().insertRevision(current) != -1L)
		}
		pointCursorAt(current, insert = false)

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.REVISION_CHAIN_MISMATCH),
			writer.write(EVENT_ID),
		)
	}

	@Test
	fun `manifest source integrity mismatch fails closed before persisted reuse`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE session_manifest_source SET qos_code = qos_code + 1 " +
				"WHERE logical_tracking_id = ? AND manifest_revision = ?",
			arrayOf(LOGICAL_ID, MANIFEST_REVISION),
		)

		assertEquals(
			CellCapturedWriteResult.AdapterRejected(
				CellWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE,
			),
			writer.write(EVENT_ID),
		)
	}

	@Test
	fun `cursor CAS collision rolls back an appended correction`() = runTest {
		installValidFixture(candidateWriter = true)
		val first = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		rewriteLatestAggregateAsHistorical(first.logicalFactId)
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		val result = writer.write(EVENT_ID) { checkpoint ->
			if (checkpoint == CellCapturedWriteCheckpoint.REVISION_INSERTED) {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE cell_captured_fact_cursor SET cursor_revision = cursor_revision + 1 " +
						"WHERE logical_fact_id = ?",
					arrayOf(first.logicalFactId),
				)
			}
		}

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.CURSOR_CHANGED),
			result,
		)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(
			1L,
			database.cellCapturedFactDao().cursor(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				first.logicalFactId,
			)?.cursorRevision,
		)
		assertEquals(evidenceRevision, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `destination authority rotation after qualification rolls back the write`() = runTest {
		installValidFixture(candidateWriter = true)
		assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))

		val result = writer.write(EVENT_ID) { checkpoint ->
			if (checkpoint == CellCapturedWriteCheckpoint.QUALIFIED) {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE source_destination_owner SET owner_generation = 2 " +
						"WHERE source_kind = ? AND destination = ?",
					arrayOf(
						CELL_SOURCE,
						SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
					),
				)
			}
		}

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.DESTINATION_OWNER_CHANGED),
			result,
		)
		assertEquals(1L, database.cellCapturedFactDao().revisionCount())
		assertEquals(
			SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			database.sourceDestinationOwnerDao().get(
				CELL_SOURCE,
				SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
			)?.ownerGeneration,
		)
	}

	@Test
	fun `retention rotation after qualification is rechecked and rolled back`() = runTest {
		installValidFixture(candidateWriter = true)

		val result = writer.write(EVENT_ID) { checkpoint ->
			if (checkpoint == CellCapturedWriteCheckpoint.QUALIFIED) {
				database.openHelper.writableDatabase.execSQL(
					"UPDATE source_evidence_state SET retained_from_ms = ? WHERE id = 1",
					arrayOf(OBSERVED_WALL_MS + 1L),
				)
			}
		}

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.RETAINED_DATA),
			result,
		)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(null, database.sourceEvidenceStateDao().get()?.retainedFromMs)
	}

	@Test
	fun `cancellation after cursor advancement rolls back fact cursor and evidence`() = runTest {
		installValidFixture(candidateWriter = true)
		val evidenceRevision = requireNotNull(database.sourceEvidenceStateDao().get()).revision

		assertFailsWith<CancellationException> {
			writer.write(EVENT_ID) { checkpoint ->
				if (checkpoint == CellCapturedWriteCheckpoint.CURSOR_ADVANCED) {
					throw CancellationException("test cancellation")
				}
			}
		}

		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
		assertEquals(0L, database.cellCapturedFactDao().cursorCount())
		assertEquals(evidenceRevision, database.sourceEvidenceStateDao().get()?.revision)
	}

	@Test
	fun `candidate writer remains dormant without exact destination owner`() = runTest {
		installValidFixture(candidateWriter = true, installDestinationOwner = false)

		assertEquals(
			CellCapturedWriteResult.Rejected(CellCapturedWriteRejection.DESTINATION_OWNER_CHANGED),
			writer.write(EVENT_ID),
		)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
	}

	@Test
	fun `v1 WAL cannot be rebound to a later source deletion generation`() = runTest {
		installValidFixture(candidateWriter = true, deletionGeneration = 1L)

		assertEquals(
			CellCapturedWriteResult.AdapterRejected(
				CellWalAdapterRejection.SCOPE_DELETION_AUTHORITY_MISMATCH,
			),
			writer.write(EVENT_ID),
		)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
	}

	@Test
	fun `source deletion generation advances only by exact sequential CAS`() = runTest {
		database.cellCapturedFactDao().insertDeletionGeneration(
			CellCaptureDeletionGenerationEntity(
				logicalTrackingId = LOGICAL_ID,
				serviceRunId = RUN_ID,
				collectedDataEpoch = 0L,
				generation = 1L,
				updatedAtMs = RUN_START_WALL_MS,
			),
		)

		assertEquals(
			0,
			database.cellCapturedFactDao().advanceDeletionGenerationExact(
				LOGICAL_ID, RUN_ID, 0L, 1L, 3L, RUN_START_WALL_MS + 1L,
			),
		)
		assertEquals(
			1,
			database.cellCapturedFactDao().advanceDeletionGenerationExact(
				LOGICAL_ID, RUN_ID, 0L, 1L, 2L, RUN_START_WALL_MS + 1L,
			),
		)
		assertEquals(2L, database.cellCapturedFactDao().deletionGeneration(LOGICAL_ID, RUN_ID)?.generation)
	}

	@Test
	fun `global epoch rotation blocks old generation zero WAL before scope reuse`() = runTest {
		installValidFixture(candidateWriter = true)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET collected_data_epoch = 1 WHERE id = 1",
		)

		assertEquals(
			CellCapturedWriteResult.AdapterRejected(CellWalAdapterRejection.DELETED_EVIDENCE),
			writer.write(EVENT_ID),
		)
		assertEquals(0L, database.cellCapturedFactDao().revisionCount())
	}

	@Test
	fun `partial delivery persists only fresh child coverage while retaining full WAL interval`() = runTest {
		val staleTime = AUTHORIZATION_START_NANOS - 1L
		installValidFixture(
			candidateWriter = true,
			observations = listOf(
				CellObservationEvidence("", "LTE", true, -100, staleTime),
				CellObservationEvidence("", "NR", false, -85, OBSERVED_END_NANOS),
			),
		)

		val applied = assertIs<CellCapturedWriteResult.Applied>(writer.write(EVENT_ID))
		val fact = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				applied.logicalFactId,
				applied.semanticRevision,
			),
		)

		assertEquals(staleTime, fact.observedIntervalStartNanos)
		assertEquals(OBSERVED_END_NANOS, fact.coverageIntervalStartNanos)
		assertEquals(OBSERVED_END_NANOS, fact.coverageIntervalEndNanos)
		assertEquals(1, fact.acceptedChildCount)
		assertEquals(1, fact.staleChildCount)
	}

	private suspend fun installValidFixture(
		sourceSequence: Long = SOURCE_SEQUENCE,
		deliveryIdentityOverride: String? = null,
		evidenceState: SourceEvidenceState = SourceEvidenceState(),
		zoneId: String = ZONE_ID,
		observedWallMs: Long = OBSERVED_WALL_MS,
		segmentRunId: String = RUN_ID,
		candidateWriter: Boolean = false,
		installDestinationOwner: Boolean = candidateWriter,
		deletionGeneration: Long? = null,
		observations: List<CellObservationEvidence> = observations(),
		openAuthority: Boolean = false,
	) {
		database.sourceEvidenceStateDao().ensure(evidenceState)
		if (deletionGeneration != null) {
			database.cellCapturedFactDao().insertDeletionGeneration(
				CellCaptureDeletionGenerationEntity(
					logicalTrackingId = LOGICAL_ID,
					serviceRunId = RUN_ID,
					collectedDataEpoch = evidenceState.collectedDataEpoch,
					generation = deletionGeneration,
					updatedAtMs = RUN_START_WALL_MS,
				),
			)
		}
		if (installDestinationOwner) {
			database.sourceDestinationOwnerDao().insertIfAbsent(
				SourceDestinationOwnerEntity(
					sourceKind = CELL_SOURCE,
					destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL,
					owner = SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS,
					ownerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					updatedAtMs = RUN_START_WALL_MS,
				),
			)
		}
		val plan = cellPlan()
		insertPlan(plan)
		installPolicyAndConsent()
		val segmentId = database.sessionSegmentDao().insert(segment(segmentRunId))
		assertEquals(SEGMENT_ID, segmentId)
		installSessionAndManifest(segmentId, zoneId, candidateWriter, openAuthority)
		val demand = demand(openAuthority)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		database.sourceBrokerDao().insertRegistration(
			registration(plan.physicalConfigurationFingerprint(), openAuthority = openAuthority),
		)
		val authorization = SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE,
			registrationGeneration = REGISTRATION_GENERATION,
			authorizationRevision = AUTHORIZATION_REVISION,
			demands = listOf(demand),
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = AUTHORIZATION_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
		)
		database.sourceBrokerDao().insertAuthorizations(authorization)
		val payload = CellSnapshotPayload(null, observations, CellRefreshOutcome.CALLBACK)
		val encodedPayload = payloadCodec.encode(payload, PAYLOAD_VERSION)
		val deliveryIdentity = deliveryIdentityOverride ?: cellProviderDeliveryIdentity(
			BOOT_ID,
			observations,
		).value
		val unsignedWal = SourceEventWalEntity(
			eventId = EVENT_ID.value,
			providerDedupKey = null,
			deliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = RUN_ID,
			sourceKind = CELL_SOURCE,
			sourceInstanceId = SOURCE_INSTANCE,
			registrationGeneration = REGISTRATION_GENERATION,
			physicalConfigurationFingerprint = plan.physicalConfigurationFingerprint(),
			authorizationRevision = AUTHORIZATION_REVISION,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = authorization.first().authorizationFingerprint,
			sourceSequence = sourceSequence,
			configRevision = PLAN_REVISION,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION.ordinal,
			clockDomainId = BOOT_ID,
			observedElapsedNanos = requireNotNull(
				observations.mapNotNull(CellObservationEvidence::providerTimestampNanos).maxOrNull(),
			),
			observedIntervalStartNanos = requireNotNull(
				observations.mapNotNull(CellObservationEvidence::providerTimestampNanos).minOrNull(),
			),
			receivedElapsedNanos = RECEIVED_NANOS,
			wallTimeMs = observedWallMs,
			wallTimeUncertaintyMs = WALL_UNCERTAINTY_MS,
			capturedCollectedDataEpoch = 0L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			sessionManifestRevision = MANIFEST_REVISION,
			lifecycleLeaseGeneration = LEASE_GENERATION,
			acquiredAtMs = observedWallMs,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = PAYLOAD_VERSION,
			payload = encodedPayload.bytes,
			payloadChecksum = encodedPayload.checksum,
			createdAtMs = observedWallMs,
		)
		val wal = unsignedWal.copy(integrityIdentity = unsignedWal.calculatedIntegrityIdentity())
		assertEquals(1L, database.sourceEventWalDao().insertIgnoringDuplicate(wal))
	}

	private suspend fun insertAdditionalWal(
		eventId: SourceEventId,
		observations: List<CellObservationEvidence>,
		sourceSequence: Long,
	) {
		val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT_ID.value))
		val encodedPayload = payloadCodec.encode(
			CellSnapshotPayload(null, observations, CellRefreshOutcome.CALLBACK),
			PAYLOAD_VERSION,
		)
		val latestProviderTime = requireNotNull(
			observations.mapNotNull(CellObservationEvidence::providerTimestampNanos).maxOrNull(),
		)
		val observedWallMs = OBSERVED_WALL_MS + SECOND_DELIVERY_SHIFT_NANOS / 1_000_000L
		val unsigned = original.copy(
			admissionOrdinal = 0L,
			eventId = eventId.value,
			deliveryIdentity = cellProviderDeliveryIdentity(BOOT_ID, observations).value,
			sourceSequence = sourceSequence,
			observedElapsedNanos = latestProviderTime,
			observedIntervalStartNanos = requireNotNull(
				observations.mapNotNull(CellObservationEvidence::providerTimestampNanos).minOrNull(),
			),
			receivedElapsedNanos = latestProviderTime + 100_000_000L,
			wallTimeMs = observedWallMs,
			acquiredAtMs = observedWallMs,
			payload = encodedPayload.bytes,
			payloadChecksum = encodedPayload.checksum,
			createdAtMs = observedWallMs,
			integrityIdentity = SourceEventWalEntity.LEGACY_PENDING_CHECKSUM,
		)
		val wal = unsigned.copy(integrityIdentity = unsigned.calculatedIntegrityIdentity())
		assertEquals(2L, database.sourceEventWalDao().insertIgnoringDuplicate(wal))
	}

	private suspend fun fact(
		logicalFactId: String,
		semanticRevision: Long,
	): CellCapturedFactRevisionEntity = requireNotNull(
		database.cellCapturedFactDao().revision(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			logicalFactId,
			semanticRevision,
		),
	)

	private fun syntheticRevision(
		base: CellCapturedFactRevisionEntity,
		semanticRevision: Long,
	): CellCapturedFactRevisionEntity {
		val unsigned = base.copy(
			semanticRevision = semanticRevision,
			supersedesSemanticRevision = semanticRevision - 1L,
			mutationId = CellCapturedFactRevisionIntegrity.mutationId(
				base.logicalFactId,
				semanticRevision,
			),
			effectChecksum = ZERO_CHECKSUM,
		)
		return unsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)
	}

	private suspend fun pointCursorAt(
		revision: CellCapturedFactRevisionEntity,
		insert: Boolean = true,
	) {
		if (insert) {
			assertEquals(true, database.cellCapturedFactDao().insertRevision(revision) != -1L)
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_semantic_revision = ?, " +
				"latest_mutation_id = ?, latest_effect_checksum = ?, " +
				"latest_source_admission_ordinal = ?, cursor_revision = cursor_revision + 1 " +
				"WHERE logical_fact_id = ?",
			arrayOf(
				revision.semanticRevision,
				revision.mutationId,
				revision.effectChecksum,
				revision.sourceAdmissionOrdinal,
				revision.logicalFactId,
			),
		)
	}

	private fun settleFixtureAuthority() {
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET status = 'RETIRED', retired_at_ms = ?, " +
				"retired_elapsed_realtime_nanos = ? WHERE source_kind = ? " +
				"AND registration_generation = ?",
			arrayOf(
				SESSION_END_WALL_MS,
				REGISTRATION_END_NANOS,
				CELL_SOURCE,
				REGISTRATION_GENERATION,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE logical_tracking_session SET state = 'FINALIZED', lifecycle_revision = 2, " +
				"cutoff_at_ms = ?, cutoff_elapsed_nanos = ?, completed_at_ms = ?, " +
				"final_admission_ordinal = 1, current_service_run_id = NULL " +
				"WHERE logical_tracking_id = ?",
			arrayOf(SESSION_END_WALL_MS, SESSION_END_NANOS, SESSION_END_WALL_MS, LOGICAL_ID),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_service_run SET state = 'FINALIZED', completed_at_ms = ?, " +
				"completion_reason = 'USER_STOP', runtime_acknowledgement = 'STOP_ACCEPTED', " +
				"run_revision = 2, presentation_acknowledgement = 'QUIESCED', " +
				"presentation_acknowledged_at_ms = ? WHERE service_run_id = ?",
			arrayOf(SESSION_END_WALL_MS, SESSION_END_WALL_MS, RUN_ID),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET status = 'RETIRED', retire_boot_id = ?, " +
				"retire_elapsed_realtime_nanos = ?, retired_at_ms = ? WHERE demand_id = ?",
			arrayOf(BOOT_ID, SESSION_END_NANOS, SESSION_END_WALL_MS, DEMAND_ID),
		)
	}

	private suspend fun rewriteLatestAggregateAsHistorical(logicalFactId: String) {
		val current = requireNotNull(
			database.cellCapturedFactDao().revision(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				logicalFactId,
				1L,
			),
		)
		val unsigned = current.copy(
			registeredObservationCount = 0,
			effectChecksum = ZERO_CHECKSUM,
		)
		val historical = unsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET registered_observation_count = ?, " +
				"effect_checksum = ? WHERE logical_fact_id = ? AND semantic_revision = 1",
			arrayOf(0, historical.effectChecksum, logicalFactId),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? " +
				"WHERE logical_fact_id = ?",
			arrayOf(historical.effectChecksum, logicalFactId),
		)
	}

	private suspend fun rewriteAsHistoricalCoverageOnly(
		logicalFactId: String,
		ownerLogicalFactId: String,
	) {
		val current = fact(logicalFactId, 1L)
		val unsigned = current.copy(
			factKind = CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
			aggregateOwnerLogicalFactId = ownerLogicalFactId,
			aggregateOwnerSemanticRevision = 1L,
			observationCount = null,
			registeredObservationCount = null,
			gsmCount = null,
			cdmaCount = null,
			wcdmaCount = null,
			tdscdmaCount = null,
			lteCount = null,
			nrCount = null,
			qualityUnknownCount = null,
			qualityNoneOrUnknownCount = null,
			qualityPoorCount = null,
			qualityModerateCount = null,
			qualityGoodCount = null,
			qualityGreatCount = null,
			weakObservationCount = null,
			knownQualityObservationCount = null,
			allKnownQualityIsWeak = null,
			effectChecksum = ZERO_CHECKSUM,
		)
		val historical = unsigned.copy(
			effectChecksum = CellCapturedFactRevisionIntegrity.effectChecksum(unsigned),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_revision SET fact_kind = ?, " +
				"aggregate_owner_logical_fact_id = ?, aggregate_owner_semantic_revision = ?, " +
				"observation_count = NULL, registered_observation_count = NULL, " +
				"gsm_count = NULL, cdma_count = NULL, wcdma_count = NULL, tdscdma_count = NULL, " +
				"lte_count = NULL, nr_count = NULL, quality_unknown_count = NULL, " +
				"quality_none_or_unknown_count = NULL, quality_poor_count = NULL, " +
				"quality_moderate_count = NULL, quality_good_count = NULL, " +
				"quality_great_count = NULL, weak_observation_count = NULL, " +
				"known_quality_observation_count = NULL, all_known_quality_is_weak = NULL, " +
				"effect_checksum = ? WHERE logical_fact_id = ? AND semantic_revision = 1",
			arrayOf(
				CellCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
				ownerLogicalFactId,
				1L,
				historical.effectChecksum,
				logicalFactId,
			),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE cell_captured_fact_cursor SET latest_effect_checksum = ? " +
				"WHERE logical_fact_id = ?",
			arrayOf(historical.effectChecksum, logicalFactId),
		)
	}

	private suspend fun insertPlan(plan: CellPlan) {
		val encoded = planCodec.encode(plan)
		database.sourcePlanStateDao().insertRevision(
			AcquisitionPlanRevisionEntity(
				revision = PLAN_REVISION,
				planId = "cell-plan",
				createdAtMs = RUN_START_WALL_MS,
				status = "EFFECTIVE",
				sourcePolicyRevision = POLICY_REVISION,
			),
		)
		database.sourcePlanStateDao().insertDesiredPlans(
			listOf(
				SourceDesiredPlanEntity(
					revision = PLAN_REVISION,
					sourceKind = CELL_SOURCE,
					payloadVersion = 1,
					payload = encoded.bytes,
					payloadChecksum = encoded.checksum,
				),
			),
		)
	}

	private suspend fun installPolicyAndConsent() {
		val policyDao = database.sourcePolicyDao()
		policyDao.ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = POLICY_REVISION,
				legacySettingsFingerprint = null,
				updatedAtMs = RUN_START_WALL_MS,
			),
		)
		policyDao.insertPolicies(
			listOf(
				SourcePolicyEntity(
					policyRevision = POLICY_REVISION,
					sourceKind = CELL_SOURCE,
					enabled = true,
					qosCode = QOS_CODE,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = true,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = false,
					captureConsentEpoch = CONSENT_EPOCH,
					controlConsentEpoch = null,
					ambientConsentEpoch = null,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = RUN_START_WALL_MS,
					changeReason = "TEST",
				),
			),
		)
		policyDao.insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					sourceKind = CELL_SOURCE,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					epoch = CONSENT_EPOCH,
					eligible = true,
					persistenceEligible = true,
					policyRevision = POLICY_REVISION,
					effectiveBootId = BOOT_ID,
					effectiveElapsedRealtimeNanos = POLICY_START_NANOS,
					effectiveWallTimeMs = RUN_START_WALL_MS,
					changeReason = "TEST",
				),
			),
		)
	}

	private suspend fun installSessionAndManifest(
		segmentId: Long,
		zoneId: String,
		candidateWriter: Boolean,
		openAuthority: Boolean = false,
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = if (openAuthority) "ACTIVE" else "FINALIZED",
				lifecycleRevision = if (openAuthority) 1L else 2L,
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				startOrigin = START_ORIGIN,
				clockDomainId = BOOT_ID,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				cutoffAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
				cutoffElapsedNanos = SESSION_END_NANOS.takeUnless { openAuthority },
				completedAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
				finalAdmissionOrdinal = 1L.takeUnless { openAuthority },
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = MANIFEST_REVISION,
				currentIntentRevision = 1L,
				currentServiceRunId = RUN_ID.takeIf { openAuthority },
				lifecycleLeaseGeneration = LEASE_GENERATION,
				lifecycleBootId = BOOT_ID,
				automationEpoch = null,
			),
		)
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = RUN_ID,
				logicalTrackingId = LOGICAL_ID,
				state = if (openAuthority) "ACTIVE" else "FINALIZED",
				desiredPlanRevision = PLAN_REVISION,
				rolloutRevision = ROLLOUT_REVISION,
				foregroundCapabilityFlags = 0L,
				startedAtMs = RUN_START_WALL_MS,
				startedElapsedNanos = RUN_START_NANOS,
				completedAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
				completionReason = "USER_STOP".takeUnless { openAuthority },
				bootId = BOOT_ID,
				leaseGeneration = LEASE_GENERATION,
				startOrigin = START_ORIGIN,
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = if (openAuthority) "START_ACCEPTED" else "STOP_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 2L,
				startDeliveryToken = "cell-start-token",
				startCommandGeneration = 1L,
				preparedManifestRevision = MANIFEST_REVISION,
				preparedIntentRevision = 1L,
				androidDeliveryState = "FOREGROUND_ACCEPTED",
				androidDeliveryUpdatedAtMs = RUN_START_WALL_MS,
				startIsUserInitiated = true,
				startIsAmbient = false,
				sessionSegmentId = segmentId,
				presentationAcknowledgement = if (openAuthority) {
					SourceServiceRunEntity.PRESENTATION_PENDING
				} else {
					SourceServiceRunEntity.PRESENTATION_QUIESCED
				},
				presentationAcknowledgedAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
			),
		)
		val source = SessionManifestSourceEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			sourceKind = CELL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch = CONSENT_EPOCH,
			persistenceEligible = true,
			qosCode = QOS_CODE,
			outputDestination = if (candidateWriter) {
				SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL
			} else null,
			writerOwner = if (candidateWriter) {
				SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS
			} else null,
			writerOwnerGeneration = if (candidateWriter) {
				SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
			} else null,
			writerProjectionId = if (candidateWriter) {
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
			} else null,
			writerProjectionVersion = if (candidateWriter) {
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
			} else null,
			writerBindingGeneration = if (candidateWriter) {
				SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION
			} else null,
		)
		val unsigned = SessionManifestVersionEntity(
			logicalTrackingId = LOGICAL_ID,
			manifestRevision = MANIFEST_REVISION,
			serviceRunId = RUN_ID,
			sessionMode = "MANUAL",
			sourcePolicyRevision = POLICY_REVISION,
			acquisitionPlanRevision = PLAN_REVISION,
			rolloutRevision = ROLLOUT_REVISION,
			startOrigin = START_ORIGIN,
			effectiveBootId = BOOT_ID,
			effectiveElapsedRealtimeNanos = RUN_START_NANOS,
			effectiveWallTimeMs = RUN_START_WALL_MS,
			zoneId = zoneId,
			automationEpoch = null,
			changeReason = "MANUAL_START",
			manifestChecksum = "",
		)
		database.sourceSessionDao().insertManifest(
			unsigned.copy(manifestChecksum = SessionManifestIntegrity.compute(unsigned, listOf(source))),
		)
		database.sourceSessionDao().insertManifestSources(listOf(source))
	}

	private fun registration(
		fingerprint: String = cellPlan().physicalConfigurationFingerprint(),
		generation: Long = REGISTRATION_GENERATION,
		sourceInstance: String = SOURCE_INSTANCE,
		openAuthority: Boolean = false,
	) = ProviderRegistrationGenerationEntity(
		sourceKind = CELL_SOURCE,
		registrationGeneration = generation,
		sourceInstanceId = sourceInstance,
		ownerScope = "source-broker:$CELL_SOURCE",
		clockDomainId = BOOT_ID,
		physicalConfigurationFingerprint = fingerprint,
		collectedDataEpoch = 0L,
		providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "process-$generation",
		status = if (openAuthority) ProviderRegistrationGenerationEntity.STATUS_ACTIVE else
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = RUN_START_WALL_MS,
		reservedElapsedRealtimeNanos = RUN_START_NANOS,
		acceptedAtMs = RUN_START_WALL_MS,
		acceptedElapsedRealtimeNanos = REGISTRATION_START_NANOS,
		retiredAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
		retiredElapsedRealtimeNanos = REGISTRATION_END_NANOS.takeUnless { openAuthority },
		failureCode = null,
		captureCallbackBarrierAuthorizationRevision = AUTHORIZATION_REVISION,
	)

	private fun demand(openAuthority: Boolean = false) = SourceDemandEntity(
		demandId = DEMAND_ID,
		consumerId = "session:$RUN_ID",
		sourceKind = CELL_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		manifestRevision = MANIFEST_REVISION,
		lifecycleLeaseGeneration = LEASE_GENERATION,
		sourcePolicyRevision = POLICY_REVISION,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = QOS_CODE,
		minimumAcquisitionSpec = "cell:v1:change_callbacks",
		adaptiveReductionAllowed = false,
		maximumAgeMs = 1_000L,
		desiredLatencyMs = 1_000L,
		requestedDeliveryLatencyMs = 0L,
		requestedBootId = BOOT_ID,
		requestedElapsedRealtimeNanos = RUN_START_NANOS,
		requestedAtMs = RUN_START_WALL_MS,
		status = if (openAuthority) SourceDemandEntity.STATUS_ACTIVE else SourceDemandEntity.STATUS_RETIRED,
		retireBootId = BOOT_ID.takeUnless { openAuthority },
		retireElapsedRealtimeNanos = SESSION_END_NANOS.takeUnless { openAuthority },
		retiredAtMs = SESSION_END_WALL_MS.takeUnless { openAuthority },
	)

	private fun segment(runId: String) = SessionSegment(
		id = SEGMENT_ID,
		startTimeMs = RUN_START_WALL_MS,
		endTimeMs = SESSION_END_WALL_MS,
		distanceM = 0f,
		steps = null,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		inferenceVersion = null,
		createdAt = SESSION_END_WALL_MS,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = runId,
	)

	private fun cellPlan() = CellPlan(
		revision = PLAN_REVISION,
		mode = CellMode.OBSERVE_CHANGES,
		minimumRefreshAttemptIntervalMs = 60_000L,
		maximumAcceptableCachedAgeMs = 1_000L,
		subscriptionIds = emptySet(),
		backoff = RetryBackoff(1_000L, 60_000L),
	)

	private fun observations() = listOf(
		CellObservationEvidence("", "LTE", true, -100, OBSERVED_START_NANOS),
		CellObservationEvidence("", "NR", false, -85, OBSERVED_END_NANOS),
	)

	private companion object {
		val EVENT_ID = SourceEventId("cell-event-1")
		val SECOND_EVENT_ID = SourceEventId("cell-event-2")
		val CELL_SOURCE = SourceKind.CELL.stableCode
		const val LOGICAL_ID = "logical-cell"
		const val RUN_ID = "run-cell"
		const val SOURCE_INSTANCE = "cell-instance"
		const val DEMAND_ID = "cell-demand"
		const val BOOT_ID = "boot-1"
		const val ZONE_ID = "Europe/Prague"
		const val PLAN_REVISION = 1L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 1L
		const val MANIFEST_REVISION = 1L
		const val LEASE_GENERATION = 1L
		const val REGISTRATION_GENERATION = 1L
		const val AUTHORIZATION_REVISION = 1L
		const val ROLLOUT_REVISION = 1L
		const val SEGMENT_ID = 1L
		const val SOURCE_SEQUENCE = 1L
		const val PAYLOAD_VERSION = 1
		const val QOS_CODE = 2
		const val START_ORIGIN = "MANUAL_FOREGROUND_START"
		const val RUN_START_NANOS = 1_000_000_000L
		const val POLICY_START_NANOS = 1_000_000_000L
		const val REGISTRATION_START_NANOS = 1_100_000_000L
		const val AUTHORIZATION_START_NANOS = 1_200_000_000L
		const val OBSERVED_START_NANOS = 1_500_000_000L
		const val OBSERVED_END_NANOS = 1_600_000_000L
		const val RECEIVED_NANOS = 1_700_000_000L
		const val REGISTRATION_END_NANOS = 3_000_000_000L
		const val SESSION_END_NANOS = 4_000_000_000L
		const val RUN_START_WALL_MS = 1_699_999_999_000L
		const val OBSERVED_WALL_MS = 1_700_000_000_000L
		const val SESSION_END_WALL_MS = 1_700_000_003_000L
		const val WALL_UNCERTAINTY_MS = 1L
		const val SECOND_DELIVERY_SHIFT_NANOS = 200_000_000L
		const val ZERO_CHECKSUM =
			"0000000000000000000000000000000000000000000000000000000000000000"
	}
}

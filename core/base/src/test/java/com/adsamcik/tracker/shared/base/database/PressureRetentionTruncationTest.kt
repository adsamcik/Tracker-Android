package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.PressureWalClosureKind
import com.adsamcik.tracker.shared.base.database.data.PressureWalPayload
import com.adsamcik.tracker.shared.base.database.data.PressureWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.PressureWalSensorAccuracy
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureRetentionTruncationTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			SourceEvidenceState(collectedDataEpoch = COLLECTED_DATA_EPOCH),
		)
		database.sourceEvidenceStateDao().updateLifecycle(
			epoch = COLLECTED_DATA_EPOCH,
			retainedFromMs = RETENTION_FLOOR_MS,
			updatedAtMs = MARKED_AT_MS,
		) shouldBe 1
		database.sourcePolicyDao().insertPolicies(listOf(pressurePolicy()))
		database.sourcePolicyDao().insertConsentEpochs(listOf(pressureConsent()))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `total page overflow rolls back an earlier marker and lineage deletion`() = runTest {
		insertRetainedPressureRun("a", 1L)
		insertRetainedPressureRun("b", 2L)

		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
				limits = limits(maximumFactPages = 1L),
				checkpoint = {},
			)
		}

		database.pressureFactRevisionDao().count() shouldBe 2L
		database.pressureFactRetentionAuthorityDao().count() shouldBe 0L
		database.pressureFactRetentionRevisionAuthorityDao().count() shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `cancellation after marker insertion rolls back marker and exact lineage deletion`() = runTest {
		insertRetainedPressureRun("cancel", 1L)

		shouldThrow<CancellationException> {
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
				limits = limits(),
				checkpoint = { checkpoint ->
					if (checkpoint == PressureRetentionCheckpoint.RUN_MARKED) {
						throw CancellationException("cancel retention after marker")
					}
				},
			)
		}

		database.pressureFactRevisionDao().count() shouldBe 1L
		database.pressureFactRetentionAuthorityDao().count() shouldBe 0L
		database.pressureFactRetentionRevisionAuthorityDao().count() shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `lineage revision overflow rejects without a retention marker`() = runTest {
		val fixture = insertRetainedPressureRun("lineage-overflow", 1L)
		val correction = pressureCorrection(fixture, 2L, "pressure-lineage-overflow-2")
		database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(correction))
		database.pressureFactRevisionDao().insert(correction)

		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
				limits = limits(maximumLineageRevisions = 1),
				checkpoint = {},
			)
		}

		database.pressureFactRevisionDao().count() shouldBe 2L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `single revision Pressure retention rejects missing WAL authority`() = runTest {
		insertRetainedPressureRun("single-missing-wal", 1L, insertWal = false)

		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
			)
		}

		database.pressureFactRevisionDao().count() shouldBe 1L
		database.pressureFactRetentionAuthorityDao().count() shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `single revision Pressure retention rejects mismatched WAL authority`() = runTest {
		val fixture = insertRetainedPressureRun("single-mismatched-wal", 1L, insertWal = false)
		val mismatched = pressureWal(fixture.fact)
			.copy(sourcePolicyRevision = fixture.fact.sourcePolicyRevision + 1L)
			.let { wal -> wal.copy(integrityIdentity = wal.calculatedIntegrityIdentity()) }
		database.sourceEventWalDao().insertIgnoringDuplicate(mismatched)

		shouldThrow<IllegalStateException> {
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
			)
		}

		database.pressureFactRevisionDao().count() shouldBe 1L
		database.pressureFactRetentionAuthorityDao().count() shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	@Test
	fun `single revision Pressure retention accepts exact WAL authority`() = runTest {
		val fixture = insertRetainedPressureRun("single-valid-wal", 1L)

		database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
			beforeMs = RETENTION_FLOOR_MS,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			markedAtMs = MARKED_AT_MS,
		) shouldBe 1

		database.pressureFactRevisionDao().count() shouldBe 0L
		database.pressureFactRetentionAuthorityDao().get(
			fixture.fact.writerProjectionId,
			fixture.fact.writerProjectionVersion,
			fixture.fact.logicalFactId,
		)?.hasValidStoredIntegrity() shouldBe true
	}

	@Test
	fun `retention removes corrected Pressure lineage with stable fact identity and distinct WALs`() =
		runTest {
			val fixture = insertRetainedPressureRun("corrected", 1L)
			val correction = pressureCorrection(fixture, 2L, "pressure-corrected-2")
			database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(fixture.fact))
			database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(correction))
			database.pressureFactRevisionDao().insert(correction)

			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
			) shouldBe 2

			database.pressureFactRevisionDao().count() shouldBe 0L
			database.sourceDeletionFenceDao().countAll() shouldBe 1L
			val retained = requireNotNull(database.pressureFactRetentionAuthorityDao().get(
				fixture.fact.writerProjectionId,
				fixture.fact.writerProjectionVersion,
				fixture.fact.logicalFactId,
			))
			retained.hasValidStoredIntegrity() shouldBe true
			retained.triggerSourceEventId shouldBe fixture.fact.sourceEventId
			retained.currentSourceEventId shouldBe correction.sourceEventId
			retained.currentSemanticRevision shouldBe correction.semanticRevision
			database.sourceEventWalDao().getByEventId(
				correction.sourceEventId,
			)?.eventId shouldBe correction.sourceEventId
		}

	@Test
	fun `mixed run retains current lineages and receipts only exact deleted lineages`() = runTest {
		val fixture = insertRetainedPressureRun("mixed", 1L)
		val retained = retainedIndependentPressureFact(fixture, 2L)
		database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(retained))
		database.pressureFactRevisionDao().insert(retained)

		database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
			beforeMs = RETENTION_FLOOR_MS,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			markedAtMs = MARKED_AT_MS,
		) shouldBe 1

		database.pressureFactRevisionDao().latest(
			retained.writerProjectionId,
			retained.writerProjectionVersion,
			retained.logicalFactId,
		) shouldBe retained
		database.pressureFactRevisionDao().latest(
			fixture.fact.writerProjectionId,
			fixture.fact.writerProjectionVersion,
			fixture.fact.logicalFactId,
		) shouldBe null
		database.pressureFactRetentionAuthorityDao().get(
			fixture.fact.writerProjectionId,
			fixture.fact.writerProjectionVersion,
			fixture.fact.logicalFactId,
		)?.hasValidStoredIntegrity() shouldBe true
		database.pressureFactRetentionAuthorityDao().get(
			retained.writerProjectionId,
			retained.writerProjectionVersion,
			retained.logicalFactId,
		) shouldBe null
	}

	@Test
	fun `unrelated retained lineage leaves surviving evidence absent`() = runTest {
		val fixture = insertRetainedPressureRun("mixed-resolution", 1L)
		database.pressureFactRevisionDao().deleteExactLineages(
			logicalTrackingId = fixture.fact.logicalTrackingId,
			serviceRunId = fixture.fact.serviceRunId,
			writerProjectionId = fixture.fact.writerProjectionId,
			writerProjectionVersion = fixture.fact.writerProjectionVersion,
			logicalFactIds = listOf(fixture.fact.logicalFactId),
		) shouldBe 1
		val surviving = retainedIndependentPressureFact(fixture, 2L)
		database.pressureFactRevisionDao().insert(surviving)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(fixture.fact),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)

		resolveRetentionByEvidence(surviving) shouldBe PressureFactRetentionResolution.Absent
	}

	@Test
	fun `live retained logical fact collision invalidates absent and authenticated resolution`() =
		runTest {
			val fixture = insertRetainedPressureRun("live-logical-collision", 1L)
			database.persistPressureFactRetentionAuthority(
				revisions = listOf(fixture.fact),
				retainedFromMs = RETENTION_FLOOR_MS,
				retainedAtMs = MARKED_AT_MS,
			)

			resolveRetentionByEvidence(fixture.fact) shouldBe PressureFactRetentionResolution.Invalid
			resolveRetentionByFact(fixture.fact) shouldBe PressureFactRetentionResolution.Invalid
			val survivor = retainedIndependentPressureFact(fixture, 2L)
			resolveRetentionByEvidence(survivor) shouldBe PressureFactRetentionResolution.Invalid
			resolveRetentionByFact(survivor) shouldBe PressureFactRetentionResolution.Invalid
		}

	@Test
	fun `live retained event collision invalidates run authority`() = runTest {
		val fixture = insertRetainedPressureRun("live-event-collision", 1L)
		database.pressureFactRevisionDao().deleteExactLineages(
			logicalTrackingId = fixture.fact.logicalTrackingId,
			serviceRunId = fixture.fact.serviceRunId,
			writerProjectionId = fixture.fact.writerProjectionId,
			writerProjectionVersion = fixture.fact.writerProjectionVersion,
			logicalFactIds = listOf(fixture.fact.logicalFactId),
		) shouldBe 1
		val unsignedCollision = retainedIndependentPressureFact(fixture, 2L).copy(
			sourceEventId = fixture.fact.sourceEventId,
			effectChecksum = "pending",
		)
		val collision = unsignedCollision.copy(
			effectChecksum =
				PressureFactRevisionIntegrity.effectChecksum(unsignedCollision, fixture.binding),
		)
		database.pressureFactRevisionDao().insert(collision)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(fixture.fact),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)

		resolveRetentionByEvidence(fixture.fact) shouldBe PressureFactRetentionResolution.Invalid
		resolveRetentionByFact(fixture.fact) shouldBe PressureFactRetentionResolution.Invalid
	}

	@Test
	fun `live retained admission collision invalidates run authority`() = runTest {
		val fixture = insertRetainedPressureRun("live-admission-collision", 1L)
		database.pressureFactRevisionDao().deleteExactLineages(
			logicalTrackingId = fixture.fact.logicalTrackingId,
			serviceRunId = fixture.fact.serviceRunId,
			writerProjectionId = fixture.fact.writerProjectionId,
			writerProjectionVersion = fixture.fact.writerProjectionVersion,
			logicalFactIds = listOf(fixture.fact.logicalFactId),
		) shouldBe 1
		val unsignedCollision = retainedIndependentPressureFact(fixture, 2L).copy(
			sourceAdmissionOrdinal = fixture.fact.sourceAdmissionOrdinal,
			effectChecksum = "pending",
		)
		val collision = unsignedCollision.copy(
			effectChecksum =
				PressureFactRevisionIntegrity.effectChecksum(unsignedCollision, fixture.binding),
		)
		database.pressureFactRevisionDao().insert(collision)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(fixture.fact),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)

		resolveRetentionByEvidence(fixture.fact) shouldBe PressureFactRetentionResolution.Invalid
		resolveRetentionByFact(fixture.fact) shouldBe PressureFactRetentionResolution.Invalid
	}

	@Test
	fun `exact retained event with wrong admission fails closed`() = runTest {
		val fixture = insertRetainedPressureRun("wrong-admission", 1L)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(fixture.fact),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)

		resolveRetentionByEvidence(
			fact = fixture.fact,
			sourceAdmissionOrdinal = fixture.fact.sourceAdmissionOrdinal + 1L,
		) shouldBe PressureFactRetentionResolution.Invalid
	}

	@Test
	fun `duplicate conflicting child identity invalidates surviving evidence`() = runTest {
		val fixture = insertRetainedPressureRun("duplicate-child", 1L)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(fixture.fact),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)
		database.openHelper.writableDatabase.execSQL(
			"DROP INDEX idx_pressure_retention_current_event",
		)
		database.openHelper.writableDatabase.execSQL(
			"DROP INDEX idx_pressure_retention_revision_event",
		)
		val conflictUnsigned = independentPressureFact(fixture, 2L).copy(
			sourceEventId = fixture.fact.sourceEventId,
			effectChecksum = "pending",
		)
		val conflict = conflictUnsigned.copy(
			effectChecksum =
				PressureFactRevisionIntegrity.effectChecksum(conflictUnsigned, fixture.binding),
		)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(conflict),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)

		resolveRetentionByEvidence(
			retainedIndependentPressureFact(fixture, 3L),
		) shouldBe PressureFactRetentionResolution.Invalid
	}

	@Test
	fun `orphan retained parent invalidates surviving evidence`() = runTest {
		val fixture = insertRetainedPressureRun("orphan-parent", 1L)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(fixture.fact),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM pressure_fact_retention_revision_authority " +
				"WHERE logical_fact_id = ?",
			arrayOf(fixture.fact.logicalFactId),
		)

		resolveRetentionByEvidence(
			retainedIndependentPressureFact(fixture, 2L),
		) shouldBe PressureFactRetentionResolution.Invalid
	}

	@Test
	fun `orphan retained child invalidates surviving evidence`() = runTest {
		val fixture = insertRetainedPressureRun("orphan-child", 1L)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(fixture.fact),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)
		database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM pressure_fact_retention_authority WHERE logical_fact_id = ?",
			arrayOf(fixture.fact.logicalFactId),
		)
		database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")

		resolveRetentionByEvidence(
			retainedIndependentPressureFact(fixture, 2L),
		) shouldBe PressureFactRetentionResolution.Invalid
	}

	@Test
	fun `corrupt unrelated retained authority invalidates surviving evidence`() = runTest {
		val fixture = insertRetainedPressureRun("corrupt-run", 1L)
		database.persistPressureFactRetentionAuthority(
			revisions = listOf(fixture.fact),
			retainedFromMs = RETENTION_FLOOR_MS,
			retainedAtMs = MARKED_AT_MS,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE pressure_fact_retention_authority SET effect_checksum = 'corrupt' " +
				"WHERE logical_fact_id = ?",
			arrayOf(fixture.fact.logicalFactId),
		)

		resolveRetentionByEvidence(
			retainedIndependentPressureFact(fixture, 2L),
		) shouldBe PressureFactRetentionResolution.Invalid
	}

	@Test
	fun `malformed run scope cannot resolve absent retention evidence`() = runTest {
		val fixture = insertRetainedPressureRun("malformed-scope", 1L)

		resolveRetentionByEvidence(
			fact = retainedIndependentPressureFact(fixture, 2L),
			logicalTrackingId = "",
		) shouldBe PressureFactRetentionResolution.Invalid
	}

	@Test
	fun `window crossing retention floor removes the complete exact lineage`() = runTest {
		val fixture = insertRetainedPressureRun("floor-crossing", 1L)
		database.pressureFactRevisionDao().deleteExactLineages(
			logicalTrackingId = fixture.fact.logicalTrackingId,
			serviceRunId = fixture.fact.serviceRunId,
			writerProjectionId = fixture.fact.writerProjectionId,
			writerProjectionVersion = fixture.fact.writerProjectionVersion,
			logicalFactIds = listOf(fixture.fact.logicalFactId),
		) shouldBe 1
		val wallOffsetMs = 1_400L - fixture.fact.intervalStartTimeMs
		val unsigned = fixture.fact.copy(
			intervalStartTimeMs = 1_400L,
			intervalEndTimeMs = 1_400L + WINDOW_MS,
			windowStartElapsedRealtimeNanos =
				fixture.fact.windowStartElapsedRealtimeNanos + wallOffsetMs * 1_000_000L,
			windowEndElapsedRealtimeNanos =
				fixture.fact.windowEndElapsedRealtimeNanos + wallOffsetMs * 1_000_000L,
			appliedAtMs = 1_400L + WINDOW_MS,
			effectChecksum = "pending",
		)
		val crossing = unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, fixture.binding),
		)
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM source_event_wal WHERE event_id = ?",
			arrayOf(fixture.fact.sourceEventId),
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(crossing))
		database.pressureFactRevisionDao().insert(crossing)

		database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
			beforeMs = RETENTION_FLOOR_MS,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			markedAtMs = MARKED_AT_MS,
		) shouldBe 1

		val authority = requireNotNull(database.pressureFactRetentionAuthorityDao().get(
			crossing.writerProjectionId,
			crossing.writerProjectionVersion,
			crossing.logicalFactId,
		))
		authority.lineageIntervalStartMs shouldBe 1_400L
		authority.lineageIntervalEndMs shouldBe 1_550L
		authority.lineageEarliestPossibleStartMs shouldBe 1_399L
	}

	@Test
	fun `older correction revision crossing floor binds current retained identity`() = runTest {
		val fixture = insertRetainedPressureRun("old-revision", 1L)
		database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(fixture.fact))
		val intermediate = retainedPressureCorrection(
			fixture = fixture,
			admissionOrdinal = 2L,
			eventId = "pressure-old-revision-intermediate",
		)
		val current = retainedPressureCorrection(
			fixture = fixture,
			admissionOrdinal = 3L,
			eventId = "pressure-old-revision-current",
			semanticRevision = 3L,
		)
		database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(intermediate))
		database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(current))
		database.pressureFactRevisionDao().insert(intermediate)
		database.pressureFactRevisionDao().insert(current)

		database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
			beforeMs = RETENTION_FLOOR_MS,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			markedAtMs = MARKED_AT_MS,
		) shouldBe 3

		val authority = requireNotNull(database.pressureFactRetentionAuthorityDao().get(
			current.writerProjectionId,
			current.writerProjectionVersion,
			current.logicalFactId,
		))
		authority.triggerSemanticRevision shouldBe 1L
		authority.triggerSourceEventId shouldBe fixture.fact.sourceEventId
		authority.currentSemanticRevision shouldBe 3L
		authority.currentSourceEventId shouldBe current.sourceEventId
		val revisionAuthorities =
			database.pressureFactRetentionRevisionAuthorityDao().forLineage(
				current.writerProjectionId,
				current.writerProjectionVersion,
				current.logicalFactId,
				4,
			)
		authority.authenticatesRevisionAuthorities(revisionAuthorities) shouldBe true
		revisionAuthorities.map { it.sourceEventId } shouldBe listOf(
			fixture.fact.sourceEventId,
			intermediate.sourceEventId,
			current.sourceEventId,
		)
		database.persistPressureFactRetentionAuthority(
			listOf(fixture.fact, intermediate, current),
			RETENTION_FLOOR_MS,
			MARKED_AT_MS,
		) shouldBe authority
		shouldThrow<IllegalStateException> {
			database.persistPressureFactRetentionAuthority(
				listOf(
					fixture.fact,
					intermediate.copy(sourcePolicyRevision = intermediate.sourcePolicyRevision + 1L),
					current,
				),
				RETENTION_FLOOR_MS,
				MARKED_AT_MS,
			)
		}
		database.resolvePressureRetentionByFact(
			writerProjectionId = current.writerProjectionId,
			writerProjectionVersion = current.writerProjectionVersion,
			logicalFactId = current.logicalFactId,
			semanticRevision = intermediate.semanticRevision,
			sourceEventId = intermediate.sourceEventId,
			sourceAdmissionOrdinal = intermediate.sourceAdmissionOrdinal,
			sourcePolicyRevision = intermediate.sourcePolicyRevision,
			captureConsentEpoch = intermediate.captureConsentEpoch,
			collectedDataEpoch = intermediate.collectedDataEpoch,
			logicalTrackingId = intermediate.logicalTrackingId,
			serviceRunId = intermediate.serviceRunId,
			retainedFromMs = RETENTION_FLOOR_MS,
		) shouldBe PressureFactRetentionResolution.Authenticated(
			authority,
			revisionAuthorities[1],
		)
		shouldThrow<SQLiteException> {
			database.pressureFactRevisionDao().insert(
				independentPressureFact(fixture, 10L).copy(
					sourceEventId = intermediate.sourceEventId,
				),
			)
		}
		shouldThrow<SQLiteException> {
			database.pressureFactRevisionDao().insert(
				independentPressureFact(fixture, intermediate.sourceAdmissionOrdinal).copy(
					sourceEventId = "pressure-resurrection-by-admission",
				),
			)
		}
		listOf(fixture.fact, intermediate, current).forEachIndexed { index, revision ->
			database.resolvePressureRetentionByFact(
				writerProjectionId = revision.writerProjectionId,
				writerProjectionVersion = revision.writerProjectionVersion,
				logicalFactId = revision.logicalFactId,
				semanticRevision = revision.semanticRevision,
				sourceEventId = revision.sourceEventId,
				sourceAdmissionOrdinal = revision.sourceAdmissionOrdinal,
				sourcePolicyRevision = revision.sourcePolicyRevision,
				captureConsentEpoch = revision.captureConsentEpoch,
				collectedDataEpoch = revision.collectedDataEpoch,
				logicalTrackingId = revision.logicalTrackingId,
				serviceRunId = revision.serviceRunId,
				retainedFromMs = RETENTION_FLOOR_MS,
			) shouldBe PressureFactRetentionResolution.Authenticated(
				authority,
				revisionAuthorities[index],
			)
		}
	}

	@Test
	fun `retention authority corruption and resurrection are rejected and full clear removes authority`() =
		runTest {
			val fixture = insertRetainedPressureRun("terminal-authority", 1L)
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
			) shouldBe 1

			val authority = requireNotNull(database.pressureFactRetentionAuthorityDao().get(
				fixture.fact.writerProjectionId,
				fixture.fact.writerProjectionVersion,
				fixture.fact.logicalFactId,
			))
			database.pressureFactRetentionAuthorityDao().insertIfAbsent(authority) shouldBe -1L
			database.pressureFactRetentionAuthorityDao().get(
				fixture.fact.writerProjectionId,
				fixture.fact.writerProjectionVersion,
				fixture.fact.logicalFactId,
			) shouldBe authority
			shouldThrow<SQLiteException> {
				database.pressureFactRevisionDao().insert(fixture.fact)
			}
			val retainedRevision = database.pressureFactRetentionRevisionAuthorityDao()
				.forLineage(
					fixture.fact.writerProjectionId,
					fixture.fact.writerProjectionVersion,
					fixture.fact.logicalFactId,
					2,
				).single()
			database.pressureFactRetentionRevisionAuthorityDao().insertIfAbsent(
				listOf(retainedRevision),
			) shouldBe listOf(-1L)
			database.openHelper.writableDatabase.execSQL(
				"UPDATE pressure_fact_retention_authority SET effect_checksum = ? " +
					"WHERE logical_fact_id = ?",
				arrayOf("corrupt", fixture.fact.logicalFactId),
			)
			database.pressureFactRetentionAuthorityDao().get(
				fixture.fact.writerProjectionId,
				fixture.fact.writerProjectionVersion,
				fixture.fact.logicalFactId,
			)?.hasValidStoredIntegrity() shouldBe false

			database.clearAllTables()

			database.pressureFactRetentionAuthorityDao().count() shouldBe 0L
			database.pressureFactRetentionRevisionAuthorityDao().count() shouldBe 0L
		}

	@Test
	fun `Pressure source erase removes retained lineage authority`() = runTest {
		insertRetainedPressureRun("source-erase-authority", 1L)
		database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
			beforeMs = RETENTION_FLOOR_MS,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			markedAtMs = MARKED_AT_MS,
		) shouldBe 1
		database.sourceEventWalDao().deleteAll()

		database.withTransaction {
			database.deletePressureSourceEraseLocalPayloadInTransaction(
				PressureSourceEraseLocalAudit(
					factRevisionCount = 0,
					retentionAuthorityCount = 1,
					retentionRevisionAuthorityCount = 1,
					walEventCount = 0,
					legacySampleCount = 0,
					legacySampleWitnesses = emptyList(),
					scopes = emptyList(),
					latestDurableTimeMs = MARKED_AT_MS,
					writerFence = PressureSourceEraseWriterFence(
						collectedDataEpoch = COLLECTED_DATA_EPOCH,
						owner = SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE,
						ownerGeneration =
							SourceDestinationOwnerEntity.FIRST_LEGACY_PRESSURE_FENCE_GENERATION,
					),
				),
			)
		}

		database.pressureFactRetentionAuthorityDao().count() shouldBe 0L
		database.pressureFactRetentionRevisionAuthorityDao().count() shouldBe 0L
	}

	@Test
	fun `retention carries one authenticated correction lineage across pages`() = runTest {
		val fixture = insertRetainedPressureRun("corrected-page", 1L)
		database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(fixture.fact))
		for (revision in 2L..CORRECTED_LINEAGE_REVISION_COUNT) {
			val correction = pressureCorrection(
				fixture = fixture,
				admissionOrdinal = revision,
				eventId = "pressure-corrected-page-$revision",
				semanticRevision = revision,
			)
			database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(correction))
			database.pressureFactRevisionDao().insert(correction)
		}
		val observations = mutableListOf<Triple<Int, Int, Int>>()

		database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
			beforeMs = RETENTION_FLOOR_MS,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			markedAtMs = MARKED_AT_MS,
			limits = limits(),
			checkpoint = {},
			afterFactPageScanned = { pageCount, factCount, residentLineageRevisionCount ->
				observations += Triple(pageCount, factCount, residentLineageRevisionCount)
			},
		) shouldBe CORRECTED_LINEAGE_REVISION_COUNT.toInt()

		observations shouldBe listOf(
			Triple(1, RETENTION_PAGE_SIZE, RETENTION_PAGE_SIZE),
			Triple(
				2,
				CORRECTED_LINEAGE_REVISION_COUNT.toInt(),
				CORRECTED_LINEAGE_REVISION_COUNT.toInt(),
			),
		)
		database.pressureFactRevisionDao().count() shouldBe 0L
	}

	@Test
	fun `million-lineage budget streams bounded pages with one resident lineage and bounded deletes`() =
		runTest {
		val fixture = insertRetainedPressureRun("large-pages", 1L)
		for (ordinal in 2L..LARGE_RETENTION_LINEAGE_COUNT) {
			val fact = independentPressureFact(fixture, ordinal)
			database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(fact))
			database.pressureFactRevisionDao().insert(fact)
		}
		val checkpoints = mutableListOf<PressureRetentionCheckpoint>()
		val observations = mutableListOf<Triple<Int, Int, Int>>()

		database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
			beforeMs = RETENTION_FLOOR_MS,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			markedAtMs = MARKED_AT_MS,
			limits = limits(maximumFactLineages = 1_000_000L),
			checkpoint = { checkpoint -> checkpoints += checkpoint },
			afterFactPageScanned = { pageCount, factCount, residentLineageRevisionCount ->
				observations += Triple(pageCount, factCount, residentLineageRevisionCount)
			},
		) shouldBe LARGE_RETENTION_LINEAGE_COUNT.toInt()

		observations.map { it.first } shouldBe listOf(1, 2, 3)
		observations.map { it.second } shouldBe
			listOf(RETENTION_PAGE_SIZE, RETENTION_PAGE_SIZE * 2, 513)
		observations.maxOf { it.third } shouldBe 1
		checkpoints.count { it == PressureRetentionCheckpoint.DELETE_BATCH_APPLIED } shouldBe
			5
		checkpoints.indexOf(PressureRetentionCheckpoint.TRANSACTION_STARTED) shouldBe 0
		(checkpoints.indexOf(PressureRetentionCheckpoint.RUN_PAGE_LOADED) <
			checkpoints.indexOf(PressureRetentionCheckpoint.FACT_PAGE_LOADED)) shouldBe true
		(checkpoints.indexOf(PressureRetentionCheckpoint.LINEAGE_AUTHENTICATED) <
			checkpoints.indexOf(PressureRetentionCheckpoint.RETENTION_AUTHORITY_INSERTED)) shouldBe true
		(checkpoints.indexOf(PressureRetentionCheckpoint.RETENTION_AUTHORITY_INSERTED) <
			checkpoints.indexOf(PressureRetentionCheckpoint.RUN_MARKED)) shouldBe true
		(checkpoints.indexOf(PressureRetentionCheckpoint.RUN_MARKED) <
			checkpoints.indexOf(PressureRetentionCheckpoint.DELETE_BATCH_APPLIED)) shouldBe true
		(checkpoints.lastIndexOf(PressureRetentionCheckpoint.DELETE_BATCH_APPLIED) <
			checkpoints.indexOf(PressureRetentionCheckpoint.RUN_AUTHENTICATED)) shouldBe true
		database.pressureFactRevisionDao().count() shouldBe 0L
		database.pressureFactRetentionAuthorityDao().count() shouldBe
			LARGE_RETENTION_LINEAGE_COUNT
	}

	@Test
	fun `production retention budget admits a million single revision lineages`() {
		(DEFAULT_PRESSURE_RETENTION_TRAVERSAL_LIMITS.maximumFactLineages >= 1_000_000L) shouldBe true
		(DEFAULT_PRESSURE_RETENTION_TRAVERSAL_LIMITS.maximumFactPagesPerRun.toLong() *
			RETENTION_PAGE_SIZE >= 1_000_000L) shouldBe true
		MAX_PRESSURE_RETENTION_REVISION_AUTHORITIES shouldBe 8_192
	}

	@Test
	fun `cancellation from a later retention page rolls back the streamed transaction`() = runTest {
		val fixture = insertRetainedPressureRun("cancel-page", 1L)
		for (ordinal in 2L..CANCELLATION_LINEAGE_COUNT) {
			val fact = independentPressureFact(fixture, ordinal)
			database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(fact))
			database.pressureFactRevisionDao().insert(fact)
		}

		shouldThrow<CancellationException> {
			database.pruneAuthenticatedPressureFactsAffectedByRetentionFloor(
				beforeMs = RETENTION_FLOOR_MS,
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				markedAtMs = MARKED_AT_MS,
				limits = limits(),
				checkpoint = {},
				afterFactPageScanned = { pageCount, _, _ ->
					if (pageCount == 2) {
						throw CancellationException("cancel streamed Pressure retention")
					}
				},
			)
		}

		database.pressureFactRevisionDao().count() shouldBe CANCELLATION_LINEAGE_COUNT
		database.pressureFactRetentionAuthorityDao().count() shouldBe 0L
		database.pressureFactRetentionRevisionAuthorityDao().count() shouldBe 0L
		database.sourceDeletionFenceDao().countAll() shouldBe 0L
	}

	private suspend fun insertRetainedPressureRun(
		key: String,
		admissionOrdinal: Long,
		insertWal: Boolean = true,
	): PressureRetentionFixture {
		val logicalId = "logical-pressure-$key"
		val runId = "run-pressure-$key"
		val segmentId = database.sessionSegmentDao().insert(
			SessionSegment(
				startTimeMs = RUN_START_MS,
				endTimeMs = RUN_END_MS,
				distanceM = 0f,
				steps = null,
				primaryActivity = null,
				activityConfidence = null,
				sampleCount = 0,
				source = SegmentSource.USER_CREATED,
				inferenceVersion = null,
				createdAt = RUN_END_MS,
				logicalTrackingId = logicalId,
				serviceRunId = runId,
			),
		)
		database.sourceSessionDao().insertSession(logicalSession(logicalId))
		database.sourceSessionDao().insertServiceRun(serviceRun(logicalId, runId, segmentId))
		val binding = pressureManifestSource(logicalId)
		val unsignedManifest = pressureManifest(logicalId, runId)
		val manifest = unsignedManifest.copy(
			manifestChecksum = SessionManifestIntegrity.compute(unsignedManifest, listOf(binding)),
		)
		database.sourceSessionDao().insertManifest(manifest)
		database.sourceSessionDao().insertManifestSources(listOf(binding))
		val unsignedFact = pressureFact(logicalId, runId, key, admissionOrdinal)
		val fact = unsignedFact.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsignedFact, binding),
		)
		database.pressureFactRevisionDao().insert(fact).let { inserted ->
			check(inserted > 0L)
		}
		if (insertWal) {
			database.sourceEventWalDao().insertIgnoringDuplicate(pressureWal(fact))
		}
		return PressureRetentionFixture(binding, fact)
	}

	private fun logicalSession(logicalId: String) = LogicalTrackingSessionEntity(
		logicalTrackingId = logicalId,
		state = "FINALIZED",
		lifecycleRevision = 2L,
		desiredPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = "MANUAL_FOREGROUND_START",
		clockDomainId = BOOT_ID,
		startedAtMs = RUN_START_MS,
		startedElapsedNanos = 0L,
		cutoffAtMs = RUN_END_MS,
		cutoffElapsedNanos = RUN_END_MS,
		completedAtMs = RUN_END_MS,
		finalAdmissionOrdinal = 2L,
		failureCode = null,
		sessionMode = "MANUAL",
		currentManifestRevision = MANIFEST_REVISION,
		currentIntentRevision = 1L,
		currentServiceRunId = null,
		lifecycleLeaseGeneration = 1L,
		lifecycleBootId = BOOT_ID,
		automationEpoch = null,
	)

	private fun serviceRun(logicalId: String, runId: String, segmentId: Long) =
		SourceServiceRunEntity(
			serviceRunId = runId,
			logicalTrackingId = logicalId,
			state = "FINALIZED",
			desiredPlanRevision = 1L,
			rolloutRevision = 2L,
			foregroundCapabilityFlags = 0L,
			startedAtMs = RUN_START_MS,
			startedElapsedNanos = 0L,
			completedAtMs = RUN_END_MS,
			completionReason = "USER_STOP",
			bootId = BOOT_ID,
			leaseGeneration = 1L,
			startOrigin = "MANUAL_FOREGROUND_START",
			desiredForegroundCapabilityFlags = 0L,
			appliedForegroundCapabilityFlags = 0L,
			runtimeAcknowledgement = "STOP_ACCEPTED",
			runtimeFailureCode = null,
			runRevision = 2L,
			startDeliveryToken = "delivery-$runId",
			startCommandGeneration = 1L,
			preparedManifestRevision = MANIFEST_REVISION,
			preparedIntentRevision = 1L,
			androidDeliveryState = "FOREGROUND_ACCEPTED",
			androidDeliveryUpdatedAtMs = RUN_START_MS,
			startIsUserInitiated = true,
			startIsAmbient = false,
			sessionSegmentId = segmentId,
			presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
			presentationAcknowledgedAtMs = RUN_END_MS,
		)

	private fun pressureManifest(logicalId: String, runId: String) = SessionManifestVersionEntity(
		logicalTrackingId = logicalId,
		manifestRevision = MANIFEST_REVISION,
		serviceRunId = runId,
		sessionMode = "MANUAL",
		sourcePolicyRevision = POLICY_REVISION,
		acquisitionPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = "MANUAL_FOREGROUND_START",
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = RUN_START_MS,
		zoneId = "UTC",
		automationEpoch = null,
		changeReason = "TEST",
		manifestChecksum = "",
	)

	private fun pressureManifestSource(logicalId: String) = SessionManifestSourceEntity(
		logicalTrackingId = logicalId,
		manifestRevision = MANIFEST_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpoch = CONSENT_EPOCH,
		persistenceEligible = true,
		qosCode = CAPTURE_QOS,
		outputDestination = SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE,
		writerOwner = SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS,
		writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
		writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
		writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
	)

	@Suppress("LongMethod")
	private fun pressureFact(
		logicalId: String,
		runId: String,
		key: String,
		admissionOrdinal: Long,
	): PressureFactRevisionEntity {
		val eventId = "pressure-$key"
		val logicalFactId = "${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:$eventId"
		return PressureFactRevisionEntity(
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			mutationId = "$logicalFactId:1",
			sourceEventId = eventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			writerProjectionId = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION,
			payloadVersion = PressureFactRevisionEntity.QUALIFIED_PRESSURE_PAYLOAD_VERSION,
			intervalStartTimeMs = RUN_START_MS,
			intervalEndTimeMs = RUN_START_MS + WINDOW_MS,
			windowStartElapsedRealtimeNanos = admissionOrdinal * SECOND_NANOS,
			windowEndElapsedRealtimeNanos = admissionOrdinal * SECOND_NANOS + WINDOW_NANOS,
			clockDomainId = BOOT_ID,
			wallTimeUncertaintyMs = 1L,
			sampleCount = 4,
			meanHectopascals = 1_001.5,
			sumSquaredDeviations = 5.0,
			minimumHectopascals = 1_000f,
			maximumHectopascals = 1_003f,
			firstProviderSequence = admissionOrdinal * 10L + 1L,
			lastProviderSequence = admissionOrdinal * 10L + 4L,
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
			logicalTrackingId = logicalId,
			serviceRunId = runId,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			manifestRevision = MANIFEST_REVISION,
			sourcePolicyRevision = POLICY_REVISION,
			captureConsentEpoch = CONSENT_EPOCH,
			collectedDataEpoch = COLLECTED_DATA_EPOCH,
			effectChecksum = "pending",
			appliedAtMs = RUN_START_MS + WINDOW_MS,
		)
	}

	private fun pressureCorrection(
		fixture: PressureRetentionFixture,
		admissionOrdinal: Long,
		eventId: String,
		semanticRevision: Long = 2L,
	): PressureFactRevisionEntity {
		val offsetMs = semanticRevision - 1L
		val offsetNanos = offsetMs * 1_000_000L
		val unsigned = fixture.fact.copy(
			semanticRevision = semanticRevision,
			mutationId = "${fixture.fact.logicalFactId}:$semanticRevision",
			sourceEventId = eventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			intervalStartTimeMs = fixture.fact.intervalStartTimeMs + offsetMs,
			intervalEndTimeMs = fixture.fact.intervalEndTimeMs + offsetMs,
			windowStartElapsedRealtimeNanos =
				fixture.fact.windowStartElapsedRealtimeNanos + offsetNanos,
			windowEndElapsedRealtimeNanos =
				fixture.fact.windowEndElapsedRealtimeNanos + offsetNanos,
			firstProviderSequence = fixture.fact.lastProviderSequence +
				(semanticRevision - 2L) * fixture.fact.sampleCount + 1L,
			lastProviderSequence = fixture.fact.lastProviderSequence +
				(semanticRevision - 1L) * fixture.fact.sampleCount,
			appliedAtMs = fixture.fact.appliedAtMs + offsetMs,
			effectChecksum = "pending",
		)
		return unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, fixture.binding),
		)
	}

	private fun independentPressureFact(
		fixture: PressureRetentionFixture,
		admissionOrdinal: Long,
	): PressureFactRevisionEntity {
		val eventId = "pressure-large-page-$admissionOrdinal"
		val logicalFactId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:$eventId"
		val unsigned = fixture.fact.copy(
			logicalFactId = logicalFactId,
			mutationId = "$logicalFactId:1",
			sourceEventId = eventId,
			sourceAdmissionOrdinal = admissionOrdinal,
			windowStartElapsedRealtimeNanos = admissionOrdinal * SECOND_NANOS,
			windowEndElapsedRealtimeNanos = admissionOrdinal * SECOND_NANOS + WINDOW_NANOS,
			firstProviderSequence = admissionOrdinal * 10L + 1L,
			lastProviderSequence = admissionOrdinal * 10L + 4L,
			effectChecksum = "pending",
		)
		return unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, fixture.binding),
		)
	}

	private fun retainedIndependentPressureFact(
		fixture: PressureRetentionFixture,
		admissionOrdinal: Long,
	): PressureFactRevisionEntity {
		val fact = independentPressureFact(fixture, admissionOrdinal)
		val unsigned = fact.copy(
			intervalStartTimeMs = RETENTION_FLOOR_MS + 100L,
			intervalEndTimeMs = RETENTION_FLOOR_MS + 100L + WINDOW_MS,
			appliedAtMs = RETENTION_FLOOR_MS + 100L + WINDOW_MS,
			effectChecksum = "pending",
		)
		return unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, fixture.binding),
		)
	}

	private fun retainedPressureCorrection(
		fixture: PressureRetentionFixture,
		admissionOrdinal: Long,
		eventId: String,
		semanticRevision: Long = 2L,
	): PressureFactRevisionEntity {
		val correction = pressureCorrection(
			fixture,
			admissionOrdinal,
			eventId,
			semanticRevision,
		)
		val wallOffsetMs = RETENTION_FLOOR_MS + 100L - correction.intervalStartTimeMs
		val elapsedOffsetNanos = wallOffsetMs * 1_000_000L
		val unsigned = correction.copy(
			intervalStartTimeMs = RETENTION_FLOOR_MS + 100L,
			intervalEndTimeMs = RETENTION_FLOOR_MS + 100L + WINDOW_MS,
			windowStartElapsedRealtimeNanos =
				correction.windowStartElapsedRealtimeNanos + elapsedOffsetNanos,
			windowEndElapsedRealtimeNanos =
				correction.windowEndElapsedRealtimeNanos + elapsedOffsetNanos,
			appliedAtMs = RETENTION_FLOOR_MS + 100L + WINDOW_MS,
			effectChecksum = "pending",
		)
		return unsigned.copy(
			effectChecksum = PressureFactRevisionIntegrity.effectChecksum(unsigned, fixture.binding),
		)
	}

	private suspend fun resolveRetentionByEvidence(
		fact: PressureFactRevisionEntity,
		sourceEventId: String = fact.sourceEventId,
		sourceAdmissionOrdinal: Long? = fact.sourceAdmissionOrdinal,
		logicalTrackingId: String = fact.logicalTrackingId,
		serviceRunId: String = fact.serviceRunId,
	): PressureFactRetentionResolution = database.resolvePressureRetentionByEvidence(
		writerProjectionId = fact.writerProjectionId,
		writerProjectionVersion = fact.writerProjectionVersion,
		sourceEventId = sourceEventId,
		sourceAdmissionOrdinal = sourceAdmissionOrdinal,
		sourcePolicyRevision = fact.sourcePolicyRevision,
		captureConsentEpoch = fact.captureConsentEpoch,
		collectedDataEpoch = fact.collectedDataEpoch,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		retainedFromMs = RETENTION_FLOOR_MS,
	)

	private suspend fun resolveRetentionByFact(
		fact: PressureFactRevisionEntity,
	): PressureFactRetentionResolution = database.resolvePressureRetentionByFact(
		writerProjectionId = fact.writerProjectionId,
		writerProjectionVersion = fact.writerProjectionVersion,
		logicalFactId = fact.logicalFactId,
		semanticRevision = fact.semanticRevision,
		sourceEventId = fact.sourceEventId,
		sourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
		sourcePolicyRevision = fact.sourcePolicyRevision,
		captureConsentEpoch = fact.captureConsentEpoch,
		collectedDataEpoch = fact.collectedDataEpoch,
		logicalTrackingId = fact.logicalTrackingId,
		serviceRunId = fact.serviceRunId,
		retainedFromMs = RETENTION_FLOOR_MS,
	)

	private fun pressureWal(fact: PressureFactRevisionEntity): SourceEventWalEntity {
		val payload = PressureWalPayload(
			sampleCount = fact.sampleCount,
			meanHectopascals = fact.meanHectopascals,
			sumSquaredDeviations = fact.sumSquaredDeviations,
			minimumHectopascals = fact.minimumHectopascals,
			maximumHectopascals = fact.maximumHectopascals,
			windowStartElapsedRealtimeNanos = fact.windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = fact.windowEndElapsedRealtimeNanos,
			firstProviderSequence = fact.firstProviderSequence,
			lastProviderSequence = fact.lastProviderSequence,
			firstHectopascals = fact.firstHectopascals,
			lastHectopascals = fact.lastHectopascals,
			slopeHectopascalsPerSecond = fact.slopeHectopascalsPerSecond,
			rSquared = fact.rSquared,
			sensorAccuracy = PressureWalSensorAccuracy.valueOf(fact.sensorAccuracy),
			effectiveSamplePeriodMicros = fact.effectiveSamplePeriodMicros,
			effectiveMaximumReportLatencyMicros = fact.effectiveMaximumReportLatencyMicros,
			targetWindowDurationNanos = fact.targetWindowDurationNanos,
			expectedSampleCount = fact.expectedSampleCount,
			maximumInterSampleGapNanos = fact.maximumInterSampleGapNanos,
			closureKind = PressureWalClosureKind.valueOf(fact.closureKind),
		)
		val bytes = PressureWalPayloadCodec.encode(payload)
		val unsigned = SourceEventWalEntity(
			admissionOrdinal = fact.sourceAdmissionOrdinal,
			eventId = fact.sourceEventId,
			providerDedupKey = null,
			deliveryIdentity = pressureDeliveryIdentity(fact.clockDomainId, payload),
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = fact.logicalTrackingId,
			serviceRunId = fact.serviceRunId,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			sourceInstanceId = "pressure-provider",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "pressure-config",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "a".repeat(64),
			sourceSequence = fact.lastProviderSequence,
			configRevision = 1L,
			planAttribution = 0,
			clockDomainId = fact.clockDomainId,
			observedElapsedNanos = fact.windowEndElapsedRealtimeNanos,
			observedIntervalStartNanos = fact.windowStartElapsedRealtimeNanos,
			receivedElapsedNanos = fact.windowEndElapsedRealtimeNanos + 1L,
			receivedWallTimeMs = fact.intervalEndTimeMs + 1L,
			wallTimeMs = fact.intervalEndTimeMs,
			wallTimeUncertaintyMs = fact.wallTimeUncertaintyMs,
			capturedCollectedDataEpoch = fact.collectedDataEpoch,
			sourcePolicyRevision = fact.sourcePolicyRevision,
			captureConsentEpoch = fact.captureConsentEpoch,
			sessionManifestRevision = fact.manifestRevision,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = fact.intervalEndTimeMs,
			qualityFlags = fact.sourceQualityFlags,
			qualityConfidence = fact.sourceQualityConfidence,
			payloadVersion = fact.payloadVersion,
			payload = bytes,
			payloadChecksum = "",
			createdAtMs = fact.intervalEndTimeMs + 1L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
	}

	private fun pressureDeliveryIdentity(
		clockDomainId: String,
		payload: PressureWalPayload,
	): String {
		val canonical = buildString {
			append("pressure-window-v1|")
			append(clockDomainId.length)
			append(':')
			append(clockDomainId)
			append("|start:")
			append(payload.windowStartElapsedRealtimeNanos)
			append("|end:")
			append(payload.windowEndElapsedRealtimeNanos)
			append("|count:")
			append(payload.sampleCount)
			append("|mean-bits:")
			append(payload.meanHectopascals.toRawBits())
			append("|m2-bits:")
			append(payload.sumSquaredDeviations.toRawBits())
			append("|min-bits:")
			append(payload.minimumHectopascals.toRawBits())
			append("|max-bits:")
			append(payload.maximumHectopascals.toRawBits())
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	private fun pressurePolicy() = SourcePolicyEntity(
		policyRevision = POLICY_REVISION,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		enabled = true,
		qosCode = CAPTURE_QOS,
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
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "TEST",
	)

	private fun pressureConsent() = SourceConsentEpochEntity(
		sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
		purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
		epoch = CONSENT_EPOCH,
		eligible = true,
		persistenceEligible = true,
		policyRevision = POLICY_REVISION,
		effectiveBootId = BOOT_ID,
		effectiveElapsedRealtimeNanos = 0L,
		effectiveWallTimeMs = 0L,
		changeReason = "TEST",
	)

	private fun limits(
		maximumFactPages: Long = 16L,
		maximumLineageRevisions: Int = 8_192,
		maximumFactLineages: Long = 2_048L,
	) = PressureRetentionTraversalLimits(
		maximumRuns = 8L,
		maximumTraversalRows = 4_096L,
		maximumFactPages = maximumFactPages,
		maximumFactLineages = maximumFactLineages,
		maximumFactPagesPerRun = maximumFactPages.coerceAtMost(16L).toInt(),
		maximumLineageRevisions = maximumLineageRevisions,
	)

	private data class PressureRetentionFixture(
		val binding: SessionManifestSourceEntity,
		val fact: PressureFactRevisionEntity,
	)

	private companion object {
		const val COLLECTED_DATA_EPOCH = 1L
		const val POLICY_REVISION = 1L
		const val CONSENT_EPOCH = 1L
		const val MANIFEST_REVISION = 1L
		const val CAPTURE_QOS = 2
		const val BOOT_ID = "boot-1"
		const val RUN_START_MS = 1_000L
		const val RUN_END_MS = 5_000L
		const val RETENTION_FLOOR_MS = 1_500L
		const val MARKED_AT_MS = 6_000L
		const val WINDOW_MS = 150L
		const val WINDOW_NANOS = 150_000_000L
		const val SECOND_NANOS = 1_000_000_000L
		const val RETENTION_PAGE_SIZE = 256
		const val CORRECTED_LINEAGE_REVISION_COUNT = 257L
		const val LARGE_RETENTION_LINEAGE_COUNT = 513L
		const val CANCELLATION_LINEAGE_COUNT = 300L
	}
}

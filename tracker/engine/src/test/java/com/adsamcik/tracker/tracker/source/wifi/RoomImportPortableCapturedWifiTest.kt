package com.adsamcik.tracker.tracker.source.wifi

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedWifiDao
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistoryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteSelectedWifiHistoryResult
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRetryableReason
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import com.adsamcik.tracker.stats.api.repository.ReexportImportedCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ReexportImportedCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiHistorySelection
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.stats.api.repository.isReciprocalCorrectionOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RoomImportPortableCapturedWifiTest {
	private lateinit var database: AppDatabase
	private val ownershipQueries = CopyOnWriteArrayList<String>()

	@Before
	fun setUp() = runTest {
		database = Room.inMemoryDatabaseBuilder(
			ApplicationProvider.getApplicationContext<Application>(),
			AppDatabase::class.java,
		).allowMainThreadQueries().setQueryCallback({ sql, _ ->
			if ("SELECT DISTINCT 'ENTRY' AS owner_kind" in sql ||
				"FROM logical_tracking_session" in sql ||
				"FROM source_service_run" in sql && "COUNT(*)" in sql ||
				"FROM wifi_captured_fact_cursor" in sql && "COUNT(*)" in sql ||
				"FROM wifi_capture_deletion_generation" in sql && "COUNT(*)" in sql
			) ownershipQueries += sql
		}, Executor(Runnable::run)).build()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `admission persists exact product hierarchy without live capture or location authority`() = runTest {
		val request = request()

		importer(testScheduler).importEntry(request) shouldBe
			ImportPortableCapturedWifiResult.Applied(1L, 1, 1)

		val dao = database.importedWifiDao()
		dao.entryRevisionsForAdmission(request.entry.identity.value).single().contentChecksum shouldBe
			request.entry.contentChecksum.value
		dao.allRunsForAdmission(request.entry.identity.value).single().deletionScopeDigest shouldBe
			request.entry.runs.single().deletionScopeDigest.value
		dao.allRunZonesForAdmission(request.entry.identity.value).single().zoneId shouldBe "Europe/Prague"
		dao.allObservationsForAdmission(request.entry.identity.value).single().contentChecksum shouldBe
			request.entry.runs.single().observations.single().contentChecksum.value
		database.wifiCapturedFactDao().revisionCount() shouldBe 0L
		database.sourceEventWalDao().countAll() shouldBe 0L
		listOf(
			"source_demand",
			"provider_registration_generation",
			"source_authorization",
			"logical_tracking_session",
			"source_service_run",
			"source_event_wal",
			"wifi_observation",
		).forEach { rowCount(it) shouldBe 0L }
	}

	@Test
	fun `exact replay and alternate receipt are idempotent while receipt retargeting is blocked`() = runTest {
		val first = request()
		val importer = importer(testScheduler)
		importer.importEntry(first)

		importer.importEntry(first) shouldBe ImportPortableCapturedWifiResult.Duplicate(1L)
		val alternate = first.copy(receipt = receipt("job-2", 31L))
		importer.importEntry(alternate) shouldBe ImportPortableCapturedWifiResult.Duplicate(1L)
		database.importedWifiDao().receiptCount() shouldBe 2L

		importer.importEntry(
			alternate.copy(receipt = alternate.receipt.copy(sourceName = "other.trackerwifi")),
		) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.RECEIPT_CONFLICT,
		)
	}

	@Test
	fun `immediate reciprocal correction appends while skipped or reshaped correction is blocked`() = runTest {
		val first = request()
		val importer = importer(testScheduler)
		importer.importEntry(first)
		val corrected = request(
			entry = entry(observation(semanticRevision = 2L)),
			receipt = receipt("job-2", 40L),
		)

		importer.importEntry(corrected) shouldBe ImportPortableCapturedWifiResult.Applied(2L, 1, 1)
		database.importedWifiDao().entryRevisionsForAdmission(first.entry.identity.value)
			.map { it.importRevision } shouldBe listOf(1L, 2L)

		val skipped = request(
			entry = entry(observation(semanticRevision = 4L)),
			receipt = receipt("job-3", 50L),
		)
		importer.importEntry(skipped) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.CORRECTION_CONFLICT,
		)
		val rewrittenPayload = request(
			entry = entry(observation(semanticRevision = 3L, strongestSignalDbm = -39)),
			receipt = receipt("rewritten-payload", 55L),
		)
		importer.importEntry(rewrittenPayload) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.CORRECTION_CONFLICT,
		)
		val reshaped = request(
			entry = entry(
				observation(localId = "reshaped-observation", strongestSignalDbm = -38),
				runLocalId = "different-run",
				deletionScope = PortableWifiDeletionScopeDigest("c".repeat(64)),
			),
			receipt = receipt("job-4", 60L),
		)
		importer.importEntry(reshaped) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.CORRECTION_CONFLICT,
		)
	}

	@Test
	fun `identity kinds and deletion scopes are globally disjoint across imported entries`() = runTest {
		val first = request()
		val importer = importer(testScheduler)
		importer.importEntry(first)
		val priorRunIdentity = first.entry.runs.single().identity
		val collision = entry(
			observation = observation(localId = "other-observation"),
			entryIdentity = priorRunIdentity,
			runLocalId = "other-run",
			deletionScope = PortableWifiDeletionScopeDigest("e".repeat(64)),
		)

		importer.importEntry(request(collision, receipt("job-collision", 50L))) shouldBe
			ImportPortableCapturedWifiResult.Blocked(
				PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
			)
		database.importedWifiDao().distinctEntryCount() shouldBe 1L
	}

	@Test
	fun `live local owner is hashed internally and blocks duplicate imported origin`() = runTest {
		database.sourceSessionDao().insertSession(LogicalTrackingSessionEntity(
			logicalTrackingId = "entry",
			state = "FINALIZED",
			lifecycleRevision = 1L,
			desiredPlanRevision = 1L,
			rolloutRevision = 1L,
			startOrigin = "MANUAL_USER",
			clockDomainId = "boot",
			startedAtMs = 1L,
			startedElapsedNanos = 1L,
			cutoffAtMs = null,
			cutoffElapsedNanos = null,
			completedAtMs = 2L,
			finalAdmissionOrdinal = null,
			failureCode = null,
			sessionMode = "MANUAL",
			currentManifestRevision = null,
			currentIntentRevision = null,
			currentServiceRunId = null,
			lifecycleLeaseGeneration = 1L,
			lifecycleBootId = "boot",
			automationEpoch = null,
		))

		importer(testScheduler).importEntry(request()) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
		database.importedWifiDao().entryRevisionCount() shouldBe 1L
	}

	@Test
	fun `selected local deletion retains matched original identity when reverse scope is missing`() =
		runTest {
			database.sourceSessionDao().insertSession(LogicalTrackingSessionEntity(
				logicalTrackingId = "entry",
				state = "FINALIZED",
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_USER",
				clockDomainId = "boot",
				startedAtMs = 1L,
				startedElapsedNanos = 1L,
				cutoffAtMs = 2L,
				cutoffElapsedNanos = 2L,
				completedAtMs = 2L,
				finalAdmissionOrdinal = 1L,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				currentServiceRunId = null,
				lifecycleLeaseGeneration = 1L,
				lifecycleBootId = "boot",
				automationEpoch = null,
			))

			deletionSubject().delete(
				DeleteSelectedWifiHistoryRequest(
					WifiHistorySelection.Local(
						com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey(
							identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "entry").value,
						),
					),
					EPOCH,
					2_000L,
				),
			) shouldBe DeleteSelectedWifiHistoryResult.Blocked(
				com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionBlockedReason
					.ORIGINAL_SCOPE_MISSING,
			)
		}

	@Test
	fun `live cursor child and scope ownership survives missing parent rows and repeated projections`() = runTest {
		val liveLogical = "cursor-logical"
		val liveRun = "cursor-run"
		val cursorScope = PortableWifiDeletionScopeDigest(SourceDeletionFenceEntity.logicalServiceRunIdentity(
			SourceDestinationOwnerEntity.SOURCE_WIFI, "SESSION_CAPTURE", liveLogical, liveRun,
		))
		listOf(1, 2).forEach { version ->
			database.openHelper.writableDatabase.execSQL(
				"INSERT INTO wifi_captured_fact_cursor (writer_projection_id, writer_projection_version, " +
					"logical_fact_id, logical_tracking_id, service_run_id, session_segment_id, " +
					"writer_owner_generation, collected_data_epoch, scope_deletion_generation, " +
					"latest_semantic_revision, latest_mutation_id, latest_effect_checksum, " +
					"latest_source_admission_ordinal, cursor_revision, updated_at_ms) " +
					"VALUES (?, ?, ?, ?, ?, 1, 1, ?, 0, 1, 'mutation', ?, 1, 1, 1000)",
				arrayOf("projection", version, "cursor-fact", liveLogical, liveRun, EPOCH, "a".repeat(64)),
			)
		}
		database.importedWifiDao().localObservationOwnerCount() shouldBe 1L
		val importer = importer(testScheduler)
		listOf(
			entry(entryIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, liveLogical)),
			entry(runLocalId = liveRun),
			entry(deletionScope = cursorScope),
		).forEach { collision ->
			importer.importEntry(request(collision)) shouldBe ImportPortableCapturedWifiResult.Blocked(
				PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
			)
		}
		importer.importEntry(request()) shouldBe ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		rowCount("logical_tracking_session") shouldBe 0L
		rowCount("source_service_run") shouldBe 0L
	}

	@Test
	fun `cursorless immutable facts reserve every live identity and scope before admission or replay`() = runTest {
		val first = request()
		val importer = importer(testScheduler)
		val live = nativeRevision("immutable-logical", "immutable-run")
		database.wifiCapturedFactDao().insertRevision(live).let { (it > 0L) shouldBe true }
		database.wifiCapturedFactDao().cursorCount() shouldBe 0L
		database.importedWifiDao().localObservationOwnerCount() shouldBe 1L
		val liveScope = PortableWifiDeletionScopeDigest(SourceDeletionFenceEntity.logicalServiceRunIdentity(
			SourceDestinationOwnerEntity.SOURCE_WIFI, "SESSION_CAPTURE", live.logicalTrackingId, live.serviceRunId,
		))
		listOf(
			entry(entryIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, live.logicalTrackingId)),
			entry(runLocalId = live.serviceRunId),
			entry(observation(localId = live.logicalFactId)),
			entry(deletionScope = liveScope),
		).forEachIndexed { index, collision ->
			importer.importEntry(request(collision, receipt("immutable-$index"))) shouldBe
				ImportPortableCapturedWifiResult.Blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
		database.importedWifiDao().entryRevisionCount() shouldBe 1L
		database.importedWifiDao().receiptCount() shouldBe 0L
		importer.importEntry(first) shouldBe ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		// A newly retained live owner must also defeat an otherwise exact imported replay.
		val replayOwner = nativeRevision("entry", "replay-run")
		database.wifiCapturedFactDao().insertRevision(replayOwner).let { (it > 0L) shouldBe true }
		importer.importEntry(first) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
		)
		database.importedWifiDao().entryRevisionCount() shouldBe 1L
		database.importedWifiDao().receiptCount() shouldBe 1L
		database.wifiCapturedFactDao().revisionCount() shouldBe 2L
		rowCount("logical_tracking_session") shouldBe 0L
		rowCount("source_service_run") shouldBe 0L
	}

	@Test
	fun `orphan aggregate reference reserves the absent immutable owner identity`() = runTest {
		val owner = nativeRevision("aggregate-logical", "aggregate-run")
		val dependent = nativeRevision("aggregate-logical", "aggregate-run", "dependent", owner.logicalFactId)
		database.wifiCapturedFactDao().insertRevision(owner).let { (it > 0L) shouldBe true }
		database.wifiCapturedFactDao().insertRevision(dependent).let { (it > 0L) shouldBe true }
		database.importedWifiDao().localObservationOwnerCount() shouldBe 2L
		// Deliberate raw-storage corruption: authenticate namespaces without requiring the missing parent.
		val storage = database.openHelper.writableDatabase
		storage.setForeignKeyConstraintsEnabled(false)
		try {
			storage.execSQL("DELETE FROM wifi_captured_fact_revision WHERE logical_fact_id = ?", arrayOf(owner.logicalFactId))
		} finally {
			storage.setForeignKeyConstraintsEnabled(true)
		}
		database.wifiCapturedFactDao().revisionCount() shouldBe 1L
		database.wifiCapturedFactDao().cursorCount() shouldBe 0L
		database.importedWifiDao().localObservationOwnerCount() shouldBe 2L

		importer(testScheduler).importEntry(request(entry(observation(localId = owner.logicalFactId)))) shouldBe
			ImportPortableCapturedWifiResult.Blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		database.importedWifiDao().entryRevisionCount() shouldBe 0L
		database.importedWifiDao().receiptCount() shouldBe 0L
		database.wifiCapturedFactDao().revisionCount() shouldBe 1L
	}

	@Test
	fun `immutable union owners are paged boundedly and conflicting ownership fails closed`() = runTest {
		val dao = database.importedWifiDao()
		(0..ImportedWifiDao.OWNER_PAGE_SIZE).forEach { index ->
			database.wifiCapturedFactDao().insertRevision(nativeRevision("paged-logical", "paged-run-$index"))
				.let { (it > 0L) shouldBe true }
		}
		dao.localObservationOwnerCount() shouldBe 257L
		val first = dao.localObservationOwnerPage(null, ImportedWifiDao.OWNER_PAGE_SIZE)
		first.size shouldBe 256
		dao.localObservationOwnerPage(first.last().logicalFactId, ImportedWifiDao.OWNER_PAGE_SIZE).size shouldBe 1
		importer(testScheduler, WifiImportLimits(maximumGlobalAuthorityRows = 256L)).importEntry(request()) shouldBe
			ImportPortableCapturedWifiResult.Unverifiable(PortableCapturedWifiImportUnverifiableReason.DEPENDENCY_OVERFLOW)
		dao.entryRevisionCount() shouldBe 0L
		importer(testScheduler).importEntry(request()) shouldBe ImportPortableCapturedWifiResult.Applied(1L, 1, 1)

		val repeated = nativeRevision("paged-logical", "paged-run-0", "contradictory")
		database.wifiCapturedFactDao().insertRevision(repeated).let { (it > 0L) shouldBe true }
		// Deliberately reuse its raw fact ID under another owner; never skip that reverse authority.
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO wifi_captured_fact_cursor (writer_projection_id, writer_projection_version, " +
				"logical_fact_id, logical_tracking_id, service_run_id, session_segment_id, " +
				"writer_owner_generation, collected_data_epoch, scope_deletion_generation, " +
				"latest_semantic_revision, latest_mutation_id, latest_effect_checksum, " +
				"latest_source_admission_ordinal, cursor_revision, updated_at_ms) " +
				"VALUES (?, 1, ?, 'contradictory-logical', 'contradictory-run', 1, 1, ?, 0, 1, 'mutation', ?, 1, 1, 1000)",
			arrayOf("projection", repeated.logicalFactId, EPOCH, "a".repeat(64)),
		)
		importer(testScheduler).importEntry(request()) shouldBe ImportPortableCapturedWifiResult.Unverifiable(
			PortableCapturedWifiImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		dao.receiptCount() shouldBe 1L
	}

	@Test
	fun `all source purpose and opaque fence namespaces are preserved and block reuse`() = runTest {
		val fences = listOf(
			SourceDeletionFenceEntity.createLogicalServiceRun(SourceKind.CELL.stableCode,
				"SESSION_CAPTURE", "cell-logical", "cell-run", 1L, EPOCH, 20L),
			SourceDeletionFenceEntity.createLogicalServiceRun(SourceDestinationOwnerEntity.SOURCE_WIFI,
				"CONTROL_CONTINUATION", "control-logical", "control-run", 1L, EPOCH, 20L),
			SourceDeletionFenceEntity.createLogicalServiceRun(99, "OPAQUE_PURPOSE", "unknown-logical",
				"unknown-run", 1L, EPOCH, 20L),
		)
		fences.forEach { database.sourceDeletionFenceDao().upsert(it) }
		val importer = importer(testScheduler)
		fences.forEachIndexed { index, fence ->
			listOf(
				entry(entryIdentity = PortableWifiOpaqueIdentity(fence.scopeIdentityDigest)),
				entry(deletionScope = PortableWifiDeletionScopeDigest(fence.scopeIdentityDigest)),
			).forEach { collision ->
				importer.importEntry(request(collision, receipt("fence-$index"))) shouldBe
					ImportPortableCapturedWifiResult.Blocked(PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
		}
		database.importedWifiDao().entryRevisionCount() shouldBe 0L
		database.importedWifiDao().receiptCount() shouldBe 0L
		database.importedWifiDao().deletionFenceIdentityOwners(fences.map { it.scopeIdentityDigest }, 4).toSet() shouldBe
			fences.toSet()
		rowCount("source_deletion_fence") shouldBe 3L
		rowCount("source_event_wal") shouldBe 0L
		rowCount("source_demand") shouldBe 0L
		rowCount("provider_registration_generation") shouldBe 0L
	}

	@Test
	fun `foreign fence epoch or checksum corruption is typed before receipt writes`() = runTest {
		val fence = SourceDeletionFenceEntity.createLogicalServiceRun(SourceKind.CELL.stableCode,
			"CONTROL_AUTOSTART", "stale-logical", "stale-run", 1L, EPOCH + 1L, 20L)
		database.sourceDeletionFenceDao().upsert(fence)
		val collision = entry(entryIdentity = PortableWifiOpaqueIdentity(fence.scopeIdentityDigest))
		val importer = importer(testScheduler)
		importer.importEntry(request(collision)) shouldBe ImportPortableCapturedWifiResult.Unverifiable(
			PortableCapturedWifiImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_deletion_fence SET effect_checksum = ? WHERE scope_identity_digest = ?",
			arrayOf("0".repeat(64), fence.scopeIdentityDigest),
		)
		importer.importEntry(request(collision)) shouldBe ImportPortableCapturedWifiResult.Unverifiable(
			PortableCapturedWifiImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		)
		database.importedWifiDao().entryRevisionCount() shouldBe 0L
		database.importedWifiDao().receiptCount() shouldBe 0L
		rowCount("source_deletion_fence") shouldBe 1L
	}

	@Test
	fun `aggregate owner is exact same-run evidence and mismatched aggregate is rejected atomically`() = runTest {
		val owner = observation(localId = "aggregate-owner")
		val dependent = observation(
			localId = "coverage-child",
			aggregateOwnerIdentity = owner.identity,
		)
		val valid = entryWithObservations(
			listOf(owner, dependent),
			entryLocalId = "aggregate-entry",
			runLocalId = "aggregate-run",
			deletionScope = PortableWifiDeletionScopeDigest("a".repeat(64)),
		)

		importer(testScheduler).importEntry(
			request(valid, receipt("aggregate-job", 40L)),
		) shouldBe ImportPortableCapturedWifiResult.Applied(1L, 1, 2)
		database.importedWifiDao().allObservationsForAdmission(valid.identity.value)
			.single { it.identity == dependent.identity.value }.aggregateOwnerIdentity shouldBe owner.identity.value

		val mismatched = entryWithObservations(
			listOf(
				observation(localId = "bad-owner"),
				observation(
					localId = "bad-child",
					aggregateOwnerIdentity = identity(PortableWifiIdentityKind.OBSERVATION, "bad-owner"),
					strongestSignalDbm = -39,
				),
			),
			entryLocalId = "bad-entry",
			runLocalId = "bad-run",
			deletionScope = PortableWifiDeletionScopeDigest("b".repeat(64)),
		)
		importer(testScheduler).importEntry(
			request(mismatched, receipt("bad-job", 50L)),
		) shouldBe ImportPortableCapturedWifiResult.Unverifiable(
			PortableCapturedWifiImportUnverifiableReason.ENTRY_INVALID,
		)
		database.importedWifiDao().distinctEntryCount() shouldBe 1L
	}

	@Test
	fun `current retention floor and exact source or imported deletion fence prevent resurrection`() = runTest {
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 901L, 20L) shouldBe 1
		val importer = importer(testScheduler)
		importer.importEntry(request()) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.RETENTION_BOUNDARY,
		)

		database.close()
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val fenced = entry(deletionScope = PortableWifiDeletionScopeDigest(
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				"SESSION_CAPTURE",
				"deleted-logical",
				"deleted-run",
			),
		))
		database.sourceDeletionFenceDao().upsert(SourceDeletionFenceEntity.createLogicalServiceRun(
			SourceDestinationOwnerEntity.SOURCE_WIFI,
			"SESSION_CAPTURE",
			"deleted-logical",
			"deleted-run",
			1L,
			EPOCH,
			30L,
		))
		importer(testScheduler).importEntry(request(fenced)) shouldBe
			ImportPortableCapturedWifiResult.Blocked(PortableCapturedWifiImportBlockedReason.DELETED_SCOPE)

		database.importedWifiDao().insertDeletionGeneration(
			ImportedWifiDeletionGenerationEntity.create(
				identity(PortableWifiIdentityKind.PHYSICAL_RUN, "generation-run").value,
				identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "generation-entry").value,
				"f".repeat(64),
				EPOCH,
				1L,
				31L,
			),
		)
		val generationFenced = entry(
			entryIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "generation-entry"),
			runLocalId = "generation-run",
			deletionScope = PortableWifiDeletionScopeDigest("f".repeat(64)),
			observation = observation(localId = "generation-observation"),
		)
		importer(testScheduler).importEntry(
			request(generationFenced, receipt("generation-job", 32L)),
		) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.DELETED_RUN,
		)
		val tombstoned = entry(
			entryIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "tombstoned-entry"),
			runLocalId = "tombstoned-run",
			deletionScope = PortableWifiDeletionScopeDigest("9".repeat(64)),
			observation = observation(localId = "tombstoned-observation"),
		)
		database.importedWifiDao().insertEntryDeletion(ImportedWifiEntryDeletionEntity.create(
			tombstoned.identity.value,
			EPOCH,
			1L,
			33L,
		))
		importer(testScheduler).importEntry(
			request(tombstoned, receipt("tombstone-job", 34L)),
		) shouldBe ImportPortableCapturedWifiResult.Blocked(
			PortableCapturedWifiImportBlockedReason.DELETED_ENTRY,
		)
		rowCount("imported_wifi_entry_revision") shouldBe 0L
	}

	@Test
	fun `combined global cap fails before mutation and cancellation rolls back all children`() = runTest {
		val limited = importer(
			testScheduler,
			limits = WifiImportLimits(maximumEntries = 10L, maximumGlobalAuthorityRows = 1L),
		)
		limited.importEntry(request()) shouldBe ImportPortableCapturedWifiResult.Unverifiable(
			PortableCapturedWifiImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		)
		database.importedWifiDao().entryRevisionCount() shouldBe 0L

		val cancelling = importer(testScheduler) { checkpoint ->
			if (checkpoint == PortableWifiImportWriteCheckpoint.OBSERVATION_INSERTED) {
				throw CancellationException("cancel after child")
			}
		}
		shouldThrow<CancellationException> { cancelling.importEntry(request()) }
		database.importedWifiDao().entryRevisionCount() shouldBe 0L
		database.importedWifiDao().runCount() shouldBe 0L
		database.importedWifiDao().observationCount() shouldBe 0L
		database.importedWifiDao().receiptCount() shouldBe 0L
	}

	@Test
	fun `stored checksum corruption fails closed without binding a new receipt`() = runTest {
		val first = request()
		val importer = importer(testScheduler)
		importer.importEntry(first)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_wifi_observation SET content_checksum = ?",
			arrayOf("f".repeat(64)),
		)

		importer.importEntry(first.copy(receipt = receipt("corrupt-replay", 50L))) shouldBe
			ImportPortableCapturedWifiResult.Unverifiable(
				PortableCapturedWifiImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		database.importedWifiDao().receiptCount() shouldBe 1L
	}

	@Test
	fun `large bounded identity snapshot is admitted through finite fence batches`() = runTest {
		val entry = entryWithObservations((0 until 1_100).map { observation(localId = "observation-$it") })

		importer(testScheduler).importEntry(request(entry)) shouldBe
			ImportPortableCapturedWifiResult.Applied(1L, 1, 1_100)
		database.importedWifiDao().observationCount() shouldBe 1_100L
		rowCount("source_event_wal") shouldBe 0L
	}

	@Test
	fun `four max-shape entries use one union per identity chunk and one local owner snapshot`() = runTest {
		val importer = importer(testScheduler)
		repeat(4) { entryIndex ->
			val observations = (0 until WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY).map {
				observationIndex ->
				observation("dense-$entryIndex-$observationIndex")
			}
			val entry = entryWithObservations(
				observations,
				entryLocalId = "dense-entry-$entryIndex",
				runLocalId = "dense-run-$entryIndex",
				deletionScope = PortableWifiDeletionScopeDigest(
					sha256("dense-scope-$entryIndex"),
				),
			)
			importer.importEntry(
				request(entry, receipt("dense-job-$entryIndex", 100L + entryIndex)),
			) shouldBe ImportPortableCapturedWifiResult.Applied(
				1L,
				1,
				WifiCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY,
			)
		}
		ownershipQueries.clear()

		database.withTransaction { productEvaluator().selectRecentInTransaction(4) }

		val authorityUnionQueries = ownershipQueries.count {
			"SELECT DISTINCT 'ENTRY' AS owner_kind" in it
		}
		val localOwnerQueries = ownershipQueries.size - authorityUnionQueries
		authorityUnionQueries shouldBe 65
		(localOwnerQueries <= 8) shouldBe true
	}

	@Test
	fun `existing plus new global rows and injected entry cap reject without mutation`() = runTest {
		val importer = importer(testScheduler)
		importer.importEntry(request()) shouldBe ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		val second = entry(
			entryIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "second-entry"),
			runLocalId = "second-run",
			deletionScope = PortableWifiDeletionScopeDigest("e".repeat(64)),
			observation = observation(localId = "second-observation"),
		)
		val secondRequest = request(second, receipt("second-job", 50L))

		importer(testScheduler, limits = WifiImportLimits(maximumGlobalAuthorityRows = 9L))
			.importEntry(secondRequest) shouldBe ImportPortableCapturedWifiResult.Unverifiable(
				PortableCapturedWifiImportUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		importer(testScheduler, limits = WifiImportLimits(maximumEntries = 1L))
			.importEntry(secondRequest) shouldBe ImportPortableCapturedWifiResult.Unverifiable(
				PortableCapturedWifiImportUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		database.importedWifiDao().distinctEntryCount() shouldBe 1L
		database.importedWifiDao().receiptCount() shouldBe 1L
	}

	@Test
	fun `stored orphan child authority is rejected rather than omitted from checksum reconstruction`() = runTest {
		val request = request()
		importer(testScheduler).importEntry(request)
		val dao = database.importedWifiDao()
		val identity = request.entry.identity.value
		val failure = shouldThrow<ImportedWifiLineageFailure> {
			ImportedWifiLineageAuthenticator.authenticate(
				identity, EPOCH,
				dao.entryRevisionsForAdmission(identity), dao.receiptsForAdmission(identity),
				dao.allRunsForAdmission(identity), dao.allRunZonesForAdmission(identity),
				dao.allObservationsForAdmission(identity) + dao.allObservationsForAdmission(identity)
					.map { it.copy(runIdentity = "e".repeat(64)) },
			)
		}
		failure.reason shouldBe ImportedWifiLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE
		dao.receiptCount() shouldBe 1L
	}

	@Test
	fun `deletion marker entry owner cannot be reused as a new child identity`() = runTest {
		val markerEntry = "e".repeat(64)
		database.importedWifiDao().insertDeletionGeneration(ImportedWifiDeletionGenerationEntity.create(
			"8".repeat(64), markerEntry, "9".repeat(64), EPOCH, 1L, 50L,
		))
		val collision = entry(observation = observation(localId = "new-observation"),
			runLocalId = "new-run").let { original ->
			val run = original.runs.single()
			val replacement = PortableWifiIntegrity.createRun(
				identity = PortableWifiOpaqueIdentity(markerEntry),
				deletionScopeDigest = run.deletionScopeDigest,
				startTimeMs = run.startTimeMs, endTimeMs = run.endTimeMs,
				storedZoneIds = run.storedZoneIds, captureCoverage = run.captureCoverage,
				availability = run.availability, acquisitionCompleteness = run.acquisitionCompleteness,
				hasUnresolvedProviderRange = false, retentionLoss = false, observations = run.observations,
			)
			PortableWifiIntegrity.createEntry(original.identity, original.sessionMode,
				original.startTimeMs, original.endTimeMs, listOf(replacement))
		}

		importer(testScheduler).importEntry(request(collision)) shouldBe
			ImportPortableCapturedWifiResult.Blocked(
				PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
			)
		database.importedWifiDao().entryRevisionCount() shouldBe 0L
	}

	@Test
	fun `semantic successor arithmetic cannot wrap or rewrite the payload`() {
		val previous = entry(observation(semanticRevision = Long.MAX_VALUE))
		entry(observation()).isReciprocalCorrectionOf(previous) shouldBe false
		entry(observation(semanticRevision = 2L)).isReciprocalCorrectionOf(entry()) shouldBe true
		entry(observation(semanticRevision = 2L, strongestSignalDbm = -39))
			.isReciprocalCorrectionOf(entry()) shouldBe false
	}

	@Test
	fun `product evaluator authenticates complete replacement hierarchy and latest correction`() = runTest {
		val first = entryWithRuns(
			run("run-a", "a".repeat(64), listOf(observation("observation-a"))),
			run("run-b", "b".repeat(64), listOf(observation("observation-b"))),
		)
		val corrected = entryWithRuns(
			run("run-a", "a".repeat(64), listOf(observation("observation-a", semanticRevision = 2L))),
			run("run-b", "b".repeat(64), listOf(observation("observation-b"))),
		)
		val importer = importer(testScheduler)
		importer.importEntry(request(first)) shouldBe ImportPortableCapturedWifiResult.Applied(1L, 2, 2)
		importer.importEntry(request(corrected, receipt("correction", 40L))) shouldBe
			ImportPortableCapturedWifiResult.Applied(2L, 2, 2)

		val evaluated = database.withTransaction {
			productEvaluator().selectIdentityInTransaction(
				WifiImportedHistorySelectionKey(first.identity.value),
			)
		}
		val readable = evaluated as ImportedWifiProductEvaluation.Readable
		readable.candidate.importRevision shouldBe 2L
		readable.entry shouldBe corrected
		readable.deletedRunIdentities shouldBe emptySet()
		readable.retentionLimited shouldBe false
		readable.retainedObservationIdentities.size shouldBe 2
		RoomReexportImportedCapturedWifi(
			database,
			productEvaluator(),
			UnconfinedTestDispatcher(testScheduler),
		).reexport(
			ReexportImportedCapturedWifiRequest(importedSelection(first), EPOCH),
		) {} shouldBe ReexportImportedCapturedWifiResult.Blocked(
			com.adsamcik.tracker.stats.api.repository.ImportedWifiReexportBlockedReason.STALE_SELECTION,
		)
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		val reexported = RoomReexportImportedCapturedWifi(
			database,
			productEvaluator(),
			UnconfinedTestDispatcher(testScheduler),
		).reexport(
			ReexportImportedCapturedWifiRequest(
				WifiImportedHistorySelection(
					WifiImportedHistorySelectionKey(first.identity.value),
					2L,
					corrected.contentChecksum.value,
				),
				EPOCH,
			),
		) { emitted += it }
		reexported shouldBe ReexportImportedCapturedWifiResult.Exported(
			2L,
			corrected.identity,
			corrected.contentChecksum,
			2,
			2,
		)
		emitted.single() shouldBe corrected
	}

	@Test
	fun `imported range is keyset bounded and authenticates latest revisions before filtering`() = runTest {
		val first = entry()
		val second = entry(
			entryIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "range-second"),
			runLocalId = "range-second-run",
			deletionScope = PortableWifiDeletionScopeDigest("c".repeat(64)),
			observation = observation("range-second-observation").copyWithTimes(1_900L, 2_000L, 10L),
		)
		val importer = importer(testScheduler)
		importer.importEntry(request(first)) shouldBe ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		importer.importEntry(request(second, receipt("range-second-job", 50L))) shouldBe
			ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		var ownershipSnapshots = 0
		val evaluator = productEvaluator(checkpoint = { checkpoint ->
			if (checkpoint == ImportedWifiProductReadCheckpoint.OWNERSHIP_AUTHENTICATED) {
				ownershipSnapshots += 1
			}
		})

		val firstPage = database.withTransaction {
			evaluator.selectRangeInTransaction(
				ImportedWifiProductRangeRequest(0L, 3_000L, 1),
			)
		}
		firstPage.hasMore shouldBe true
		val firstIdentity = firstPage.evaluations.single().candidate.identity
		(firstIdentity in setOf(first.identity, second.identity)) shouldBe true
		val secondPage = database.withTransaction {
			evaluator.selectRangeInTransaction(
				ImportedWifiProductRangeRequest(
					0L,
					3_000L,
					1,
					firstPage.evaluations.single().candidate.newestMemberStartTimeMs,
					firstPage.evaluations.single().candidate.newestMemberIdentity,
				),
			)
		}
		ownershipSnapshots shouldBe 2
		secondPage.hasMore shouldBe false
		setOf(firstIdentity, secondPage.evaluations.single().candidate.identity) shouldBe
			setOf(first.identity, second.identity)
		database.withTransaction {
			productEvaluator().selectRangeInTransaction(
				ImportedWifiProductRangeRequest(1_300L, 1_800L, 10),
			)
		}.evaluations shouldBe emptyList()
	}

	@Test
	fun `product evaluator fails typed when a later local origin reuses only part of imported identity`() =
		runTest {
			val imported = entry()
			importer(testScheduler).importEntry(request(imported))
			database.sourceSessionDao().insertSession(
				LogicalTrackingSessionEntity(
					logicalTrackingId = "entry",
					state = "FINALIZED",
					lifecycleRevision = 1L,
					desiredPlanRevision = 1L,
					rolloutRevision = 1L,
					startOrigin = "MANUAL_USER",
					clockDomainId = "boot",
					startedAtMs = 1L,
					startedElapsedNanos = 1L,
					cutoffAtMs = null,
					cutoffElapsedNanos = null,
					completedAtMs = 2L,
					finalAdmissionOrdinal = null,
					failureCode = null,
					sessionMode = "MANUAL",
					currentManifestRevision = null,
					currentIntentRevision = null,
					currentServiceRunId = null,
					lifecycleLeaseGeneration = 1L,
					lifecycleBootId = "boot",
					automationEpoch = null,
				),
			)

			val evaluated = database.withTransaction {
				productEvaluator().selectIdentityInTransaction(
					WifiImportedHistorySelectionKey(imported.identity.value),
				)
			} as ImportedWifiProductEvaluation.Unverifiable

			evaluated.reason shouldBe ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT
			evaluated.collidingLocalLogicalTrackingId shouldBe "entry"
			database.openHelper.writableDatabase.execSQL(
				"UPDATE imported_wifi_observation SET content_checksum = ?",
				arrayOf("f".repeat(64)),
			)
			val corrupt = database.withTransaction {
				productEvaluator().selectIdentityInTransaction(
					WifiImportedHistorySelectionKey(imported.identity.value),
				)
			} as ImportedWifiProductEvaluation.Unverifiable
			corrupt.reason shouldBe ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE
			corrupt.collidingLocalLogicalTrackingId shouldBe "entry"
		}

	@Test
	fun `product evaluator applies retention and exact run deletion before returning values`() = runTest {
		val imported = entryWithRuns(
			run("run-old", "a".repeat(64), listOf(observation("old"))),
			run(
				"run-current",
				"b".repeat(64),
				listOf(observation("current").copyWithTimes(1_100L, 1_200L, 10L)),
			),
		)
		importer(testScheduler).importEntry(request(imported))
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 1_000L, 50L) shouldBe 1
		val deletedRun = imported.runs.last()
		database.importedWifiDao().insertDeletionGeneration(
			ImportedWifiDeletionGenerationEntity.create(
				deletedRun.identity.value,
				imported.identity.value,
				deletedRun.deletionScopeDigest.value,
				EPOCH,
				1L,
				51L,
			),
		)
		database.sourceDeletionFenceDao().upsert(
			SourceDeletionFenceEntity.createForOriginalRunDigest(
				SourceDestinationOwnerEntity.SOURCE_WIFI,
				"SESSION_CAPTURE",
				deletedRun.deletionScopeDigest.value,
				1L,
				EPOCH,
				51L,
			),
		)

		val evaluated = database.withTransaction {
			productEvaluator().selectIdentityInTransaction(
				WifiImportedHistorySelectionKey(imported.identity.value),
			)
		} as ImportedWifiProductEvaluation.Readable
		evaluated.retentionLimited shouldBe true
		evaluated.retainedObservationIdentities shouldBe
			setOf(deletedRun.observations.single().identity)
		evaluated.deletedRunIdentities shouldBe setOf(deletedRun.identity)
	}

	@Test
	fun `product evaluator rejects rehashed aggregate corruption overflow and cancellation`() = runTest {
		val owner = observation("owner")
		val dependent = observation("dependent", aggregateOwnerIdentity = owner.identity)
		val imported = entryWithObservations(listOf(owner, dependent))
		importer(testScheduler).importEntry(request(imported))

		val corruptedDependent = PortableWifiIntegrity.createObservation(
			identity = dependent.identity,
			semanticRevision = dependent.semanticRevision,
			supersedesSemanticRevision = dependent.supersedesSemanticRevision,
			aggregateOwnerIdentity = owner.identity,
			aggregateOwnerSemanticRevision = owner.semanticRevision,
			coverageStartTimeMs = dependent.coverageStartTimeMs,
			observedTimeMs = dependent.observedTimeMs,
			latestPossibleTimeMs = dependent.latestPossibleTimeMs,
			wallTimeUncertaintyMs = dependent.wallTimeUncertaintyMs,
			storedZoneId = dependent.storedZoneId,
			availability = dependent.availability,
			resultCompleteness = dependent.resultCompleteness,
			submittedResultCount = dependent.submittedResultCount,
			acceptedResultCount = dependent.acceptedResultCount,
			staleResultCount = dependent.staleResultCount,
			clockUnverifiableResultCount = dependent.clockUnverifiableResultCount,
			malformedResultCount = dependent.malformedResultCount,
			observationCount = dependent.observationCount,
			twoPointFourGhzCount = dependent.twoPointFourGhzCount,
			fiveGhzCount = dependent.fiveGhzCount,
			sixGhzCount = dependent.sixGhzCount,
			otherBandCount = dependent.otherBandCount,
			strongestSignalDbm = -39,
			weakestSignalDbm = dependent.weakestSignalDbm,
			meanSignalDbm = dependent.meanSignalDbm,
			sourceQualityFlags = dependent.sourceQualityFlags,
			sourceQualityConfidence = dependent.sourceQualityConfidence,
		)
		val corruptRun = PortableWifiIntegrity.createRun(
			identity = imported.runs.single().identity,
			deletionScopeDigest = imported.runs.single().deletionScopeDigest,
			startTimeMs = imported.runs.single().startTimeMs,
			endTimeMs = imported.runs.single().endTimeMs,
			storedZoneIds = imported.runs.single().storedZoneIds,
			captureCoverage = imported.runs.single().captureCoverage,
			availability = imported.runs.single().availability,
			acquisitionCompleteness = imported.runs.single().acquisitionCompleteness,
			hasUnresolvedProviderRange = false,
			retentionLoss = false,
			observations = listOf(owner, corruptedDependent).sortedWith(
				com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_OBSERVATION_ORDER,
			),
		)
		val corruptEntry = PortableWifiIntegrity.createEntry(
			imported.identity,
			imported.sessionMode,
			imported.startTimeMs,
			imported.endTimeMs,
			listOf(corruptRun),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_wifi_observation SET content_checksum = ?, strongest_signal_dbm = ? " +
				"WHERE identity = ?",
			arrayOf(corruptedDependent.contentChecksum.value, -39, dependent.identity.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_wifi_run SET content_checksum = ?",
			arrayOf(corruptRun.contentChecksum.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_wifi_entry_revision SET content_checksum = ?",
			arrayOf(corruptEntry.contentChecksum.value),
		)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_wifi_receipt SET entry_content_checksum = ?",
			arrayOf(corruptEntry.contentChecksum.value),
		)
		val corrupt = database.withTransaction {
			productEvaluator().selectIdentityInTransaction(
				WifiImportedHistorySelectionKey(imported.identity.value),
			)
		} as ImportedWifiProductEvaluation.Unverifiable
		corrupt.reason shouldBe ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE

		val overflow = database.withTransaction {
			productEvaluator(ImportedWifiProductLimits(maximumAuthorityRows = 1))
				.selectIdentityInTransaction(WifiImportedHistorySelectionKey(imported.identity.value))
		} as ImportedWifiProductEvaluation.Unverifiable
		overflow.reason shouldBe ImportedWifiProductFailure.DEPENDENCY_OVERFLOW

		val cancelling = productEvaluator(checkpoint = { stage ->
			if (stage == ImportedWifiProductReadCheckpoint.LINEAGES_LOADED) {
				throw CancellationException("cancel imported product read")
			}
		})
		shouldThrow<CancellationException> {
			database.withTransaction {
				cancelling.selectIdentityInTransaction(
					WifiImportedHistorySelectionKey(imported.identity.value),
				)
			}
		}

	}

	@Test
	fun `product evaluator authenticates an old complete lineage but withholds stale epoch values`() = runTest {
		val imported = entry()
		importer(testScheduler).importEntry(request(imported))
		database.sourceEvidenceStateDao().updateLifecycle(EPOCH + 1L, null, 60L) shouldBe 1

		val stale = database.withTransaction {
			productEvaluator().selectIdentityInTransaction(
				WifiImportedHistorySelectionKey(imported.identity.value),
			)
		} as ImportedWifiProductEvaluation.Unverifiable

		stale.reason shouldBe ImportedWifiProductFailure.STALE_COLLECTED_DATA_EPOCH
	}

	@Test
	fun `latest imported reexport emits only after Room snapshot and propagates sink cancellation`() = runTest {
		val imported = entry()
		importer(testScheduler).importEntry(request(imported))
		val reexport = RoomReexportImportedCapturedWifi(
			database,
			productEvaluator(),
			UnconfinedTestDispatcher(testScheduler),
		)
		var sinkInTransaction = true
		val emitted = mutableListOf<PortableCapturedWifiEntryV1>()
		val result = reexport.reexport(
			ReexportImportedCapturedWifiRequest(
				importedSelection(imported),
				EPOCH,
			),
		) { entry ->
			sinkInTransaction = database.openHelper.writableDatabase.inTransaction()
			emitted += entry
		}
		result shouldBe ReexportImportedCapturedWifiResult.Exported(
			1L,
			imported.identity,
			imported.contentChecksum,
			1,
			1,
		)
		sinkInTransaction shouldBe false
		emitted.single() shouldBe imported

		shouldThrow<CancellationException> {
			reexport.reexport(
				ReexportImportedCapturedWifiRequest(
					importedSelection(imported),
					EPOCH,
				),
			) { throw CancellationException("cancel imported sink") }
		}
		shouldThrow<IllegalStateException> {
			reexport.reexport(
				ReexportImportedCapturedWifiRequest(
					importedSelection(imported),
					EPOCH,
				),
			) { throw IllegalStateException("imported sink failed") }
		}
	}

	@Test
	fun `imported reexport maps local collision authority and budget failures as nonretryable`() = runTest {
		val imported = entry()
		val evaluation = ImportedWifiProductEvaluation.Readable(
			candidate = com.adsamcik.tracker.stats.api.repository.ImportedWifiProductCandidate(
				imported.identity,
				1L,
				imported.contentChecksum,
				imported.startTimeMs,
				imported.endTimeMs,
				30L,
				imported.runs.single().startTimeMs,
				imported.runs.single().identity,
			),
			entry = imported,
			entryDeleted = false,
			deletedRunIdentities = emptySet(),
			retainedObservationIdentities = imported.runs.flatMapTo(linkedSetOf()) { run ->
				run.observations.map { it.identity }
			},
			retentionLimited = false,
			collidingLocalLogicalTrackingId = "local-collision",
		)
		val evaluator = object : com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator {
			override suspend fun selectIdentityInTransaction(
				selection: WifiImportedHistorySelectionKey,
			): ImportedWifiProductEvaluation = evaluation

			override suspend fun selectRecentInTransaction(
				limit: Int,
			): List<ImportedWifiProductEvaluation> = listOf(evaluation)

			override suspend fun selectRangeInTransaction(
				request: ImportedWifiProductRangeRequest,
			) = com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage(
				listOf(evaluation),
				false,
			)
		}
		suspend fun result(error: RuntimeException): ReexportImportedCapturedWifiResult =
			RoomReexportImportedCapturedWifi(
				database,
				evaluator,
				object : ReadLocalPortableCapturedWifi {
					override suspend fun readInTransaction(
						request: com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest,
					): ReadLocalPortableCapturedWifiResult = throw error
				},
				com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader {
					com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult.NotDeleted
				},
				UnconfinedTestDispatcher(testScheduler),
			).reexport(
				ReexportImportedCapturedWifiRequest(importedSelection(imported), EPOCH),
			) {}

		result(WifiCapturedMaintenanceLimitExceeded()) shouldBe
			ReexportImportedCapturedWifiResult.Unverifiable(
				ImportedWifiProductFailure.DEPENDENCY_OVERFLOW,
			)
		result(
			WifiCapturedRetentionBlockedException(
				WifiCapturedRetentionBlockedReason.DESTINATION_OWNER_CHANGED,
			),
		) shouldBe ReexportImportedCapturedWifiResult.Unverifiable(
			ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT,
		)
	}

	@Test
	fun `imported reexport withholds retention deletion epoch change and closed storage`() = runTest {
		val imported = entry()
		importer(testScheduler).importEntry(request(imported))
		val selection = importedSelection(imported)
		fun reexporter() = RoomReexportImportedCapturedWifi(
			database,
			productEvaluator(),
			UnconfinedTestDispatcher(testScheduler),
		)

		reexporter().reexport(
			ReexportImportedCapturedWifiRequest(selection, EPOCH + 1L),
		) {} shouldBe ReexportImportedCapturedWifiResult.Blocked(
			com.adsamcik.tracker.stats.api.repository.ImportedWifiReexportBlockedReason
				.COLLECTED_DATA_EPOCH_CHANGED,
		)

		database.sourceEvidenceStateDao().updateLifecycle(EPOCH, 901L, 70L) shouldBe 1
		reexporter().reexport(
			ReexportImportedCapturedWifiRequest(selection, EPOCH),
		) {} shouldBe ReexportImportedCapturedWifiResult.Unavailable(
			com.adsamcik.tracker.stats.api.repository.ImportedWifiReexportUnavailableReason.RETENTION_LIMIT,
		)

		database.close()
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		importer(testScheduler).importEntry(request(imported))
		val run = imported.runs.single()
		database.importedWifiDao().insertDeletionGeneration(
			ImportedWifiDeletionGenerationEntity.create(
				run.identity.value,
				imported.identity.value,
				run.deletionScopeDigest.value,
				EPOCH,
				1L,
				71L,
			),
		)
		reexporter().reexport(
			ReexportImportedCapturedWifiRequest(selection, EPOCH),
		) {} shouldBe ReexportImportedCapturedWifiResult.Deleted

		database.close()
		reexporter().reexport(
			ReexportImportedCapturedWifiRequest(selection, EPOCH),
		) {} shouldBe ReexportImportedCapturedWifiResult.RetryableFailure(
			PortableWifiRetryableReason.STORAGE_UNAVAILABLE,
		)
	}

	@Test
	fun `selected imported deletion records complete authority before cascade and blocks resurrection`() =
		runTest {
			val imported = entryWithObservations(
				listOf(
					observation("owner"),
					observation(
						"dependent",
						aggregateOwnerIdentity = identity(PortableWifiIdentityKind.OBSERVATION, "owner"),
					),
				),
			)
			val unrelated = entry(
				entryIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "unrelated-entry"),
				runLocalId = "unrelated-run",
				deletionScope = PortableWifiDeletionScopeDigest("6".repeat(64)),
				observation = observation("unrelated-observation"),
			)
			val importer = importer(testScheduler)
			importer.importEntry(request(imported))
			importer.importEntry(request(unrelated, receipt("unrelated-job", 60L)))
			val selection = WifiHistorySelection.Imported(importedSelection(imported))
			val subject = deletionSubject()

			subject.delete(
				DeleteSelectedWifiHistoryRequest(selection, EPOCH, 2_000L),
			) shouldBe DeleteSelectedWifiHistoryResult.Deleted(
				com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin.IMPORTED,
				1,
				2,
			)
			database.importedWifiDao().entryRevisionCount() shouldBe 1L
			database.importedWifiDao().entryRevisionsForAdmission(unrelated.identity.value).size shouldBe 1
			database.importedWifiDao().observationCount() shouldBe 1L
			database.importedWifiDao().allObservationsForAdmission(unrelated.identity.value).size shouldBe 1
			database.importedWifiDao().selectedDeletionReceipt(
				imported.identity.value,
				com.adsamcik.tracker.shared.base.database.data
					.WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
			)
				.shouldNotBeNull()
			database.importedWifiDao().selectedDeletionProtectedIdentities(
				imported.identity.value,
				com.adsamcik.tracker.shared.base.database.data
					.WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
				16,
			).size shouldBe 5

			subject.delete(
				DeleteSelectedWifiHistoryRequest(selection, EPOCH, 2_000L),
			) shouldBe DeleteSelectedWifiHistoryResult.AlreadyDeleted(
				com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin.IMPORTED,
			)
			val deleted = database.withTransaction {
				subject.readDeletedInTransaction(selection)
			} as com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult.Deleted
			deleted.entry.state shouldBe
				com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState.DELETED
			deleted.entry.selection shouldBe selection
			RoomReexportImportedCapturedWifi(
				database,
				productEvaluator(),
				object : ReadLocalPortableCapturedWifi {
					override suspend fun readInTransaction(
						request: com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest,
					): ReadLocalPortableCapturedWifiResult =
						error("Deleted imported reexport cannot read local source")
				},
				subject,
				UnconfinedTestDispatcher(testScheduler),
			).reexport(
				ReexportImportedCapturedWifiRequest(importedSelection(imported), EPOCH),
			) {} shouldBe ReexportImportedCapturedWifiResult.Deleted
			importer.importEntry(
				request(imported, receipt("deleted-reimport", 2_100L)),
			) shouldBe ImportPortableCapturedWifiResult.Blocked(
				PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
			)

			val reparented = entry(
				entryIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "reparent-entry"),
				runLocalId = "reparent-run",
				deletionScope = PortableWifiDeletionScopeDigest("7".repeat(64)),
				observation = observation("dependent"),
			)
			importer(testScheduler).importEntry(
				request(reparented, receipt("reparent-job", 2_200L)),
			) shouldBe ImportPortableCapturedWifiResult.Blocked(
				PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
			)
		}

	@Test
	fun `selected imported deletion stale correction cancellation and storage failure are typed`() = runTest {
		val original = entry()
		val corrected = entry(observation(semanticRevision = 2L))
		val importer = importer(testScheduler)
		importer.importEntry(request(original))
		importer.importEntry(request(corrected, receipt("corrected", 40L)))

		deletionSubject().delete(
			DeleteSelectedWifiHistoryRequest(
				WifiHistorySelection.Imported(importedSelection(original)),
				EPOCH,
				2_000L,
			),
		) shouldBe DeleteSelectedWifiHistoryResult.Blocked(
			com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionBlockedReason.STALE_SELECTION,
		)
		deletionSubject(
			limits = WifiSelectedDeletionLimits(maximumProtectedIdentities = 1),
		).delete(
			DeleteSelectedWifiHistoryRequest(
				WifiHistorySelection.Imported(importedSelection(corrected, 2L)),
				EPOCH,
				2_000L,
			),
		) shouldBe DeleteSelectedWifiHistoryResult.Unverifiable(
			com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionUnverifiableReason
				.DEPENDENCY_OVERFLOW,
		)

		val cancelling = deletionSubject { checkpoint ->
			if (checkpoint == WifiSelectedDeletionCheckpoint.MARKERS_RECORDED) {
				throw CancellationException("cancel after deletion markers")
			}
		}
		shouldThrow<CancellationException> {
			cancelling.delete(
				DeleteSelectedWifiHistoryRequest(
					WifiHistorySelection.Imported(importedSelection(corrected, 2L)),
					EPOCH,
					2_000L,
				),
			)
		}
		database.importedWifiDao().entryRevisionCount() shouldBe 2L
		database.importedWifiDao().selectedDeletionReceiptCount() shouldBe 0L

		val subject = deletionSubject()
		database.close()
		subject.delete(
			DeleteSelectedWifiHistoryRequest(
				WifiHistorySelection.Imported(importedSelection(corrected, 2L)),
				EPOCH,
				2_000L,
			),
		) shouldBe DeleteSelectedWifiHistoryResult.RetryableFailure(
			com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionRetryableReason
				.STORAGE_UNAVAILABLE,
		)
	}

	@Test
	fun `selected imported deletion receipt survives safe Room reopen and still blocks replay`(): Unit =
		runBlocking {
			val originalDatabase = database
			val context: Application = ApplicationProvider.getApplicationContext()
			val file = File(
				System.getProperty("java.io.tmpdir"),
				"wifi-selected-deletion-${System.nanoTime()}.db",
			)
			check(!file.exists() || file.delete())
			var reopened: AppDatabase? = null
			try {
				val imported = entry()
				importer(testScheduler).importEntry(request(imported))
				val selection = WifiHistorySelection.Imported(importedSelection(imported))
				deletionSubject().delete(
					DeleteSelectedWifiHistoryRequest(selection, EPOCH, 2_000L),
				) shouldBe DeleteSelectedWifiHistoryResult.Deleted(
					com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin.IMPORTED,
					1,
					1,
				)
				database.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(file.path))
				originalDatabase.close()
				reopened = Room.databaseBuilder(context, AppDatabase::class.java, file.path)
					.allowMainThreadQueries()
					.build()
				database = reopened

				deletionSubject().delete(
					DeleteSelectedWifiHistoryRequest(selection, EPOCH, 2_000L),
				) shouldBe DeleteSelectedWifiHistoryResult.AlreadyDeleted(
					com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin.IMPORTED,
				)
				importer(testScheduler).importEntry(
					request(imported, receipt("reopen-replay", 3_000L)),
				) shouldBe ImportPortableCapturedWifiResult.Blocked(
					PortableCapturedWifiImportBlockedReason.OPAQUE_IDENTITY_CONFLICT,
				)
			} finally {
				reopened?.close()
				if (database === originalDatabase) originalDatabase.close()
				database = AppDatabase.testDatabase(context)
				file.delete()
			}
		}

	@Test
	fun `rehashed selected deletion child reparenting fails closed after payload removal`() = runTest {
		val imported = entry()
		importer(testScheduler).importEntry(request(imported))
		val selection = WifiHistorySelection.Imported(importedSelection(imported))
		val subject = deletionSubject()
		subject.delete(DeleteSelectedWifiHistoryRequest(selection, EPOCH, 2_000L))
		val observationMarker = database.importedWifiDao().selectedDeletionProtectedIdentities(
			imported.identity.value,
			com.adsamcik.tracker.shared.base.database.data
				.WifiSelectedDeletionReceiptEntity.ORIGIN_IMPORTED,
			16,
		).single {
			it.identityKind ==
				com.adsamcik.tracker.shared.base.database.data
					.WifiSelectedDeletionProtectedIdentityEntity.KIND_OBSERVATION
		}
		val reparented = com.adsamcik.tracker.shared.base.database.data
			.WifiSelectedDeletionProtectedIdentityEntity.create(
				selectionIdentity = observationMarker.selectionIdentity,
				receiptOrigin = observationMarker.receiptOrigin,
				identityKind = observationMarker.identityKind,
				protectedIdentity = observationMarker.protectedIdentity,
				ownerEntryIdentity = observationMarker.ownerEntryIdentity,
				ownerRunIdentity = "e".repeat(64),
				deletionScopeDigest = null,
				aggregateOwnerIdentity = observationMarker.aggregateOwnerIdentity,
				aggregateOwnerSemanticRevision = observationMarker.aggregateOwnerSemanticRevision,
				revisionCount = observationMarker.revisionCount,
				revisionSetChecksum = observationMarker.revisionSetChecksum,
				collectedDataEpoch = observationMarker.collectedDataEpoch,
			)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE wifi_selected_deletion_protected_identity SET owner_run_identity = ?, " +
				"effect_checksum = ? WHERE selection_identity = ? AND identity_kind = ? " +
				"AND protected_identity = ?",
			arrayOf(
				reparented.ownerRunIdentity,
				reparented.effectChecksum,
				reparented.selectionIdentity,
				reparented.identityKind,
				reparented.protectedIdentity,
			),
		)

		database.withTransaction {
			subject.readDeletedInTransaction(selection)
		} shouldBe com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult.Unverifiable(
			com.adsamcik.tracker.stats.api.repository.WifiHistoryDeletionUnverifiableReason
				.STORED_EVIDENCE_UNVERIFIABLE,
		)
	}

	@Test
	fun `storage failure is typed and never reported as an admitted entry`() = runTest {
		val importer = importer(testScheduler)
		database.close()

		importer.importEntry(request()) shouldBe ImportPortableCapturedWifiResult.RetryableFailure(
			PortableWifiRetryableReason.STORAGE_UNAVAILABLE,
		)
	}

	private fun importer(
		scheduler: TestCoroutineScheduler,
		limits: WifiImportLimits = WifiImportLimits(),
		checkpoint: suspend (PortableWifiImportWriteCheckpoint) -> Unit = {},
	) = RoomImportPortableCapturedWifi(
		database,
		UnconfinedTestDispatcher(scheduler),
		checkpoint,
		limits,
	)

	private fun productEvaluator(
		limits: ImportedWifiProductLimits = ImportedWifiProductLimits(),
		checkpoint: suspend (ImportedWifiProductReadCheckpoint) -> Unit = {},
	) = RoomImportedWifiProductEvaluator(database, checkpoint, limits)

	private fun deletionSubject(
		checkpoint: suspend (WifiSelectedDeletionCheckpoint) -> Unit = {},
		limits: WifiSelectedDeletionLimits = WifiSelectedDeletionLimits(),
	) = RoomDeleteSelectedWifiHistory(
		database = database,
		importedEvaluator = productEvaluator(),
		localPortableReader = object : ReadLocalPortableCapturedWifi {
			override suspend fun readInTransaction(
				request: com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest,
			): ReadLocalPortableCapturedWifiResult = error("Imported deletion cannot read local source")
		},
		maintenance = WifiCapturedFactMaintenance(
			database,
			DefaultSourcePayloadCodec(),
			SourcePlanCodec(),
		),
		ioDispatcher = UnconfinedTestDispatcher(testScheduler),
		limits = limits,
		checkpoint = checkpoint,
	)

	private fun request(
		entry: PortableCapturedWifiEntryV1 = entry(),
		receipt: PortableCapturedWifiImportReceipt = receipt(),
	) = ImportPortableCapturedWifiRequest(entry, receipt, EPOCH)

	private fun receipt(jobId: String = "job-1", receivedAtMs: Long = 30L) =
		PortableCapturedWifiImportReceipt(jobId, "entry-1", "backup.trackerwifi", receivedAtMs)

	private fun importedSelection(
		entry: PortableCapturedWifiEntryV1,
		importRevision: Long = 1L,
	) = WifiImportedHistorySelection(
		WifiImportedHistorySelectionKey(entry.identity.value),
		importRevision,
		entry.contentChecksum.value,
	)

	private fun entry(
		observation: PortableCapturedWifiObservationV1 = observation(),
		entryIdentity: PortableWifiOpaqueIdentity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "entry"),
		runLocalId: String = "run",
		deletionScope: PortableWifiDeletionScopeDigest = PortableWifiDeletionScopeDigest("d".repeat(64)),
	): PortableCapturedWifiEntryV1 = entryWithObservations(
		observations = listOf(observation),
		entryIdentity = entryIdentity,
		runLocalId = runLocalId,
		deletionScope = deletionScope,
	)

	private fun entryWithObservations(
		observations: List<PortableCapturedWifiObservationV1>,
		entryLocalId: String = "entry",
		entryIdentity: PortableWifiOpaqueIdentity = identity(
			PortableWifiIdentityKind.LOGICAL_ENTRY,
			entryLocalId,
		),
		runLocalId: String = "run",
		deletionScope: PortableWifiDeletionScopeDigest = PortableWifiDeletionScopeDigest("d".repeat(64)),
	): PortableCapturedWifiEntryV1 {
		val run = PortableWifiIntegrity.createRun(
			identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, runLocalId),
			deletionScopeDigest = deletionScope,
			startTimeMs = 800L,
			endTimeMs = 1_200L,
			storedZoneIds = listOf("Europe/Prague"),
			captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
			availability = PortableWifiRunAvailability.RETAINED,
			acquisitionCompleteness = PortableWifiAcquisitionCompleteness.COMPLETE,
			hasUnresolvedProviderRange = false,
			retentionLoss = false,
			observations = observations.sortedWith(com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_OBSERVATION_ORDER),
		)
		return PortableWifiIntegrity.createEntry(
			identity = entryIdentity,
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			runs = listOf(run),
		)
	}

	private fun entryWithRuns(
		vararg runs: PortableCapturedWifiRunV1,
	): PortableCapturedWifiEntryV1 {
		val ordered = runs.sortedWith(com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_RUN_ORDER)
		return PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "entry"),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = ordered.minOf { it.startTimeMs },
			endTimeMs = ordered.maxOf { it.endTimeMs },
			runs = ordered,
		)
	}

	private fun run(
		localId: String,
		deletionScope: String,
		observations: List<PortableCapturedWifiObservationV1>,
	): PortableCapturedWifiRunV1 = PortableWifiIntegrity.createRun(
		identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, localId),
		deletionScopeDigest = PortableWifiDeletionScopeDigest(deletionScope),
		startTimeMs = 800L,
		endTimeMs = 1_200L,
		storedZoneIds = listOf("Europe/Prague"),
		captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
		availability = PortableWifiRunAvailability.RETAINED,
		acquisitionCompleteness = PortableWifiAcquisitionCompleteness.COMPLETE,
		hasUnresolvedProviderRange = false,
		retentionLoss = false,
		observations = observations.sortedWith(
			com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_OBSERVATION_ORDER,
		),
	)

	private fun observation(
		localId: String = "observation",
		semanticRevision: Long = 1L,
		strongestSignalDbm: Int = -40,
		aggregateOwnerIdentity: PortableWifiOpaqueIdentity? = null,
	): PortableCapturedWifiObservationV1 = PortableWifiIntegrity.createObservation(
		identity = identity(PortableWifiIdentityKind.OBSERVATION, localId),
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = semanticRevision.takeIf { it > 1L }?.minus(1L),
		aggregateOwnerIdentity = aggregateOwnerIdentity,
		aggregateOwnerSemanticRevision = semanticRevision.takeIf { aggregateOwnerIdentity != null },
		coverageStartTimeMs = 900L,
		observedTimeMs = 1_000L,
		latestPossibleTimeMs = 1_010L,
		wallTimeUncertaintyMs = 10L,
		storedZoneId = "Europe/Prague",
		availability = PortableWifiAvailability.AVAILABLE,
		resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
		submittedResultCount = 2,
		acceptedResultCount = 2,
		staleResultCount = 0,
		clockUnverifiableResultCount = 0,
		malformedResultCount = 0,
		observationCount = 2,
		twoPointFourGhzCount = 1,
		fiveGhzCount = 1,
		sixGhzCount = 0,
		otherBandCount = 0,
		strongestSignalDbm = strongestSignalDbm,
		weakestSignalDbm = -60,
		meanSignalDbm = -50.0,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
	)

	private fun PortableCapturedWifiObservationV1.copyWithTimes(
		coverageStartTimeMs: Long,
		observedTimeMs: Long,
		wallTimeUncertaintyMs: Long,
	): PortableCapturedWifiObservationV1 = PortableWifiIntegrity.createObservation(
		identity = identity,
		semanticRevision = semanticRevision,
		supersedesSemanticRevision = supersedesSemanticRevision,
		aggregateOwnerIdentity = aggregateOwnerIdentity,
		aggregateOwnerSemanticRevision = aggregateOwnerSemanticRevision,
		coverageStartTimeMs = coverageStartTimeMs,
		observedTimeMs = observedTimeMs,
		latestPossibleTimeMs = Math.addExact(observedTimeMs, wallTimeUncertaintyMs),
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		storedZoneId = storedZoneId,
		availability = availability,
		resultCompleteness = resultCompleteness,
		submittedResultCount = submittedResultCount,
		acceptedResultCount = acceptedResultCount,
		staleResultCount = staleResultCount,
		clockUnverifiableResultCount = clockUnverifiableResultCount,
		malformedResultCount = malformedResultCount,
		observationCount = observationCount,
		twoPointFourGhzCount = twoPointFourGhzCount,
		fiveGhzCount = fiveGhzCount,
		sixGhzCount = sixGhzCount,
		otherBandCount = otherBandCount,
		strongestSignalDbm = strongestSignalDbm,
		weakestSignalDbm = weakestSignalDbm,
		meanSignalDbm = meanSignalDbm,
		sourceQualityFlags = sourceQualityFlags,
		sourceQualityConfidence = sourceQualityConfidence,
	)

	private fun identity(kind: PortableWifiIdentityKind, local: String) =
		PortableWifiOpaqueIdentity.derive(kind, local)

	/** Real native value/checksum shape, intentionally without session/run/cursor parent authority. */
	private fun nativeRevision(
		logical: String,
		run: String,
		delivery: String = run,
		aggregateOwner: String? = null,
	): WifiCapturedFactRevisionEntity {
		val deliveryIdentity = identity(PortableWifiIdentityKind.OBSERVATION, "delivery-$delivery").value
		val factIdentity = WifiCapturedFactRevisionIntegrity.logicalFactId(deliveryIdentity, logical, run, 1L, 1L, EPOCH, 0L)
		val aggregate = aggregateOwner == null
		val unsigned = WifiCapturedFactRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = factIdentity, semanticRevision = 1L, supersedesSemanticRevision = null,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(factIdentity, 1L),
			factKind = if (aggregate) "AGGREGATE" else "COVERAGE_ONLY",
			aggregateOwnerLogicalFactId = aggregateOwner,
			aggregateOwnerSemanticRevision = if (aggregate) null else 1L,
			aggregateOwnerCursorRevision = if (aggregate) null else 1L,
			logicalTrackingId = logical, serviceRunId = run, sessionSegmentId = 1L,
			purpose = "SESSION_CAPTURE", capturedSourceCodes = SourceDestinationOwnerEntity.SOURCE_WIFI.toString(),
			controlSourceCodes = "", sourceEventId = "event-$delivery", sourceAdmissionOrdinal = 1L,
			walIntegrityIdentity = "a".repeat(64), payloadChecksum = "b".repeat(64), sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0, deliveryUnitCount = 1, sourceSequence = 0L,
			planAttribution = "CAPTURED_REGISTRATION", sourceInstanceId = "wifi-instance", registrationGeneration = 1L,
			configurationRevision = 1L, physicalConfigurationFingerprint = "configuration", authorizationRevision = 1L,
			authorizationFingerprint = "c".repeat(64), purposeEligibilityMask = 4L,
			sourcePolicyRevision = 1L, captureConsentEpoch = 1L, manifestRevision = 1L, lifecycleLeaseGeneration = 1L,
			collectedDataEpoch = EPOCH, scopeDeletionGeneration = 0L, clockDomainId = "boot", storedZoneId = "Europe/Prague",
			planPayloadVersion = 1, planPayloadChecksum = "d".repeat(64), maximumObservationAgeNanos = 1_000_000_000L,
			resultContract = "ANDROID_SCAN_RESULTS_V1", registrationAppliedAtNanos = 0L,
			providerAcceptanceStartNanos = 1L, providerAcceptanceEndNanos = 2_000_000_000L,
			authorizationEffectStartNanos = 1L, authorizationEffectEndNanos = 2_000_000_000L,
			sessionRunEffectStartNanos = 1L, sessionRunEffectEndNanos = 2_000_000_000L,
			observedIntervalStartNanos = 1_000_000_000L, observedElapsedNanos = 1_000_000_000L,
			receivedElapsedNanos = 1_100_000_000L, coverageIntervalStartNanos = 1_000_000_000L,
			coverageIntervalEndNanos = 1_000_000_000L, observedWallTimeMs = 1_000L, wallTimeUncertaintyMs = 10L,
			acquiredAtMs = 1_000L, qualityFlags = 0L, qualityConfidence = 1f, availability = "AVAILABLE",
			submittedResultCount = 1, acceptedResultCount = 1, staleResultCount = 0,
			clockUnverifiableResultCount = 0, malformedResultCount = 0, coverageCompleteness = "COMPLETE",
			observationCount = if (aggregate) 1 else null, twoPointFourGhzCount = if (aggregate) 1 else null,
			fiveGhzCount = if (aggregate) 0 else null, sixGhzCount = if (aggregate) 0 else null,
			otherBandCount = if (aggregate) 0 else null, strongestSignalDbm = if (aggregate) -50 else null,
			weakestSignalDbm = if (aggregate) -50 else null, signalSumDbm = if (aggregate) -50L else null,
			effectChecksum = "0".repeat(64), appliedAtMs = 1_000L,
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun rowCount(table: String): Long = database.openHelper.readableDatabase
		.query("SELECT COUNT(*) FROM $table")
		.use { cursor ->
			check(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private companion object { const val EPOCH = 7L }
}

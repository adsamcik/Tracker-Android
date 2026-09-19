package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionIntegrity
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
class WifiCapturedFullClearTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		context.deleteDatabase(DATABASE_NAME)
		database = openDatabase()
	}

	@After
	fun tearDown() {
		if (::database.isInitialized) database.close()
		context.deleteDatabase(DATABASE_NAME)
	}

	@Test
	fun `suspend full clear deletes wifi dependents first and persists repeated empty state`() = runTest {
		foreignKeysEnabled() shouldBe true
		val graph = seedGraph()
		val dao = database.wifiCapturedFactDao()
		shouldThrow<SQLiteConstraintException> {
			dao.deleteExactRevisionLineages(
				graph.aggregateHead.writerProjectionId,
				graph.aggregateHead.writerProjectionVersion,
				listOf(graph.aggregateHead.logicalFactId),
			)
		}
		assertGraphPresent(graph)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = EPOCH + 1L,
			retainedFromMs = null,
			updatedAtMs = 2_000L,
		)

		assertCleared(graph, EPOCH + 1L)
		assertEvidenceState(EPOCH + 1L, revision = 1L, deletedHighWater = 2L)
		reopen()
		foreignKeysEnabled() shouldBe true
		assertCleared(graph, EPOCH + 1L)
		assertEvidenceState(EPOCH + 1L, revision = 1L, deletedHighWater = 2L)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = EPOCH + 2L,
			retainedFromMs = 2_500L,
			updatedAtMs = 3_000L,
		)

		reopen()
		assertCleared(graph, EPOCH + 2L)
		assertEvidenceState(
			EPOCH + 2L,
			revision = 2L,
			deletedHighWater = 2L,
			retainedFromMs = 2_500L,
		)
		database.sourceEventWalDao()
			.insertIgnoringDuplicate(event(
				eventId = "new-epoch",
				sourceSequence = 1L,
				deliveryIdentity = "7".repeat(64),
				collectedDataEpoch = EPOCH + 2L,
			)) shouldBe 3L
		database.sourceEventWalDao().getByEventId(AGGREGATE_EVENT_ID) shouldBe null
		database.sourceEventWalDao().getByEventId(COVERAGE_EVENT_ID) shouldBe null
		assertCleared(graph, EPOCH + 2L)
	}

	@Test
	fun `synchronous full clear deletes wifi graph and durably advances epoch`() = runTest {
		foreignKeysEnabled() shouldBe true
		val graph = seedGraph()

		AppDatabase.deleteAllCollectedData(database)

		assertCleared(graph, EPOCH + 1L)
		assertEvidenceState(EPOCH + 1L, revision = 1L, deletedHighWater = 2L)
		reopen()
		foreignKeysEnabled() shouldBe true
		assertCleared(graph, EPOCH + 1L)
		assertEvidenceState(EPOCH + 1L, revision = 1L, deletedHighWater = 2L)
	}

	@Test
	fun `later wifi deletion failure rolls back facts cursors and deletion marker`() = runTest {
		foreignKeysEnabled() shouldBe true
		val graph = seedGraph()
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER reject_wifi_deletion_generation " +
				"BEFORE DELETE ON wifi_capture_deletion_generation " +
				"BEGIN SELECT RAISE(ABORT, '$ROLLBACK_TRIGGER_MARKER'); END",
		)

		val failure = requireNotNull(runCatching {
			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = null,
				updatedAtMs = 2_000L,
			)
		}.exceptionOrNull())
		failure.hasMessageInCauseChain(ROLLBACK_TRIGGER_MARKER) shouldBe true

		assertGraphPresent(graph)
		database.sourceEventWalDao().countAll() shouldBe 2L
		assertEvidenceState(EPOCH, revision = 0L, deletedHighWater = 0L)
		reopen()
		foreignKeysEnabled() shouldBe true
		assertGraphPresent(graph)
		database.sourceEventWalDao().countAll() shouldBe 2L
		assertEvidenceState(EPOCH, revision = 0L, deletedHighWater = 0L)
	}

	private fun openDatabase(): AppDatabase = AppDatabase.fileBuilder(
		context,
		DATABASE_NAME,
	)
		.allowMainThreadQueries()
		.build()

	private fun reopen() {
		database.close()
		database = openDatabase()
	}

	private fun foreignKeysEnabled(): Boolean = database.openHelper.writableDatabase
		.query("PRAGMA foreign_keys")
		.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

	private suspend fun seedGraph(): WifiGraph {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
		val aggregateWal = insertWal(
			eventId = AGGREGATE_EVENT_ID,
			sourceSequence = 1L,
			deliveryIdentity = "1".repeat(64),
			expectedAdmissionOrdinal = 1L,
		)
		val coverageWal = insertWal(
			eventId = COVERAGE_EVENT_ID,
			sourceSequence = 2L,
			deliveryIdentity = "2".repeat(64),
			expectedAdmissionOrdinal = 2L,
		)
		val aggregateV1 = revision(aggregateWal)
		val aggregateV2 = correctedRevision(aggregateV1)
		val coverageV1 = revision(coverageWal, aggregateOwner = aggregateV2)
		val coverageV2 = correctedRevision(coverageV1)
		val dao = database.wifiCapturedFactDao()
		dao.insertRevision(aggregateV1)
		dao.insertRevision(aggregateV2)
		dao.insertRevision(coverageV1)
		dao.insertRevision(coverageV2)
		dao.insertCursor(cursor(aggregateV2))
		dao.insertCursor(cursor(coverageV2))
		dao.insertDeletionGeneration(
			WifiCaptureDeletionGenerationEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				updatedAtMs = 1_000L,
			),
		)
		return WifiGraph(aggregateV1, aggregateV2, coverageV1, coverageV2)
	}

	private suspend fun insertWal(
		eventId: String,
		sourceSequence: Long,
		deliveryIdentity: String,
		expectedAdmissionOrdinal: Long,
	): SourceEventWalEntity {
		val candidate = event(eventId, sourceSequence, deliveryIdentity)
		candidate.hasQualifiedIntegrity() shouldBe true
		database.sourceEventWalDao().insertIgnoringDuplicate(candidate) shouldBe expectedAdmissionOrdinal
		return requireNotNull(database.sourceEventWalDao().getByEventId(eventId)).also { stored ->
			stored.admissionOrdinal shouldBe expectedAdmissionOrdinal
			stored.hasQualifiedIntegrity() shouldBe true
		}
	}

	private suspend fun assertGraphPresent(graph: WifiGraph) {
		val dao = database.wifiCapturedFactDao()
		graph.revisions.forEach { revision ->
			dao.revision(
				revision.writerProjectionId,
				revision.writerProjectionVersion,
				revision.logicalFactId,
				revision.semanticRevision,
			) shouldBe revision
		}
		dao.cursor(
			graph.aggregateHead.writerProjectionId,
			graph.aggregateHead.writerProjectionVersion,
			graph.aggregateHead.logicalFactId,
		) shouldBe cursor(graph.aggregateHead)
		dao.cursor(
			graph.coverageHead.writerProjectionId,
			graph.coverageHead.writerProjectionVersion,
			graph.coverageHead.logicalFactId,
		) shouldBe cursor(graph.coverageHead)
		dao.revisionCount() shouldBe 4L
		dao.cursorCount() shouldBe 2L
		dao.deletionGenerationCount() shouldBe 1L
	}

	private suspend fun assertCleared(graph: WifiGraph, expectedEpoch: Long) {
		val dao = database.wifiCapturedFactDao()
		dao.revisionCount() shouldBe 0L
		dao.cursorCount() shouldBe 0L
		dao.deletionGenerationCount() shouldBe 1L
		requireNotNull(
			dao.deletionGeneration(LOGICAL_TRACKING_ID, SERVICE_RUN_ID),
		).collectedDataEpoch shouldBe expectedEpoch
		graph.revisions.forEach { revision ->
			dao.revision(
				revision.writerProjectionId,
				revision.writerProjectionVersion,
				revision.logicalFactId,
				revision.semanticRevision,
			) shouldBe null
		}
	}

	private suspend fun assertEvidenceState(
		epoch: Long,
		revision: Long,
		deletedHighWater: Long,
		retainedFromMs: Long? = null,
	) {
		requireNotNull(database.sourceEvidenceStateDao().get()).also { state ->
			state.collectedDataEpoch shouldBe epoch
			state.revision shouldBe revision
			state.deletedSourceEventHighWaterOrdinal shouldBe deletedHighWater
			state.retainedFromMs shouldBe retainedFromMs
		}
	}

	private fun revision(
		wal: SourceEventWalEntity,
		aggregateOwner: WifiCapturedFactRevisionEntity? = null,
	): WifiCapturedFactRevisionEntity {
		val deliveryIdentity = requireNotNull(wal.deliveryIdentity)
		val logicalFactId = WifiCapturedFactRevisionIntegrity.logicalFactId(
			deliveryIdentity,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
			SESSION_SEGMENT_ID,
			requireNotNull(wal.sessionManifestRevision),
			wal.capturedCollectedDataEpoch,
			0L,
		)
		val isAggregate = aggregateOwner == null
		val unsigned = WifiCapturedFactRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.WIFI_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalFactId = logicalFactId,
			semanticRevision = 1L,
			supersedesSemanticRevision = null,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(logicalFactId, 1L),
			factKind = if (isAggregate) WifiCapturedFactRevisionEntity.FACT_KIND_AGGREGATE else
				WifiCapturedFactRevisionEntity.FACT_KIND_COVERAGE_ONLY,
			aggregateOwnerLogicalFactId = aggregateOwner?.logicalFactId,
			aggregateOwnerSemanticRevision = aggregateOwner?.semanticRevision,
			aggregateOwnerCursorRevision = aggregateOwner?.semanticRevision,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sessionSegmentId = SESSION_SEGMENT_ID,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			capturedSourceCodes = SourceDestinationOwnerEntity.SOURCE_WIFI.toString(),
			controlSourceCodes = "",
			sourceEventId = wal.eventId,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			walIntegrityIdentity = wal.integrityIdentity,
			payloadChecksum = wal.payloadChecksum,
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = requireNotNull(wal.deliveryUnitIndex),
			deliveryUnitCount = requireNotNull(wal.deliveryUnitCount),
			sourceSequence = wal.sourceSequence,
			planAttribution = WifiCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION,
			sourceInstanceId = wal.sourceInstanceId,
			registrationGeneration = wal.registrationGeneration,
			configurationRevision = requireNotNull(wal.configRevision),
			physicalConfigurationFingerprint = requireNotNull(wal.physicalConfigurationFingerprint),
			authorizationRevision = requireNotNull(wal.authorizationRevision),
			authorizationFingerprint = requireNotNull(wal.authorizationFingerprint),
			purposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
			sourcePolicyRevision = requireNotNull(wal.sourcePolicyRevision),
			captureConsentEpoch = requireNotNull(wal.captureConsentEpoch),
			manifestRevision = requireNotNull(wal.sessionManifestRevision),
			lifecycleLeaseGeneration = requireNotNull(wal.lifecycleLeaseGeneration),
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			scopeDeletionGeneration = 0L,
			clockDomainId = wal.clockDomainId,
			storedZoneId = "Europe/Prague",
			planPayloadVersion = 1,
			planPayloadChecksum = "6".repeat(64),
			maximumObservationAgeNanos = 1_000_000_000L,
			resultContract = "ANDROID_SCAN_RESULTS_V1",
			registrationAppliedAtNanos = 0L,
			providerAcceptanceStartNanos = 1L,
			providerAcceptanceEndNanos = 2_000_000_000L,
			authorizationEffectStartNanos = 1L,
			authorizationEffectEndNanos = 2_000_000_000L,
			sessionRunEffectStartNanos = 1L,
			sessionRunEffectEndNanos = 2_000_000_000L,
			observedIntervalStartNanos = requireNotNull(wal.observedIntervalStartNanos),
			observedElapsedNanos = wal.observedElapsedNanos,
			receivedElapsedNanos = wal.receivedElapsedNanos,
			coverageIntervalStartNanos = requireNotNull(wal.observedIntervalStartNanos),
			coverageIntervalEndNanos = wal.observedElapsedNanos,
			observedWallTimeMs = requireNotNull(wal.wallTimeMs),
			wallTimeUncertaintyMs = requireNotNull(wal.wallTimeUncertaintyMs),
			acquiredAtMs = wal.acquiredAtMs,
			qualityFlags = wal.qualityFlags,
			qualityConfidence = wal.qualityConfidence,
			availability = WifiCapturedFactRevisionEntity.AVAILABILITY_AVAILABLE,
			submittedResultCount = 1,
			acceptedResultCount = 1,
			staleResultCount = 0,
			clockUnverifiableResultCount = 0,
			malformedResultCount = 0,
			coverageCompleteness = WifiCapturedFactRevisionEntity.COVERAGE_COMPLETE,
			observationCount = 1.takeIf { isAggregate },
			twoPointFourGhzCount = 1.takeIf { isAggregate },
			fiveGhzCount = 0.takeIf { isAggregate },
			sixGhzCount = 0.takeIf { isAggregate },
			otherBandCount = 0.takeIf { isAggregate },
			strongestSignalDbm = (-50).takeIf { isAggregate },
			weakestSignalDbm = (-50).takeIf { isAggregate },
			signalSumDbm = (-50L).takeIf { isAggregate },
			effectChecksum = "0".repeat(64),
			appliedAtMs = requireNotNull(wal.wallTimeMs),
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun correctedRevision(revision: WifiCapturedFactRevisionEntity): WifiCapturedFactRevisionEntity {
		val unsigned = revision.copy(
			semanticRevision = 2L,
			supersedesSemanticRevision = 1L,
			mutationId = WifiCapturedFactRevisionIntegrity.mutationId(revision.logicalFactId, 2L),
			effectChecksum = "0".repeat(64),
		)
		return unsigned.copy(effectChecksum = WifiCapturedFactRevisionIntegrity.effectChecksum(unsigned))
	}

	private fun cursor(fact: WifiCapturedFactRevisionEntity) = WifiCapturedFactCursorEntity(
		writerProjectionId = fact.writerProjectionId,
		writerProjectionVersion = fact.writerProjectionVersion,
		logicalFactId = fact.logicalFactId,
		logicalTrackingId = fact.logicalTrackingId,
		serviceRunId = fact.serviceRunId,
		sessionSegmentId = fact.sessionSegmentId,
		writerOwnerGeneration = fact.writerOwnerGeneration,
		collectedDataEpoch = fact.collectedDataEpoch,
		scopeDeletionGeneration = fact.scopeDeletionGeneration,
		latestSemanticRevision = fact.semanticRevision,
		latestMutationId = fact.mutationId,
		latestEffectChecksum = fact.effectChecksum,
		latestSourceAdmissionOrdinal = fact.sourceAdmissionOrdinal,
		cursorRevision = fact.semanticRevision,
		updatedAtMs = fact.appliedAtMs,
	)

	private fun event(
		eventId: String,
		sourceSequence: Long,
		deliveryIdentity: String,
		collectedDataEpoch: Long = EPOCH,
	): SourceEventWalEntity {
		// This core fixture proves persisted WAL/fact consistency, not tracker-engine codec gates.
		val unsigned = SourceEventWalEntity(
			eventId = eventId,
			providerDedupKey = null,
			deliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			logicalTrackingId = LOGICAL_TRACKING_ID,
			serviceRunId = SERVICE_RUN_ID,
			sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
			sourceInstanceId = "wifi-instance",
			registrationGeneration = 1L,
			physicalConfigurationFingerprint = "configuration",
			authorizationRevision = 1L,
			authorizationPurposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			authorizationFingerprint = "5".repeat(64),
			sourceSequence = sourceSequence,
			configRevision = 1L,
			planAttribution = CAPTURED_REGISTRATION_ORDINAL,
			clockDomainId = "boot",
			observedElapsedNanos = 1_000_000_000L,
			observedIntervalStartNanos = 1_000_000_000L,
			receivedElapsedNanos = 1_100_000_000L,
			wallTimeMs = 1_000L,
			wallTimeUncertaintyMs = 10L,
			capturedCollectedDataEpoch = collectedDataEpoch,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			acquiredAtMs = 1_000L,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 1,
			payload = byteArrayOf(sourceSequence.toByte()),
			payloadChecksum = "0".repeat(64),
			createdAtMs = 1_001L,
		)
		val checksummed = unsigned.copy(payloadChecksum = unsigned.calculatedPayloadChecksum())
		return checksummed.copy(integrityIdentity = checksummed.calculatedIntegrityIdentity())
	}

	private fun Throwable.hasMessageInCauseChain(marker: String): Boolean =
		generateSequence(this) { throwable -> throwable.cause }
			.any { throwable -> throwable.message?.contains(marker) == true }

	private data class WifiGraph(
		val aggregateV1: WifiCapturedFactRevisionEntity,
		val aggregateV2: WifiCapturedFactRevisionEntity,
		val coverageV1: WifiCapturedFactRevisionEntity,
		val coverageV2: WifiCapturedFactRevisionEntity,
	) {
		val aggregateHead: WifiCapturedFactRevisionEntity get() = aggregateV2
		val coverageHead: WifiCapturedFactRevisionEntity get() = coverageV2
		val revisions: List<WifiCapturedFactRevisionEntity>
			get() = listOf(aggregateV1, aggregateV2, coverageV1, coverageV2)
	}

	private companion object {
		const val DATABASE_NAME = "wifi-captured-full-clear-test.db"
		const val EPOCH = 7L
		const val LOGICAL_TRACKING_ID = "logical-tracking"
		const val SERVICE_RUN_ID = "service-run"
		const val SESSION_SEGMENT_ID = 1L
		const val AGGREGATE_EVENT_ID = "wifi-aggregate-event"
		const val COVERAGE_EVENT_ID = "wifi-coverage-event"
		const val CAPTURED_REGISTRATION_ORDINAL = 0
		const val ROLLBACK_TRIGGER_MARKER = "wifi-full-clear-late-trigger"
	}
}

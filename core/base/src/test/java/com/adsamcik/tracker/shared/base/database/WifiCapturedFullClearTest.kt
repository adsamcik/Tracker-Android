package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
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
				graph.aggregate.writerProjectionId,
				graph.aggregate.writerProjectionVersion,
				listOf(graph.aggregate.logicalFactId),
			)
		}
		assertGraphPresent(graph)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = EPOCH + 1L,
			retainedFromMs = null,
			updatedAtMs = 2_000L,
		)

		assertCleared(graph)
		assertEvidenceState(EPOCH + 1L, revision = 1L, deletedHighWater = 1L)
		reopen()
		foreignKeysEnabled() shouldBe true
		assertCleared(graph)
		assertEvidenceState(EPOCH + 1L, revision = 1L, deletedHighWater = 1L)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = EPOCH + 2L,
			retainedFromMs = 2_500L,
			updatedAtMs = 3_000L,
		)

		reopen()
		assertCleared(graph)
		assertEvidenceState(
			EPOCH + 2L,
			revision = 2L,
			deletedHighWater = 1L,
			retainedFromMs = 2_500L,
		)
		database.sourceEventWalDao()
			.insertIgnoringDuplicate(event("new-epoch", 1L, EPOCH + 2L)) shouldBe 2L
		database.sourceEventWalDao().getByEventId("old-epoch") shouldBe null
		assertCleared(graph)
	}

	@Test
	fun `synchronous full clear deletes wifi graph and durably advances epoch`() = runTest {
		foreignKeysEnabled() shouldBe true
		val graph = seedGraph()

		AppDatabase.deleteAllCollectedData(database)

		assertCleared(graph)
		assertEvidenceState(EPOCH + 1L, revision = 1L, deletedHighWater = 1L)
		reopen()
		foreignKeysEnabled() shouldBe true
		assertCleared(graph)
		assertEvidenceState(EPOCH + 1L, revision = 1L, deletedHighWater = 1L)
	}

	@Test
	fun `later wifi deletion failure rolls back facts cursors and deletion marker`() = runTest {
		foreignKeysEnabled() shouldBe true
		val graph = seedGraph()
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER reject_wifi_deletion_generation " +
				"BEFORE DELETE ON wifi_capture_deletion_generation " +
				"BEGIN SELECT RAISE(ABORT, 'test rollback'); END",
		)

		runCatching {
			AppDatabase.deleteAllCollectedData(
				database = database,
				collectedDataEpoch = EPOCH + 1L,
				retainedFromMs = null,
				updatedAtMs = 2_000L,
			)
		}.isFailure shouldBe true

		assertGraphPresent(graph)
		database.sourceEventWalDao().countAll() shouldBe 1L
		assertEvidenceState(EPOCH, revision = 0L, deletedHighWater = 0L)
		reopen()
		foreignKeysEnabled() shouldBe true
		assertGraphPresent(graph)
		database.sourceEventWalDao().countAll() shouldBe 1L
		assertEvidenceState(EPOCH, revision = 0L, deletedHighWater = 0L)
	}

	private fun openDatabase(): AppDatabase = Room.databaseBuilder(
		context,
		AppDatabase::class.java,
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
		database.sourceEventWalDao().insertIgnoringDuplicate(event("old-epoch", 1L)) shouldBe 1L
		val aggregate = revision(deliveryIdentity = "1".repeat(64), sourceAdmissionOrdinal = 1L)
		val coverage = revision(
			deliveryIdentity = "2".repeat(64),
			sourceAdmissionOrdinal = 2L,
			aggregateOwner = aggregate,
		)
		val dao = database.wifiCapturedFactDao()
		dao.insertRevision(aggregate)
		dao.insertRevision(coverage)
		dao.insertCursor(cursor(aggregate))
		dao.insertCursor(cursor(coverage))
		dao.insertDeletionGeneration(
			WifiCaptureDeletionGenerationEntity(
				logicalTrackingId = LOGICAL_TRACKING_ID,
				serviceRunId = SERVICE_RUN_ID,
				collectedDataEpoch = EPOCH,
				generation = 1L,
				updatedAtMs = 1_000L,
			),
		)
		return WifiGraph(aggregate, coverage)
	}

	private suspend fun assertGraphPresent(graph: WifiGraph) {
		val dao = database.wifiCapturedFactDao()
		dao.revision(
			graph.aggregate.writerProjectionId,
			graph.aggregate.writerProjectionVersion,
			graph.aggregate.logicalFactId,
			graph.aggregate.semanticRevision,
		) shouldBe graph.aggregate
		dao.revision(
			graph.coverage.writerProjectionId,
			graph.coverage.writerProjectionVersion,
			graph.coverage.logicalFactId,
			graph.coverage.semanticRevision,
		) shouldBe graph.coverage
		dao.cursor(
			graph.aggregate.writerProjectionId,
			graph.aggregate.writerProjectionVersion,
			graph.aggregate.logicalFactId,
		) shouldBe cursor(graph.aggregate)
		dao.cursor(
			graph.coverage.writerProjectionId,
			graph.coverage.writerProjectionVersion,
			graph.coverage.logicalFactId,
		) shouldBe cursor(graph.coverage)
		dao.revisionCount() shouldBe 2L
		dao.cursorCount() shouldBe 2L
		dao.deletionGenerationCount() shouldBe 1L
	}

	private suspend fun assertCleared(graph: WifiGraph) {
		val dao = database.wifiCapturedFactDao()
		dao.revisionCount() shouldBe 0L
		dao.cursorCount() shouldBe 0L
		dao.deletionGenerationCount() shouldBe 0L
		dao.revision(
			graph.aggregate.writerProjectionId,
			graph.aggregate.writerProjectionVersion,
			graph.aggregate.logicalFactId,
			graph.aggregate.semanticRevision,
		) shouldBe null
		dao.revision(
			graph.coverage.writerProjectionId,
			graph.coverage.writerProjectionVersion,
			graph.coverage.logicalFactId,
			graph.coverage.semanticRevision,
		) shouldBe null
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
		deliveryIdentity: String,
		sourceAdmissionOrdinal: Long,
		aggregateOwner: WifiCapturedFactRevisionEntity? = null,
	): WifiCapturedFactRevisionEntity {
		val logicalFactId = WifiCapturedFactRevisionIntegrity.logicalFactId(
			deliveryIdentity,
			LOGICAL_TRACKING_ID,
			SERVICE_RUN_ID,
			SESSION_SEGMENT_ID,
			1L,
			EPOCH,
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
			sourceEventId = "event-$sourceAdmissionOrdinal",
			sourceAdmissionOrdinal = sourceAdmissionOrdinal,
			walIntegrityIdentity = "3".repeat(64),
			payloadChecksum = "4".repeat(64),
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = 0,
			deliveryUnitCount = 1,
			sourceSequence = sourceAdmissionOrdinal,
			planAttribution = WifiCapturedFactRevisionEntity.PLAN_ATTRIBUTION_CAPTURED_REGISTRATION,
			sourceInstanceId = "wifi-instance",
			registrationGeneration = 1L,
			configurationRevision = 1L,
			physicalConfigurationFingerprint = "configuration",
			authorizationRevision = 1L,
			authorizationFingerprint = "5".repeat(64),
			purposeEligibilityMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE,
			sourcePolicyRevision = 1L,
			captureConsentEpoch = 1L,
			manifestRevision = 1L,
			lifecycleLeaseGeneration = 1L,
			collectedDataEpoch = EPOCH,
			scopeDeletionGeneration = 0L,
			clockDomainId = "boot",
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
			observedIntervalStartNanos = 1_000_000_000L,
			observedElapsedNanos = 1_000_000_000L,
			receivedElapsedNanos = 1_100_000_000L,
			coverageIntervalStartNanos = 1_000_000_000L,
			coverageIntervalEndNanos = 1_000_000_000L,
			observedWallTimeMs = 1_000L,
			wallTimeUncertaintyMs = 10L,
			acquiredAtMs = 1_000L,
			qualityFlags = 0L,
			qualityConfidence = 1f,
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
			appliedAtMs = 1_000L,
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
		collectedDataEpoch: Long = EPOCH,
	) = SourceEventWalEntity(
		eventId = eventId,
		providerDedupKey = null,
		logicalTrackingId = null,
		serviceRunId = null,
		sourceKind = SourceDestinationOwnerEntity.SOURCE_WIFI,
		sourceInstanceId = "wifi-instance",
		registrationGeneration = 1L,
		sourceSequence = sourceSequence,
		configRevision = 1L,
		planAttribution = 0,
		clockDomainId = "boot",
		observedElapsedNanos = 1L,
		receivedElapsedNanos = 2L,
		wallTimeMs = 10L,
		wallTimeUncertaintyMs = 1L,
		capturedCollectedDataEpoch = collectedDataEpoch,
		acquiredAtMs = 10L,
		qualityFlags = 0L,
		qualityConfidence = null,
		payloadVersion = 1,
		payload = byteArrayOf(1),
		payloadChecksum = "checksum-$eventId",
		createdAtMs = 10L,
	)

	private data class WifiGraph(
		val aggregate: WifiCapturedFactRevisionEntity,
		val coverage: WifiCapturedFactRevisionEntity,
	)

	private companion object {
		const val DATABASE_NAME = "wifi-captured-full-clear-test.db"
		const val EPOCH = 7L
		const val LOGICAL_TRACKING_ID = "logical-tracking"
		const val SERVICE_RUN_ID = "service-run"
		const val SESSION_SEGMENT_ID = 1L
	}
}

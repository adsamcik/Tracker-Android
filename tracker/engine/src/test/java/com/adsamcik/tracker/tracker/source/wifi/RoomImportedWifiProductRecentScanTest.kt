package com.adsamcik.tracker.tracker.source.wifi

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiImportReceipt
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.WifiCapturedPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class RoomImportedWifiProductRecentScanTest {
	private lateinit var database: AppDatabase
	private val ownerQueries = CopyOnWriteArrayList<String>()
	private val executedQueries = CopyOnWriteArrayList<String>()

	@Before
	fun setUp() = runTest {
		database = AppDatabase.inMemoryBuilder(
			ApplicationProvider.getApplicationContext<Application>(),
		).allowMainThreadQueries().setQueryCallback({ sql, _ ->
			executedQueries += sql
			if ("FROM logical_tracking_session" in sql) {
				ownerQueries += sql
			}
		}, Executor(Runnable::run)).build()
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState(collectedDataEpoch = EPOCH))
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `transaction scan reuses one local snapshot and authenticates newest-member keyset`() = runTest {
		val ownedEntries = listOf(
			"older-envelope-newest-member" to entry("older-envelope-newest-member", 0L, 300L),
			"newer-envelope-older-member" to entry("newer-envelope-older-member", 200L),
			"tie-left" to entry("tie-left", 400L),
			"tie-right" to entry("tie-right", 400L),
		)
		ownedEntries.forEachIndexed { index, (owner, entry) ->
			importer().importEntry(request(entry, "recent-page-$index", 100L + index)) shouldBe
				ImportPortableCapturedWifiResult.Applied(
					1L,
					entry.runs.size,
					entry.runs.sumOf { it.observations.size },
				)
			insertLocalOwners(owner, entry.runs.size, index * 10L + 1L)
		}
		val entries = ownedEntries.map { it.second }
		val expected = entries.sortedWith(
			compareByDescending<PortableCapturedWifiEntryV1> {
				it.runs.maxOf(PortableCapturedWifiRunV1::startTimeMs)
			}.thenByDescending {
				it.runs.maxWith(RECENCY_RUN_ORDER).identity.value
			},
		)
		ownerQueries.clear()

		val evaluated = database.withTransaction {
			val scan = RoomImportedWifiProductEvaluator(database).openRecentScanInTransaction()
			val first = scan.selectPageInTransaction(ImportedWifiProductRecentRequest(2))
			val cursor = first.evaluations.last().candidate
			val second = scan.selectPageInTransaction(
				ImportedWifiProductRecentRequest(
					2,
					cursor.newestMemberStartTimeMs,
					cursor.newestMemberIdentity,
				),
			)
			first.hasMore shouldBe true
			second.hasMore shouldBe false
			first.evaluations + second.evaluations
		}

		ownerQueries.count {
			"SELECT COUNT(*) FROM logical_tracking_session" in it
		} shouldBe 1
		evaluated.map { it.candidate.identity } shouldBe expected.map { it.identity }
		evaluated.forEach { evaluation ->
			val readable = evaluation as ImportedWifiProductEvaluation.Readable
			val newest = readable.entry.runs.maxWith(RECENCY_RUN_ORDER)
			readable.candidate.newestMemberStartTimeMs shouldBe newest.startTimeMs
			readable.candidate.newestMemberIdentity shouldBe newest.identity
			readable.collidingLocalLogicalTrackingId shouldBe ownedEntries.single {
				it.second.identity == readable.entry.identity
			}.first
		}
	}

	@Test
	fun `transaction scan propagates cancellation and accumulates authority budget`() = runTest {
		val entries = listOf(
			entry("budget-a", 300L),
			entry("budget-b", 200L),
			entry("budget-c", 100L),
		)
		entries.forEachIndexed { index, entry ->
			importer().importEntry(request(entry, "budget-page-$index", 200L + index)) shouldBe
				ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		}
		val bounded = RoomImportedWifiProductEvaluator(
			database,
			{},
			ImportedWifiProductLimits(maximumAuthorityRows = 30),
		)
		val pages = database.withTransaction {
			val scan = bounded.openRecentScanInTransaction()
			val first = scan.selectPageInTransaction(ImportedWifiProductRecentRequest(1))
			val cursor = first.evaluations.single().candidate
			val second = scan.selectPageInTransaction(
				ImportedWifiProductRecentRequest(
					1,
					cursor.newestMemberStartTimeMs,
					cursor.newestMemberIdentity,
				),
			)
			first to second
		}
		(pages.first.evaluations.single() is ImportedWifiProductEvaluation.Readable) shouldBe true
		(pages.second.evaluations.single() as ImportedWifiProductEvaluation.Unverifiable).reason shouldBe
			ImportedWifiProductFailure.DEPENDENCY_OVERFLOW

		val cancelling = RoomImportedWifiProductEvaluator(
			database,
			{ checkpoint ->
				if (checkpoint == ImportedWifiProductReadCheckpoint.LINEAGES_LOADED) {
					throw CancellationException("cancel recent scan")
				}
			},
			ImportedWifiProductLimits(),
		)
		shouldThrow<CancellationException> {
			database.withTransaction {
				cancelling.openRecentScanInTransaction().selectPageInTransaction(
					ImportedWifiProductRecentRequest(1),
				)
			}
		}
	}

	@Test
	fun `receipt bytes are rejected by numeric preflight before receipt rows materialize`() = runTest {
		val imported = entry("maximum-provenance", 100L)
		val maximum = WifiCapturedPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH
		val request = ImportPortableCapturedWifiRequest(
			imported,
			PortableCapturedWifiImportReceipt(
				"j".repeat(maximum),
				"k".repeat(maximum),
				"s".repeat(maximum),
				200L,
			),
			EPOCH,
		)
		importer().importEntry(request) shouldBe ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		executedQueries.clear()
		val evaluator = RoomImportedWifiProductEvaluator(
			database,
			{},
			ImportedWifiProductLimits(maximumAuthorityBytes = 24L * 1024L),
		)

		val failure = database.withTransaction {
			evaluator.selectIdentityInTransaction(
				com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey(
					imported.identity.value,
				),
			)
		} as ImportedWifiProductEvaluation.Unverifiable

		failure.reason shouldBe ImportedWifiProductFailure.DEPENDENCY_OVERFLOW
		failure.authenticatedSelection shouldBe null
		executedQueries.any {
			"COUNT(*) AS row_count" in it && "FROM imported_wifi_receipt" in it
		} shouldBe true
		executedQueries.none {
			"SELECT * FROM imported_wifi_receipt" in it &&
				"entry_identity IN" in it
		} shouldBe true
	}

	@Test
	fun `export range qualifies touching scans beyond headers without unrelated corrupt cohorts`() = runTest {
		val touching = entry("touching", 100L, endOffset = 10L)
		val outside = entry("outside", 1_000L)
		listOf(touching, outside).forEachIndexed { index, value ->
			importer().importEntry(request(value, "export-$index", 2_000L)) shouldBe
				ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_wifi_run SET content_checksum = 'broken' WHERE entry_identity = ?",
			arrayOf(outside.identity.value),
		)
		val evaluator = RoomImportedWifiProductEvaluator(database)
		database.withTransaction {
			val range = ImportedWifiProductRangeRequest(121L, 125L, 100)
			evaluator.selectRangeInTransaction(range).evaluations shouldBe emptyList()
			val selected = evaluator.selectExportRangeInTransaction(range)
			selected.hasMore shouldBe false
			(selected.evaluations.single() as ImportedWifiProductEvaluation.Readable).entry shouldBe touching
			evaluator.selectExportRangeInTransaction(range.copy(fromInclusiveMs = 122L)).evaluations shouldBe emptyList()
		}
	}

	@Test
	fun `export gap-only run is selected and an invalid outside clock cannot silently disappear`() = runTest {
		val gap = entry("gap", 100L, unresolvedGap = true)
		val unknown = entry("unknown", 1_000L)
		importer().importEntry(request(gap, "gap-export", 2_000L)) shouldBe
			ImportPortableCapturedWifiResult.Applied(1L, 1, 0)
		importer().importEntry(request(unknown, "unknown-export", 2_000L)) shouldBe
			ImportPortableCapturedWifiResult.Applied(1L, 1, 1)
		val evaluator = RoomImportedWifiProductEvaluator(database)
		database.withTransaction {
			val selected = evaluator.selectExportRangeInTransaction(ImportedWifiProductRangeRequest(150L, 151L, 100))
			(selected.evaluations.single() as ImportedWifiProductEvaluation.Readable).entry shouldBe gap
		}
		database.openHelper.writableDatabase.execSQL(
			"UPDATE imported_wifi_observation SET wall_time_uncertainty_ms = 100 " +
				"WHERE entry_identity = ?", arrayOf(unknown.identity.value))
		database.withTransaction {
			val selected = evaluator.selectExportRangeInTransaction(ImportedWifiProductRangeRequest(300L, 301L, 100))
			val failure = selected.evaluations.filterIsInstance<ImportedWifiProductEvaluation.Unverifiable>().single()
			failure.candidate.identity shouldBe unknown.identity
			failure.reason shouldBe ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE
		}
	}

	private fun importer() = RoomImportPortableCapturedWifi(
		database,
		UnconfinedTestDispatcher(),
		{},
		WifiImportLimits(),
	)

	private fun request(
		entry: PortableCapturedWifiEntryV1,
		jobId: String,
		receivedAtMs: Long,
	) = ImportPortableCapturedWifiRequest(
		entry,
		PortableCapturedWifiImportReceipt(
			jobId,
			"entry-$jobId",
			"recent-scan.trackerwifi",
			receivedAtMs,
		),
		EPOCH,
	)

	// Keep the imported-history local session, its runs and matching current radio cursors together.
	@Suppress("LongMethod")
	private suspend fun insertLocalOwners(
		logicalId: String,
		runCount: Int,
		segmentIdBase: Long,
	) {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = logicalId,
				state = "FINALIZED",
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 1L,
				startOrigin = "MANUAL_USER",
				clockDomainId = "boot",
				startedAtMs = 0L,
				startedElapsedNanos = 0L,
				cutoffAtMs = 1L,
				cutoffElapsedNanos = 1L,
				completedAtMs = 1L,
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
		repeat(runCount) { index ->
			val runId = "$logicalId-run-$index"
			val segmentId = segmentIdBase + index
			database.sourceSessionDao().insertServiceRun(
				SourceServiceRunEntity(
					serviceRunId = runId,
					logicalTrackingId = logicalId,
					state = "FINALIZED",
					desiredPlanRevision = 1L,
					rolloutRevision = 1L,
					foregroundCapabilityFlags = 0L,
					startedAtMs = 0L,
					startedElapsedNanos = 0L,
					completedAtMs = 1L,
					completionReason = "USER_STOP",
					bootId = "boot",
					leaseGeneration = 1L,
					startOrigin = "MANUAL_USER",
					desiredForegroundCapabilityFlags = 0L,
					appliedForegroundCapabilityFlags = 0L,
					runtimeAcknowledgement = "STOP_ACCEPTED",
					runtimeFailureCode = null,
					runRevision = 1L,
					startDeliveryToken = "token-$runId",
					startCommandGeneration = 1L,
					preparedManifestRevision = 1L,
					preparedIntentRevision = 1L,
					androidDeliveryState = "FOREGROUND_ACCEPTED",
					androidDeliveryUpdatedAtMs = 0L,
					startIsUserInitiated = true,
					startIsAmbient = false,
					sessionSegmentId = segmentId,
					presentationAcknowledgement = SourceServiceRunEntity.PRESENTATION_QUIESCED,
					presentationAcknowledgedAtMs = 1L,
				),
			)
			database.wifiCapturedFactDao().insertCursor(
				WifiCapturedFactCursorEntity(
					writerProjectionId = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_ID,
					writerProjectionVersion = SourceDestinationOwnerEntity.WIFI_FACT_PROJECTION_VERSION,
					logicalFactId = "$logicalId-observation-$index",
					logicalTrackingId = logicalId,
					serviceRunId = runId,
					sessionSegmentId = segmentId,
					writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
					collectedDataEpoch = EPOCH,
					scopeDeletionGeneration = 0L,
					latestSemanticRevision = 1L,
					latestMutationId = "mutation-$logicalId-$index",
					latestEffectChecksum = "0".repeat(64),
					latestSourceAdmissionOrdinal = 1L,
					cursorRevision = 1L,
					updatedAtMs = 0L,
				),
			)
		}
	}

	// Keep each portable Wi-Fi run and its observation beside the complete enclosing entry fixture.
	@Suppress("LongMethod")
	private fun entry(owner: String, vararg startTimes: Long, endOffset: Long = 100L,
		unresolvedGap: Boolean = false): PortableCapturedWifiEntryV1 {
		val runs = startTimes.mapIndexed { index, startTimeMs ->
			val observation = PortableWifiIntegrity.createObservation(
				identity = identity(PortableWifiIdentityKind.OBSERVATION, "$owner-observation-$index"),
				semanticRevision = 1L,
				supersedesSemanticRevision = null,
				aggregateOwnerIdentity = null,
				aggregateOwnerSemanticRevision = null,
				coverageStartTimeMs = startTimeMs + 10L,
				observedTimeMs = startTimeMs + 20L,
				latestPossibleTimeMs = startTimeMs + 21L,
				wallTimeUncertaintyMs = 1L,
				storedZoneId = "Europe/Prague",
				availability = PortableWifiAvailability.AVAILABLE,
				resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
				submittedResultCount = 1,
				acceptedResultCount = 1,
				staleResultCount = 0,
				clockUnverifiableResultCount = 0,
				malformedResultCount = 0,
				observationCount = 1,
				twoPointFourGhzCount = 1,
				fiveGhzCount = 0,
				sixGhzCount = 0,
				otherBandCount = 0,
				strongestSignalDbm = -50,
				weakestSignalDbm = -50,
				meanSignalDbm = -50.0,
				sourceQualityFlags = 0L,
				sourceQualityConfidence = 1f,
			)
			PortableWifiIntegrity.createRun(
				identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, "$owner-run-$index"),
				deletionScopeDigest = PortableWifiDeletionScopeDigest(
					SourceDeletionFenceEntity.logicalServiceRunIdentity(
						SourceDestinationOwnerEntity.SOURCE_WIFI,
						SessionManifestPurposeCode.SESSION_CAPTURE,
						owner,
						"$owner-run-$index",
					),
				),
				startTimeMs = startTimeMs,
				endTimeMs = startTimeMs + endOffset,
				storedZoneIds = listOf("Europe/Prague"),
				captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
				availability = if (unresolvedGap) {
					PortableWifiRunAvailability.NO_RETAINED_OBSERVATION
				}
					else {
					PortableWifiRunAvailability.RETAINED
				},
				acquisitionCompleteness = if (unresolvedGap) {
					PortableWifiAcquisitionCompleteness.PARTIAL
				}
					else {
					PortableWifiAcquisitionCompleteness.COMPLETE
				},
				hasUnresolvedProviderRange = unresolvedGap,
				retentionLoss = false,
				observations = if (unresolvedGap) {
					emptyList()
				} else {
					listOf(observation)
				},
			)
		}.sortedWith(com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_RUN_ORDER)
		return PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, owner),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = runs.minOf(PortableCapturedWifiRunV1::startTimeMs),
			endTimeMs = runs.maxOf(PortableCapturedWifiRunV1::endTimeMs),
			runs = runs,
		)
	}

	private fun identity(kind: PortableWifiIdentityKind, value: String) =
		PortableWifiOpaqueIdentity.derive(kind, value)

	private companion object {
		const val EPOCH = 0L
		val RECENCY_RUN_ORDER = compareBy<PortableCapturedWifiRunV1>(
			{ it.startTimeMs },
			{ it.identity.value },
		)
	}
}

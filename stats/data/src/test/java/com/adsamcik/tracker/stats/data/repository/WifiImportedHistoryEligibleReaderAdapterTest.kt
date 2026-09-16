package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductCandidate
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentPageEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRecentScan
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiIntegrity
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
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
class WifiImportedHistoryEligibleReaderAdapterTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `caller transaction pages past newer exact local duplicates to one older import`() = runTest {
		val firstDuplicate = portableEntry("local-first", 300L)
		val secondDuplicate = portableEntry("local-second", 200L)
		val accepted = portableEntry("imported-accepted", 100L)
		val evaluator = FakeRecentPageEvaluator(
			database,
			listOf(
				readable(firstDuplicate, "local-first"),
				readable(secondDuplicate, "local-second"),
				readable(accepted),
			),
			maximumRowsPerPage = 1,
		)
		val localReads = mutableListOf<String>()
		val reader = WifiImportedHistoryEligibleReaderAdapter(
			evaluator,
			localReader(
				mapOf(
					"local-first" to firstDuplicate,
					"local-second" to secondDuplicate,
				),
				localReads,
			),
			WifiImportedHistoryEligibleLimits(maximumCandidates = 4, pageSize = 2),
		)

		val page = database.withTransaction {
			reader.recentImportedEligibleForSharedHistoryInTransaction(1)
		} as ImportedHistoryEligiblePage.Available

		page.entries.single().selection shouldBe readable(accepted).candidate.selection
		page.entries.single().recency shouldBe ImportedHistoryRecency(
			HistorySource.WIFI,
			100L,
			ImportedHistoryRecencyTieIdentity(accepted.runs.single().identity.value),
		)
		evaluator.requestCount shouldBe 3
		evaluator.openCount shouldBe 1
		localReads shouldBe listOf("local-first", "local-second")
	}

	@Test
	fun `eligible recency follows authenticated newest member and opaque tie`() = runTest {
		val olderEnvelopeNewerMember = portableEntry("older-envelope", 0L, 200L)
		val newerEnvelopeOlderMember = portableEntry("newer-envelope", 100L)
		val tieLeft = portableEntry("tie-left", 400L)
		val tieRight = portableEntry("tie-right", 400L)
		val evaluator = FakeRecentPageEvaluator(
			database,
			listOf(
				readable(olderEnvelopeNewerMember),
				readable(newerEnvelopeOlderMember),
				readable(tieLeft),
				readable(tieRight),
			),
		)
		val reader = WifiImportedHistoryEligibleReaderAdapter(
			evaluator,
			localReader(emptyMap(), mutableListOf()),
			WifiImportedHistoryEligibleLimits(),
		)

		val page = database.withTransaction {
			reader.recentImportedEligibleForSharedHistoryInTransaction(4)
		} as ImportedHistoryEligiblePage.Available
		val expected = evaluator.ordered.map { it.candidate }

		page.entries.map { it.selection } shouldBe expected.map { it.selection }
		page.entries.map { it.recency.newestMemberStartTimeMs } shouldBe
			expected.map { it.newestMemberStartTimeMs }
		page.entries.map { it.recency.newestMemberTieIdentity } shouldBe expected.map {
			ImportedHistoryRecencyTieIdentity(it.newestMemberIdentity.value)
		}
		page.entries.indexOfFirst {
			it.selection == readable(olderEnvelopeNewerMember).candidate.selection
		} < page.entries.indexOfFirst {
			it.selection == readable(newerEnvelopeOlderMember).candidate.selection
		} shouldBe true
	}

	@Test
	fun `true local conflict and corrupt imported origin fail instead of being accepted`() = runTest {
		val imported = portableEntry("local-conflict", 100L)
		val conflictingLocal = portableEntry("local-conflict", 200L)
		val conflictReader = WifiImportedHistoryEligibleReaderAdapter(
			FakeRecentPageEvaluator(
				database,
				listOf(readable(imported, "local-conflict")),
			),
			localReader(mapOf("local-conflict" to conflictingLocal), mutableListOf()),
			WifiImportedHistoryEligibleLimits(),
		)

		database.withTransaction {
			conflictReader.recentImportedEligibleForSharedHistoryInTransaction(1)
		} shouldBe ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
		)

		val corruptReader = WifiImportedHistoryEligibleReaderAdapter(
			FakeRecentPageEvaluator(
				database,
				listOf(
					ImportedWifiProductEvaluation.Unverifiable(
						readable(imported).candidate,
						ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT,
					),
				),
			),
			localReader(emptyMap(), mutableListOf()),
			WifiImportedHistoryEligibleLimits(),
		)
		database.withTransaction {
			corruptReader.recentImportedEligibleForSharedHistoryInTransaction(1)
		} shouldBe ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
		)
	}

	@Test
	fun `candidate scan budget and absent recency authority are typed unavailable`() = runTest {
		val duplicateOwners = listOf(
			"budget-first" to 300L,
			"budget-second" to 200L,
			"budget-third" to 100L,
		)
		val duplicates = duplicateOwners.map { (owner, startTimeMs) ->
			portableEntry(owner, startTimeMs)
		}
		val budgetReader = WifiImportedHistoryEligibleReaderAdapter(
			FakeRecentPageEvaluator(
				database,
				duplicates.zip(duplicateOwners).map { (entry, owner) ->
					readable(entry, owner.first)
				},
				maximumRowsPerPage = 1,
			),
			localReader(
				duplicates.zip(duplicateOwners).associate { (entry, owner) ->
					owner.first to entry
				},
				mutableListOf(),
			),
			WifiImportedHistoryEligibleLimits(maximumCandidates = 2, pageSize = 1),
		)
		database.withTransaction {
			budgetReader.recentImportedEligibleForSharedHistoryInTransaction(1)
		} shouldBe ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
		)

		val missingEntry = portableEntry("missing-recency", 100L)
		val readableMissing = readable(missingEntry)
		val missing = readableMissing.copy(
			candidate = readableMissing.candidate.copy(
				newestMemberStartTimeMs = 101L,
				newestMemberIdentity = identity(
					PortableWifiIdentityKind.PHYSICAL_RUN,
					"missing-recency-unowned-run",
				),
			),
		)
		val missingReader = WifiImportedHistoryEligibleReaderAdapter(
			FakeRecentPageEvaluator(
				database,
				listOf(missing),
			),
			localReader(emptyMap(), mutableListOf()),
			WifiImportedHistoryEligibleLimits(),
		)
		database.withTransaction {
			missingReader.recentImportedEligibleForSharedHistoryInTransaction(1)
		} shouldBe ImportedHistoryEligiblePage.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
		)
	}

	private fun localReader(
		entries: Map<String, PortableCapturedWifiEntryV1>,
		reads: MutableList<String>,
	) = object : ReadLocalPortableCapturedWifi {
		override suspend fun readInTransaction(
			request: com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest,
		): ReadLocalPortableCapturedWifiResult {
			check(database.inTransaction())
			reads += request.logicalTrackingId
			return entries[request.logicalTrackingId]?.let(
				ReadLocalPortableCapturedWifiResult::Ready,
			) ?: error("Missing local portable fixture")
		}
	}

	private fun readable(
		entry: PortableCapturedWifiEntryV1,
		collidingLocalLogicalId: String? = null,
	): ImportedWifiProductEvaluation.Readable {
		val newest = entry.runs.maxWith(
			compareBy<com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1>(
				{ it.startTimeMs },
				{ it.identity.value },
			),
		)
		return ImportedWifiProductEvaluation.Readable(
			candidate = ImportedWifiProductCandidate(
				identity = entry.identity,
				importRevision = 1L,
				contentChecksum = entry.contentChecksum,
				startTimeMs = entry.startTimeMs,
				endTimeMs = entry.endTimeMs,
				receivedAtMs = entry.endTimeMs,
				newestMemberStartTimeMs = newest.startTimeMs,
				newestMemberIdentity = newest.identity,
			),
			entry = entry,
			entryDeleted = false,
			deletedRunIdentities = emptySet(),
			retainedObservationIdentities = entry.runs.flatMapTo(linkedSetOf()) { run ->
				run.observations.map { it.identity }
			},
			retentionLimited = false,
			collidingLocalLogicalTrackingId = collidingLocalLogicalId,
		)
	}

	private fun portableEntry(
		logicalId: String,
		vararg runStartTimes: Long,
	): PortableCapturedWifiEntryV1 {
		val runs = runStartTimes.mapIndexed { index, startTimeMs ->
			val observation = PortableWifiIntegrity.createObservation(
				identity = identity(PortableWifiIdentityKind.OBSERVATION, "$logicalId-observation-$index"),
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
				identity = identity(PortableWifiIdentityKind.PHYSICAL_RUN, "$logicalId-run-$index"),
				deletionScopeDigest = PortableWifiDeletionScopeDigest(
					identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "$logicalId-scope-$index").value,
				),
				startTimeMs = startTimeMs,
				endTimeMs = startTimeMs + 100L,
				storedZoneIds = listOf("Europe/Prague"),
				captureCoverage = PortableWifiCaptureCoverage.WHOLE_RUN,
				availability = PortableWifiRunAvailability.RETAINED,
				acquisitionCompleteness = PortableWifiAcquisitionCompleteness.COMPLETE,
				hasUnresolvedProviderRange = false,
				retentionLoss = false,
				observations = listOf(observation),
			)
		}.sortedWith(com.adsamcik.tracker.stats.api.repository.PORTABLE_WIFI_RUN_ORDER)
		return PortableWifiIntegrity.createEntry(
			identity = identity(PortableWifiIdentityKind.LOGICAL_ENTRY, logicalId),
			sessionMode = PortableWifiSessionMode.MANUAL,
			startTimeMs = runs.minOf { it.startTimeMs },
			endTimeMs = runs.maxOf { it.endTimeMs },
			runs = runs,
		)
	}

	private fun identity(kind: PortableWifiIdentityKind, value: String) =
		PortableWifiOpaqueIdentity.derive(kind, value)

	private class FakeRecentPageEvaluator(
		private val database: AppDatabase,
		evaluations: List<ImportedWifiProductEvaluation>,
		private val maximumRowsPerPage: Int = Int.MAX_VALUE,
	) : ImportedWifiProductRecentPageEvaluator {
		val ordered = evaluations.sortedWith(
			compareByDescending<ImportedWifiProductEvaluation> {
				it.candidate.newestMemberStartTimeMs
			}.thenByDescending { it.candidate.newestMemberIdentity.value },
		)
		var requestCount = 0
			private set
		var openCount = 0
			private set

		override suspend fun openRecentScanInTransaction(): ImportedWifiProductRecentScan {
			check(database.inTransaction())
			openCount++
			return ImportedWifiProductRecentScan { request ->
				selectRecentPageInTransaction(request)
			}
		}

		override suspend fun selectRecentPageInTransaction(
			request: ImportedWifiProductRecentRequest,
		): ImportedWifiProductRecentPage {
			check(database.inTransaction())
			requestCount++
			val remaining = ordered.filter { evaluation ->
				val start = evaluation.candidate.newestMemberStartTimeMs
				val identity = evaluation.candidate.newestMemberIdentity.value
				request.beforeNewestMemberStartTimeMs == null ||
					start < request.beforeNewestMemberStartTimeMs ||
					start == request.beforeNewestMemberStartTimeMs &&
					identity < requireNotNull(request.beforeNewestMemberIdentity).value
			}
			val selected = remaining.take(minOf(request.limit, maximumRowsPerPage))
			return ImportedWifiProductRecentPage(
				evaluations = selected,
				hasMore = selected.size < remaining.size,
			)
		}
	}
}

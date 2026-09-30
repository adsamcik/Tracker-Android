package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiRunEntity
import com.adsamcik.tracker.shared.base.database.data.PortableRadioChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.stats.api.repository.ExportPortableCapturedWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductCandidate
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluator
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangePage
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductRangeRequest
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
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
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.PortableWifiSessionMode
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifi
import com.adsamcik.tracker.stats.api.repository.ReadLocalPortableCapturedWifiResult
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryReader
import com.adsamcik.tracker.stats.api.repository.WifiDeletedHistoryResult
import com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelectionKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room detail reads with qualified-product fixtures; full import authority is covered separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedWifiHistoryDetailsRoomTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `complete stored Wi-Fi details preserve names and exact measured boundary points`() = runTest {
		val value = seedParent()
		database.importedRadioChildDao().insertWifiSession(children(value))
		val public = read(value)
		public.observations.single().networks.map { it.ssid } shouldBe listOf("Network 0", "Network 1")
		public.observations.single().networks.map { it.observedTimeMs } shouldBe listOf(100L, 112L)
	}

	@Test
	fun `Wi-Fi import without optional detail group retains its summary`() = runTest {
		val public = read(seedParent())
		public.observations.single().networks shouldBe emptyList()
		public.observations.single().observationCount shouldBe 2
	}

	@Test
	fun `a present partial Wi-Fi group cannot expose an incomplete network list`() = runTest {
		val value = seedParent()
		database.importedRadioChildDao().insertWifiSession(children(value).take(1))
		shouldThrow<IllegalStateException> { read(value) }
	}

	@Test
	fun `Wi-Fi child checksum must use its exact parent semantic revision`() = runTest {
		val value = seedParent()
		database.importedRadioChildDao().insertWifiSession(children(value).mapIndexed { index, row ->
			if (index == 0) row.copy(contentChecksum = checksum(row, 2L)) else row
		})
		shouldThrow<IllegalStateException> { read(value) }
	}

	@Test
	fun `valid Wi-Fi checksum cannot expand its parent measured interval`() = runTest {
		val value = seedParent()
		listOf(99L to 0L, 113L to 0L, 100L to 1L, 112L to 1L,
			Long.MAX_VALUE to 1L).forEach { (time, uncertainty) ->
			database.importedRadioChildDao().deleteWifiSession(value.identity.value, 1L)
			database.importedRadioChildDao().insertWifiSession(children(value).mapIndexed { index, row ->
				if (index == 0) row.copy(observedWallTimeMs = time, wallTimeUncertaintyMs = uncertainty)
					.let { it.copy(contentChecksum = checksum(it)) } else row
			})
			shouldThrow<IllegalStateException> { read(value) }
		}
	}

	@Test
	fun `Wi-Fi children of a deleted run cannot leak or break surviving observations`() = runTest {
		val value = seedParent(listOf(run("old", 10L), run("current", 30L)))
		val old = value.runs.first()
		val current = value.runs.last()
		val hidden = children(value, old).map { it.copy(observedWallTimeMs = 999L)
			.let { row -> row.copy(contentChecksum = checksum(row)) } }
		database.importedRadioChildDao().insertWifiSession(hidden + children(value, current))
		val public = read(value, setOf(old.identity))
		public.observations shouldHaveSize 1
		public.observations.single().networks.map { it.observedTimeMs } shouldBe listOf(100L, 112L)
	}

	@Test
	fun `deleted or unretained Wi-Fi detail owners do not consume the surviving read budget`() = runTest {
		val value = seedParent(listOf(run("old-large", 10L, 2_049), run("current", 30L)))
		val old = value.runs.first()
		val current = value.runs.last()
		database.importedRadioChildDao().insertWifiSession(old.observations.flatMap { children(value, old, it) } +
			children(value, current))
		val deleted = read(value, setOf(old.identity))
		deleted.observations.single().networks shouldHaveSize 2
		deleted.causes.contains(WifiHistoryCause.READ_BUDGET_EXCEEDED) shouldBe false
		val retained = evaluation(value, retained = setOf(current.observations.single().identity))
		val retentionView = database.withTransaction {
			database.withImportedWifiDetails(retained, retained.toPublicWifiEntry()) }
		retentionView.observations.single().networks shouldHaveSize 2
		retentionView.causes.contains(WifiHistoryCause.READ_BUDGET_EXCEEDED) shouldBe false
	}

	@Test
	fun `genuine Wi-Fi detail overflow preserves selection and reports the existing read budget cause`() = runTest {
		val value = seedParent(listOf(run("large-current", 10L, 2_049)))
		val run = value.runs.single()
		database.importedRadioChildDao().insertWifiSession(run.observations.flatMap { children(value, run, it) })
		val public = read(value)
		public.state shouldBe WifiHistoryProductState.FAILED
		public.causes shouldBe setOf(WifiHistoryCause.READ_BUDGET_EXCEEDED)
		public.observations shouldBe emptyList()
		public.importedSelection?.key?.value shouldBe value.identity.value
	}

	@Test
	fun `recent Wi-Fi detail read cannot convert cancellation into a returned failure page`() = runTest {
		val value = seedParent()
		val qualified = evaluation(value)
		val repository = DefaultWifiHistoryRepository(database, SourceProductLaneExecutionAuthority { false },
			object : ImportedWifiProductEvaluator {
				override suspend fun selectIdentityInTransaction(selection: WifiImportedHistorySelectionKey) = qualified
				override suspend fun selectRangeInTransaction(request: ImportedWifiProductRangeRequest) =
					ImportedWifiProductRangePage(listOf(qualified), false)
				override suspend fun selectRecentInTransaction(limit: Int): List<ImportedWifiProductEvaluation> {
					// Cancellation reaches the detail read after source qualification has completed.
					requireNotNull(currentCoroutineContext()[Job]).cancel(CancellationException("detail read cancelled"))
					return listOf(qualified)
				}
			}, object : ReadLocalPortableCapturedWifi {
				override suspend fun readInTransaction(request: ExportPortableCapturedWifiRequest): ReadLocalPortableCapturedWifiResult =
					error("No native counterpart in this imported fixture")
			}, WifiDeletedHistoryReader { WifiDeletedHistoryResult.NotDeleted },
			BootClockDomainProvider { "boot-details" }, Dispatchers.Unconfined)
		var returnedPage = false
		val cancelled = async {
			database.withTransaction {
				repository.recentInTransaction(1)
				returnedPage = true
			}
		}
		shouldThrow<CancellationException> { cancelled.await() }
		returnedPage shouldBe false
	}

	private suspend fun read(value: PortableCapturedWifiEntryV1,
		deleted: Set<PortableWifiOpaqueIdentity> = emptySet()): WifiHistoryEntry {
		val evaluation = evaluation(value, deleted)
		return database.withTransaction {
			database.withImportedWifiDetails(evaluation, evaluation.toPublicWifiEntry())
		}
	}

	private fun evaluation(value: PortableCapturedWifiEntryV1,
		deleted: Set<PortableWifiOpaqueIdentity> = emptySet(),
		retained: Set<PortableWifiOpaqueIdentity> = value.runs.flatMapTo(linkedSetOf()) { item ->
			item.observations.map { it.identity } }): ImportedWifiProductEvaluation.Readable {
		val newest = value.runs.last()
		return ImportedWifiProductEvaluation.Readable(
			candidate = ImportedWifiProductCandidate(value.identity, 1L, value.contentChecksum,
				value.startTimeMs, value.endTimeMs, 500L, newest.startTimeMs, newest.identity),
			entry = value,
			entryDeleted = false,
			deletedRunIdentities = deleted,
			retainedObservationIdentities = retained,
			retentionLimited = retained.size < value.runs.sumOf { it.observations.size },
			collidingLocalLogicalTrackingId = null,
		)
	}

	/** Seed qualified portable parents through real FK-enforced DAOs, not a fake child reader. */
	private suspend fun seedParent(runs: List<PortableCapturedWifiRunV1> = listOf(run("run", 10L)))
		: PortableCapturedWifiEntryV1 {
		val value = PortableWifiIntegrity.createEntry(
			identity(PortableWifiIdentityKind.LOGICAL_ENTRY, "wifi-details"), PortableWifiSessionMode.MANUAL,
			runs.first().startTimeMs, runs.last().endTimeMs, runs,
		)
		database.withTransaction {
			val dao = database.importedWifiDao()
			dao.insertEntryRevision(ImportedWifiEntryRevisionEntity(
				value.identity.value, 1L, null, value.contentChecksum.value,
				ImportedWifiEntryRevisionEntity.SOURCE_FORMAT, 1, value.sessionMode.name,
				value.startTimeMs, value.endTimeMs, 0L, "job-details", "entry-details", "wifi.trackerwifi", 500L,
			))
			value.runs.forEach { item ->
				dao.insertRun(ImportedWifiRunEntity(value.identity.value, 1L, item.identity.value,
					item.deletionScopeDigest.value, item.contentChecksum.value, item.startTimeMs, item.endTimeMs,
					item.captureCoverage.name, item.availability.name, item.acquisitionCompleteness.name,
					item.hasUnresolvedProviderRange, item.retentionLoss, 0L, 0L))
				item.observations.forEach { observation ->
					dao.insertObservation(ImportedWifiObservationEntity(
						entryIdentity = value.identity.value, entryImportRevision = 1L,
						runIdentity = item.identity.value, identity = observation.identity.value,
						semanticRevision = observation.semanticRevision, supersedesSemanticRevision = null,
						aggregateOwnerIdentity = null, aggregateOwnerSemanticRevision = null,
						contentChecksum = observation.contentChecksum.value,
						coverageStartTimeMs = observation.coverageStartTimeMs,
						observedTimeMs = observation.observedTimeMs,
						latestPossibleTimeMs = observation.latestPossibleTimeMs,
						wallTimeUncertaintyMs = observation.wallTimeUncertaintyMs,
						storedZoneId = observation.storedZoneId, availability = observation.availability.name,
						resultCompleteness = observation.resultCompleteness.name,
						submittedResultCount = observation.submittedResultCount,
						acceptedResultCount = observation.acceptedResultCount,
						staleResultCount = observation.staleResultCount,
						clockUnverifiableResultCount = observation.clockUnverifiableResultCount,
						malformedResultCount = observation.malformedResultCount,
						observationCount = observation.observationCount,
						twoPointFourGhzCount = observation.twoPointFourGhzCount,
						fiveGhzCount = observation.fiveGhzCount, sixGhzCount = observation.sixGhzCount,
						otherBandCount = observation.otherBandCount,
						strongestSignalDbm = observation.strongestSignalDbm,
						weakestSignalDbm = observation.weakestSignalDbm, meanSignalDbm = observation.meanSignalDbm,
						sourceQualityFlags = observation.sourceQualityFlags,
						sourceQualityConfidence = observation.sourceQualityConfidence,
					))
				}
			}
		}
		return value
	}

	private fun run(seed: String, start: Long, observationCount: Int = 1): PortableCapturedWifiRunV1 {
		val observations = (0 until observationCount).map { ordinal -> PortableWifiIntegrity.createObservation(
			identity = identity(PortableWifiIdentityKind.OBSERVATION, "$seed-$ordinal-observation"),
			semanticRevision = 1L, supersedesSemanticRevision = null,
			aggregateOwnerIdentity = null, aggregateOwnerSemanticRevision = null,
			coverageStartTimeMs = 100L, observedTimeMs = 110L, latestPossibleTimeMs = 112L,
			wallTimeUncertaintyMs = 2L, storedZoneId = "UTC", availability = PortableWifiAvailability.AVAILABLE,
			resultCompleteness = PortableWifiResultCompleteness.COMPLETE,
			submittedResultCount = 2, acceptedResultCount = 2, staleResultCount = 0,
			clockUnverifiableResultCount = 0, malformedResultCount = 0, observationCount = 2,
			twoPointFourGhzCount = 1, fiveGhzCount = 1, sixGhzCount = 0, otherBandCount = 0,
			strongestSignalDbm = -50, weakestSignalDbm = -70, meanSignalDbm = -60.0,
			sourceQualityFlags = 0L, sourceQualityConfidence = 1f,
		) }.sortedBy { it.identity.value }
		return PortableWifiIntegrity.createRun(
			identity(PortableWifiIdentityKind.PHYSICAL_RUN, seed),
			PortableWifiDeletionScopeDigest(identity(PortableWifiIdentityKind.PHYSICAL_RUN, "$seed-scope").value),
			start, start + 10L, listOf("UTC"), PortableWifiCaptureCoverage.WHOLE_RUN,
			PortableWifiRunAvailability.RETAINED, PortableWifiAcquisitionCompleteness.COMPLETE,
			false, false, observations,
		)
	}

	private fun children(value: PortableCapturedWifiEntryV1,
		run: PortableCapturedWifiRunV1 = value.runs.single(),
		observation: PortableCapturedWifiObservationV1 = run.observations.single()): List<ImportedWifiChildEntity> =
		listOf(100L, 112L).mapIndexed { index, time ->
			ImportedWifiChildEntity(value.identity.value, 1L, run.identity.value,
				observation.identity.value, index, 1_000L + index, time, 0L,
				"00:00:00:00:00:0$index", "Network $index", if (index == 0) 2_412 else 5_180,
				if (index == 0) -50 else -70, "").let { it.copy(contentChecksum = checksum(it)) }
		}

	private fun checksum(row: ImportedWifiChildEntity, revision: Long = 1L) =
		PortableRadioChildIntegrity.digest("wifi", listOf(row.entryIdentity, row.runIdentity,
			row.observationIdentity), listOf(revision, row.itemIndex, row.providerTimestampNanos,
			row.observedWallTimeMs, row.wallTimeUncertaintyMs, row.bssid, row.ssid,
			row.frequencyMhz, row.signalDbm))

	private fun identity(kind: PortableWifiIdentityKind, seed: String) = PortableWifiOpaqueIdentity.derive(kind, seed)
}

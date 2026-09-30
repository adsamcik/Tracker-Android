package com.adsamcik.tracker.impexp.exporter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exact Room detail-query budgets, not a substitute for qualified whole-file export coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioBackgroundExportBudgetRoomTest {
	private lateinit var database: AppDatabase

	@Before fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}

	@After fun close() { database.close() }

	@Test fun `wifi overflow shrinks the next query and never reads a later owner`() = runTest {
		val owners = listOf(seedWifi('1', 2), seedWifi('2', 2), seedWifi('3', 2))
		val queries = mutableListOf<Pair<String, Int>>()
		assertFailsWith<IllegalArgumentException> {
			readBoundedBackgroundRadioChildren(owners, maximumRows = 3) { fact, revision, limit ->
				queries += fact to limit
				database.importedRadioChildDao().wifiBackgroundChildrenForRevision(fact, revision, limit)
			}
		}
		assertEquals(listOf(owners[0].first to 4, owners[1].first to 2), queries)
		assertEquals(2, database.importedRadioChildDao()
			.wifiBackgroundChildrenForRevision(owners[2].first, 1, 3).size)
	}

	@Test fun `cell overflow fails before accumulating excess or reading a later owner`() = runTest {
		val owners = listOf(seedCell('1', 2), seedCell('2', 2), seedCell('3', 2))
		val queries = mutableListOf<Pair<String, Int>>()
		assertFailsWith<IllegalArgumentException> {
			readBoundedBackgroundRadioChildren(owners, maximumRows = 3) { fact, revision, limit ->
				queries += fact to limit
				database.importedRadioChildDao().cellBackgroundChildrenForRevision(fact, revision, limit)
			}
		}
		assertEquals(listOf(owners[0].first to 4, owners[1].first to 2), queries)
		assertEquals(2, database.importedRadioChildDao()
			.cellBackgroundChildrenForRevision(owners[2].first, 1, 3).size)
	}

	@Test fun `second storage group receives only the rows left by the first group`() = runTest {
		val firstOwner = seedWifi('1', 2)
		val secondOwner = seedWifi('2', 2)
		val firstRows = readBoundedBackgroundRadioChildren(listOf(firstOwner), maximumRows = 3) {
			fact, revision, limit ->
			database.importedRadioChildDao().wifiBackgroundChildrenForRevision(fact, revision, limit)
		}
		val secondLimits = mutableListOf<Int>()
		assertFailsWith<IllegalArgumentException> {
			readBoundedBackgroundRadioChildren(listOf(secondOwner), maximumRows = 3 - firstRows.size) {
				fact, revision, limit ->
				secondLimits += limit
				database.importedRadioChildDao().wifiBackgroundChildrenForRevision(fact, revision, limit)
			}
		}
		assertEquals(2, firstRows.size)
		assertEquals(listOf(2), secondLimits)
	}

	@Test fun `exact full budget still permits summary-only owners but rejects a later detail`() = runTest {
		val detailed = seedCell('1', 2)
		val summaryOnly = seedCell('2', 0)
		val laterDetail = seedCell('3', 1)
		val limits = mutableListOf<Int>()
		val rows = readBoundedBackgroundRadioChildren(listOf(detailed, summaryOnly), maximumRows = 2) {
			fact, revision, limit ->
			limits += limit
			database.importedRadioChildDao().cellBackgroundChildrenForRevision(fact, revision, limit)
		}
		assertEquals(2, rows.size)
		assertEquals(listOf(3, 1), limits)
		assertFailsWith<IllegalArgumentException> {
			readBoundedBackgroundRadioChildren(listOf(laterDetail), maximumRows = 0) {
				fact, revision, limit ->
				assertEquals(1, limit)
				database.importedRadioChildDao().cellBackgroundChildrenForRevision(fact, revision, limit)
			}
		}
	}

	@Test fun `selected wifi revision is not crowded out by an older revision`() = runTest {
		seedWifi('1', 5, revision = 1)
		val selected = seedWifi('1', 1, revision = 2)
		val rows = readBoundedBackgroundRadioChildren(listOf(selected), maximumRows = 1) {
			fact, revision, limit ->
			database.importedRadioChildDao().wifiBackgroundChildrenForRevision(fact, revision, limit)
		}
		assertEquals(listOf(2L), rows.map { it.semanticRevision })
		assertEquals(listOf("revision-2"), rows.map { it.ssid })
	}

	@Test fun `cancellation after a cell query stops before another owner and preserves saved data`() = runTest {
		val owners = listOf(seedCell('1', 1), seedCell('2', 1))
		val queried = mutableListOf<String>()
		val attempt = async {
			readBoundedBackgroundRadioChildren(owners, maximumRows = 2) { fact, revision, limit ->
				queried += fact
				val rows = database.importedRadioChildDao()
					.cellBackgroundChildrenForRevision(fact, revision, limit)
				currentCoroutineContext().cancel()
				rows
			}
		}
		assertFailsWith<CancellationException> { attempt.await() }
		assertEquals(listOf(owners[0].first), queried)
		assertEquals(1, database.importedRadioChildDao()
			.cellBackgroundChildrenForRevision(owners[0].first, 1, 2).size)
		assertEquals(1, database.importedRadioChildDao()
			.cellBackgroundChildrenForRevision(owners[1].first, 1, 2).size)
	}

	private suspend fun seedWifi(id: Char, detailCount: Int, revision: Long = 1): Pair<String, Long> {
		val factId = id.toString().repeat(64)
		val observationCount = detailCount.coerceAtLeast(1)
		database.ambientWifiFactDao().insertImportedFact(ImportedAmbientWifiFactEntity(
			archiveId = ARCHIVE,
			factId = factId,
			semanticRevision = revision,
			supersedesSemanticRevision = (revision - 1).takeIf { revision > 1 },
			contentChecksum = CHECKSUM,
			portableEffectChecksum = CHECKSUM,
			portableOrigin = "PORTABLE_IMPORT",
			coverageStartTimeMs = 1_000,
			observedTimeMs = 2_000,
			latestPossibleTimeMs = 2_000,
			storedZoneId = "UTC",
			structuralEpochDay = 0,
			coverageCompleteness = "COMPLETE",
			observationCount = observationCount,
			twoPointFourGhzCount = observationCount,
			fiveGhzCount = 0,
			sixGhzCount = 0,
			otherBandCount = 0,
			strongestSignalDbm = -40,
			weakestSignalDbm = -40,
			meanSignalDbm = -40.0,
			retentionPolicyId = "policy",
			retentionApprovalRevision = 1,
			collectedDataEpoch = 0,
			importDeletionGeneration = 0,
			receivedAtMs = 3_000,
		))
		database.importedRadioChildDao().insertWifiBackground((0 until detailCount).map { index ->
			ImportedAmbientWifiChildEntity(ARCHIVE, factId, revision, index, 2_000_000_000,
				2_000, 0, "00:11:22:33:44:55", "revision-$revision", 2_412, -40, CHECKSUM)
		})
		return factId to revision
	}

	private suspend fun seedCell(id: Char, detailCount: Int): Pair<String, Long> {
		val factId = id.toString().repeat(64)
		val observationCount = detailCount.coerceAtLeast(1)
		database.ambientCellFactDao().insertImportedFact(ImportedAmbientCellFactEntity(
			archiveId = ARCHIVE,
			factId = factId,
			semanticRevision = 1,
			supersedesSemanticRevision = null,
			contentChecksum = CHECKSUM,
			portableEffectChecksum = CHECKSUM,
			portableOrigin = "PORTABLE_IMPORT",
			coverageStartTimeMs = 1_000,
			observedTimeMs = 2_000,
			latestPossibleTimeMs = 2_000,
			storedZoneId = "UTC",
			structuralEpochDay = 0,
			coverageCompleteness = "COMPLETE",
			subscriptionCompleteness = "COMPLETE",
			observationCount = observationCount,
			registeredObservationCount = observationCount,
			gsmCount = observationCount,
			cdmaCount = 0,
			wcdmaCount = 0,
			tdscdmaCount = 0,
			lteCount = 0,
			nrCount = 0,
			qualityUnknownCount = observationCount,
			qualityNoneOrUnknownCount = 0,
			qualityPoorCount = 0,
			qualityModerateCount = 0,
			qualityGoodCount = 0,
			qualityGreatCount = 0,
			retentionPolicyId = "policy",
			retentionApprovalRevision = 1,
			collectedDataEpoch = 0,
			importDeletionGeneration = 0,
			receivedAtMs = 3_000,
		))
		database.importedRadioChildDao().insertCellBackground((0 until detailCount).map { index ->
			ImportedAmbientCellChildEntity(ARCHIVE, factId, 1, index, 2_000_000_000,
				2_000, 0, "tower-$index", "gsm", true, -40, CHECKSUM)
		})
		return factId to 1L
	}

	private companion object {
		val ARCHIVE = "a".repeat(64)
		val CHECKSUM = "b".repeat(64)
	}
}

package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AmbientCellDeletionResult
import com.adsamcik.tracker.shared.base.database.AmbientCellRetentionCommand
import com.adsamcik.tracker.shared.base.database.AmbientCellRetentionResult
import com.adsamcik.tracker.shared.base.database.AmbientCellMaintenanceUnavailableReason
import com.adsamcik.tracker.shared.base.database.AmbientWifiDeletionResult
import com.adsamcik.tracker.shared.base.database.AmbientWifiRetentionCommand
import com.adsamcik.tracker.shared.base.database.AmbientWifiRetentionResult
import com.adsamcik.tracker.shared.base.database.AmbientWifiMaintenanceUnavailableReason
import com.adsamcik.tracker.shared.base.database.clearAmbientCellProductPreservingReplayFootprints
import com.adsamcik.tracker.shared.base.database.clearAmbientWifiProductPreservingReplayFootprints
import com.adsamcik.tracker.shared.base.database.deleteAmbientCellAfterConsentReset
import com.adsamcik.tracker.shared.base.database.deleteAmbientWifiAfterConsentReset
import com.adsamcik.tracker.shared.base.database.fenceSourcePurposesInTransaction
import com.adsamcik.tracker.shared.base.database.pruneAmbientCell
import com.adsamcik.tracker.shared.base.database.pruneAmbientWifi
import com.adsamcik.tracker.shared.base.database.rotateCurrentSourceAuthorizationInTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.stats.api.repository.RadioContextPurpose
import com.adsamcik.tracker.stats.api.repository.RadioContextSource
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextReadResult
import com.adsamcik.tracker.stats.api.repository.RadioLocationContextRequest
import kotlin.test.assertTrue
import com.adsamcik.tracker.stats.api.repository.HistoryBrowseMode
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.deleteAllCollectedDataForTesting
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellChildChecksumInput
import com.adsamcik.tracker.shared.base.database.data.RadioChildOwner
import com.adsamcik.tracker.shared.base.database.data.RadioChildPurpose
import com.adsamcik.tracker.shared.base.database.data.RadioObservationChildIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.WifiChildChecksumInput
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRead
import com.adsamcik.tracker.stats.api.repository.AllDaySourceStatusRequest
import com.adsamcik.tracker.stats.api.repository.AllDayReadingTime
import com.adsamcik.tracker.stats.api.repository.HistoryObservationWindow
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.AmbientCellDayReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadResult
import com.adsamcik.tracker.stats.api.repository.AmbientWifiDayReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room current-fact and child joins, rather than a duplicate of the reader predicate. */
// One paired radio Room fixture keeps saved detail ownership consistent across readers and cleanup.
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomAmbientRadioDetailCompletenessTest {
	private lateinit var database: AppDatabase
	private val dispatcher = UnconfinedTestDispatcher()

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()


    @Test fun `canonical Wi-Fi physical keys are ASCII while saved network names remain Unicode`() = runTest {
        val wifi = insertWifi(true)
        val child = database.radioObservationChildDao().wifiCurrentBackgroundChildrenForFactIds(listOf(wifi.logicalFactId), 3).first()
        val name = "Kavárna 東京 📶"
        val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, child.writerId, child.writerVersion,
            child.logicalFactId, child.semanticRevision, child.itemIndex)
        val checksum = RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(owner, child.providerTimestampNanos,
            child.observedWallTimeMs, child.wallTimeUncertaintyMs, child.bssid, name, child.frequencyMhz, child.signalDbm))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ambient_wifi_child SET ssid = ?, content_checksum = ? WHERE logical_fact_id = ? AND item_index = ?",
            arrayOf<Any?>(name, checksum, child.logicalFactId, child.itemIndex))
        val range = measuredRadioRange(0L, 3_000L).copy(sources = setOf(HistorySource.WIFI),
            origins = setOf(HistoryOrigin.LOCAL), purposes = setOf(HistoryPurpose.BACKGROUND))
        database.withTransaction {
            val reader = measuredRadioReader()
            val heads = reader.candidates(HistorySource.WIFI, range, null)
            assertEquals(2, heads.size)
            assertTrue(heads.all { requireNotNull(it.physicalIdentity).all { c -> c.code < 128 } })
            val read = reader.qualify(HistorySource.WIFI, heads, range, measuredCapturedRadioFixture(database))
            assertEquals(2, read.rows.size); assertTrue(read.problems.isEmpty())
            assertEquals(name, assertIs<HistoryMeasurement.Wifi>(read.rows.values.single {
                it.position.startTimeMs == child.observedWallTimeMs }.measurement).network.ssid)
        }
    }

    @Test fun `canonical counterpart SQL failure preserves an already qualified released Wi-Fi reading`() = runTest {
        insertWifi(true)
        database.withTransaction { insertLegacyWifiForMeasuredHistory(901L, 2_100L) }
        val reader = measuredRadioReader()
        val range = measuredRadioRange(0L, 3_000L).copy(sources = setOf(HistorySource.WIFI),
            origins = setOf(HistoryOrigin.LOCAL, HistoryOrigin.LEGACY))
        val heads = reader.candidates(HistorySource.WIFI, range, null)
        assertEquals(3, heads.size)
        // The captured fact table has no rows in this disposable fixture; real counterpart SQL must fail.
        database.openHelper.writableDatabase.execSQL("DROP TABLE wifi_captured_fact_revision")
        val read = database.withTransaction {
            reader.qualify(HistorySource.WIFI, heads, range, measuredCapturedRadioFixture(database)) }
        assertEquals(1, read.rows.size)
        assertEquals(setOf(HistoryOrigin.LEGACY), read.rows.values.single().origins)
        assertEquals(2_100L, read.rows.values.single().position.startTimeMs)
        assertTrue(read.problems.contains(HistoryProblem(HistorySource.WIFI, HistoryProblemReason.STORAGE_UNAVAILABLE)))
    }

    @Test fun `canonical blank original-event Cell row advances without hiding valid sibling leaves`() = runTest {
        val old = insertCell(true)
        val newer = appendNewerCellGroup(old)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ambient_cell_fact_revision SET source_event_id = '' WHERE logical_fact_id = ?", arrayOf<Any?>(old.logicalFactId))
        val range = measuredRadioRange(0L, 4_000L).copy(sources = setOf(HistorySource.CELL),
            origins = setOf(HistoryOrigin.LOCAL), purposes = setOf(HistoryPurpose.BACKGROUND))
        database.withTransaction {
            val reader = measuredRadioReader()
            val heads = reader.candidates(HistorySource.CELL, range, null)
            assertEquals(4, heads.size)
            assertEquals(2, heads.count { it.physicalEventId == "" })
            val read = reader.qualify(HistorySource.CELL, heads, range, measuredCapturedRadioFixture(database))
            assertEquals(setOf(2_600L, 2_700L), read.rows.values.map { it.position.startTimeMs }.toSet())
            assertTrue(read.rows.keys.all { position -> heads.single { measuredHistoryPosition(it) == position }.factId == newer.logicalFactId })
            assertTrue(read.problems.contains(HistoryProblem(HistorySource.CELL, HistoryProblemReason.INTEGRITY_UNAVAILABLE)))
            val next = reader.candidates(HistorySource.CELL, range, measuredHistoryPosition(heads.last()))
            assertTrue(next.isEmpty())
        }
    }

	@Test fun `exact selected background parents retain the complete qualified network and tower set`() = runTest {
		val offset = 86_400_000L - 1_950L
		val wifi = insertWifi(true, wallOffsetMs = offset)
		val cell = insertCell(true, wallOffsetMs = offset)
		database.withTransaction {
			val w = RoomAmbientWifiRepository(database, dispatcher).readSelectedInTransaction(
				listOf(wifi.logicalFactId), emptyList(), emptyList(), emptyList())
			val c = RoomAmbientCellRepository(database, dispatcher).readSelectedInTransaction(
				listOf(cell.logicalFactId), emptyList(), emptyList(), emptyList())
			assertEquals(wifi, w.nativeRows.single().fact); assertEquals(cell, c.nativeRows.single().fact)
			assertEquals(listOf(86_399_950L, 86_400_050L), assertIs<AmbientWifiReadResult.Snapshot>(w.result).facts.single().networks.map { it.observedTimeMs })
			assertEquals(listOf(86_399_950L, 86_400_050L), assertIs<AmbientCellReadResult.Snapshot>(c.result).facts.single().towers.map { it.observedTimeMs })
		}
		assertEquals(wifi, database.ambientWifiFactDao().revision(wifi.writerId, wifi.writerVersion, wifi.logicalFactId, 1L))
		assertEquals(cell, database.ambientCellFactDao().revision(cell.writerId, cell.writerVersion, cell.logicalFactId, 1L))
	}


	@Test fun `selected background radio read still rejects a missing mandatory child set`() = runTest {
		val wifi = insertWifi(true); val cell = insertCell(true)
		database.openHelper.writableDatabase.execSQL("DELETE FROM ambient_wifi_child WHERE item_index = 1")
		database.withTransaction {
			assertEquals(AmbientWifiReadResult.StorageUnavailable, RoomAmbientWifiRepository(database, dispatcher)
				.readSelectedInTransaction(listOf(wifi.logicalFactId), emptyList(), emptyList(), emptyList()).result)
			assertEquals(cell.logicalFactId, assertIs<AmbientCellReadResult.Snapshot>(RoomAmbientCellRepository(database, dispatcher)
				.readSelectedInTransaction(listOf(cell.logicalFactId), emptyList(), emptyList(), emptyList()).result).facts.single().identity)
		}
	}

	@Test fun `measured radio discovery uses each overnight child time rather than delivery day`() = runTest {
		val offset = 86_400_000L - 1_950L
		val wifi = insertWifi(true, wallOffsetMs = offset, storedZoneId = "America/Los_Angeles")
		val cell = insertCell(true, wallOffsetMs = offset, storedZoneId = "America/Los_Angeles")
		val dao = database.radioObservationChildDao()
		for (expected in listOf(
			listOf(86_399_000L, 86_400_000L, 86_399_950L, 0L),
			listOf(86_400_000L, 86_401_000L, 86_400_050L, 1L))) {
			val from = expected[0]
			val to = expected[1]
			val time = expected[2]
			val index = expected[3]
			val w = dao.wifiMeasuredHistoryCandidates(from, to, listOf("LOCAL"), listOf("BACKGROUND"), null, null, null, 101).single()
			val c = dao.cellMeasuredHistoryCandidates(from, to, listOf("LOCAL"), listOf("BACKGROUND"), null, null, null, 101).single()
			assertEquals("ambient_wifi", w.kind); assertEquals("ambient_cell", c.kind)
			assertEquals(time, w.startTimeMs); assertEquals(time, c.startTimeMs)
			assertEquals(index.toInt(), w.itemIndex); assertEquals(index.toInt(), c.itemIndex)
			assertEquals(wifi.logicalFactId, w.factId); assertEquals(cell.logicalFactId, c.factId)
			assertEquals(null, w.endTimeMs); assertEquals(null, c.endTimeMs)
		}
	}

	@Test fun `radio keyset discovery makes progress beyond one hundred same-time released readings`() = runTest {
		database.withTransaction {
			for (id in 1L..150L) insertLegacyWifiForMeasuredHistory(id, 2_000L)
		}
		val dao = database.radioObservationChildDao()
		val first = dao.wifiMeasuredHistoryCandidates(0L, 3_000L, listOf("LEGACY"), listOf("UNKNOWN"), null, null, null, 101)
		assertEquals(101, first.size)
		val consumed = measuredHistoryPosition(first[99])
		val second = dao.wifiMeasuredHistoryCandidates(0L, 3_000L, listOf("LEGACY"), listOf("UNKNOWN"),
			consumed.startTimeMs, consumed.originTag, consumed.stableId, 101)
		assertEquals(50, second.size)
		val all = first.take(100) + second
		assertEquals((1L..150L).toSet(), all.map { it.rowId }.toSet())
		assertEquals(150, all.map { measuredHistoryPosition(it) }.distinct().size)
		assertTrue(all.zipWithNext().all { (a, b) -> measuredHistoryPosition(a) < measuredHistoryPosition(b) })
		assertTrue(all.all { it.purpose == "UNKNOWN" && it.origin == "LEGACY" && it.endTimeMs == null })
	}

	@Test fun `radio discovery filters origins and purpose without manufacturing modern legacy ownership`() = runTest {
		insertWifi(true); insertCell(true)
		database.withTransaction { insertLegacyWifiForMeasuredHistory(1L, 2_000L) }
		val dao = database.radioObservationChildDao()
		assertTrue(dao.wifiMeasuredHistoryCandidates(0L, 3_000L, listOf("LEGACY"), listOf("CAPTURE", "BACKGROUND"), null, null, null, 101).isEmpty())
		assertEquals(1L, dao.wifiMeasuredHistoryCandidates(2_000L, 2_001L, listOf("LEGACY"), listOf("UNKNOWN"), null, null, null, 101).single().rowId)
		assertEquals(2, dao.wifiMeasuredHistoryCandidates(0L, 3_000L, listOf("LOCAL"), listOf("BACKGROUND"), null, null, null, 101).size)
		assertEquals(2, dao.cellMeasuredHistoryCandidates(0L, 3_000L, listOf("LOCAL"), listOf("BACKGROUND"), null, null, null, 101).size)
		assertTrue(dao.wifiMeasuredHistoryCandidates(0L, 2_000L, listOf("LEGACY"), listOf("UNKNOWN"), null, null, null, 101).isEmpty())
	}

	private fun insertLegacyWifiForMeasuredHistory(id: Long, timeMs: Long) {
		database.openHelper.writableDatabase.execSQL(
			"INSERT INTO legacy_wifi_child (legacy_row_id,time_ms,bssid,ssid,capabilities,frequency,level,provenance,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
			arrayOf<Any?>(id, timeMs, "aa:bb:cc:dd:ee:ff", "Saved network", "", 2_412, -55, "LEGACY_RELEASED", timeMs))
	}

	@Test fun `measured radio qualifier preserves each authorized overnight leaf on its actual day`() = runTest {
		val offset = 86_400_000L - 1_950L
		insertWifi(true, wallOffsetMs = offset, storedZoneId = "America/Los_Angeles")
		insertCell(true, wallOffsetMs = offset, storedZoneId = "America/Los_Angeles")
		val reader = measuredRadioReader()
		data class ExpectedLeaf(val from: Long, val to: Long, val observed: Long, val name: String)
		for (expected in listOf(
			ExpectedLeaf(86_399_000L, 86_400_000L, 86_399_950L, "Home"),
			ExpectedLeaf(86_400_000L, 86_401_000L, 86_400_050L, "Cafe"))) {
			val from = expected.from
			val to = expected.to
			val observed = expected.observed
			val name = expected.name
			val range = measuredRadioRange(from, to)
			for (source in listOf(HistorySource.WIFI, HistorySource.CELL)) database.withTransaction {
				val keys = reader.candidates(source, range, null)
				val result = reader.qualifyBackgroundAndLegacy(source, keys, range)
				assertTrue(result.problems.isEmpty())
				val row = result.rows.values.single()
				assertEquals(observed, row.position.startTimeMs)
				assertEquals(HistoryPurpose.BACKGROUND, row.purpose)
				assertEquals(setOf(HistoryOrigin.LOCAL), row.origins)
				assertEquals(null, row.toExclusiveMs)
				if (source == HistorySource.WIFI) {
					assertEquals(name,
					assertIs<HistoryMeasurement.Wifi>(row.measurement).network.ssid)
				}
				else {
					assertEquals(observed, assertIs<HistoryMeasurement.Cell>(row.measurement).tower.observedTimeMs)
				}
			}
		}
	}

	@Test fun `old summary keeps its real measured bounds and inclusive last measurement selection`() = runTest {
		insertWifi(false); insertCell(false)
		val reader = measuredRadioReader()
		val range = measuredRadioRange(2_000L, 2_001L)
		for (source in listOf(HistorySource.WIFI, HistorySource.CELL)) database.withTransaction {
			val keys = reader.candidates(source, range, null)
			assertEquals(1, keys.size)
			val result = reader.qualifyBackgroundAndLegacy(source, keys, range)
			assertTrue(result.problems.isEmpty())
			val row = result.rows.values.single()
			assertEquals(1_899L, row.position.startTimeMs)
			assertEquals(2_000L, row.toExclusiveMs)
			assertEquals(HistoryPurpose.BACKGROUND, row.purpose)
			if (source == HistorySource.WIFI) {
				assertEquals(2,
				assertIs<HistoryMeasurement.BackgroundWifiSummary>(row.measurement).fact.observationCount)
			}
			else {
				assertEquals(2, assertIs<HistoryMeasurement.BackgroundCellSummary>(row.measurement).fact.observationCount)
			}
		}
	}

	@Test fun `one damaged radio parent leaves independently qualified sibling leaves visible`() = runTest {
		val wifi = insertWifi(true); insertCell(true)
		val newer = appendNewerWifiGroup(wifi)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_wifi_child SET content_checksum = ? WHERE logical_fact_id = ? AND item_index = 0",
			arrayOf<Any?>("0".repeat(64), wifi.logicalFactId))
		val reader = measuredRadioReader()
		val range = measuredRadioRange(0L, 4_000L)
		database.withTransaction {
			val keys = reader.candidates(HistorySource.WIFI, range, null)
			assertEquals(4, keys.size)
			val result = reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, keys, range)
			assertEquals(setOf(2_600L, 2_700L), result.rows.values.map { it.position.startTimeMs }.toSet())
			assertTrue(result.rows.keys.all { key -> keys.single { measuredHistoryPosition(it) == key }.factId == newer.logicalFactId })
			assertTrue(result.problems.any { it.source == HistorySource.WIFI })
			val sibling = reader.qualifyBackgroundAndLegacy(HistorySource.CELL,
				reader.candidates(HistorySource.CELL, range, null), range)
			assertEquals(2, sibling.rows.size); assertTrue(sibling.problems.isEmpty())
		}
	}

	@Test fun `dense released Wi-Fi reads retain every saved leaf through bounded qualification pages`() = runTest {
		database.sourceEvidenceStateDao().ensure()
		database.withTransaction { for (id in 1L..150L) insertLegacyWifiForMeasuredHistory(id, 2_000L) }
		val reader = measuredRadioReader()
		val range = measuredRadioRange(0L, 3_000L).copy(origins = setOf(HistoryOrigin.LEGACY), purposes = setOf(HistoryPurpose.UNKNOWN))
		val first = reader.candidates(HistorySource.WIFI, range, null)
		val a = database.withTransaction { reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, first.take(100), range) }
		val second = reader.candidates(HistorySource.WIFI, range, measuredHistoryPosition(first[99]))
		val b = database.withTransaction { reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, second, range) }
		assertEquals(100, a.rows.size); assertEquals(50, b.rows.size)
		assertTrue(a.problems.isEmpty()); assertTrue(b.problems.isEmpty())
		val all = a.rows.values + b.rows.values
		assertEquals(150, all.map { it.position }.distinct().size)
		assertTrue(all.all { it.purpose == HistoryPurpose.UNKNOWN && it.name == null && it.toExclusiveMs == null })
		assertTrue(all.all { assertIs<HistoryMeasurement.LegacyWifi>(it.measurement).ssid == "Saved network" })
	}

	@Test fun `released storage failure preserves independently readable background leaves`() = runTest {
		insertWifi(true)
		database.withTransaction { insertLegacyWifiForMeasuredHistory(1L, 2_000L) }
		val reader = measuredRadioReader()
		val range = measuredRadioRange(0L, 3_000L)
		val keys = reader.candidates(HistorySource.WIFI, range, null)
		assertEquals(3, keys.size)
		// Real missing-table fault after key discovery, scoped to this disposable Room database.
		database.openHelper.writableDatabase.execSQL("DROP TABLE legacy_wifi_child")
		val result = database.withTransaction { reader.qualifyBackgroundAndLegacy(HistorySource.WIFI, keys, range) }
		assertEquals(setOf(1_900L, 2_000L), result.rows.values.map { it.position.startTimeMs }.toSet())
		assertTrue(result.problems.contains(HistoryProblem(HistorySource.WIFI, HistoryProblemReason.STORAGE_UNAVAILABLE)))
	}

	private fun measuredRadioReader() = MeasuredRadioHistoryReader(database,
		RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher))
	private fun measuredRadioRange(from: Long, to: Long) = HistoryRange(from, to, "UTC",
		sources = setOf(HistorySource.WIFI, HistorySource.CELL),
		purposes = setOf(HistoryPurpose.BACKGROUND, HistoryPurpose.UNKNOWN), mode = HistoryBrowseMode.OBSERVATIONS)

	@Test fun `paired radio consumers reuse the same accepted native facts and exact identities`() = runTest {
		val wifi = insertWifi(true); val cell = insertCell(true)
		database.withTransaction {
			val wifiRead = RoomAmbientWifiRepository(database, dispatcher).readQualifiedInTransaction(wifiWindow())
			val cellRead = RoomAmbientCellRepository(database, dispatcher).readQualifiedInTransaction(cellWindow())
			assertEquals(wifi, wifiRead.nativeRows.single().fact)
			assertEquals(cell, cellRead.nativeRows.single().fact)
			assertEquals(wifi.logicalFactId, assertIs<AmbientWifiReadResult.Snapshot>(wifiRead.result).facts.single().identity)
			assertEquals(cell.logicalFactId, assertIs<AmbientCellReadResult.Snapshot>(cellRead.result).facts.single().identity)
		}
		val wifiContext = radioContext(RadioContextSource.WIFI).observations.single()
		val cellContext = radioContext(RadioContextSource.CELL).observations.single()
		assertEquals(wifi.sourceEventId, wifiContext.sourceEventId)
		assertEquals(cell.sourceEventId, cellContext.sourceEventId)
		assertEquals(wifi.observedWallTimeMs, wifiContext.observedWallTimeMs)
		assertEquals(cell.observedWallTimeMs, cellContext.observedWallTimeMs)
		assertIs<com.adsamcik.tracker.stats.api.repository.OptionalRetainedLocationContext.Unlocated>(wifiContext.location)
		assertIs<com.adsamcik.tracker.stats.api.repository.OptionalRetainedLocationContext.Unlocated>(cellContext.location)
	}

	@Test fun `unaccepted native radio rows cannot enter optional map context and other source survives`() = runTest {
		insertWifi(true); val cell = insertCell(true)
		database.openHelper.writableDatabase.execSQL("UPDATE ambient_wifi_fact_revision SET effect_checksum = ?", arrayOf<Any?>("0".repeat(64)))
		val wifiRead = database.withTransaction {
			RoomAmbientWifiRepository(database, dispatcher).readQualifiedInTransaction(wifiWindow())
		}
		assertTrue(wifiRead.nativeRows.isEmpty())
		assertTrue(wifiRead.readGaps.isNotEmpty())
		assertTrue(radioContext(RadioContextSource.WIFI).observations.isEmpty())
		assertEquals(cell.sourceEventId, radioContext(RadioContextSource.CELL).observations.single().sourceEventId)
	}

	@Test fun `all-day native status finds each overnight child on its own measured day`() = runTest {
		val offset = 86_400_000L - 1_950L
		val originalWifi = insertWifi(true, wallOffsetMs = offset, storedZoneId = "America/Los_Angeles")
		val originalCell = insertCell(true, wallOffsetMs = offset, storedZoneId = "America/Los_Angeles")
		val query = RoomAllDaySourceStatusQuery(database, mockk(),
			RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher), dispatcher)
		for ((window, expectedTime) in listOf(HistoryObservationWindow(86_399_000L, 86_400_000L) to 86_399_950L,
			HistoryObservationWindow(86_400_000L, 86_401_000L) to 86_400_050L)) {
			val wifi = assertIs<AllDaySourceStatusRead.Wifi>(query.read(AllDaySourceStatusRequest.Wifi(window))).facts.single()
			val cell = assertIs<AllDaySourceStatusRead.Cell>(query.read(AllDaySourceStatusRequest.Cell(window))).facts.single()
			assertEquals(listOf(AllDayReadingTime(expectedTime, 1L)), wifi.readings)
			assertEquals(listOf(AllDayReadingTime(expectedTime, 1L)), cell.readings)
			assertEquals(originalWifi.logicalFactId, wifi.identity)
			assertEquals(originalCell.logicalFactId, cell.identity)
			assertEquals(1L, wifi.semanticRevision); assertEquals(1L, cell.semanticRevision)
			assertEquals(originalWifi.observedWallTimeMs, wifi.observedTimeMs)
			assertEquals(originalCell.observedWallTimeMs, cell.observedTimeMs)
		}
		assertEquals(originalWifi, database.ambientWifiFactDao().revision(originalWifi.writerId,
			originalWifi.writerVersion, originalWifi.logicalFactId, 1))
		assertEquals(originalCell, database.ambientCellFactDao().revision(originalCell.writerId,
			originalCell.writerVersion, originalCell.logicalFactId, 1))
	}

	@Test fun `missing mandatory Wi-Fi children make status unavailable while Cell details remain usable`() = runTest {
		insertWifi(true); insertCell(true)
		database.openHelper.writableDatabase.execSQL("DELETE FROM ambient_wifi_child WHERE item_index = 1")
		val query = RoomAllDaySourceStatusQuery(database, mockk(),
			RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher), dispatcher)
		val window = HistoryObservationWindow(0L, 3_000L)
		assertEquals(AllDaySourceStatusRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE),
			query.read(AllDaySourceStatusRequest.Wifi(window)))
		assertEquals(listOf(1_900L, 2_000L), assertIs<AllDaySourceStatusRead.Cell>(query.read(
			AllDaySourceStatusRequest.Cell(window))).facts.single().readings.map { it.observedTimeMs })
		assertTrue(radioContext(RadioContextSource.WIFI).observations.isEmpty())
		assertEquals("cell-event", radioContext(RadioContextSource.CELL).observations.single().sourceEventId)
	}

	@Test
	fun `Wi-Fi v6 becomes unavailable when every current detail is missing`() = runTest {
		val fact = insertWifi(detailGroup = true)
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		assertEquals(2, assertIs<AmbientWifiReadResult.Snapshot>(repository.read(wifiWindow()))
			.facts.single().networks.size)
		assertEquals(2, database.radioObservationChildDao().deleteWifiBackgroundRevision(
			fact.writerId, fact.writerVersion, fact.logicalFactId, fact.semanticRevision))
		assertWifiUnavailable(repository)
	}

	@Test fun `complete native details outside their parent time bounds are unavailable`() = runTest {
		insertWifi(detailGroup = true, firstChildTimeMs = 1_800L)
		insertCell(detailGroup = true, firstChildTimeMs = 2_100L)
		assertWifiUnavailable(RoomAmbientWifiRepository(database, dispatcher))
		assertCellUnavailable(RoomAmbientCellRepository(database, dispatcher))
	}

	@Test
	fun `Cell v6 becomes unavailable when every current detail is missing`() = runTest {
		val fact = insertCell(detailGroup = true)
		val repository = RoomAmbientCellRepository(database, dispatcher)
		assertEquals(2, assertIs<AmbientCellReadResult.Snapshot>(repository.read(cellWindow()))
			.facts.single().towers.size)
		assertEquals(2, database.radioObservationChildDao().deleteCellBackgroundRevision(
			fact.writerId, fact.writerVersion, fact.logicalFactId, fact.semanticRevision))
		assertCellUnavailable(repository)
	}

	@Test
	fun `Wi-Fi v6 cannot present a partly missing set as complete details`() = runTest {
		insertWifi(detailGroup = true)
		database.openHelper.writableDatabase.execSQL("DELETE FROM ambient_wifi_child WHERE item_index = 1")
		assertWifiUnavailable(RoomAmbientWifiRepository(database, dispatcher))
	}

	@Test
	fun `Cell v6 cannot present a partly missing set as complete details`() = runTest {
		insertCell(detailGroup = true)
		database.openHelper.writableDatabase.execSQL("DELETE FROM ambient_cell_child WHERE item_index = 1")
		assertCellUnavailable(RoomAmbientCellRepository(database, dispatcher))
	}

	@Test
	fun `Wi-Fi v6 exposes complete exact details through range and day readers`() = runTest {
		insertWifi(detailGroup = true)
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		for (result in listOf(repository.read(wifiWindow()), repository.readDay(wifiDay()))) {
			val fact = assertIs<AmbientWifiReadResult.Snapshot>(result).facts.single()
			assertEquals(2, fact.observationCount)
			assertEquals(listOf("Home", "Cafe"), fact.networks.map { it.ssid })
			assertEquals(listOf("aa:bb:cc:dd:ee:f0", "aa:bb:cc:dd:ee:f1"), fact.networks.map { it.bssid })
			assertEquals(listOf(1_900L, 2_000L), fact.networks.map { it.observedTimeMs })
		}
	}

	@Test
	fun `Cell v6 exposes complete exact details through range and day readers`() = runTest {
		insertCell(detailGroup = true)
		val repository = RoomAmbientCellRepository(database, dispatcher)
		for (result in listOf(repository.read(cellWindow()), repository.readDay(cellDay()))) {
			val fact = assertIs<AmbientCellReadResult.Snapshot>(result).facts.single()
			assertEquals(2, fact.observationCount)
			assertEquals(listOf("LTE:123", "NR:456"), fact.towers.map { it.identity })
			assertEquals(listOf(true, false), fact.towers.map { it.registered })
			assertEquals(listOf(1_900L, 2_000L), fact.towers.map { it.observedTimeMs })
		}
	}

	@Test
	fun `legacy Wi-Fi summary without any child remains readable`() = runTest {
		insertWifi(detailGroup = false)
		val repository = RoomAmbientWifiRepository(database, dispatcher)
		for (result in listOf(repository.read(wifiWindow()), repository.readDay(wifiDay()))) {
			val fact = assertIs<AmbientWifiReadResult.Snapshot>(result).facts.single()
			assertEquals(2, fact.observationCount)
			assertEquals(emptyList(), fact.networks)
		}
	}

	@Test
	fun `legacy Cell summary without any child remains readable`() = runTest {
		insertCell(detailGroup = false)
		val repository = RoomAmbientCellRepository(database, dispatcher)
		for (result in listOf(repository.read(cellWindow()), repository.readDay(cellDay()))) {
			val fact = assertIs<AmbientCellReadResult.Snapshot>(result).facts.single()
			assertEquals(2, fact.observationCount)
			assertEquals(emptyList(), fact.towers)
		}
	}

	@Test fun `Room capture fence then Wi-Fi consent erase preserves Cell history and details`() = runTest {
		assertSourceCleanupPreservesOtherRadio(SourceDestinationOwnerEntity.SOURCE_WIFI, clearProduct = false)
	}

	@Test fun `Room capture fence then Cell consent erase preserves Wi-Fi history and details`() = runTest {
		assertSourceCleanupPreservesOtherRadio(SourceDestinationOwnerEntity.SOURCE_CELL, clearProduct = false)
	}

	@Test fun `Wi-Fi product clear removes its detail children and preserves Cell history`() = runTest {
		assertSourceCleanupPreservesOtherRadio(SourceDestinationOwnerEntity.SOURCE_WIFI, clearProduct = true)
	}

	@Test fun `Cell product clear removes its detail children and preserves Wi-Fi history`() = runTest {
		assertSourceCleanupPreservesOtherRadio(SourceDestinationOwnerEntity.SOURCE_CELL, clearProduct = true)
	}

	@Test fun `Wi-Fi retention prunes exact detail children and keeps newer Cell history`() = runTest {
		assertPruningPreservesOtherRadio(SourceDestinationOwnerEntity.SOURCE_WIFI)
	}

	@Test fun `Cell retention prunes exact detail children and keeps newer Wi-Fi history`() = runTest {
		assertPruningPreservesOtherRadio(SourceDestinationOwnerEntity.SOURCE_CELL)
	}

	@Test fun `wrong retention epoch leaves both radio parents and exact detail children unchanged`() = runTest {
		val wifi = insertWifi(true)
		val cell = insertCell(true)
		val savedWifi = wifiChildren(wifi)
		val savedCell = cellChildren(cell)
		database.sourceEvidenceStateDao().updateLifecycle(0L, 2_500L, 2_500L)
		assertEquals(AmbientWifiRetentionResult.Unavailable(AmbientWifiMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED),
			database.pruneAmbientWifi(AmbientWifiRetentionCommand(2_500L, 1L, 2_600L)))
		assertEquals(AmbientCellRetentionResult.Unavailable(AmbientCellMaintenanceUnavailableReason.COLLECTED_DATA_EPOCH_CHANGED),
			database.pruneAmbientCell(AmbientCellRetentionCommand(2_500L, 1L, 2_600L)))
		assertEquals(savedWifi, wifiChildren(wifi))
		assertEquals(savedCell, cellChildren(cell))
		assertEquals(wifi, database.ambientWifiFactDao().revision(wifi.writerId, wifi.writerVersion, wifi.logicalFactId, 1L))
		assertEquals(cell, database.ambientCellFactDao().revision(cell.writerId, cell.writerVersion, cell.logicalFactId, 1L))
	}

	@Test fun `outer collected-data clear removes both radio detail sets with their parents`() = runTest {
		val wifi = insertWifi(true)
		val cell = insertCell(true)
		assertEquals(2, wifiChildren(wifi).size)
		assertEquals(2, cellChildren(cell).size)
		AppDatabase.deleteAllCollectedDataForTesting(database, "radio-details-full-clear", 1L, 4_000L, 4_000L)
		assertEquals(0L, database.ambientWifiFactDao().localFactCount())
		assertEquals(0L, database.ambientCellFactDao().localFactCount())
		assertTrue(wifiChildren(wifi).isEmpty())
		assertTrue(cellChildren(cell).isEmpty())
		assertEquals(1L, requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch)
		assertTrue(AmbientWifiFactIntegrity.isAuthentic(requireNotNull(
			database.ambientWifiFactDao().latestDeletionMarker(0L))))
		assertTrue(AmbientCellFactIntegrity.isAuthentic(requireNotNull(
			database.ambientCellFactDao().latestDeletionMarker(0L))))
	}

	private suspend fun assertSourceCleanupPreservesOtherRadio(erasedSource: Int, clearProduct: Boolean) {
		val wifi = insertWifi(true)
		val cell = insertCell(true)
		val wifiRepository = RoomAmbientWifiRepository(database, dispatcher)
		val cellRepository = RoomAmbientCellRepository(database, dispatcher)
		val savedWifi = assertIs<AmbientWifiReadResult.Snapshot>(wifiRepository.read(wifiWindow()))
		val savedCell = assertIs<AmbientCellReadResult.Snapshot>(cellRepository.read(cellWindow()))
		val savedWifiChildren = wifiChildren(wifi)
		val savedCellChildren = cellChildren(cell)
		val savedWifiContext = radioContext(RadioContextSource.WIFI)
		val savedCellContext = radioContext(RadioContextSource.CELL)
		assertEquals(listOf("Home", "Cafe"), savedWifi.facts.single().networks.map { it.ssid })
		assertEquals(listOf("LTE:123", "NR:456"), savedCell.facts.single().towers.map { it.identity })
		assertEquals(listOf(1_900L, 2_000L), savedWifiChildren.map { it.observedWallTimeMs })
		assertEquals(listOf(1_900L, 2_000L), savedCellChildren.map { it.observedWallTimeMs })
		assertEquals(listOf(RadioContextPurpose.AMBIENT), savedWifiContext.observations.map { it.purpose })
		assertEquals(listOf(RadioContextPurpose.AMBIENT), savedCellContext.observations.map { it.purpose })
		installJoinedCaptureAndFence()
		// This is the production Room purpose fence, not a public End/provider teardown fixture.
		assertEquals(savedWifi.facts, assertIs<AmbientWifiReadResult.Snapshot>(wifiRepository.read(wifiWindow())).facts)
		assertEquals(savedCell.facts, assertIs<AmbientCellReadResult.Snapshot>(cellRepository.read(cellWindow())).facts)
		assertEquals(savedWifiContext, radioContext(RadioContextSource.WIFI))
		assertEquals(savedCellContext, radioContext(RadioContextSource.CELL))
		if (!clearProduct) {
			resetAmbientConsent(erasedSource)
		}
		if (erasedSource == SourceDestinationOwnerEntity.SOURCE_WIFI) {
			val result = if (clearProduct) {
				database.clearAmbientWifiProductPreservingReplayFootprints(0L, 4_000L)
			}
				else {
				database.deleteAmbientWifiAfterConsentReset(0L, 2L, 4_000L)
			}
			assertEquals(1L, assertIs<AmbientWifiDeletionResult.Deleted>(result).localRevisionCount)
			assertTrue(wifiChildren(wifi).isEmpty())
			assertEquals(0L, database.ambientWifiFactDao().localFactCount())
			assertTrue(assertIs<AmbientWifiReadResult.Snapshot>(wifiRepository.read(wifiWindow())).facts.isEmpty())
			assertTrue(radioContext(RadioContextSource.WIFI).observations.isEmpty())
			assertEquals(savedCell.facts, assertIs<AmbientCellReadResult.Snapshot>(cellRepository.read(cellWindow())).facts)
			assertEquals(savedCellChildren, cellChildren(cell))
			assertEquals(savedCellContext, radioContext(RadioContextSource.CELL))
		} else {
			val result = if (clearProduct) {
				database.clearAmbientCellProductPreservingReplayFootprints(0L, 4_000L)
			}
				else {
				database.deleteAmbientCellAfterConsentReset(0L, 2L, 4_000L)
			}
			assertEquals(1L, assertIs<AmbientCellDeletionResult.Deleted>(result).localRevisionCount)
			assertTrue(cellChildren(cell).isEmpty())
			assertEquals(0L, database.ambientCellFactDao().localFactCount())
			assertTrue(assertIs<AmbientCellReadResult.Snapshot>(cellRepository.read(cellWindow())).facts.isEmpty())
			assertTrue(radioContext(RadioContextSource.CELL).observations.isEmpty())
			assertEquals(savedWifi.facts, assertIs<AmbientWifiReadResult.Snapshot>(wifiRepository.read(wifiWindow())).facts)
			assertEquals(savedWifiChildren, wifiChildren(wifi))
			assertEquals(savedWifiContext, radioContext(RadioContextSource.WIFI))
		}
		assertEquals(0L, database.locationSampleDao().countAll())
	}

	private suspend fun installJoinedCaptureAndFence() = database.withTransaction {
		val dao = database.sourceBrokerDao()
		for ((source, name) in listOf(SourceDestinationOwnerEntity.SOURCE_WIFI to "wifi",
			SourceDestinationOwnerEntity.SOURCE_CELL to "cell")) {
			val background = dao.demandsByIds(listOf("$name-demand")).single()
			val policyDao = database.sourcePolicyDao()
			val historical = requireNotNull(policyDao.policyAtRevision(1L, source))
			policyDao.insertPolicies(listOf(historical.copy(policyRevision = 2L,
				capturePersistenceEligible = true, captureConsentEpoch = 2L,
				effectiveElapsedRealtimeNanos = 3_100_000_000L, effectiveWallTimeMs = 3_100L,
				changeReason = "TEST_CAPTURE_JOIN")))
			policyDao.insertConsentEpochs(listOf(SourceConsentEpochEntity(source, SourceBrokerPurpose.SESSION_CAPTURE,
				2L, true, true, 2L, "boot", 3_100_000_000L, 3_100L, "TEST_CAPTURE_JOIN")))
			val capture = background.copy(demandId = "$name-capture", consumerId = "session:joined",
				purpose = SourceBrokerPurpose.SESSION_CAPTURE, logicalTrackingId = "joined",
				serviceRunId = "joined-run", manifestRevision = 1L, lifecycleLeaseGeneration = 1L,
				sourcePolicyRevision = 2L, consentEpoch = 2L,
				requestedElapsedRealtimeNanos = 3_100_000_000L, requestedAtMs = 3_100L,
				liveAmbientRetentionPolicyId = null, liveAmbientRetentionApprovalRevision = null)
			dao.insertDemands(listOf(capture))
			val previous = requireNotNull(dao.registration(source, 1L))
			dao.insertRegistration(previous.copy(registrationGeneration = 2L,
				physicalConfigurationFingerprint = "$name-joined",
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = 3_100L, reservedElapsedRealtimeNanos = 3_100_000_000L,
				acceptedAtMs = 3_200L, acceptedElapsedRealtimeNanos = 3_200_000_000L,
				retiredAtMs = null, retiredElapsedRealtimeNanos = null))
			dao.insertAuthorizations(SourceBrokerAuthorization.rows(source, 2L, 2L,
				listOf(background, capture), "boot", 3_200_000_000L, 3_200L))
			assertEquals(SourceBrokerPurpose.MASK_AMBIENT_PRODUCT or SourceBrokerPurpose.MASK_SESSION_CAPTURE,
				requireNotNull(dao.latestAuthorization(source, 2L).toAuthorizationSnapshotOrNull()).purposeEligibilityMask)
			database.fenceSourcePurposesInTransaction(source, listOf(SourceBrokerPurpose.SESSION_CAPTURE),
				"boot", 3_300_000_000L, 3_300L)
			assertEquals(SourceDemandEntity.STATUS_RETIRING, dao.demandHistory("session:joined")
				.single { it.sourceKind == source }.status)
			assertEquals(SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
				requireNotNull(dao.latestAuthorization(source, 2L).toAuthorizationSnapshotOrNull()).purposeEligibilityMask)
		}
		val policyDao = database.sourcePolicyDao()
		val current = policyDao.authority()
		if (current == null) {
			policyDao.ensureAuthority(SourcePolicyAuthorityEntity(bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			currentPolicyRevision = 2L, legacySettingsFingerprint = null, updatedAtMs = 3_300L))
		}
		else {
			assertEquals(1, policyDao.compareAndSetAuthority(current.bootstrapState, current.currentPolicyRevision,
			SourcePolicyAuthorityEntity.STATE_ACTIVE, 2L, null, 3_300L))
		}
	}

	private suspend fun resetAmbientConsent(source: Int) = database.withTransaction {
		val name = if (source == SourceDestinationOwnerEntity.SOURCE_WIFI) {
			"wifi"
		} else {
			"cell"
		}
		val policyDao = database.sourcePolicyDao()
		policyDao.insertPolicies(policyDao.policiesAtRevision(2L).map { policy ->
			val next = policy.copy(policyRevision = 3L, effectiveElapsedRealtimeNanos = 3_400_000_000L,
				effectiveWallTimeMs = 3_400L, changeReason = "TEST_CONSENT_RESET")
			if (policy.sourceKind == source) {
				next.copy(ambientPersistenceEligible = false, ambientConsentEpoch = null)
			}
			else {
				next
			}
		})
		val authority = policyDao.authority()
		if (authority == null) {
			policyDao.ensureAuthority(SourcePolicyAuthorityEntity(
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE, currentPolicyRevision = 3L,
			legacySettingsFingerprint = null, updatedAtMs = 3_400L))
		}
		else {
			assertEquals(1, policyDao.compareAndSetAuthority(authority.bootstrapState, authority.currentPolicyRevision,
			SourcePolicyAuthorityEntity.STATE_ACTIVE, 3L, null, 3_400L))
		}
		policyDao.insertConsentEpochs(listOf(SourceConsentEpochEntity(source, SourceBrokerPurpose.AMBIENT_PRODUCT,
			2L, false, false, 3L, "boot", 3_400_000_000L, 3_400L, "TEST_CONSENT_RESET")))
		val dao = database.sourceBrokerDao()
		assertEquals(1, dao.retireConsumer("$name-consumer", "boot", 3_400_000_000L, 3_400L))
		database.rotateCurrentSourceAuthorizationInTransaction(source, "boot", 3_400_000_000L, 3_400L)
		assertEquals(0L, requireNotNull(dao.latestAuthorization(source, 2L).toAuthorizationSnapshotOrNull()).purposeEligibilityMask)
		if (source == SourceDestinationOwnerEntity.SOURCE_WIFI) {
			database.ambientWifiFactDao().insertAuthority(
			AmbientWifiAuthorityIntegrity.create(1L, AmbientWifiAuthorityEntity.STATE_REVOKED, 3L, 2L,
				"wifi-retention", 1L, 0L, 0L, "boot", 3_400_000_000L, 3_400L, 1L, "wifi-revoked", 1L, "wifi-demand"))
		}
		else {
			database.ambientCellFactDao().insertAuthority(AmbientCellAuthorityIntegrity.create(
			1L, AmbientCellAuthorityEntity.STATE_REVOKED, 3L, 2L, "cell-retention", 1L, 0L, 0L,
			"boot", 3_400_000_000L, 3_400L, 1L, "cell-revoked", 1L, "cell-demand"))
		}
	}

	private suspend fun assertPruningPreservesOtherRadio(prunedSource: Int) {
		val wifi = insertWifi(true, wallOffsetMs = if (prunedSource == SourceDestinationOwnerEntity.SOURCE_WIFI) {
			0L
		} else {
			10_000L
		})
		val cell = insertCell(true, wallOffsetMs = if (prunedSource == SourceDestinationOwnerEntity.SOURCE_CELL) {
			0L
		} else {
			10_000L
		})
		val savedWifiChildren = wifiChildren(wifi)
		val savedCellChildren = cellChildren(cell)
		val newerWifi = if (prunedSource == SourceDestinationOwnerEntity.SOURCE_WIFI) {
			appendNewerWifiGroup(wifi)
		} else {
			null
		}
		val newerCell = if (prunedSource == SourceDestinationOwnerEntity.SOURCE_CELL) {
			appendNewerCellGroup(cell)
		} else {
			null
		}
		val savedNewerWifiChildren = newerWifi?.let { wifiChildren(it) }
		val savedNewerCellChildren = newerCell?.let { cellChildren(it) }
		database.sourceEvidenceStateDao().updateLifecycle(0L, 2_500L, 2_500L)
		if (prunedSource == SourceDestinationOwnerEntity.SOURCE_WIFI) {
			database.ambientWifiFactDao().insertRetentionAuthority(AmbientWifiRetentionAuthorityIntegrity.create(
				"LIVE_AMBIENT", 2L, "ACTIVE", "wifi-retention", 1L, 1L, 0L, "boot", 2_500_000_000L, 2_500L, 2_500L))
			assertEquals(AmbientWifiRetentionResult.Pruned(1, 0, 0), database.pruneAmbientWifi(AmbientWifiRetentionCommand(2_500L, 0L, 2_600L)))
			assertTrue(wifiChildren(wifi).isEmpty())
			assertEquals(savedNewerWifiChildren, wifiChildren(requireNotNull(newerWifi)))
			assertEquals(newerWifi.logicalFactId, assertIs<AmbientWifiReadResult.Snapshot>(RoomAmbientWifiRepository(database, dispatcher)
				.read(AmbientWifiReadRequest(0L, 20_000L, setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10))).facts.single().identity)
			assertEquals(savedCellChildren, cellChildren(cell))
			assertEquals(cell.logicalFactId, assertIs<AmbientCellReadResult.Snapshot>(RoomAmbientCellRepository(database, dispatcher)
				.read(AmbientCellReadRequest(0L, 20_000L, setOf(AmbientCellOrigin.LOCAL_DEVICE), 10))).facts.single().identity)
		} else {
			database.ambientCellFactDao().insertRetentionAuthority(AmbientCellRetentionAuthorityIntegrity.create(
				"LIVE_AMBIENT", 2L, "ACTIVE", "cell-retention", 1L, 1L, 0L, "boot", 2_500_000_000L, 2_500L, 2_500L))
			assertEquals(AmbientCellRetentionResult.Pruned(1, 0, 0), database.pruneAmbientCell(AmbientCellRetentionCommand(2_500L, 0L, 2_600L)))
			assertTrue(cellChildren(cell).isEmpty())
			assertEquals(savedNewerCellChildren, cellChildren(requireNotNull(newerCell)))
			assertEquals(newerCell.logicalFactId, assertIs<AmbientCellReadResult.Snapshot>(RoomAmbientCellRepository(database, dispatcher)
				.read(AmbientCellReadRequest(0L, 20_000L, setOf(AmbientCellOrigin.LOCAL_DEVICE), 10))).facts.single().identity)
			assertEquals(savedWifiChildren, wifiChildren(wifi))
			assertEquals(wifi.logicalFactId, assertIs<AmbientWifiReadResult.Snapshot>(RoomAmbientWifiRepository(database, dispatcher)
				.read(AmbientWifiReadRequest(0L, 20_000L, setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10))).facts.single().identity)
		}
	}

	private suspend fun appendNewerWifiGroup(previous: AmbientWifiFactRevisionEntity): AmbientWifiFactRevisionEntity {
		val delivery = "2".repeat(64)
		val id = AmbientWifiFactIntegrity.logicalFactId(delivery, 1L, 0L, 0L, previous.purposeDemandId)
		val unsigned = previous.copy(logicalFactId = id, mutationId = AmbientWifiFactIntegrity.mutationId(id, 1L),
			sourceEventId = "wifi-newer-event", sourceAdmissionOrdinal = 2L, sourceDeliveryIdentity = delivery,
			observedIntervalStartNanos = previous.observedIntervalStartNanos + 700_000_000L,
			observedElapsedNanos = previous.observedElapsedNanos + 700_000_000L,
			receivedElapsedNanos = previous.receivedElapsedNanos + 700_000_000L,
			coverageStartTimeMs = previous.coverageStartTimeMs + 700L,
			observedWallTimeMs = previous.observedWallTimeMs + 700L, appliedAtMs = previous.appliedAtMs + 700L)
		val fact = unsigned.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(unsigned))
		database.ambientWifiFactDao().insertRevision(fact)
		database.ambientWifiFactDao().insertCursor(AmbientWifiFactCursorEntity(fact.writerId, fact.writerVersion,
			id, 1L, fact.mutationId, fact.effectChecksum, 2L, 0L, 0L, 1L, fact.appliedAtMs))
		database.radioObservationChildDao().insertWifiBackground(wifiChildren(previous).map { child ->
			val observed = child.providerTimestampNanos + 700_000_000L
			val wall = child.observedWallTimeMs + 700L
			val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, fact.writerId, fact.writerVersion, id, 1L, child.itemIndex)
			child.copy(logicalFactId = id, providerTimestampNanos = observed, observedWallTimeMs = wall,
				contentChecksum = RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(owner, observed, wall,
					child.wallTimeUncertaintyMs, child.bssid, child.ssid, child.frequencyMhz, child.signalDbm)))
		})
		return fact
	}

	private suspend fun appendNewerCellGroup(previous: AmbientCellFactRevisionEntity): AmbientCellFactRevisionEntity {
		val delivery = "3".repeat(64)
		val id = AmbientCellFactIntegrity.logicalFactId(delivery, 1L, 0L, 0L, previous.purposeDemandId)
		val unsigned = previous.copy(logicalFactId = id, mutationId = AmbientCellFactIntegrity.mutationId(id, 1L),
			sourceEventId = "cell-newer-event", sourceAdmissionOrdinal = 2L, sourceDeliveryIdentity = delivery,
			observedIntervalStartNanos = previous.observedIntervalStartNanos + 700_000_000L,
			observedElapsedNanos = previous.observedElapsedNanos + 700_000_000L,
			receivedElapsedNanos = previous.receivedElapsedNanos + 700_000_000L,
			coverageStartTimeMs = previous.coverageStartTimeMs + 700L,
			observedWallTimeMs = previous.observedWallTimeMs + 700L, appliedAtMs = previous.appliedAtMs + 700L)
		val fact = unsigned.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(unsigned))
		database.ambientCellFactDao().insertRevision(fact)
		database.ambientCellFactDao().insertCursor(AmbientCellFactCursorEntity(fact.writerId, fact.writerVersion,
			id, 1L, fact.mutationId, fact.effectChecksum, 2L, 0L, 0L, 1L, fact.appliedAtMs))
		database.radioObservationChildDao().insertCellBackground(cellChildren(previous).map { child ->
			val observed = child.providerTimestampNanos + 700_000_000L
			val wall = child.observedWallTimeMs + 700L
			val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, fact.writerId, fact.writerVersion, id, 1L, child.itemIndex)
			child.copy(logicalFactId = id, providerTimestampNanos = observed, observedWallTimeMs = wall,
				contentChecksum = RadioObservationChildIntegrity.cell(CellChildChecksumInput(owner, observed, wall,
					child.wallTimeUncertaintyMs, child.towerIdentity, child.radioType, child.registered, child.signalDbm)))
		})
		return fact
	}

	private suspend fun wifiChildren(fact: AmbientWifiFactRevisionEntity) = database.radioObservationChildDao()
		.wifiBackgroundChildren(fact.writerId, fact.writerVersion, fact.logicalFactId, fact.semanticRevision, 3)

	private suspend fun cellChildren(fact: AmbientCellFactRevisionEntity) = database.radioObservationChildDao()
		.cellBackgroundChildren(fact.writerId, fact.writerVersion, fact.logicalFactId, fact.semanticRevision, 3)

	private suspend fun radioContext(source: RadioContextSource): RadioLocationContextReadResult.Ready =
		assertIs(RoomRadioLocationContextRepository(database, mockk(), mockk(),
			RoomAmbientWifiRepository(database, dispatcher), RoomAmbientCellRepository(database, dispatcher),
			RetainedLocationContextReader(database), dispatcher).read(RadioLocationContextRequest(source, 0L, 3_000L)))

	private suspend fun assertWifiUnavailable(repository: RoomAmbientWifiRepository) {
		assertEquals(AmbientWifiReadResult.StorageUnavailable, repository.read(wifiWindow()))
		assertEquals(AmbientWifiReadResult.StorageUnavailable, repository.readDay(wifiDay()))
	}

	private suspend fun assertCellUnavailable(repository: RoomAmbientCellRepository) {
		assertEquals(AmbientCellReadResult.StorageUnavailable, repository.read(cellWindow()))
		assertEquals(AmbientCellReadResult.StorageUnavailable, repository.readDay(cellDay()))
	}

	private fun wifiWindow() = AmbientWifiReadRequest(0L, 3_000L, setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10)
	private fun cellWindow() = AmbientCellReadRequest(0L, 3_000L, setOf(AmbientCellOrigin.LOCAL_DEVICE), 10)
	private fun wifiDay() = AmbientWifiDayReadRequest(0L, "UTC", setOf(AmbientWifiOrigin.LOCAL_DEVICE), 10)
	private fun cellDay() = AmbientCellDayReadRequest(0L, "UTC", setOf(AmbientCellOrigin.LOCAL_DEVICE), 10)

	@Suppress("LongMethod")
	private suspend fun insertWifi(detailGroup: Boolean, firstChildTimeMs: Long = 1_900L,
		wallOffsetMs: Long = 0L, storedZoneId: String = "UTC"): AmbientWifiFactRevisionEntity {
		val demand = "wifi-demand".takeIf { detailGroup }
		val authorization = installAuthority(SourceDestinationOwnerEntity.SOURCE_WIFI, "wifi", demand, wallOffsetMs)
		val day = Instant.ofEpochMilli(wallOffsetMs + 2_000L).atZone(ZoneId.of(storedZoneId)).toLocalDate()
		val delivery = "a".repeat(64)
		val id = AmbientWifiFactIntegrity.logicalFactId(delivery, 1L, 0L, 0L, demand)
		val unsigned = AmbientWifiFactRevisionEntity(
			writerId = AmbientWifiFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientWifiFactRevisionEntity.WRITER_VERSION,
			writerOwnerGeneration = 1L, logicalFactId = id, semanticRevision = 1L,
			supersedesSemanticRevision = null, mutationId = AmbientWifiFactIntegrity.mutationId(id, 1L),
			factKind = AmbientWifiFactRevisionEntity.FACT_KIND_AGGREGATE,
			aggregateOwnerLogicalFactId = null, aggregateOwnerSemanticRevision = null,
			sourceEventId = "wifi-event", sourceAdmissionOrdinal = 1L,
			walIntegrityIdentity = "b".repeat(64), payloadChecksum = "c".repeat(64),
			sourceDeliveryIdentity = delivery, sourceInstanceId = "wifi-instance", registrationGeneration = 1L,
			configurationRevision = 1L, physicalConfigurationFingerprint = "wifi-passive",
			authorizationRevision = 1L, authorizationFingerprint = authorization,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			sourcePolicyRevision = 1L, ambientConsentEpoch = 1L, retentionPolicyId = "wifi-retention",
			retentionApprovalRevision = 1L, collectedDataEpoch = 0L, scopeDeletionGeneration = 0L,
			clockDomainId = "boot", storedZoneId = storedZoneId, structuralEpochDay = day.toEpochDay(),
			structuralDayStartTimeMs = day.atStartOfDay(ZoneId.of(storedZoneId)).toInstant().toEpochMilli(),
			structuralDayEndTimeMs = day.plusDays(1).atStartOfDay(ZoneId.of(storedZoneId)).toInstant().toEpochMilli(),
			observedIntervalStartNanos = 1_000_000_000L + wallOffsetMs * 1_000_000L,
			observedElapsedNanos = 1_100_000_000L + wallOffsetMs * 1_000_000L,
			receivedElapsedNanos = 1_200_000_000L + wallOffsetMs * 1_000_000L, coverageStartTimeMs = wallOffsetMs + 1_900L,
			observedWallTimeMs = wallOffsetMs + 2_000L, wallTimeUncertaintyMs = 1L,
			coverageCompleteness = AmbientWifiFactRevisionEntity.COMPLETENESS_COMPLETE,
			observationCount = 2, twoPointFourGhzCount = 1, fiveGhzCount = 1,
			sixGhzCount = 0, otherBandCount = 0, strongestSignalDbm = -40, weakestSignalDbm = -70,
			signalSumDbm = -110L, qualityFlags = 0L, qualityConfidence = null,
			effectChecksum = "0".repeat(64), appliedAtMs = wallOffsetMs + 2_100L, purposeDemandId = demand,
		)
		val fact = unsigned.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(unsigned))
		database.ambientWifiFactDao().insertRevision(fact)
		database.ambientWifiFactDao().insertCursor(AmbientWifiFactCursorEntity(
			fact.writerId, fact.writerVersion, id, 1L, fact.mutationId, fact.effectChecksum,
			1L, 0L, 0L, 1L, wallOffsetMs + 2_100L))
		if (detailGroup) {
			database.radioObservationChildDao().insertWifiBackground((0..1).map { index ->
			val providerTime = 1_000_000_000L + index * 100_000_000L + wallOffsetMs * 1_000_000L
			val wall = wallOffsetMs + if (index == 0) {
				firstChildTimeMs
			} else {
				2_000L
			}
			val bssid = "aa:bb:cc:dd:ee:f$index"
			val name = if (index == 0) {
				"Home"
			} else {
				"Cafe"
			}
			val frequency = if (index == 0) {
				2_412
			} else {
				5_180
			}
			val signal = if (index == 0) {
				-70
			} else {
				-40
			}
			val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, fact.writerId, fact.writerVersion, id, 1L, index)
			AmbientWifiChildEntity(fact.writerId, fact.writerVersion, id, 1L, index, providerTime,
				wall, 1L, bssid, name, frequency, signal,
				RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(owner, providerTime, wall, 1L,
					bssid, name, frequency, signal)))
		})
		}
		return fact
	}

	@Suppress("LongMethod")
	private suspend fun insertCell(detailGroup: Boolean, firstChildTimeMs: Long = 1_900L,
		wallOffsetMs: Long = 0L, storedZoneId: String = "UTC"): AmbientCellFactRevisionEntity {
		val demand = "cell-demand".takeIf { detailGroup }
		val authorization = installAuthority(SourceDestinationOwnerEntity.SOURCE_CELL, "cell", demand, wallOffsetMs)
		val day = Instant.ofEpochMilli(wallOffsetMs + 2_000L).atZone(ZoneId.of(storedZoneId)).toLocalDate()
		val delivery = "e".repeat(64)
		val id = AmbientCellFactIntegrity.logicalFactId(delivery, 1L, 0L, 0L, demand)
		val unsigned = AmbientCellFactRevisionEntity(
			writerId = AmbientCellFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientCellFactRevisionEntity.WRITER_VERSION,
			writerOwnerGeneration = 1L, logicalFactId = id, semanticRevision = 1L,
			supersedesSemanticRevision = null, mutationId = AmbientCellFactIntegrity.mutationId(id, 1L),
			factKind = AmbientCellFactRevisionEntity.FACT_KIND_AGGREGATE,
			aggregateOwnerLogicalFactId = null, aggregateOwnerSemanticRevision = null,
			sourceEventId = "cell-event", sourceAdmissionOrdinal = 1L,
			walIntegrityIdentity = "b".repeat(64), payloadChecksum = "c".repeat(64),
			sourceDeliveryIdentity = delivery, sourceInstanceId = "cell-instance", registrationGeneration = 1L,
			configurationRevision = 1L, physicalConfigurationFingerprint = "cell-passive",
			authorizationRevision = 1L, authorizationFingerprint = authorization,
			purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			sourcePolicyRevision = 1L, ambientConsentEpoch = 1L, retentionPolicyId = "cell-retention",
			retentionApprovalRevision = 1L, collectedDataEpoch = 0L, scopeDeletionGeneration = 0L,
			clockDomainId = "boot", storedZoneId = storedZoneId, structuralEpochDay = day.toEpochDay(),
			structuralDayStartTimeMs = day.atStartOfDay(ZoneId.of(storedZoneId)).toInstant().toEpochMilli(),
			structuralDayEndTimeMs = day.plusDays(1).atStartOfDay(ZoneId.of(storedZoneId)).toInstant().toEpochMilli(),
			observedIntervalStartNanos = 1_000_000_000L + wallOffsetMs * 1_000_000L,
			observedElapsedNanos = 1_100_000_000L + wallOffsetMs * 1_000_000L,
			receivedElapsedNanos = 1_200_000_000L + wallOffsetMs * 1_000_000L, coverageStartTimeMs = wallOffsetMs + 1_900L,
			observedWallTimeMs = wallOffsetMs + 2_000L, wallTimeUncertaintyMs = 1L,
			coverageCompleteness = AmbientCellFactRevisionEntity.COMPLETENESS_COMPLETE,
			subscriptionCompleteness = AmbientCellFactRevisionEntity.SUBSCRIPTION_COMPLETE,
			observationCount = 2, registeredObservationCount = 1,
			gsmCount = 0, cdmaCount = 0, wcdmaCount = 0, tdscdmaCount = 0, lteCount = 1, nrCount = 1,
			qualityUnknownCount = 0, qualityNoneOrUnknownCount = 0, qualityPoorCount = 1,
			qualityModerateCount = 0, qualityGoodCount = 1, qualityGreatCount = 0,
			qualityFlags = 0L, qualityConfidence = null, effectChecksum = "0".repeat(64),
			appliedAtMs = wallOffsetMs + 2_100L, purposeDemandId = demand,
		)
		val fact = unsigned.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(unsigned))
		database.ambientCellFactDao().insertRevision(fact)
		database.ambientCellFactDao().insertCursor(AmbientCellFactCursorEntity(
			fact.writerId, fact.writerVersion, id, 1L, fact.mutationId, fact.effectChecksum,
			1L, 0L, 0L, 1L, wallOffsetMs + 2_100L))
		if (detailGroup) {
			database.radioObservationChildDao().insertCellBackground((0..1).map { index ->
			val providerTime = 1_000_000_000L + index * 100_000_000L + wallOffsetMs * 1_000_000L
			val wall = wallOffsetMs + if (index == 0) {
				firstChildTimeMs
			} else {
				2_000L
			}
			val tower = if (index == 0) {
				"LTE:123"
			} else {
				"NR:456"
			}
			val radio = if (index == 0) {
				"LTE"
			} else {
				"NR"
			}
			val registered = index == 0
			val signal = if (index == 0) {
				-72
			} else {
				-90
			}
			val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, fact.writerId, fact.writerVersion, id, 1L, index)
			AmbientCellChildEntity(fact.writerId, fact.writerVersion, id, 1L, index, providerTime,
				wall, 1L, tower, radio, registered, signal,
				RadioObservationChildIntegrity.cell(CellChildChecksumInput(owner, providerTime, wall, 1L,
					tower, radio, registered, signal)))
		})
		}
		return fact
	}

	/** Exact historical grant; detail tests must not fail merely because authority is absent. */
	private suspend fun installAuthority(source: Int, name: String, factDemandId: String?, wallOffsetMs: Long = 0L): String {
		database.sourceEvidenceStateDao().ensure()
		database.sourcePolicyDao().insertPolicies(listOf(SourcePolicyEntity(1, source, true, 1,
			null, null, null, false, false, true, null, null, 1, "boot", 0, 0, "USER")))
		database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(source,
			SourceBrokerPurpose.AMBIENT_PRODUCT, 1, true, true, 1, "boot", 0, 0, "USER")))
		database.sourceBrokerDao().insertRegistration(ProviderRegistrationGenerationEntity(source, 1,
			"$name-instance", "owner", "boot", "$name-passive", 0,
			ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND, "process",
			ProviderRegistrationGenerationEntity.STATUS_RETIRED, 0, 0, 0, 0,
			wallOffsetMs + 3_000, 3_000_000_000 + wallOffsetMs * 1_000_000L, null))
		val demand = SourceDemandEntity(demandId = factDemandId ?: "$name-legacy-demand",
			consumerId = "$name-consumer", sourceKind = source, purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			logicalTrackingId = null, serviceRunId = null, manifestRevision = null, lifecycleLeaseGeneration = null,
			sourcePolicyRevision = 1, consentEpoch = 1, persistenceEligible = true, qosCode = 1,
			maximumAgeMs = 60_000, desiredLatencyMs = 0, requestedBootId = "boot",
			requestedElapsedRealtimeNanos = 0, requestedAtMs = 0, status = "ACTIVE", retireBootId = null,
			retireElapsedRealtimeNanos = null, retiredAtMs = null,
			liveAmbientRetentionPolicyId = "$name-retention", liveAmbientRetentionApprovalRevision = 1)
		database.sourceBrokerDao().insertDemands(listOf(demand))
		val authorization = SourceBrokerAuthorization.rows(source, 1, 1, listOf(demand), "boot", 0, 0)
		database.sourceBrokerDao().insertAuthorizations(authorization)
		if (source == SourceDestinationOwnerEntity.SOURCE_WIFI) {
			database.ambientWifiFactDao().insertRetentionAuthority(AmbientWifiRetentionAuthorityIntegrity.create(
				"LIVE_AMBIENT", 1, "ACTIVE", "$name-retention", 1, 1, 0, "boot", 0, 0))
		} else {
			database.ambientCellFactDao().insertRetentionAuthority(AmbientCellRetentionAuthorityIntegrity.create(
				"LIVE_AMBIENT", 1, "ACTIVE", "$name-retention", 1, 1, 0, "boot", 0, 0))
		}
		return authorization.first().authorizationFingerprint
	}
}

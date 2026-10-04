package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
		if (detailGroup) database.radioObservationChildDao().insertWifiBackground((0..1).map { index ->
			val providerTime = 1_000_000_000L + index * 100_000_000L + wallOffsetMs * 1_000_000L
			val wall = wallOffsetMs + if (index == 0) firstChildTimeMs else 2_000L
			val bssid = "aa:bb:cc:dd:ee:f$index"
			val name = if (index == 0) "Home" else "Cafe"
			val frequency = if (index == 0) 2_412 else 5_180
			val signal = if (index == 0) -70 else -40
			val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, fact.writerId, fact.writerVersion, id, 1L, index)
			AmbientWifiChildEntity(fact.writerId, fact.writerVersion, id, 1L, index, providerTime,
				wall, 1L, bssid, name, frequency, signal,
				RadioObservationChildIntegrity.wifi(WifiChildChecksumInput(owner, providerTime, wall, 1L,
					bssid, name, frequency, signal)))
		})
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
		if (detailGroup) database.radioObservationChildDao().insertCellBackground((0..1).map { index ->
			val providerTime = 1_000_000_000L + index * 100_000_000L + wallOffsetMs * 1_000_000L
			val wall = wallOffsetMs + if (index == 0) firstChildTimeMs else 2_000L
			val tower = if (index == 0) "LTE:123" else "NR:456"
			val radio = if (index == 0) "LTE" else "NR"
			val registered = index == 0
			val signal = if (index == 0) -72 else -90
			val owner = RadioChildOwner(RadioChildPurpose.BACKGROUND, fact.writerId, fact.writerVersion, id, 1L, index)
			AmbientCellChildEntity(fact.writerId, fact.writerVersion, id, 1L, index, providerTime,
				wall, 1L, tower, radio, registered, signal,
				RadioObservationChildIntegrity.cell(CellChildChecksumInput(owner, providerTime, wall, 1L,
					tower, radio, registered, signal)))
		})
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

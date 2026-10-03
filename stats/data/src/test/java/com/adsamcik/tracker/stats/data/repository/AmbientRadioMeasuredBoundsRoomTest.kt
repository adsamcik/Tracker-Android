package com.adsamcik.tracker.stats.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.measuredStartTimeMs
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason
import io.kotest.matchers.shouldBe
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native Room rows -> API -> source-owned portable bounds; authored, not executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientRadioMeasuredBoundsRoomTest {
	private lateinit var db: AppDatabase
	@Before fun setup() {
		db = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
	}
	@After fun close() { db.close() }

	@Test fun `rounded Wi-Fi and Cell sibling intervals fit the full API and portable parent`() = runTest {
		insert(wifi())
		insert(cell())
		val wifiApi = assertNotNull(db.ambientWifiFactDao().effectiveLocalOverWindow(1_000, 1_001, 10)
			.single().toApiFact())
		val cellApi = assertNotNull(db.ambientCellFactDao().effectiveLocalOverWindow(1_000, 1_001, 10)
			.single().toApiFact())
		wifiApi.coverageStartTimeMs shouldBe 998L
		cellApi.coverageStartTimeMs shouldBe 998L
		wifiApi.latestPossibleTimeMs shouldBe 1_001L
		cellApi.latestPossibleTimeMs shouldBe 1_001L
		val wifiPortable = AmbientWifiPortableIntegrity.createFact(wifiApi)
		val cellPortable = AmbientCellPortableIntegrity.createFact(cellApi)
		wifiPortable.coverageStartTimeMs shouldBe wifiApi.coverageStartTimeMs
		cellPortable.coverageStartTimeMs shouldBe cellApi.coverageStartTimeMs
		wifiPortable.latestPossibleTimeMs shouldBe wifiApi.latestPossibleTimeMs
		cellPortable.latestPossibleTimeMs shouldBe cellApi.latestPossibleTimeMs
		// Both children map to wall=1000, including a sibling fractionally before the latest.
		// Its 1 ms rounding uncertainty must fit the parent without tolerant import rules.
		listOf(1_000L to 0L, 1_000L to 1L).forEach { (wall, uncertainty) ->
			(wall - uncertainty >= wifiPortable.coverageStartTimeMs) shouldBe true
			(wall + uncertainty <= wifiPortable.latestPossibleTimeMs) shouldBe true
			(wall - uncertainty >= cellPortable.coverageStartTimeMs) shouldBe true
			(wall + uncertainty <= cellPortable.latestPossibleTimeMs) shouldBe true
		}
		AmbientWifiPortableIntegrity.createArchive("e".repeat(64), listOf(wifiPortable), emptyList())
			.facts.single() shouldBe wifiPortable
		AmbientCellPortableIntegrity.createArchive("f".repeat(64), listOf(cellPortable), emptyList())
			.facts.single() shouldBe cellPortable
	}

	@Test fun `full returned uncertainty does not widen observation-date or stored-day selection`() = runTest {
		insert(wifi())
		insert(cell())
		// Returned bounds overlap these windows, but their observation timestamp is outside.
		db.ambientWifiFactDao().effectiveLocalOverWindow(999, 1_000, 10) shouldBe emptyList()
		db.ambientCellFactDao().effectiveLocalOverWindow(999, 1_000, 10) shouldBe emptyList()
		db.ambientWifiFactDao().effectiveLocalOverWindow(1_001, 1_002, 10) shouldBe emptyList()
		db.ambientCellFactDao().effectiveLocalOverWindow(1_001, 1_002, 10) shouldBe emptyList()
		db.ambientWifiFactDao().effectiveLocalForDay(0, "UTC", 10).size shouldBe 1
		db.ambientCellFactDao().effectiveLocalForDay(0, "UTC", 10).size shouldBe 1
		db.ambientWifiFactDao().effectiveLocalForDay(1, "UTC", 10) shouldBe emptyList()
		db.ambientCellFactDao().effectiveLocalForDay(1, "UTC", 10) shouldBe emptyList()
	}

	@Test fun `zero-span zero-uncertainty bounds stay exact and negative bounds are not invented`() = runTest {
		val wifiDraft = wifi(uncertainty = 0).let { it.copy(observedIntervalStartNanos = it.observedElapsedNanos) }
		val cellDraft = cell(uncertainty = 0).let { it.copy(observedIntervalStartNanos = it.observedElapsedNanos) }
		insert(wifiDraft.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(wifiDraft)))
		insert(cellDraft.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(cellDraft)))
		val wifiRow = db.ambientWifiFactDao().effectiveLocalOverWindow(1_000, 1_001, 10).single()
		val cellRow = db.ambientCellFactDao().effectiveLocalOverWindow(1_000, 1_001, 10).single()
		assertNotNull(wifiRow.toApiFact()).coverageStartTimeMs shouldBe 1_000L
		assertNotNull(cellRow.toApiFact()).coverageStartTimeMs shouldBe 1_000L
		assertNotNull(wifiRow.toApiFact()).latestPossibleTimeMs shouldBe 1_000L
		assertNotNull(cellRow.toApiFact()).latestPossibleTimeMs shouldBe 1_000L
		assertNull(wifiRow.copy(fact = wifi(observedMs = 0, uncertainty = 1)).toApiFact())
		assertNull(cellRow.copy(fact = cell(observedMs = 0, uncertainty = 1)).toApiFact())
	}

	@Test fun `older midnight-clipped groups recover real bounds without changing saved identity or date`() = runTest {
		val wifi = wifiAcrossMidnight()
		val cell = cellAcrossMidnight()
		insert(wifi)
		insert(cell)
		val wifiRow = db.ambientWifiFactDao().effectiveLocalOverWindow(DAY, DAY + 20_000, 10).single()
		val cellRow = db.ambientCellFactDao().effectiveLocalOverWindow(DAY, DAY + 20_000, 10).single()
		val wifiApi = assertNotNull(wifiRow.toApiFact())
		val cellApi = assertNotNull(cellRow.toApiFact())
		wifiApi.coverageStartTimeMs shouldBe DAY - 21_001
		cellApi.coverageStartTimeMs shouldBe DAY - 21_001
		wifiApi.structuralEpochDay shouldBe 1L
		cellApi.structuralEpochDay shouldBe 1L
		wifiApi.identity shouldBe wifi.logicalFactId
		cellApi.identity shouldBe cell.logicalFactId
		AmbientWifiPortableIntegrity.createFact(wifiApi).coverageStartTimeMs shouldBe DAY - 21_001
		AmbientCellPortableIntegrity.createFact(cellApi).coverageStartTimeMs shouldBe DAY - 21_001
		// Recovery is a read of the saved elapsed interval, not a rewrite or a new daily group.
		db.ambientWifiFactDao().effectiveLocalOverWindow(DAY, DAY + 20_000, 10).single().fact shouldBe wifi
		db.ambientCellFactDao().effectiveLocalOverWindow(DAY, DAY + 20_000, 10).single().fact shouldBe cell
	}

	@Test fun `observation discovery finds the earlier part of an older midnight-clipped delivery`() = runTest {
		insert(wifiAcrossMidnight(uncertainty = 0))
		insert(cellAcrossMidnight(uncertainty = 0))
		db.ambientWifiFactDao().effectiveLocalOverObservationWindow(DAY - 21_000, DAY, 10).size shouldBe 1
		db.ambientCellFactDao().effectiveLocalOverObservationWindow(DAY - 21_000, DAY, 10).size shouldBe 1
		// The following day has no candidate past its actual latest observed instant.
		db.ambientWifiFactDao().effectiveLocalOverObservationWindow(DAY + 10_001, DAY + 20_000, 10) shouldBe emptyList()
		db.ambientCellFactDao().effectiveLocalOverObservationWindow(DAY + 10_001, DAY + 20_000, 10) shouldBe emptyList()
	}

	@Test fun `fractional older midnight groups enclose every child and are found on its measured day`() = runTest {
		val wifiDraft = wifiAcrossMidnight().copy(coverageStartTimeMs = DAY, observedWallTimeMs = DAY,
			observedIntervalStartNanos = 8_900_000, observedElapsedNanos = 9_600_000,
			receivedElapsedNanos = 10_000_000)
		val cellDraft = cellAcrossMidnight().copy(coverageStartTimeMs = DAY, observedWallTimeMs = DAY,
			observedIntervalStartNanos = 8_900_000, observedElapsedNanos = 9_600_000,
			receivedElapsedNanos = 10_000_000)
		insert(wifiDraft.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(wifiDraft)))
		insert(cellDraft.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(cellDraft)))
		val wifiApi = assertNotNull(db.ambientWifiFactDao()
			.effectiveLocalOverObservationWindow(DAY - 1, DAY, 10).single().toApiFact())
		val cellApi = assertNotNull(db.ambientCellFactDao()
			.effectiveLocalOverObservationWindow(DAY - 1, DAY, 10).single().toApiFact())
		val wifiPortable = AmbientWifiPortableIntegrity.createFact(wifiApi)
		val cellPortable = AmbientCellPortableIntegrity.createFact(cellApi)
		wifiPortable.coverageStartTimeMs shouldBe DAY - 2
		cellPortable.coverageStartTimeMs shouldBe DAY - 2
		// Callback at midnight: floor(1.1 ms) maps the earlier child to DAY-1 with U1.
		// The later child maps to DAY with U1. Both strict intervals fit the parent.
		listOf(DAY - 1 to 1L, DAY to 1L).forEach { (wall, uncertainty) ->
			(wall - uncertainty >= wifiPortable.coverageStartTimeMs) shouldBe true
			(wall + uncertainty <= wifiPortable.latestPossibleTimeMs) shouldBe true
			(wall - uncertainty >= cellPortable.coverageStartTimeMs) shouldBe true
			(wall + uncertainty <= cellPortable.latestPossibleTimeMs) shouldBe true
		}
	}

	@Test fun `retention does not expose an older child's time hidden by a midnight-clipped parent`() = runTest {
		insert(wifiAcrossMidnight())
		insert(cellAcrossMidnight())
		db.sourceEvidenceStateDao().ensure()
		db.sourceEvidenceStateDao().updateLifecycle(0, DAY, DAY + 20_000)
		val reader = RetainedAmbientRadioReader(db)
		val wifi = reader.qualifyWifiInTransaction(
			db.ambientWifiFactDao().effectiveLocalOverWindow(DAY, DAY + 20_000, 10), 10)
		val cell = reader.qualifyCellInTransaction(
			db.ambientCellFactDao().effectiveLocalOverWindow(DAY, DAY + 20_000, 10), 10)
		wifi.rows shouldBe emptyList()
		cell.rows shouldBe emptyList()
		wifi.gaps shouldBe setOf(RadioContextReadGapReason.RETENTION_BOUNDARY)
		cell.gaps shouldBe setOf(RadioContextReadGapReason.RETENTION_BOUNDARY)
	}

	@Test fun `an elapsed interval before the UTC epoch is unavailable instead of invented`() = runTest {
		val wifiDraft = wifi().copy(observedIntervalStartNanos = 1, observedElapsedNanos = 2_000_000_001,
			receivedElapsedNanos = 2_000_000_001)
		val cellDraft = cell().copy(observedIntervalStartNanos = 1, observedElapsedNanos = 2_000_000_001,
			receivedElapsedNanos = 2_000_000_001)
		val wifi = wifiDraft.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(wifiDraft))
		val cell = cellDraft.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(cellDraft))
		insert(wifi)
		insert(cell)
		assertNull(wifi.measuredStartTimeMs())
		assertNull(cell.measuredStartTimeMs())
		assertNull(db.ambientWifiFactDao().effectiveLocalOverWindow(1_000, 1_001, 10).single().toApiFact())
		assertNull(db.ambientCellFactDao().effectiveLocalOverWindow(1_000, 1_001, 10).single().toApiFact())
	}

	private fun wifiAcrossMidnight(uncertainty: Long = 1): AmbientWifiFactRevisionEntity {
		val draft = wifi().copy(structuralEpochDay = 1, structuralDayStartTimeMs = DAY,
			structuralDayEndTimeMs = 2 * DAY, coverageStartTimeMs = DAY, observedWallTimeMs = DAY + 10_000,
			observedIntervalStartNanos = 1_000_000, observedElapsedNanos = 31_001_000_000,
			receivedElapsedNanos = 40_001_000_000, wallTimeUncertaintyMs = uncertainty, appliedAtMs = DAY + 20_000)
		return draft.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(draft))
	}

	private fun cellAcrossMidnight(uncertainty: Long = 1): AmbientCellFactRevisionEntity {
		val draft = cell().copy(structuralEpochDay = 1, structuralDayStartTimeMs = DAY,
			structuralDayEndTimeMs = 2 * DAY, coverageStartTimeMs = DAY, observedWallTimeMs = DAY + 10_000,
			observedIntervalStartNanos = 1_000_000, observedElapsedNanos = 31_001_000_000,
			receivedElapsedNanos = 40_001_000_000, wallTimeUncertaintyMs = uncertainty, appliedAtMs = DAY + 20_000)
		return draft.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(draft))
	}

	private suspend fun insert(fact: AmbientWifiFactRevisionEntity) {
		db.ambientWifiFactDao().insertRevision(fact)
		db.ambientWifiFactDao().insertCursor(AmbientWifiFactCursorEntity(fact.writerId, fact.writerVersion,
			fact.logicalFactId, fact.semanticRevision, fact.mutationId, fact.effectChecksum,
			fact.sourceAdmissionOrdinal, fact.collectedDataEpoch, fact.scopeDeletionGeneration, 1, fact.appliedAtMs))
	}
	private suspend fun insert(fact: AmbientCellFactRevisionEntity) {
		db.ambientCellFactDao().insertRevision(fact)
		db.ambientCellFactDao().insertCursor(AmbientCellFactCursorEntity(fact.writerId, fact.writerVersion,
			fact.logicalFactId, fact.semanticRevision, fact.mutationId, fact.effectChecksum,
			fact.sourceAdmissionOrdinal, fact.collectedDataEpoch, fact.scopeDeletionGeneration, 1, fact.appliedAtMs))
	}

	/** Handcrafted, authentic immutable fact values isolate the public interval mapping. */
	private fun wifi(observedMs: Long = 1_000, uncertainty: Long = 1): AmbientWifiFactRevisionEntity {
		val delivery = "a".repeat(64)
		val id = AmbientWifiFactIntegrity.logicalFactId(delivery, 0, 0, 0, "wifi-demand")
		val unsigned = AmbientWifiFactRevisionEntity(
			writerId = AmbientWifiFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientWifiFactRevisionEntity.WRITER_VERSION, writerOwnerGeneration = 1,
			logicalFactId = id, semanticRevision = 1, supersedesSemanticRevision = null,
			mutationId = AmbientWifiFactIntegrity.mutationId(id, 1), factKind = "AGGREGATE",
			aggregateOwnerLogicalFactId = null, aggregateOwnerSemanticRevision = null,
			sourceEventId = "wifi", sourceAdmissionOrdinal = 1, walIntegrityIdentity = "b".repeat(64),
			payloadChecksum = "c".repeat(64), sourceDeliveryIdentity = delivery, sourceInstanceId = "instance",
			registrationGeneration = 1, configurationRevision = 1, physicalConfigurationFingerprint = "physical",
			authorizationRevision = 1, authorizationFingerprint = "d".repeat(64),
			purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT, sourcePolicyRevision = 1,
			ambientConsentEpoch = 0, retentionPolicyId = "retention", retentionApprovalRevision = 1,
			collectedDataEpoch = 0, scopeDeletionGeneration = 0, clockDomainId = "boot", storedZoneId = "UTC",
			structuralEpochDay = 0, structuralDayStartTimeMs = 0, structuralDayEndTimeMs = 86_400_000,
			observedIntervalStartNanos = 7_999_000, observedElapsedNanos = 8_000_000, receivedElapsedNanos = 10_000_000,
			coverageStartTimeMs = observedMs, observedWallTimeMs = observedMs, wallTimeUncertaintyMs = uncertainty,
			coverageCompleteness = "UNVERIFIABLE", observationCount = 2, twoPointFourGhzCount = 1,
			fiveGhzCount = 1, sixGhzCount = 0, otherBandCount = 0, strongestSignalDbm = -45,
			weakestSignalDbm = -61, signalSumDbm = -106, qualityFlags = 0, qualityConfidence = null,
			effectChecksum = "0".repeat(64), appliedAtMs = 2_000, purposeDemandId = "wifi-demand")
		return unsigned.copy(effectChecksum = AmbientWifiFactIntegrity.effectChecksum(unsigned))
	}

	private fun cell(observedMs: Long = 1_000, uncertainty: Long = 1): AmbientCellFactRevisionEntity {
		val delivery = "e".repeat(64)
		val id = AmbientCellFactIntegrity.logicalFactId(delivery, 0, 0, 0, "cell-demand")
		val unsigned = AmbientCellFactRevisionEntity(
			writerId = AmbientCellFactRevisionEntity.WRITER_ID,
			writerVersion = AmbientCellFactRevisionEntity.WRITER_VERSION, writerOwnerGeneration = 1,
			logicalFactId = id, semanticRevision = 1, supersedesSemanticRevision = null,
			mutationId = AmbientCellFactIntegrity.mutationId(id, 1), factKind = "AGGREGATE",
			aggregateOwnerLogicalFactId = null, aggregateOwnerSemanticRevision = null,
			sourceEventId = "cell", sourceAdmissionOrdinal = 1, walIntegrityIdentity = "b".repeat(64),
			payloadChecksum = "c".repeat(64), sourceDeliveryIdentity = delivery, sourceInstanceId = "instance",
			registrationGeneration = 1, configurationRevision = 1, physicalConfigurationFingerprint = "physical",
			authorizationRevision = 1, authorizationFingerprint = "d".repeat(64),
			purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT, sourcePolicyRevision = 1,
			ambientConsentEpoch = 0, retentionPolicyId = "retention", retentionApprovalRevision = 1,
			collectedDataEpoch = 0, scopeDeletionGeneration = 0, clockDomainId = "boot", storedZoneId = "UTC",
			structuralEpochDay = 0, structuralDayStartTimeMs = 0, structuralDayEndTimeMs = 86_400_000,
			observedIntervalStartNanos = 7_999_999, observedElapsedNanos = 8_000_000, receivedElapsedNanos = 10_000_000,
			coverageStartTimeMs = observedMs, observedWallTimeMs = observedMs, wallTimeUncertaintyMs = uncertainty,
			coverageCompleteness = "UNVERIFIABLE", subscriptionCompleteness = "UNKNOWN", observationCount = 2,
			registeredObservationCount = 2, gsmCount = 0, cdmaCount = 0, wcdmaCount = 0, tdscdmaCount = 0,
			lteCount = 2, nrCount = 0, qualityUnknownCount = 2, qualityNoneOrUnknownCount = 0,
			qualityPoorCount = 0, qualityModerateCount = 0, qualityGoodCount = 0, qualityGreatCount = 0,
			qualityFlags = 0, qualityConfidence = null, effectChecksum = "0".repeat(64), appliedAtMs = 2_000,
			purposeDemandId = "cell-demand")
		return unsigned.copy(effectChecksum = AmbientCellFactIntegrity.effectChecksum(unsigned))
	}

	private companion object { const val DAY = 86_400_000L }
}

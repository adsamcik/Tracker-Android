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
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
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
		wifiApi.coverageStartTimeMs shouldBe 999L
		cellApi.coverageStartTimeMs shouldBe 999L
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

	@Test fun `zero uncertainty keeps original bounds and unsupported negative bounds are not invented`() = runTest {
		insert(wifi(uncertainty = 0))
		insert(cell(uncertainty = 0))
		val wifiRow = db.ambientWifiFactDao().effectiveLocalOverWindow(1_000, 1_001, 10).single()
		val cellRow = db.ambientCellFactDao().effectiveLocalOverWindow(1_000, 1_001, 10).single()
		assertNotNull(wifiRow.toApiFact()).coverageStartTimeMs shouldBe 1_000L
		assertNotNull(cellRow.toApiFact()).coverageStartTimeMs shouldBe 1_000L
		assertNotNull(wifiRow.toApiFact()).latestPossibleTimeMs shouldBe 1_000L
		assertNotNull(cellRow.toApiFact()).latestPossibleTimeMs shouldBe 1_000L
		assertNull(wifiRow.copy(fact = wifi(observedMs = 0, uncertainty = 1)).toApiFact())
		assertNull(cellRow.copy(fact = cell(observedMs = 0, uncertainty = 1)).toApiFact())
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
}

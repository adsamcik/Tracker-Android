package com.adsamcik.tracker.tracker.source.location

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEffectChecksum
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationAuthorizationBoundaryRepairTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `retained 105 106 107 race repairs only unused authorization time and recovers original selection`() =
		runBlocking(Dispatchers.IO) {
			installRace()
			val original = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT.value))
			val demands = database.sourceBrokerDao().demandsByIds(DEMAND_IDS)
			val before = authorization(TARGET_REVISION)
			assertEquals(TARGET_REVISION, observedRevision())
			assertTrue(database.repairRetroactiveLocationAuthorization(EVENT))
			assertEquals(CAPTURED_REVISION, observedRevision())
			assertEquals(before.map { it.copy(effectiveElapsedRealtimeNanos = ORDERED_BOUNDARY,
				effectiveWallTimeMs = ORDERED_WALL) }, authorization(TARGET_REVISION))
			val retained = requireNotNull(database.sourceEventWalDao().getByEventId(EVENT.value))
			assertEquals(original.copy(payload = retained.payload), retained)
			assertEquals(original.integrityIdentity, retained.integrityIdentity)
			assertTrue(original.payload.contentEquals(retained.payload))
			assertEquals(demands, database.sourceBrokerDao().demandsByIds(DEMAND_IDS))
			assertFalse(database.repairRetroactiveLocationAuthorization(EVENT))
		}

	@Test
	fun `a revision already captured by another observation remains unchanged`() = runBlocking(Dispatchers.IO) {
		installRace()
		val used = wal("already-used", TARGET_REVISION, ORDERED_BOUNDARY + 1L, sequence = 2L)
		database.sourceEventWalDao().insertIgnoringDuplicate(used)
		val before = authorization(TARGET_REVISION)
		assertFalse(database.repairRetroactiveLocationAuthorization(EVENT))
		assertEquals(before, authorization(TARGET_REVISION))
	}

	@Test
	fun `a deny revision is never moved forward`() = runBlocking(Dispatchers.IO) {
		installRace(denied = true)
		val before = authorization(TARGET_REVISION)
		assertFalse(database.repairRetroactiveLocationAuthorization(EVENT))
		assertEquals(before, authorization(TARGET_REVISION))
	}

	@Test
	fun `changed ambient consent cannot qualify as an internal refresh`() = runBlocking(Dispatchers.IO) {
		installRace(targetConsent = 2L)
		val before = authorization(TARGET_REVISION)
		assertFalse(database.repairRetroactiveLocationAuthorization(EVENT))
		assertEquals(before, authorization(TARGET_REVISION))
	}

	@Test
	fun `a user stop cannot qualify as an internal retirement`() = runBlocking(Dispatchers.IO) {
		installRace(retirementReason = "USER_STOP")
		val before = authorization(TARGET_REVISION)
		assertFalse(database.repairRetroactiveLocationAuthorization(EVENT))
		assertEquals(before, authorization(TARGET_REVISION))
	}

	@Test
	fun `invalid original authorization fingerprint is left unchanged`() = runBlocking(Dispatchers.IO) {
		installRace()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_authorization SET authorization_fingerprint = 'invalid' WHERE authorization_revision = 107")
		val before = authorization(TARGET_REVISION)
		assertFalse(database.repairRetroactiveLocationAuthorization(EVENT))
		assertEquals(before, authorization(TARGET_REVISION))
	}

	@Test
	fun `repair cannot cross a later saved denial even when that denial is backdated`() = runBlocking(Dispatchers.IO) {
		installRace()
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(LOCATION, GENERATION,
			TARGET_REVISION + 1L, emptyList(), BOOT, ORDERED_BOUNDARY - 1L, ORDERED_WALL - 1L))
		val before = authorization(TARGET_REVISION)
		assertFalse(database.repairRetroactiveLocationAuthorization(EVENT))
		assertEquals(before, authorization(TARGET_REVISION))
	}

	private suspend fun installRace(
		targetConsent: Long = 1L, denied: Boolean = false, retirementReason: String = "AMBIENT_LOCATION_REPLACED",
	) {
		val oldSession = demand("session-before", CAPTURED_BOUNDARY - 1L, session = true).copy(
			status = SourceDemandEntity.STATUS_RETIRED, retireBootId = BOOT,
			retireElapsedRealtimeNanos = ORDERED_BOUNDARY, retiredAtMs = ORDERED_WALL)
		val session = demand("session-after", ORDERED_BOUNDARY, session = true).copy(
			manifestRevision = 2L, lifecycleLeaseGeneration = 9618L, requestedAtMs = ORDERED_WALL)
		val oldAmbient = demand("ambient-before", CAPTURED_BOUNDARY - 2L).copy(
			status = SourceDemandEntity.STATUS_RETIRED, retireBootId = BOOT,
			retireElapsedRealtimeNanos = BACKDATED_BOUNDARY, retiredAtMs = BACKDATED_WALL)
		val ambient = demand("ambient-after", BACKDATED_BOUNDARY).copy(
			consentEpoch = targetConsent, requestedAtMs = BACKDATED_WALL)
		database.sourceBrokerDao().insertDemands(listOf(oldSession, session, oldAmbient, ambient))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(LOCATION, GENERATION,
			CAPTURED_REVISION, listOf(oldSession, oldAmbient), BOOT, CAPTURED_BOUNDARY, CAPTURED_WALL))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(LOCATION, GENERATION,
			ORDERED_REVISION, listOf(session, oldAmbient), BOOT, ORDERED_BOUNDARY, ORDERED_WALL))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(LOCATION, GENERATION,
			TARGET_REVISION, if (denied) { emptyList() } else { listOf(session, ambient) }, BOOT,
			BACKDATED_BOUNDARY, BACKDATED_WALL))
		caller(oldAmbient, retired = true, retirementReason = retirementReason)
		caller(ambient, retired = false)
		database.sourceEventWalDao().insertIgnoringDuplicate(wal(EVENT.value, CAPTURED_REVISION, OBSERVED))
	}

	private fun demand(id: String, requested: Long, session: Boolean = false): SourceDemandEntity {
		val ambient = ambientDemand(id, requested)
		return if (session) {
			ambient.copy(consumerId = "session:trip", purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				logicalTrackingId = "trip", serviceRunId = "run", manifestRevision = 1L,
				lifecycleLeaseGeneration = 9617L, qosCode = 2, desiredLatencyMs = 5_000L,
				liveAmbientRetentionPolicyId = null, liveAmbientRetentionApprovalRevision = null)
		} else {
			ambient
		}
	}

	private fun ambientDemand(id: String, requested: Long) = SourceDemandEntity(
		demandId = id,
		consumerId = "ambient:location",
		sourceKind = LOCATION,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 9L,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 0,
		minimumAcquisitionSpec = "location:v1:min_mode=LOW_POWER",
		adaptiveReductionAllowed = true,
		maximumAgeMs = 120_000L,
		desiredLatencyMs = 10_000L,
		requestedDeliveryLatencyMs = 120_000L,
		requestedBootId = BOOT,
		requestedElapsedRealtimeNanos = requested,
		requestedAtMs = CAPTURED_WALL,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
		sourceCallerAuthorityReference = "caller:$id",
		liveAmbientRetentionPolicyId = "retention",
		liveAmbientRetentionApprovalRevision = 1L,
	)

	private suspend fun caller(demand: SourceDemandEntity, retired: Boolean, retirementReason: String = "") {
		val row = SourceCallerAcceptedAuthorityEntity(
			reference = requireNotNull(demand.sourceCallerAuthorityReference),
			formatVersion = SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION,
			origin = "PURPOSE_OWNER",
			acceptedPurpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			sourceKind = LOCATION,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			policyRevision = demand.sourcePolicyRevision,
			consentEpoch = demand.consentEpoch,
			collectedDataEpoch = 0L,
			rolloutRevision = 12L,
			executionRevision = 1L,
			ownerCasToken = "caller-token",
			logicalTrackingId = null,
			manifestRevision = null,
			status = if (retired) {
				SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED
			} else {
				SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE
			},
			createdAtMs = demand.requestedAtMs,
			retiredAtMs = if (retired) { BACKDATED_WALL } else { null },
			retireReason = if (retired) { retirementReason } else { null },
			effectChecksum = "",
		)
		database.sourceCallerAuthorityDao().insert(SourceCallerAcceptedAuthorityEffectChecksum.seal(listOf(row)))
	}

	private suspend fun wal(id: String, revision: Long, observed: Long, sequence: Long = 1L): SourceEventWalEntity {
		val grant = authorization(revision).first()
		val row = SourceEventWalEntity(
			eventId = id,
			providerDedupKey = null,
			logicalTrackingId = "trip",
			serviceRunId = "run",
			sourceKind = LOCATION,
			sourceInstanceId = "location-instance",
			registrationGeneration = GENERATION,
			authorizationRevision = revision,
			authorizationPurposeEligibilityMask = grant.purposeEligibilityMask,
			authorizationFingerprint = grant.authorizationFingerprint,
			sourceSequence = sequence,
			configRevision = 78L,
			planAttribution = 0,
			clockDomainId = BOOT,
			observedElapsedNanos = observed,
			receivedElapsedNanos = observed + 1L,
			wallTimeMs = BACKDATED_WALL,
			wallTimeUncertaintyMs = 0L,
			capturedCollectedDataEpoch = 0L,
			activityAutomationEpoch = null,
			sourcePolicyRevision = 9L,
			captureConsentEpoch = 1L,
			sessionManifestRevision = 1L,
			lifecycleLeaseGeneration = 9617L,
			acquiredAtMs = BACKDATED_WALL,
			qualityFlags = 0L,
			qualityConfidence = null,
			payloadVersion = 2,
			payload = byteArrayOf(1),
			payloadChecksum = "",
			createdAtMs = BACKDATED_WALL,
		)
		val checked = row.copy(payloadChecksum = row.calculatedPayloadChecksum())
		return checked.copy(integrityIdentity = checked.calculatedIntegrityIdentity())
	}

	private suspend fun authorization(revision: Long) =
		database.sourceBrokerDao().authorizationRevision(LOCATION, GENERATION, revision)

	private suspend fun observedRevision() = database.sourceBrokerDao()
		.authorizationAt(LOCATION, GENERATION, BOOT, OBSERVED).first().authorizationRevision

	companion object {
		private const val LOCATION = 1
		private const val GENERATION = 36L
		private const val BOOT = "android-boot-count:13"
		private const val CAPTURED_REVISION = 105L
		private const val ORDERED_REVISION = 106L
		private const val TARGET_REVISION = 107L
		private const val CAPTURED_BOUNDARY = 2_303_995_297_900L
		private const val ORDERED_BOUNDARY = 2_343_041_034_000L
		private const val BACKDATED_BOUNDARY = 2_331_163_092_200L
		private const val OBSERVED = 2_334_277_009_300L
		private const val CAPTURED_WALL = 1_792_824_578_960L
		private const val ORDERED_WALL = 1_792_824_618_006L
		private const val BACKDATED_WALL = 1_792_824_606_128L
		private val EVENT = SourceEventId("retained-event")
		private val DEMAND_IDS = listOf("session-before", "session-after", "ambient-before", "ambient-after")
	}
}

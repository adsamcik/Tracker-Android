package com.adsamcik.tracker.tracker.source.projection

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.database.data.ACTIVITY_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.PRESSURE_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.ingress.DeliveryAdmissionResult
import com.adsamcik.tracker.tracker.source.ingress.RoomDurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.sourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AllDaySensorProjectionTest {
	private lateinit var database: AppDatabase
	private lateinit var ingress: RoomDurableSourceIngress
	private val snapshot = CollectedDataLifecycleSnapshot(0L, null)
	private val gate = object : TrackingStartupGate {
		override val isReady = true
		override suspend fun reconcile(retryFailedStorage: Boolean) = TrackingStartupResult.Ready(false, 0L)
	}

	@Before
	fun setUp() {
		database = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext<Application>())
		runBlocking { database.sourceEvidenceStateDao().ensure(SourceEvidenceState()) }
		val lifecycle = mockk<CollectedDataLifecycleStore>()
		coEvery { lifecycle.snapshot() } returns snapshot
		every { lifecycle.snapshots } returns MutableStateFlow(snapshot)
		ingress = RoomDurableSourceIngress(database, lifecycle, DefaultSourcePayloadCodec(),
			ExecutableSourceLaneCatalog(), Provider { gate })
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `source-only Activity and Pressure reach native history and replay once without sessions`() = runTest {
		for (source in listOf(SourceKind.ACTIVITY, SourceKind.PRESSURE)) {
			val delivery = seed(source, listOf(SourceBrokerPurpose.AMBIENT_PRODUCT))
			assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(delivery))
			project(source)
			project(source)
			assertIs<DeliveryAdmissionResult.Duplicate>(ingress.admit(delivery))
			val event = ingress.committedBatch(0L, 10).single { it.evidence.source == source }
			assertNull(event.evidence.logicalTrackingId)
			if (source == SourceKind.ACTIVITY) {
				assertEquals(1, database.activitySnapshotDao().allDayCount())
				val activity = assertNotNull(database.activitySnapshotDao().getBySourceSignalId(
					AllDaySensorObservationIdentity.activity(event.eventId.value)))
				assertEquals(85, activity.confidence)
				assertEquals(DetectedActivity.WALKING.value, activity.activityType)
			} else {
				val pressure = database.pressureSampleDao().getBySourceSignalId(
					AllDaySensorObservationIdentity.pressure(event.eventId.value))
				assertNotNull(pressure)
				assertEquals(2, pressure.sampleCount)
				assertEquals(90_000_000L, pressure.windowStartElapsedRealtimeNanos)
				assertEquals(110_000_000L, pressure.windowEndElapsedRealtimeNanos)
				assertEquals(1, database.pressureSampleDao().allDayBetween(0L, 2_000L, 10).size)
			}
		}
	}

	@Test
	fun `Pressure window crossing End keeps original all-day measurements and callback identity`() = runTest {
		val delivery = seed(SourceKind.PRESSURE,
			listOf(SourceBrokerPurpose.AMBIENT_PRODUCT, SourceBrokerPurpose.SESSION_CAPTURE))
		val background = database.sourceBrokerDao().authorizationDemands(4).filter {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		database.sourceBrokerDao().retireConsumer("capture:4", "boot", 100_000_000L, 990L)
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(4, 1L, 2L,
			background, "boot", 100_000_000L, 990L))
		assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(delivery))
		project(SourceKind.PRESSURE)
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		assertEquals(1L, stored.authorizationRevision)
		assertEquals(delivery.units.single().evidence.registrationEligibilityFingerprint,
			stored.authorizationFingerprint)
		assertNull(stored.logicalTrackingId)
		assertNull(stored.serviceRunId)
		assertNull(stored.sessionManifestRevision)
		val pressure = database.pressureSampleDao().allDayBetween(0L, 2_000L, 10).single()
		assertEquals(90_000_000L, pressure.windowStartElapsedRealtimeNanos)
		assertEquals(110_000_000L, pressure.windowEndElapsedRealtimeNanos)
	}

	@Test
	fun `Pressure quality replacement preserves the unfinished old-header window`() = runTest {
		val source = SourceKind.PRESSURE
		val delivery = seed(source, listOf(SourceBrokerPurpose.AMBIENT_PRODUCT))
		replacePressureAmbientDemand(consentEpoch = 1L)
		assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(delivery))
		project(source)
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		assertEquals(1L, stored.authorizationRevision)
		assertEquals(delivery.units.single().evidence.registrationEligibilityFingerprint,
			stored.authorizationFingerprint)
		val pressure = database.pressureSampleDao().allDayBetween(0L, 2_000L, 10).single()
		assertEquals(2, pressure.sampleCount)
		assertEquals(90_000_000L, pressure.windowStartElapsedRealtimeNanos)
		assertEquals(110_000_000L, pressure.windowEndElapsedRealtimeNanos)
	}

	@Test
	fun `Pressure replacement after revoked consent cannot borrow the old window`() = runTest {
		val delivery = seed(SourceKind.PRESSURE, listOf(SourceBrokerPurpose.AMBIENT_PRODUCT))
		replacePressureAmbientDemand(consentEpoch = 2L)
		assertIs<DeliveryAdmissionResult.PermanentFailure>(ingress.admit(delivery))
		project(SourceKind.PRESSURE)
		assertEquals(0, database.pressureSampleDao().allDayBetween(0L, 2_000L, 10).size)
	}

	private suspend fun replacePressureAmbientDemand(consentEpoch: Long) {
		val code = SourceKind.PRESSURE.stableCode
		val broker = database.sourceBrokerDao()
		val original = broker.authorizationDemands(code).single()
		val policy = requireNotNull(database.sourcePolicyDao().policyAtRevision(1L, code))
		database.sourcePolicyDao().insertPolicies(listOf(policy.copy(policyRevision = 2L,
			ambientConsentEpoch = consentEpoch, effectiveElapsedRealtimeNanos = 100_000_000L,
			effectiveWallTimeMs = 990L)))
		if (consentEpoch != 1L) {
			database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(code,
				SourceBrokerPurpose.AMBIENT_PRODUCT, consentEpoch, true, true, 2L,
				"boot", 100_000_000L, 990L, "RE_ENABLED")))
		}
		broker.retireConsumer(original.consumerId, "boot", 100_000_000L, 990L)
		val replacement = original.copy(demandId = "replacement:4", sourcePolicyRevision = 2L,
			consentEpoch = consentEpoch, maximumAgeMs = 30_000L, desiredLatencyMs = 10_000L,
			requestedElapsedRealtimeNanos = 100_000_000L, requestedAtMs = 990L)
		broker.insertDemands(listOf(replacement))
		broker.insertAuthorizations(SourceBrokerAuthorization.rows(code, 1L, 2L,
			listOf(replacement), "boot", 100_000_000L, 990L))
	}

	@Test
	fun `automatic Activity control never becomes all-day history`() = runTest {
		assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(seed(SourceKind.ACTIVITY,
			listOf(SourceBrokerPurpose.CONTROL_AUTOSTART))))
		project(SourceKind.ACTIVITY)
		assertEquals(0, database.activitySnapshotDao().allDayCount())
	}

	@Test
	fun `retention and source erase fences prevent a pending observation from returning`() = runTest {
		assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(seed(SourceKind.ACTIVITY,
			listOf(SourceBrokerPurpose.AMBIENT_PRODUCT))))
		assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(seed(SourceKind.PRESSURE,
			listOf(SourceBrokerPurpose.AMBIENT_PRODUCT))))
		database.sourceDeletionFenceDao().upsert(SourceDeletionFenceEntity.createSourceConsentEpoch(2,
			SourceBrokerPurpose.AMBIENT_PRODUCT, 2L, 0L, 1_001L))
		project(SourceKind.ACTIVITY)
		assertEquals(0, database.activitySnapshotDao().allDayCount())
		database.sourceEvidenceStateDao().updateLifecycle(0L, 1_001L, 1_001L)
		project(SourceKind.PRESSURE)
		assertEquals(0, database.pressureSampleDao().allDayBetween(0L, 2_000L, 10).size)
	}

	@Test
	fun `source Off fences later observations while the old broker request awaits reconciliation`() = runTest {
		assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(seed(SourceKind.ACTIVITY,
			listOf(SourceBrokerPurpose.AMBIENT_PRODUCT))))
		database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(2,
			SourceBrokerPurpose.AMBIENT_PRODUCT, 2L, false, false, 2L, "boot", 109_999_999L, 999L, "OFF")))
		project(SourceKind.ACTIVITY)
		assertEquals(0, database.activitySnapshotDao().allDayCount())
	}

	@Test
	fun `later recording choices do not hide valid observations waiting for materialization`() = runTest {
		assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(seed(SourceKind.ACTIVITY,
			listOf(SourceBrokerPurpose.AMBIENT_PRODUCT))))
		val original = requireNotNull(database.sourcePolicyDao().policyAtRevision(1L, 2))
		database.sourcePolicyDao().insertPolicies(listOf(original.copy(policyRevision = 2L,
			ambientPersistenceEligible = false, ambientConsentEpoch = 2L)))
		database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(2,
			SourceBrokerPurpose.AMBIENT_PRODUCT, 2L, false, false, 2L, "boot", 120_000_000L, 1_010L, "OFF")))
		project(SourceKind.ACTIVITY)
		assertEquals(1, database.activitySnapshotDao().allDayCount())
	}

	private suspend fun project(source: SourceKind) {
		val projection = AllDaySensorProjection(source, database, BootClockDomainProvider { "boot" })
		for (event in ingress.committedBatch(0L, 10).filter { it.evidence.source == source }) {
			database.withTransaction { projection.apply(event, mockk()) }
		}
	}

	@Test
	fun `a same boot wall correction keeps measured sensor data and native retention usable`() = runTest {
		val source = SourceKind.ACTIVITY
		val delivery = seed(source, listOf(SourceBrokerPurpose.AMBIENT_PRODUCT), effectiveWall = 2_000L)
		assertIs<DeliveryAdmissionResult.Admitted>(ingress.admit(delivery))
		project(source)
		assertEquals(1, database.activitySnapshotDao().allDayCount())
		val grant = com.adsamcik.tracker.tracker.source.runtime.LiveAmbientRetentionGrant(source,
			1L, 1L, 0L, null, "retention", 1L, "boot", 1L, 2_000L)
		assertEquals(true, grant.isEffectiveAt("boot", 110_000_000L, 1_000L))
		assertEquals(false, grant.isEffectiveAt("other-boot", 110_000_000L, 1_000L))
	}

	private suspend fun seed(
		source: SourceKind,
		purposes: List<String>,
		effectiveWall: Long = 1L,
	): SourceDeliveryCandidate {
		val code = source.stableCode
		val demands = purposes.map { purpose -> demand(source, purpose) }
		database.sourceBrokerDao().insertDemands(demands)
		database.sourcePolicyDao().insertPolicies(listOf(SourcePolicyEntity(1L, code, true, 2,
			null, null, null, true, source == SourceKind.ACTIVITY, true, 1L,
			1L.takeIf { source == SourceKind.ACTIVITY }, 1L, "boot", 1L, effectiveWall, "ALL_DAY_TEST")))
		database.sourcePolicyDao().insertConsentEpochs(listOf(SourceConsentEpochEntity(code,
			SourceBrokerPurpose.AMBIENT_PRODUCT, 1L, true, true, 1L, "boot", 1L, effectiveWall, "ALL_DAY_TEST")))
		val owner = "source-broker:$code"
		database.sourceRegistrationStateDao().insertIfAbsent(SourceRegistrationStateEntity(code, owner,
			"sensor-$code", "boot", 1L, 0L, 1L, 0L, 1L))
		database.sourceBrokerDao().insertRegistration(ProviderRegistrationGenerationEntity(
			sourceKind = code, registrationGeneration = 1L, sourceInstanceId = "sensor-$code", ownerScope = owner,
			clockDomainId = "boot", physicalConfigurationFingerprint = "sensor-config-$code", collectedDataEpoch = 0L,
			providerResidency = if (source == SourceKind.ACTIVITY) {
				ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
			} else {
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
			},
			providerProcessIncarnationId = "process".takeIf { source == SourceKind.PRESSURE },
			status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE, reservedAtMs = 0L,
			reservedElapsedRealtimeNanos = 0L, acceptedAtMs = 1L, acceptedElapsedRealtimeNanos = 1L,
			retiredAtMs = null, retiredElapsedRealtimeNanos = null, failureCode = null))
		database.sourceBrokerDao().insertAuthorizations(SourceBrokerAuthorization.rows(code, 1L, 1L,
			demands, "boot", 1L, 1L))
		val payload: SourcePayload = if (source == SourceKind.ACTIVITY) {
			ActivityRecognitionPayload(StableActivityTypeCode.WALKING, 85, 110_000_000L)
		} else {
			PressureWindowPayload(2, 1_001.0, 2.0, 1_000f, 1_002f, 90_000_000L, 110_000_000L, 1L, 2L,
				1_000f, 1_002f, 100.0, 1.0, PressureSensorAccuracy.HIGH, 20_000, 0, 60_000_000L, 3,
				20_000_000L, PressureWindowClosureKind.SOURCE_BOUNDARY)
		}
		val evidence = SourceEvidenceCandidate(providerDedupKey = null, logicalTrackingId = null,
			serviceRunId = null, source = source, sourceInstanceId = SourceInstanceId("sensor-$code"),
			registrationGeneration = 1L, physicalConfigurationFingerprint = "sensor-config-$code",
			authorizationRevision = 1L, registrationPurposeEligibilityMask = SourceBrokerAuthorization.purposeMask(demands),
			registrationEligibilityFingerprint = SourceBrokerAuthorization.fingerprint(demands), sourceSequence = 0L,
			configRevision = 1L, planAttribution = PlanAttribution.CAPTURED_REGISTRATION, clockDomainId = "boot",
			observedElapsedRealtimeNanos = 110_000_000L, receivedElapsedRealtimeNanos = 111_000_000L,
			receivedWallTimeMs = 1_001L, wallTimeMs = 1_000L, wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 0L, acquiredAtMs = 1_000L,
			quality = SourceQuality(flags = if (source == SourceKind.PRESSURE) {
				setOf(SourceQualityFlag.INCOMPLETE_WINDOW)
			} else {
				emptySet()
			}), payloadVersion = if (source == SourceKind.ACTIVITY) {
				ACTIVITY_WAL_PAYLOAD_VERSION
			} else {
				PRESSURE_WAL_PAYLOAD_VERSION
			}, payload = payload)
		return SourceDeliveryCandidate(sourceDeliveryIdentity("all-day:$code".encodeToByteArray()),
			listOf(SourceDeliveryUnit(0, evidence, if (source == SourceKind.PRESSURE) {
				90_000_000L
			} else {
				110_000_000L
			})))
	}

	private fun demand(source: SourceKind, purpose: String): SourceDemandEntity {
		val capture = purpose == SourceBrokerPurpose.SESSION_CAPTURE
		return SourceDemandEntity(demandId = "$purpose:${source.stableCode}",
			consumerId = if (capture) { "capture:${source.stableCode}" } else { "ambient:${source.stableCode}" },
			sourceKind = source.stableCode, purpose = purpose, logicalTrackingId = "session".takeIf { capture },
			serviceRunId = "run".takeIf { capture }, manifestRevision = 1L.takeIf { capture },
			lifecycleLeaseGeneration = 1L.takeIf { capture }, sourcePolicyRevision = 1L, consentEpoch = 1L,
			persistenceEligible = purpose != SourceBrokerPurpose.CONTROL_AUTOSTART, qosCode = 2,
			maximumAgeMs = 60_000L, desiredLatencyMs = 30_000L, requestedBootId = "boot",
			requestedElapsedRealtimeNanos = 1L, requestedAtMs = 1L, status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
			liveAmbientRetentionPolicyId = "privacy:${source.name.lowercase()}:ambient:v1",
			liveAmbientRetentionApprovalRevision = 1L)
	}
}

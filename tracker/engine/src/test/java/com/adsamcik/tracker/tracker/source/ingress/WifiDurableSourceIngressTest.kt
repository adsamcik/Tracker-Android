package com.adsamcik.tracker.tracker.source.ingress

import android.app.Application
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.runtime.AndroidConnectivityDeviceStateProvider
import com.adsamcik.tracker.tracker.source.runtime.AndroidWifiSourceBackend
import com.adsamcik.tracker.tracker.source.runtime.CoalescingSourceWakeupScheduler
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionFailureCode
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceDeliveryAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.SourceRegistration
import com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepository
import com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRetirementToken
import com.adsamcik.tracker.tracker.source.runtime.SourceStartResult
import com.adsamcik.tracker.tracker.source.runtime.WITHHELD_RADIO_IDENTIFIER_TOKEN
import com.adsamcik.tracker.tracker.source.runtime.WifiBackendAccessPoint
import com.adsamcik.tracker.tracker.source.runtime.WifiBackendEvent
import com.adsamcik.tracker.tracker.source.runtime.WifiBackendSnapshot
import com.adsamcik.tracker.tracker.source.runtime.WifiDeviceState
import com.adsamcik.tracker.tracker.source.runtime.WifiSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.qualifiedWifiProviderObservations
import com.adsamcik.tracker.tracker.source.runtime.toMinimizedEvidence
import com.adsamcik.tracker.tracker.source.runtime.wifiProviderDeliveryIdentity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
@Suppress("LargeClass") // One Room fixture keeps runtime-to-ingress race cases comparable.
class WifiDurableSourceIngressTest {
	private lateinit var database: AppDatabase
	private lateinit var ingress: RoomDurableSourceIngress

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		ingress = RoomDurableSourceIngress(
			database = database,
			lifecycleStore = WifiIngressLifecycleStore(),
			payloadCodec = DefaultSourcePayloadCodec(),
			executableLaneCatalog = WIFI_EXECUTABLE_LANE_CATALOG,
			trackingStartupGateProvider = Provider { WifiIngressStartupGate() },
		)
		installWifiCaptureLane()
		RoomTrackingRolloutStateStore(database, WIFI_EXECUTABLE_LANE_CATALOG).save(
			TrackingRolloutState.eventCanonical(
				sources = setOf(SourceKind.WIFI),
				captureModes = mapOf(SourceKind.WIFI to setOf(CaptureReachabilityMode.AMBIENT)),
			),
			updatedAtMs = 1L,
		)
		installWifiDemand()
		installWifiRegistrationGeneration(
			generation = 1L,
			authorizationRevision = 1L,
			processIncarnationId = "wifi-process-before",
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			acceptedElapsedRealtimeNanos = 5_000_000L,
			authorizationEffectiveElapsedRealtimeNanos = 6_000_000L,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `Wi-Fi exact provider replay across runtime generations allocates one Room sequence`() = runTest {
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val first = wifiDeliveryCandidate(
			registrationGeneration = 1L,
			authorizationRevision = 1L,
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 110_000_000L,
		)

		val durable = sink.admit(first)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()

		replaceWifiRuntimeGeneration()
		val replay = wifiDeliveryCandidate(
			registrationGeneration = 2L,
			authorizationRevision = 2L,
			physicalConfigurationFingerprint = SECOND_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 210_000_000L,
		)
		first.identity shouldBe replay.identity
		val firstPayload = first.units.single().evidence.payload
		val replayPayload = replay.units.single().evidence.payload
		firstPayload shouldBe replayPayload
		DefaultSourcePayloadCodec().encode(firstPayload, WIFI_PAYLOAD_VERSION).checksum shouldBe
			DefaultSourcePayloadCodec().encode(replayPayload, WIFI_PAYLOAD_VERSION).checksum

		val duplicate = sink.admit(replay)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()

		durable.admissionOrdinals shouldBe listOf(1L)
		duplicate.existingAdmissionOrdinals shouldBe durable.admissionOrdinals
		database.sourceEventWalDao().countAll() shouldBe 1L
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.sourceKind shouldBe SourceKind.WIFI.stableCode
		stored.sourceInstanceId shouldBe WIFI_SOURCE_INSTANCE_ID
		stored.registrationGeneration shouldBe 1L
		stored.sourceSequence shouldBe 0L
		stored.deliveryIdentity shouldBe first.identity.value
		val registrationState = requireNotNull(
			database.sourceRegistrationStateDao().get(
				SourceKind.WIFI.stableCode,
				WIFI_OWNER_SCOPE,
			),
		)
		registrationState.registrationGeneration shouldBe 2L
		registrationState.nextSequence shouldBe 1L
		database.sourceBrokerDao().registration(SourceKind.WIFI.stableCode, 1L)
			?.providerProcessIncarnationId shouldBe "wifi-process-before"
		database.sourceBrokerDao().registration(SourceKind.WIFI.stableCode, 2L)
			?.providerProcessIncarnationId shouldBe "wifi-process-after"
	}

	@Test
	fun `Wi-Fi mixed pre-boundary snapshot admits only its post-boundary provider fact`() = runTest {
		val qualified = qualifiedWifiProviderObservations(
			accessPoints = listOf(
				WifiBackendAccessPoint("pre-boundary", 2_412, -65, 4_000L),
				WifiBackendAccessPoint("post-boundary", 5_180, -45, 7_000L),
			),
			receivedElapsedRealtimeNanos = 10_000_000L,
			maximumAcceptableResultAgeMs = 1_000L,
			providerAcceptedElapsedRealtimeNanos = 5_000_000L,
			authorizationEffectiveElapsedRealtimeNanos = 6_000_000L,
			cutoffElapsedRealtimeNanos = null,
		).map(WifiBackendAccessPoint::toMinimizedEvidence)
		qualified.map(WifiAccessPointEvidence::frequencyMhz) shouldBe listOf(5_180)
		val delivery = wifiDeliveryCandidate(
			registrationGeneration = 1L,
			authorizationRevision = 1L,
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 10_000_000L,
			accessPoints = qualified,
		)

		DurableSourceEventSinkFactory(ingress).unbound.admit(delivery)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()

		database.sourceEventWalDao().countAll() shouldBe 1L
		val storedRow = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		storedRow.observedIntervalStartNanos shouldBe 7_000_000L
		val storedPayload = ingress.committedBatch(0L, 10).single().evidence.payload
			.shouldBeInstanceOf<WifiResultSnapshotPayload>()
		storedPayload.accessPoints.map(WifiAccessPointEvidence::frequencyMhz) shouldBe listOf(5_180)
	}

	@Test
	fun `Wi-Fi retry cutoff rebuilds the subset before one atomic Room admission`() = runTest {
		val fixture = wifiRetryRuntimeFixture(backgroundScope)
		val roomSink = DurableSourceEventSinkFactory(ingress).unbound

		fixture.runtime.start(fixture.plan, fixture.sink).shouldBeInstanceOf<SourceStartResult.Started>()
		requireNotNull(fixture.callback())(wifiRetryEvent())
		runCurrent()
		fixture.attempted.size shouldBe 1

		val stop = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
			fixture.runtime.quiesce(
				SessionCutoff(
					logicalTrackingId = "wifi-ingress-test",
					elapsedRealtimeNanos = 8_000_000L,
					wallTimeMs = 1_000L,
					deadlineElapsedRealtimeNanos =
						SystemClock.elapsedRealtimeNanos() + 60_000_000_000L,
				),
			)
		}
		runCurrent()
		advanceTimeBy(25L)
		runCurrent()
		val candidate = fixture.roomCandidate.await()
		val roomHandoff = roomSink.admit(candidate)
		fixture.roomResult.complete(roomHandoff)
		runCurrent()
		val ack = stop.await()

		fixture.attempted.size shouldBe 2
		val surviving = fixture.attempted[1].units.single()
		val survivingPayload = surviving.evidence.payload.shouldBeInstanceOf<WifiResultSnapshotPayload>()
		survivingPayload.accessPoints.map(WifiAccessPointEvidence::frequencyMhz) shouldBe listOf(2_412)
		surviving.observedIntervalStartElapsedRealtimeNanos shouldBe 7_000_000L
		surviving.evidence.observedElapsedRealtimeNanos shouldBe 7_000_000L
		fixture.attempted[0].identity shouldBe wifiProviderDeliveryIdentity(
			BOOT_CLOCK_DOMAIN_ID,
			listOf(
				WifiAccessPointEvidence("", 2_412, -50, 7_000_000L),
				WifiAccessPointEvidence("", 5_180, -55, 9_000_000L),
			),
		)
		fixture.attempted[1].identity shouldBe wifiProviderDeliveryIdentity(
			BOOT_CLOCK_DOMAIN_ID,
			listOf(WifiAccessPointEvidence("", 2_412, -50, 7_000_000L)),
		)
		roomHandoff.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		database.sourceEventWalDao().countAll() shouldBe 1L
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.deliveryIdentity shouldBe fixture.attempted[1].identity.value
		stored.sourceSequence shouldBe 0L
		stored.observedIntervalStartNanos shouldBe 7_000_000L
		stored.observedElapsedNanos shouldBe 7_000_000L
		database.sourceRegistrationStateDao().get(SourceKind.WIFI.stableCode, WIFI_OWNER_SCOPE)
			?.nextSequence shouldBe 1L
		ack.lastDurablyAdmittedSequence shouldBe 1L
		ack.lastAdmissionOrdinal shouldBe 1L
		ack.failedAdmissionCount shouldBe 0L
		ack.unresolvedSequenceStart shouldBe null
		ack.unresolvedSequenceEndInclusive shouldBe null
		ack.appDrainComplete shouldBe true
	}

	@Suppress("LongMethod") // The assertion spans the runtime/Room stop linearization handshake.
	@Test
	fun `in-flight Wi-Fi delivery rebuilds against durable stop cutoff before Room mutation`() = runTest {
		installWifiSessionCaptureAuthority()
		upgradeWifiCaptureLaneForManualSession()
		val roomSink = DurableSourceEventSinkFactory(ingress).unbound
		val attempted = mutableListOf<SourceDeliveryCandidate>()
		val firstAdmissionEntered = CompletableDeferred<Unit>()
		val releaseFirstAdmission = CompletableDeferred<Unit>()
		var firstRoomHandoff: SourceDeliveryAdmissionHandoff? = null
		val blockingSink = object : SourceEventSink {
			override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
				error("Wi-Fi must use atomic delivery admission")

			override suspend fun admit(
				delivery: SourceDeliveryCandidate,
			): SourceDeliveryAdmissionHandoff {
				attempted += delivery
				val firstAttempt = attempted.size == 1
				if (firstAttempt) {
					firstAdmissionEntered.complete(Unit)
					releaseFirstAdmission.await()
				}
				val handoff = roomSink.admit(delivery)
				if (firstAttempt) {
					firstRoomHandoff = handoff
					handoff shouldBe SourceDeliveryAdmissionHandoff.SessionCutoff(8_000_000L)
					database.sourceEventWalDao().countAll() shouldBe 0L
					database.sourceRegistrationStateDao()
						.get(SourceKind.WIFI.stableCode, WIFI_OWNER_SCOPE)?.nextSequence shouldBe 0L
				}
				return handoff
			}
		}
		val fixture = wifiCutoffRuntimeFixture(backgroundScope)

		fixture.runtime.start(fixture.plan, blockingSink)
			.shouldBeInstanceOf<SourceStartResult.Started>()
		requireNotNull(fixture.callback())(wifiRetryEvent())
		runCurrent()
		firstAdmissionEntered.await()
		markWifiSessionStopping(cutoffElapsedNanos = 8_000_000L)
		val stop = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
			fixture.runtime.quiesce(wifiSessionCutoff())
		}

		releaseFirstAdmission.complete(Unit)
		runCurrent()
		val ack = stop.await()

		attempted.size shouldBe 2
		firstRoomHandoff shouldBe SourceDeliveryAdmissionHandoff.SessionCutoff(8_000_000L)
		val originalPayload = attempted[0].units.single().evidence.payload
			.shouldBeInstanceOf<WifiResultSnapshotPayload>()
		val surviving = attempted[1].units.single()
		val survivingPayload = surviving.evidence.payload
			.shouldBeInstanceOf<WifiResultSnapshotPayload>()
		originalPayload.accessPoints.map(WifiAccessPointEvidence::frequencyMhz) shouldBe
			listOf(2_412, 5_180)
		survivingPayload.accessPoints.map(WifiAccessPointEvidence::frequencyMhz) shouldBe
			listOf(2_412)
		surviving.observedIntervalStartElapsedRealtimeNanos shouldBe 7_000_000L
		surviving.evidence.observedElapsedRealtimeNanos shouldBe 7_000_000L
		attempted[0].identity shouldBe wifiProviderDeliveryIdentity(
			BOOT_CLOCK_DOMAIN_ID,
			listOf(
				WifiAccessPointEvidence("", 2_412, -50, 7_000_000L),
				WifiAccessPointEvidence("", 5_180, -55, 9_000_000L),
			),
		)
		attempted[1].identity shouldBe wifiProviderDeliveryIdentity(
			BOOT_CLOCK_DOMAIN_ID,
			listOf(WifiAccessPointEvidence("", 2_412, -50, 7_000_000L)),
		)
		database.sourceEventWalDao().countAll() shouldBe 1L
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.deliveryIdentity shouldBe attempted[1].identity.value
		stored.sourceSequence shouldBe 0L
		stored.observedIntervalStartNanos shouldBe 7_000_000L
		stored.observedElapsedNanos shouldBe 7_000_000L
		database.sourceRegistrationStateDao().get(SourceKind.WIFI.stableCode, WIFI_OWNER_SCOPE)
			?.nextSequence shouldBe 1L
		ack.lastDurablyAdmittedSequence shouldBe 1L
		ack.lastAdmissionOrdinal shouldBe 1L
		ack.failedAdmissionCount shouldBe 0L
		ack.unresolvedSequenceStart shouldBe null
		ack.unresolvedSequenceEndInclusive shouldBe null
		ack.appDrainComplete shouldBe true
	}

	@Test
	fun `Wi-Fi-only session stop returns durable cutoff after deny-all rotation`() = runTest {
		installWifiSessionCaptureAuthority(includeAmbient = false)
		upgradeWifiCaptureLaneForManualSession()
		val registration = currentWifiRuntimeRegistration()
		registration.authorization.authorizedMembers.map(SourceAuthorizationEntity::purpose) shouldBe
			listOf(SourceBrokerPurpose.SESSION_CAPTURE)
		database.sourceBrokerDao().currentDemands("app:wifi-ingress-test") shouldBe emptyList()
		val candidate = wifiDeliveryCandidate(
			registrationGeneration = registration.state.registrationGeneration,
			authorizationRevision = registration.authorization.authorizationRevision,
			physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			receivedElapsedRealtimeNanos = 10_000_000L,
			accessPoints = listOf(
				WifiAccessPointEvidence("", 2_412, -50, 7_000_000L),
				WifiAccessPointEvidence("", 5_180, -55, 9_000_000L),
			),
			purposeEligibilityMask = registration.purposeEligibilityMask,
			eligibilityFingerprint = registration.eligibilityFingerprint,
		)

		markWifiSessionStopping(cutoffElapsedNanos = 8_000_000L, retainAmbient = false)

		database.sourceBrokerDao().latestAuthorization(SourceKind.WIFI.stableCode, 1L)
			.toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		ingress.admit(candidate) shouldBe DeliveryAdmissionResult.SessionCutoff(8_000_000L)
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceRegistrationStateDao().get(SourceKind.WIFI.stableCode, WIFI_OWNER_SCOPE)
			?.nextSequence shouldBe 0L
	}

	@Test
	fun `mismatched historical Wi-Fi envelope cannot manufacture a durable cutoff`() = runTest {
		installWifiSessionCaptureAuthority()
		upgradeWifiCaptureLaneForManualSession()
		markWifiSessionStopping(cutoffElapsedNanos = 8_000_000L)
		val candidate = wifiDeliveryCandidate(
			registrationGeneration = 1L,
			authorizationRevision = 2L,
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 10_000_000L,
			accessPoints = listOf(
				WifiAccessPointEvidence("", 5_180, -55, 9_000_000L),
			),
		)
		val mismatched = candidate.copy(
			units = candidate.units.map { unit ->
				unit.copy(
					evidence = unit.evidence.copy(
						registrationEligibilityFingerprint = "mismatched-eligibility",
					),
				)
			},
		)

		ingress.admit(mismatched) shouldBe DeliveryAdmissionResult.PermanentFailure(
			AdmissionFailureCode.STALE_SOURCE_POLICY,
		)
		database.sourceEventWalDao().countAll() shouldBe 0L
		database.sourceRegistrationStateDao().get(SourceKind.WIFI.stableCode, WIFI_OWNER_SCOPE)
			?.nextSequence shouldBe 0L
	}

	private suspend fun wifiRetryRuntimeFixture(scope: CoroutineScope): WifiRetryRuntimeFixture {
		val runtimeRegistration = currentWifiRuntimeRegistration()
		val registrations = wifiRetryRegistrations(runtimeRegistration)
		val backend = mockk<AndroidWifiSourceBackend>(relaxed = true)
		val stateProvider = mockk<AndroidConnectivityDeviceStateProvider>()
		val wakeups = mockk<CoalescingSourceWakeupScheduler>(relaxed = true)
		var callback: ((WifiBackendEvent) -> Unit)? = null
		every { backend.start(any()) } answers {
			callback = firstArg()
			true
		}
		every { backend.stop() } returns true
		every { stateProvider.wifi() } returns WifiDeviceState(
			wifiFeatureAvailable = true,
			fineLocationPermission = true,
			locationServicesEnabled = true,
			deviceIdle = false,
		)
		val attempted = mutableListOf<SourceDeliveryCandidate>()
		val roomCandidate = CompletableDeferred<SourceDeliveryCandidate>()
		val roomResult = CompletableDeferred<SourceDeliveryAdmissionHandoff>()
		return WifiRetryRuntimeFixture(
			runtime = WifiSourceRuntime(scope, registrations, backend, stateProvider, wakeups),
			plan = wifiRetryPlan(),
			sink = wifiRetrySink(attempted, roomCandidate, roomResult),
			callback = { callback },
			attempted = attempted,
			roomCandidate = roomCandidate,
			roomResult = roomResult,
		)
	}

	private suspend fun wifiCutoffRuntimeFixture(
		scope: CoroutineScope,
	): WifiCutoffRuntimeFixture {
		val runtimeRegistration = currentWifiRuntimeRegistration()
		val registrations = wifiRetryRegistrations(runtimeRegistration)
		val backend = mockk<AndroidWifiSourceBackend>(relaxed = true)
		val stateProvider = mockk<AndroidConnectivityDeviceStateProvider>()
		val wakeups = mockk<CoalescingSourceWakeupScheduler>(relaxed = true)
		var callback: ((WifiBackendEvent) -> Unit)? = null
		every { backend.start(any()) } answers {
			callback = firstArg()
			true
		}
		every { backend.stop() } returns true
		every { stateProvider.wifi() } returns WifiDeviceState(
			wifiFeatureAvailable = true,
			fineLocationPermission = true,
			locationServicesEnabled = true,
			deviceIdle = false,
		)
		return WifiCutoffRuntimeFixture(
			runtime = WifiSourceRuntime(scope, registrations, backend, stateProvider, wakeups),
			plan = wifiRetryPlan(),
			callback = { callback },
		)
	}

	private fun wifiRetryRegistrations(
		runtimeRegistration: SourceRegistration,
	): SourceRegistrationRepository = mockk<SourceRegistrationRepository>(relaxed = true) {
		coEvery { pendingRetirements(SourceKind.WIFI) } returns emptyList()
		coEvery { begin(any(), any(), any(), any(), any()) } returns runtimeRegistration
		coEvery { beginRetirement(any(), any(), any(), any()) } answers {
			SourceRegistrationRetirementToken(
				source = SourceKind.WIFI,
				sourceInstanceId = SourceInstanceId(runtimeRegistration.state.sourceInstanceId),
				registrationGeneration = runtimeRegistration.state.registrationGeneration,
				processIncarnationId = "wifi-process-before",
				retiredAtMs = thirdArg(),
				retiredElapsedRealtimeNanos = arg(3),
				reason = secondArg(),
			)
		}
		coEvery { completeRetirement(any()) } returns true
	}

	private fun wifiRetrySink(
		attempted: MutableList<SourceDeliveryCandidate>,
		roomCandidate: CompletableDeferred<SourceDeliveryCandidate>,
		roomResult: CompletableDeferred<SourceDeliveryAdmissionHandoff>,
	): SourceEventSink = object : SourceEventSink {
		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			error("Wi-Fi must use atomic delivery admission")

		override suspend fun admit(delivery: SourceDeliveryCandidate): SourceDeliveryAdmissionHandoff {
			attempted += delivery
			return if (attempted.size == 1) {
				SourceDeliveryAdmissionHandoff.RetryableFailure(
					SourceAdmissionFailureCode.STORAGE_UNAVAILABLE,
				)
			} else {
				roomCandidate.complete(delivery)
				roomResult.await()
			}
		}
	}

	private fun wifiRetryPlan() = WifiPlan(
		revision = 1L,
		mode = WifiMode.BROADCAST_DRIVEN,
		minimumAttemptIntervalMs = 30_000L,
		maximumAcceptableResultAgeMs = 5L,
		unchangedResultDedupeWindowMs = 60_000L,
		backoff = RetryBackoff(1_000L, 60_000L),
	)

	private fun wifiRetryEvent() = WifiBackendEvent.Results(
		snapshot = WifiBackendSnapshot(
			listOf(
				WifiBackendAccessPoint("raw-a", 2_412, -50, 7_000L),
				WifiBackendAccessPoint("raw-b", 5_180, -55, 9_000L),
			),
		),
		resultsUpdated = true,
		receivedElapsedRealtimeNanos = 10_000_000L,
		receivedWallTimeMs = 1_000L,
	)

	private fun wifiDeliveryCandidate(
		registrationGeneration: Long,
		authorizationRevision: Long,
		physicalConfigurationFingerprint: String,
		receivedElapsedRealtimeNanos: Long,
		accessPoints: List<WifiAccessPointEvidence> = listOf(
			WifiAccessPointEvidence(
				identifierToken = WITHHELD_RADIO_IDENTIFIER_TOKEN,
				frequencyMhz = 2_412,
				signalLevelDbm = -50,
				providerTimestampNanos = PROVIDER_TIMESTAMP_NANOS,
			),
		),
		purposeEligibilityMask: Long = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
		eligibilityFingerprint: String = WIFI_ELIGIBILITY_FINGERPRINT,
	): SourceDeliveryCandidate {
		val observed = requireNotNull(accessPoints.mapNotNull { it.providerTimestampNanos }.maxOrNull())
		val intervalStart = requireNotNull(accessPoints.mapNotNull { it.providerTimestampNanos }.minOrNull())
		val evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.WIFI,
			sourceInstanceId = SourceInstanceId(WIFI_SOURCE_INSTANCE_ID),
			registrationGeneration = registrationGeneration,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
			authorizationRevision = authorizationRevision,
			registrationPurposeEligibilityMask = purposeEligibilityMask,
			registrationEligibilityFingerprint = eligibilityFingerprint,
			// Atomic Room ingress owns the only source-sequence allocation.
			sourceSequence = 0L,
			configRevision = authorizationRevision,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = BOOT_CLOCK_DOMAIN_ID,
			observedElapsedRealtimeNanos = observed,
			receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
			wallTimeMs = 100L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 0L,
			acquiredAtMs = 100L,
			quality = SourceQuality(),
			payloadVersion = WIFI_PAYLOAD_VERSION,
			payload = WifiResultSnapshotPayload(
				accessPoints = accessPoints,
				platformTimestampMs = observed / NANOS_PER_MILLISECOND,
				resultAgeMs = null,
			),
		)
		return SourceDeliveryCandidate(
			identity = wifiProviderDeliveryIdentity(BOOT_CLOCK_DOMAIN_ID, accessPoints),
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = evidence,
					observedIntervalStartElapsedRealtimeNanos = intervalStart,
				),
			),
		)
	}

	private suspend fun currentWifiRuntimeRegistration(): SourceRegistration {
		val state = requireNotNull(
			database.sourceRegistrationStateDao().get(SourceKind.WIFI.stableCode, WIFI_OWNER_SCOPE),
		)
		val physical = requireNotNull(
			database.sourceBrokerDao().registration(SourceKind.WIFI.stableCode, state.registrationGeneration),
		)
		val authorization = requireNotNull(
			database.sourceBrokerDao().latestAuthorization(SourceKind.WIFI.stableCode, state.registrationGeneration)
				.toAuthorizationSnapshotOrNull(),
		)
		return SourceRegistration(
			ownerScope = WIFI_OWNER_SCOPE,
			state = state,
			physicalConfigurationFingerprint = physical.physicalConfigurationFingerprint,
			authorization = authorization,
			requiresProviderAcceptance = false,
			providerAcceptedElapsedRealtimeNanos = requireNotNull(
				physical.acceptedElapsedRealtimeNanos,
			),
		)
	}

	private suspend fun installWifiCaptureLane() {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.WIFI.stableCode,
				bindingGeneration = 1L,
				projectionId = WIFI_PROJECTION_ID,
				projectionVersion = 1,
				captureModeMask = CaptureReachabilityMode.AMBIENT.mask,
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 1L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 1L,
				updatedAtMs = 1L,
			),
		)
	}

	private suspend fun upgradeWifiCaptureLaneForManualSession() {
		val projectionDao = database.sourceProjectionStateDao()
		projectionDao.retireProductLane(
			sourceKind = SourceKind.WIFI.stableCode,
			bindingGeneration = 1L,
			projectionId = WIFI_PROJECTION_ID,
			projectionVersion = 1,
			expectedCurrentOrdinal = 0L,
			updatedAtMs = 2L,
		) shouldBe 1
		projectionDao.installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.WIFI.stableCode,
				bindingGeneration = 2L,
				projectionId = WIFI_PROJECTION_ID,
				projectionVersion = 1,
				captureModeMask = WIFI_CAPTURE_MODES.fold(0L) { mask, mode ->
					mask or mode.mask
				},
				productStage = SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
				activatedRolloutRevision = 2L,
				activationOrdinal = 1L,
				contiguousAdmissionOrdinal = 0L,
				retentionRequired = true,
				status = SourceProductProjectionLaneEntity.STATUS_ACTIVE,
				installedAtMs = 2L,
				updatedAtMs = 2L,
			),
		)
		RoomTrackingRolloutStateStore(database, WIFI_CAPTURE_EXECUTABLE_LANE_CATALOG).save(
			TrackingRolloutState.eventCanonical(
				sources = setOf(SourceKind.WIFI),
				revision = 2L,
				captureModes = mapOf(SourceKind.WIFI to WIFI_CAPTURE_MODES),
			),
			updatedAtMs = 2L,
		)
		ingress = RoomDurableSourceIngress(
			database = database,
			lifecycleStore = WifiIngressLifecycleStore(),
			payloadCodec = DefaultSourcePayloadCodec(),
			executableLaneCatalog = WIFI_CAPTURE_EXECUTABLE_LANE_CATALOG,
			trackingStartupGateProvider = Provider { WifiIngressStartupGate() },
		)
	}

	@Suppress("LongMethod") // Builds one complete production-shaped immutable authority fixture.
	private suspend fun installWifiSessionCaptureAuthority(includeAmbient: Boolean = true) {
		val sessionDao = database.sourceSessionDao()
		val brokerDao = database.sourceBrokerDao()
		if (!includeAmbient) {
			brokerDao.deleteAllDemands()
		}
		sessionDao.insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = WIFI_SESSION_ID,
				state = "ACTIVE",
				lifecycleRevision = 1L,
				desiredPlanRevision = 1L,
				rolloutRevision = 2L,
				startOrigin = "MANUAL_FOREGROUND_START",
				clockDomainId = BOOT_CLOCK_DOMAIN_ID,
				startedAtMs = 1_000L,
				startedElapsedNanos = 5_000_000L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				sessionMode = "MANUAL",
				currentManifestRevision = 1L,
				currentIntentRevision = 1L,
				currentServiceRunId = WIFI_SERVICE_RUN_ID,
				lifecycleLeaseGeneration = WIFI_LEASE_GENERATION,
				lifecycleBootId = BOOT_CLOCK_DOMAIN_ID,
				automationEpoch = null,
			),
		)
		sessionDao.insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = WIFI_SERVICE_RUN_ID,
				logicalTrackingId = WIFI_SESSION_ID,
				state = "ACTIVE",
				desiredPlanRevision = 1L,
				rolloutRevision = 2L,
				foregroundCapabilityFlags = 0L,
				startedAtMs = 1_000L,
				startedElapsedNanos = 5_000_000L,
				completedAtMs = null,
				completionReason = null,
				bootId = BOOT_CLOCK_DOMAIN_ID,
				leaseGeneration = WIFI_LEASE_GENERATION,
				startOrigin = "MANUAL_FOREGROUND_START",
				desiredForegroundCapabilityFlags = 0L,
				appliedForegroundCapabilityFlags = 0L,
				runtimeAcknowledgement = "START_ACCEPTED",
				runtimeFailureCode = null,
				runRevision = 1L,
			),
		)
		sessionDao.insertManifest(
			SessionManifestVersionEntity(
				logicalTrackingId = WIFI_SESSION_ID,
				manifestRevision = 1L,
				serviceRunId = WIFI_SERVICE_RUN_ID,
				sessionMode = "MANUAL",
				sourcePolicyRevision = 1L,
				acquisitionPlanRevision = 1L,
				rolloutRevision = 2L,
				startOrigin = "MANUAL_FOREGROUND_START",
				effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
				effectiveElapsedRealtimeNanos = 6_000_000L,
				effectiveWallTimeMs = 1_000L,
				zoneId = "UTC",
				automationEpoch = null,
				changeReason = "TEST",
				manifestChecksum = "wifi-session-manifest",
			),
		)
		sessionDao.insertManifestSources(
			listOf(
				SessionManifestSourceEntity(
					logicalTrackingId = WIFI_SESSION_ID,
					manifestRevision = 1L,
					sourceKind = SourceKind.WIFI.stableCode,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					consentEpoch = 1L,
					persistenceEligible = true,
					qosCode = 2,
				),
			),
		)
		brokerDao.insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = WIFI_CAPTURE_DEMAND_ID,
					consumerId = "session:$WIFI_SESSION_ID",
					sourceKind = SourceKind.WIFI.stableCode,
					purpose = SourceBrokerPurpose.SESSION_CAPTURE,
					logicalTrackingId = WIFI_SESSION_ID,
					serviceRunId = WIFI_SERVICE_RUN_ID,
					manifestRevision = 1L,
					lifecycleLeaseGeneration = WIFI_LEASE_GENERATION,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					qosCode = 2,
					maximumAgeMs = 5L,
					desiredLatencyMs = 5L,
					requestedBootId = BOOT_CLOCK_DOMAIN_ID,
					requestedElapsedRealtimeNanos = 6_000_000L,
					requestedAtMs = 1_000L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)
		val purposeMask = SourceBrokerPurpose.MASK_SESSION_CAPTURE or if (includeAmbient) {
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT
		} else {
			0L
		}
		val authorizationRows = mutableListOf<SourceAuthorizationEntity>()
		if (includeAmbient) {
			authorizationRows.add(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.WIFI.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 2L,
					memberId = "demand:$WIFI_DEMAND_ID",
					authorizationFingerprint = WIFI_CAPTURE_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = purposeMask,
					demandId = WIFI_DEMAND_ID,
					consumerId = "app:wifi-ingress-test",
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = 6_000_000L,
					effectiveWallTimeMs = 1_000L,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			)
		}
		authorizationRows.add(
			SourceAuthorizationEntity(
				sourceKind = SourceKind.WIFI.stableCode,
				registrationGeneration = 1L,
				authorizationRevision = 2L,
				memberId = "demand:$WIFI_CAPTURE_DEMAND_ID",
				authorizationFingerprint = WIFI_CAPTURE_ELIGIBILITY_FINGERPRINT,
				purposeEligibilityMask = purposeMask,
				demandId = WIFI_CAPTURE_DEMAND_ID,
				consumerId = "session:$WIFI_SESSION_ID",
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				sourcePolicyRevision = 1L,
				consentEpoch = 1L,
				persistenceEligible = true,
				effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
				effectiveElapsedRealtimeNanos = 6_000_000L,
				effectiveWallTimeMs = 1_000L,
				logicalTrackingId = WIFI_SESSION_ID,
				serviceRunId = WIFI_SERVICE_RUN_ID,
				manifestRevision = 1L,
				lifecycleLeaseGeneration = WIFI_LEASE_GENERATION,
			),
		)
		brokerDao.insertAuthorizations(authorizationRows)
		val state = requireNotNull(
			database.sourceRegistrationStateDao().get(SourceKind.WIFI.stableCode, WIFI_OWNER_SCOPE),
		)
		database.sourceRegistrationStateDao().replace(
			state.copy(appliedRevision = 2L, updatedAtMs = 1_000L),
		)
	}

	private suspend fun markWifiSessionStopping(
		cutoffElapsedNanos: Long,
		retainAmbient: Boolean = true,
	) {
		database.withTransaction {
			val sessionDao = database.sourceSessionDao()
			val brokerDao = database.sourceBrokerDao()
			val session = requireNotNull(sessionDao.session(WIFI_SESSION_ID))
			val run = requireNotNull(sessionDao.serviceRun(WIFI_SERVICE_RUN_ID))
			brokerDao.markConsumerRetiring(
				consumerId = "session:$WIFI_SESSION_ID",
				bootId = BOOT_CLOCK_DOMAIN_ID,
				elapsedRealtimeNanos = cutoffElapsedNanos,
				wallTimeMs = 1_000L,
			) shouldBe 1
			val nextAuthorization = if (retainAmbient) {
				listOf(
					SourceAuthorizationEntity(
						sourceKind = SourceKind.WIFI.stableCode,
						registrationGeneration = 1L,
						authorizationRevision = 3L,
						memberId = "demand:$WIFI_DEMAND_ID",
						authorizationFingerprint = WIFI_ELIGIBILITY_FINGERPRINT,
						purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
						demandId = WIFI_DEMAND_ID,
						consumerId = "app:wifi-ingress-test",
						purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
						sourcePolicyRevision = 1L,
						consentEpoch = 1L,
						persistenceEligible = true,
						effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
						effectiveElapsedRealtimeNanos = cutoffElapsedNanos,
						effectiveWallTimeMs = 1_000L,
						logicalTrackingId = null,
						serviceRunId = null,
						manifestRevision = null,
						lifecycleLeaseGeneration = null,
					),
				)
			} else {
				SourceBrokerAuthorization.rows(
					sourceKind = SourceKind.WIFI.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 3L,
					demands = emptyList(),
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = cutoffElapsedNanos,
					effectiveWallTimeMs = 1_000L,
				)
			}
			brokerDao.insertAuthorizations(nextAuthorization)
			sessionDao.updateSession(
				session.copy(
					state = "STOPPING",
					cutoffAtMs = 1_000L,
					cutoffElapsedNanos = cutoffElapsedNanos,
				),
			) shouldBe 1
			sessionDao.updateServiceRun(run.copy(state = "STOPPING")) shouldBe 1
		}
	}

	private fun wifiSessionCutoff() = SessionCutoff(
		logicalTrackingId = WIFI_SESSION_ID,
		elapsedRealtimeNanos = 8_000_000L,
		wallTimeMs = 1_000L,
		deadlineElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + 60_000_000_000L,
	)

	private suspend fun installWifiDemand() {
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = WIFI_DEMAND_ID,
					consumerId = "app:wifi-ingress-test",
					sourceKind = SourceKind.WIFI.stableCode,
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					qosCode = 2,
					maximumAgeMs = 30_000L,
					desiredLatencyMs = 15_000L,
					requestedBootId = BOOT_CLOCK_DOMAIN_ID,
					requestedElapsedRealtimeNanos = 0L,
					requestedAtMs = 0L,
					status = SourceDemandEntity.STATUS_ACTIVE,
					retireBootId = null,
					retireElapsedRealtimeNanos = null,
					retiredAtMs = null,
				),
			),
		)
	}

	private suspend fun installWifiRegistrationGeneration(
		generation: Long,
		authorizationRevision: Long,
		processIncarnationId: String,
		physicalConfigurationFingerprint: String,
		acceptedElapsedRealtimeNanos: Long,
		authorizationEffectiveElapsedRealtimeNanos: Long = acceptedElapsedRealtimeNanos,
	) {
		if (generation == 1L) {
			database.sourceRegistrationStateDao().insertIfAbsent(
				SourceRegistrationStateEntity(
					sourceKind = SourceKind.WIFI.stableCode,
					ownerScope = WIFI_OWNER_SCOPE,
					sourceInstanceId = WIFI_SOURCE_INSTANCE_ID,
					clockDomainId = BOOT_CLOCK_DOMAIN_ID,
					registrationGeneration = generation,
					nextSequence = 0L,
					appliedRevision = authorizationRevision,
					collectedDataEpoch = 0L,
					updatedAtMs = acceptedElapsedRealtimeNanos,
				),
			)
		}
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = SourceKind.WIFI.stableCode,
				registrationGeneration = generation,
				sourceInstanceId = WIFI_SOURCE_INSTANCE_ID,
				ownerScope = WIFI_OWNER_SCOPE,
				providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
				providerProcessIncarnationId = processIncarnationId,
				clockDomainId = BOOT_CLOCK_DOMAIN_ID,
				physicalConfigurationFingerprint = physicalConfigurationFingerprint,
				collectedDataEpoch = 0L,
				status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				reservedAtMs = acceptedElapsedRealtimeNanos,
				reservedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
				acceptedAtMs = acceptedElapsedRealtimeNanos,
				acceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
				retiredAtMs = null,
				retiredElapsedRealtimeNanos = null,
				failureCode = null,
			),
		)
		installWifiAuthorization(
			generation,
			authorizationRevision,
			authorizationEffectiveElapsedRealtimeNanos,
		)
	}

	private suspend fun installWifiAuthorization(
		generation: Long,
		authorizationRevision: Long,
		effectiveElapsedRealtimeNanos: Long,
	) {
		database.sourceBrokerDao().insertAuthorizations(
			listOf(
				SourceAuthorizationEntity(
					sourceKind = SourceKind.WIFI.stableCode,
					registrationGeneration = generation,
					authorizationRevision = authorizationRevision,
					memberId = "demand:$WIFI_DEMAND_ID",
					authorizationFingerprint = WIFI_ELIGIBILITY_FINGERPRINT,
					purposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
					demandId = WIFI_DEMAND_ID,
					consumerId = "app:wifi-ingress-test",
					purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
					sourcePolicyRevision = 1L,
					consentEpoch = 1L,
					persistenceEligible = true,
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs = effectiveElapsedRealtimeNanos,
					logicalTrackingId = null,
					serviceRunId = null,
					manifestRevision = null,
					lifecycleLeaseGeneration = null,
				),
			),
		)
	}

	private suspend fun replaceWifiRuntimeGeneration() {
		database.sourceBrokerDao().finishRegistration(
			sourceKind = SourceKind.WIFI.stableCode,
			registrationGeneration = 1L,
			sourceInstanceId = WIFI_SOURCE_INSTANCE_ID,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 200_000_000L,
			failureCode = "PROCESS_REPLACED",
		) shouldBe 1
		installWifiRegistrationGeneration(
			generation = 2L,
			authorizationRevision = 2L,
			processIncarnationId = "wifi-process-after",
			physicalConfigurationFingerprint = SECOND_PHYSICAL_CONFIGURATION,
			acceptedElapsedRealtimeNanos = 200_000_000L,
		)
		val current = requireNotNull(
			database.sourceRegistrationStateDao().get(SourceKind.WIFI.stableCode, WIFI_OWNER_SCOPE),
		)
		database.sourceRegistrationStateDao().replace(
			current.copy(
				registrationGeneration = 2L,
				appliedRevision = 2L,
				updatedAtMs = 200L,
			),
		)
	}

	private companion object {
		const val BOOT_CLOCK_DOMAIN_ID = "boot"
		const val PROVIDER_TIMESTAMP_NANOS = 100_000_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val WIFI_PAYLOAD_VERSION = 2
		const val WIFI_SOURCE_INSTANCE_ID = "wifi-source-instance"
		const val WIFI_DEMAND_ID = "wifi-ambient-demand"
		const val WIFI_CAPTURE_DEMAND_ID = "wifi-session-demand"
		const val WIFI_SESSION_ID = "wifi-session"
		const val WIFI_SERVICE_RUN_ID = "wifi-service-run"
		const val WIFI_LEASE_GENERATION = 7L
		const val WIFI_ELIGIBILITY_FINGERPRINT = "wifi-ambient-eligibility"
		const val WIFI_CAPTURE_ELIGIBILITY_FINGERPRINT = "wifi-session-eligibility"
		const val FIRST_PHYSICAL_CONFIGURATION = "wifi-physical-before"
		const val SECOND_PHYSICAL_CONFIGURATION = "wifi-physical-after"
		const val WIFI_PROJECTION_ID = "wifi-ingress-test"
		val WIFI_OWNER_SCOPE = "source-broker:${SourceKind.WIFI.stableCode}"
		val WIFI_CAPTURE_MODES = setOf(
			CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
			CaptureReachabilityMode.AMBIENT,
		)
		val WIFI_EXECUTABLE_LANE_CATALOG = ExecutableSourceLaneCatalog.explicit(
			ExecutableSourceLaneBinding(
				source = SourceKind.WIFI,
				bindingGeneration = 1L,
				projectionId = WIFI_PROJECTION_ID,
				projectionVersion = 1,
				captureModes = setOf(CaptureReachabilityMode.AMBIENT),
			),
		)
		val WIFI_CAPTURE_EXECUTABLE_LANE_CATALOG = ExecutableSourceLaneCatalog.explicit(
			ExecutableSourceLaneBinding(
				source = SourceKind.WIFI,
				bindingGeneration = 2L,
				projectionId = WIFI_PROJECTION_ID,
				projectionVersion = 1,
				captureModes = WIFI_CAPTURE_MODES,
			),
		)
	}

	private data class WifiRetryRuntimeFixture(
		val runtime: WifiSourceRuntime,
		val plan: WifiPlan,
		val sink: SourceEventSink,
		val callback: () -> ((WifiBackendEvent) -> Unit)?,
		val attempted: MutableList<SourceDeliveryCandidate>,
		val roomCandidate: CompletableDeferred<SourceDeliveryCandidate>,
		val roomResult: CompletableDeferred<SourceDeliveryAdmissionHandoff>,
	)

	private data class WifiCutoffRuntimeFixture(
		val runtime: WifiSourceRuntime,
		val plan: WifiPlan,
		val callback: () -> ((WifiBackendEvent) -> Unit)?,
	)
}

private class WifiIngressLifecycleStore : CollectedDataLifecycleStore {
	private val state = MutableStateFlow(CollectedDataLifecycleSnapshot(0L, null))
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot {
		val updated = state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs)
		state.emit(updated)
		return updated
	}
	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot {
		val updated = state.value.copy(retainedFromMs = retainedFromMs)
		state.emit(updated)
		return updated
	}
}

private class WifiIngressStartupGate : TrackingStartupGate {
	override val isReady: Boolean = true
	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		TrackingStartupResult.Ready(
			legacyRecoveryPartial = false,
			liveCompletedThroughOrdinal = 0L,
		)
}

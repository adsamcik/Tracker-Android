package com.adsamcik.tracker.tracker.source.ingress

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.database.AmbientRadioRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientCellRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.LEGACY_CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.runtime.SourceDeliveryAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.AmbientCellRuntimeJoinResult
import com.adsamcik.tracker.tracker.source.runtime.CellSourceRuntime
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.SourceCapabilities
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.WITHHELD_RADIO_IDENTIFIER_TOKEN
import com.adsamcik.tracker.tracker.source.runtime.cellProviderDeliveryIdentity
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellFactProjector
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellProjectionResult
import com.adsamcik.tracker.tracker.source.ambient.cell.AmbientCellProjectionUnverifiableReason
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellDurableSourceIngressTest {
	private lateinit var database: AppDatabase
	private lateinit var ingress: RoomDurableSourceIngress
	private lateinit var ambientEligibilityFingerprint: String

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		ingress = RoomDurableSourceIngress(
			database = database,
			lifecycleStore = CellIngressLifecycleStore(),
			payloadCodec = DefaultSourcePayloadCodec(),
			executableLaneCatalog = CELL_EXECUTABLE_LANE_CATALOG,
			trackingStartupGateProvider = Provider { CellIngressStartupGate() },
		)
		installCellCaptureLane()
		RoomTrackingRolloutStateStore(database, CELL_EXECUTABLE_LANE_CATALOG).save(
			TrackingRolloutState.eventCanonical(
				sources = setOf(SourceKind.CELL),
				captureModes = mapOf(SourceKind.CELL to setOf(CaptureReachabilityMode.AMBIENT)),
			),
			updatedAtMs = 1L,
		)
		installCellDemand()
		installCellRegistrationGeneration(
			generation = 1L,
			authorizationRevision = 1L,
			processIncarnationId = "cell-process-before",
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			acceptedElapsedRealtimeNanos = 50L,
		)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `v6 Cell reading before physical acceptance cannot discard its valid sibling`() = runTest {
		ingress.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 110L,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			observationsOverride = listOf(
				CellObservationEvidence("cached", "LTE", true, -91, 40L),
				CellObservationEvidence("current", "LTE", true, -85, 100L),
			))).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
		database.sourceEventWalDao().eventsAfter(0L, 10).single().observedIntervalStartNanos shouldBe 40L
	}

	@Test
	fun `v6 Cell retirement excludes later readings without discarding earlier authorized data`() = runTest {
		database.sourceBrokerDao().finishRegistration(SourceKind.CELL.stableCode, 1L,
			CELL_SOURCE_INSTANCE_ID, ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			200L, 200L, "STOPPED") shouldBe 1
		ingress.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 230L,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			observationsOverride = listOf(
				CellObservationEvidence("before", "LTE", true, -91, 100L),
				CellObservationEvidence("after", "LTE", true, -85, 220L),
			))).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
		ingress.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 230L,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			providerTimestampNanos = 220L, towerIdentifierToken = "after")) shouldBe
			DeliveryAdmissionResult.PermanentFailure(AdmissionFailureCode.STALE_OBSERVATION)
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `retained v5 Cell preserves its full callback summary across an older demand boundary`() = runTest {
		installAmbientCellProduct()
		val ordinal = ingress.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION,
			100_000_000L, payloadVersion = LEGACY_CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			observationsOverride = listOf(
				CellObservationEvidence("", "LTE", true, -91, 70_000_000L),
				CellObservationEvidence("", "LTE", true, -85, 90_000_000L))))
			.shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>().units.single().admissionOrdinal
		val broker = database.sourceBrokerDao()
		val first = broker.demandsByIds(listOf(CELL_DEMAND_ID)).single()
		val second = first.copy(demandId = "cell-retained-v5-callback", requestedElapsedRealtimeNanos = 80_000_000L,
			requestedAtMs = 80L)
		broker.insertDemands(listOf(second))
		database.ambientCellFactDao().retireExactAmbientDemand(first.demandId, first.consumerId,
			first.sourceKind, requireNotNull(first.sourcePolicyRevision), requireNotNull(first.consentEpoch),
			BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L) shouldBe 1
		val rows = SourceBrokerAuthorization.rows(SourceKind.CELL.stableCode, 1L, 2L,
			listOf(second), BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L)
		broker.insertAuthorizations(rows)
		database.ambientCellFactDao().insertAuthority(AmbientCellAuthorityIntegrity.create(
			2L, AmbientCellAuthorityEntity.STATE_ACTIVE, 1L, 1L, "privacy:cell:ambient:v1", 1L,
			0L, 0L, BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L, 1L, "retained-v5", 2L, second.demandId))
		// This is a retained-data compatibility fixture, not a newly admitted mixed v5 batch.
		val retained = requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(ordinal)).copy(
			authorizationRevision = 2L, configRevision = 2L,
			authorizationFingerprint = rows.first().authorizationFingerprint)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET authorization_revision = 2, config_revision = 2, " +
				"authorization_fingerprint = ?, integrity_identity = ? WHERE admission_ordinal = ?",
			arrayOf<Any?>(retained.authorizationFingerprint, retained.calculatedIntegrityIdentity(), ordinal))
		val projector = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID })
		projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.FactWritten>()
		val fact = database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).single().fact
		fact.purposeDemandId shouldBe null
		fact.observationCount shouldBe 2
		fact.observedElapsedNanos shouldBe 90_000_000L
		database.radioObservationChildDao().cellBackgroundChildren(fact.writerId, fact.writerVersion,
			fact.logicalFactId, fact.semanticRevision, 65).size shouldBe 0
		projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.Duplicate>()
	}

	@Test
	fun `one Cell delivery saves every background group and replay retains exact tower details`() = runTest {
		installAmbientCellProduct()
		val broker = database.sourceBrokerDao()
		val first = broker.demandsByIds(listOf(CELL_DEMAND_ID)).single()
		val second = first.copy(demandId = "cell-second-group", requestedElapsedRealtimeNanos = 80_000_000L,
			requestedAtMs = 80L)
		broker.insertDemands(listOf(second))
		database.ambientCellFactDao().retireExactAmbientDemand(first.demandId, first.consumerId,
			first.sourceKind, requireNotNull(first.sourcePolicyRevision), requireNotNull(first.consentEpoch),
			BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L) shouldBe 1
		val rows = SourceBrokerAuthorization.rows(SourceKind.CELL.stableCode, 1L, 2L,
			listOf(second), BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L)
		broker.insertAuthorizations(rows)
		ambientEligibilityFingerprint = rows.first().authorizationFingerprint
		database.ambientCellFactDao().insertAuthority(AmbientCellAuthorityIntegrity.create(
			2L, AmbientCellAuthorityEntity.STATE_ACTIVE, 1L, 1L, "privacy:cell:ambient:v1", 1L,
			0L, 0L, BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L, 1L, "second-group", 2L, second.demandId))
		val ordinal = ingress.admit(cellDeliveryCandidate(1L, 2L, FIRST_PHYSICAL_CONFIGURATION,
			100_000_000L, payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			observationsOverride = listOf(
				CellObservationEvidence("old-tower", "LTE", true, -91, 70_000_000L),
				CellObservationEvidence("new-tower", "LTE", true, -85, 90_000_000L))))
			.shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>().units.single().admissionOrdinal
		val projector = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID })
		database.openHelper.writableDatabase.execSQL(
			"CREATE TRIGGER fail_second_ambient_group BEFORE INSERT ON ambient_cell_fact_revision " +
				"WHEN NEW.purpose_demand_id = 'cell-second-group' BEGIN " +
				"SELECT RAISE(ABORT, 'injected group write failure'); END",
		)
		projector.project(ordinal) shouldBe AmbientCellProjectionResult.RetryableFailure
		database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).size shouldBe 0
		database.openHelper.writableDatabase.query("SELECT count(*) FROM ambient_cell_child").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 0L
		}
		database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_ambient_group")
		projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.Groups>()
			.results.size shouldBe 2
		val facts = database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10)
			.map { it.fact }.associateBy { it.purposeDemandId }
		facts.keys shouldBe setOf(first.demandId, second.demandId)
		facts.getValue(first.demandId).observedElapsedNanos shouldBe 70_000_000L
		facts.getValue(first.demandId).observedWallTimeMs shouldBe 80L
		facts.getValue(second.demandId).observedElapsedNanos shouldBe 90_000_000L
		facts.values.forEach { it.observationCount shouldBe 1 }
		val children = facts.values.flatMap { fact ->
			database.radioObservationChildDao().cellBackgroundChildren(fact.writerId, fact.writerVersion,
				fact.logicalFactId, fact.semanticRevision, 65)
		}
		children.map { it.towerIdentity }.toSet() shouldBe setOf("old-tower", "new-tower")
		projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.Groups>()
			.results.forEach { it.shouldBeInstanceOf<AmbientCellProjectionResult.Duplicate>() }
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM ambient_cell_child WHERE logical_fact_id = ?",
			arrayOf(facts.getValue(first.demandId).logicalFactId))
		val replay = projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.Groups>()
		replay.results.first() shouldBe AmbientCellProjectionResult.Unverifiable(
			AmbientCellProjectionUnverifiableReason.WRITE_CONFLICT)
		replay.results.last().shouldBeInstanceOf<AmbientCellProjectionResult.Duplicate>()
		database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).size shouldBe 2
	}

	@Test
	fun `background Cell writer accepts a historical group with a denied callback`() = runTest {
		installAmbientCellProduct()
		val denied = SourceBrokerAuthorization.rows(SourceKind.CELL.stableCode, 1L, 2L,
			emptyList(), BOOT_CLOCK_DOMAIN_ID, 105L, 105L)
		database.sourceBrokerDao().insertAuthorizations(denied)
		val original = cellDeliveryCandidate(1L, 2L, FIRST_PHYSICAL_CONFIGURATION, 110L,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC", towerIdentifierToken = "tower")
		val candidate = original.copy(units = original.units.map { unit -> unit.copy(
			evidence = unit.evidence.copy(registrationPurposeEligibilityMask = 0L,
				registrationEligibilityFingerprint = denied.single().authorizationFingerprint)) })
		val ordinal = ingress.admit(candidate).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
			.units.single().admissionOrdinal
		AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID }).project(ordinal)
			.shouldBeInstanceOf<AmbientCellProjectionResult.FactWritten>()
		val fact = database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).single().fact
		fact.purposeEligibilityMask shouldBe 0L
		fact.purposeDemandId shouldBe CELL_DEMAND_ID
		AmbientCellFactIntegrity.effectChecksum(fact) shouldBe fact.effectChecksum
	}

	@Test
	fun `background Cell keeps valid readings beside readings after physical retirement`() = runTest {
		installAmbientCellProduct()
		database.sourceBrokerDao().finishRegistration(SourceKind.CELL.stableCode, 1L, CELL_SOURCE_INSTANCE_ID,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED, 100L, 80_000_000L, "TEST_RETIRED") shouldBe 1
		val ordinal = ingress.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION,
			100_000_000L, payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			observationsOverride = listOf(
				CellObservationEvidence("valid-tower", "LTE", true, -91, 70_000_000L),
				CellObservationEvidence("late-tower", "LTE", true, -85, 90_000_000L))))
			.shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>().units.single().admissionOrdinal
		AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID }).project(ordinal)
			.shouldBeInstanceOf<AmbientCellProjectionResult.FactWritten>()
		val fact = database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).single().fact
		fact.observedElapsedNanos shouldBe 70_000_000L
		fact.observationCount shouldBe 1
		database.radioObservationChildDao().cellBackgroundChildren(fact.writerId, fact.writerVersion,
			fact.logicalFactId, fact.semanticRevision, 65).single().towerIdentity shouldBe "valid-tower"
	}

	@Test
	fun `background Cell parent bounds preserve rounding of a near-latest sibling`() = runTest {
		installAmbientCellProduct()
		val ordinal = ingress.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION,
			100_000_000L, payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			observationsOverride = listOf(
				CellObservationEvidence("rounded-tower", "LTE", true, -91, 79_999_999L),
				CellObservationEvidence("latest-tower", "LTE", true, -85, 80_000_000L))))
			.shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>().units.single().admissionOrdinal
		val projector = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID })
		projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.FactWritten>()
		val fact = database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).single().fact
		val children = database.radioObservationChildDao().cellBackgroundChildren(fact.writerId,
			fact.writerVersion, fact.logicalFactId, fact.semanticRevision, 65)
		children.size shouldBe 2
		(fact.observedWallTimeMs + fact.wallTimeUncertaintyMs) shouldBe
			children.maxOf { it.observedWallTimeMs + it.wallTimeUncertaintyMs }
		children.all { it.observedWallTimeMs - it.wallTimeUncertaintyMs >=
			fact.coverageStartTimeMs - fact.wallTimeUncertaintyMs } shouldBe true
		projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.Duplicate>()
	}

	@Test
	fun `background Cell gaps keep each qualified groups observation time`() = runTest {
		installAmbientCellProduct()
		val broker = database.sourceBrokerDao()
		val first = broker.demandsByIds(listOf(CELL_DEMAND_ID)).single()
		val second = first.copy(demandId = "cell-second-group", requestedElapsedRealtimeNanos = 80_000_000L,
			requestedAtMs = 80L)
		broker.insertDemands(listOf(second))
		database.ambientCellFactDao().retireExactAmbientDemand(first.demandId, first.consumerId,
			first.sourceKind, requireNotNull(first.sourcePolicyRevision), requireNotNull(first.consentEpoch),
			BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L) shouldBe 1
		val rows = SourceBrokerAuthorization.rows(SourceKind.CELL.stableCode, 1L, 2L,
			listOf(second), BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L)
		broker.insertAuthorizations(rows)
		ambientEligibilityFingerprint = rows.first().authorizationFingerprint
		database.ambientCellFactDao().insertAuthority(AmbientCellAuthorityIntegrity.create(
			2L, AmbientCellAuthorityEntity.STATE_ACTIVE, 1L, 1L, "privacy:cell:ambient:v1", 1L,
			0L, 0L, BOOT_CLOCK_DOMAIN_ID, 80_000_000L, 80L, 1L, "second-group", 2L, second.demandId))
		val ordinal = ingress.admit(cellDeliveryCandidate(1L, 2L, FIRST_PHYSICAL_CONFIGURATION,
			100_000_000L, payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			subscriptionId = 1, observationsOverride = listOf(
				CellObservationEvidence("old-tower", "LTE", true, -91, 70_000_000L),
				CellObservationEvidence("new-tower", "LTE", true, -85, 90_000_000L))))
			.shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>().units.single().admissionOrdinal
		val projector = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID })
		projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.Groups>()
		val gaps = database.ambientCellFactDao().gapsOverWindow(0L, 1_000L, 10)
		gaps.map { it.gapStartTimeMs } shouldBe listOf(80L, 100L)
		gaps.map { it.gapEndTimeMs } shouldBe listOf(81L, 101L)
		database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).size shouldBe 0
		projector.project(ordinal).shouldBeInstanceOf<AmbientCellProjectionResult.Groups>()
		database.ambientCellFactDao().gapsOverWindow(0L, 1_000L, 10).size shouldBe 2
	}

	@Test
	fun `v6 Cell admits a mixed revision delivery without changing its raw readings`() = runTest {
		installCellAuthorization(1L, 2L, 75L)
		val observations = listOf(
			CellObservationEvidence("tower-one", "LTE", true, -91, 60L),
			CellObservationEvidence("tower-two", "LTE", true, -85, 100L),
		)
		val candidate = cellDeliveryCandidate(1L, 2L, FIRST_PHYSICAL_CONFIGURATION, 110L,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			observationsOverride = observations)
		ingress.admit(candidate).shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.authorizationRevision shouldBe 2L
		stored.authorizationFingerprint shouldBe ambientEligibilityFingerprint
		stored.observedIntervalStartNanos shouldBe 60L
		(ingress.committedBatch(0L, 10).single().evidence.payload as CellSnapshotPayload)
			.observations shouldBe observations
		ingress.admit(candidate).shouldBeInstanceOf<DeliveryAdmissionResult.Duplicate>()
		database.sourceEventWalDao().countAll() shouldBe 1L
	}

	@Test
	fun `legacy Cell rejects a mixed authorization revision batch`() = runTest {
		installCellAuthorization(1L, 2L, 75L)
		ingress.admit(cellDeliveryCandidate(1L, 2L, FIRST_PHYSICAL_CONFIGURATION, 110L,
			payloadVersion = LEGACY_CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			observationsOverride = listOf(
				CellObservationEvidence("", "LTE", true, -91, 60L),
				CellObservationEvidence("", "LTE", true, -85, 100L),
			))) shouldBe DeliveryAdmissionResult.PermanentFailure(
			AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED)
		database.sourceEventWalDao().countAll() shouldBe 0L
	}

	@Test
	fun `v6 Cell permits a callback authorization newer than its reading`() = runTest {
		installCellAuthorization(1L, 2L, 105L)
		ingress.admit(cellDeliveryCandidate(1L, 2L, FIRST_PHYSICAL_CONFIGURATION, 110L,
			payloadVersion = CELL_WAL_PAYLOAD_VERSION, observationZoneId = "UTC",
			towerIdentifierToken = "tower"))
			.shouldBeInstanceOf<DeliveryAdmissionResult.Admitted>()
		database.sourceEventWalDao().eventsAfter(0L, 10).single().authorizationRevision shouldBe 2L
	}

	@Test
	fun `ambient Cell rejects provider acceptance after the observation wall time`() = runTest {
		val ordinal = DurableSourceEventSinkFactory(ingress).unbound.admit(
			cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 110L),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>().admissionOrdinals.single()
		val wal = requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(ordinal))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE provider_registration_generation SET accepted_at_ms = ? WHERE source_kind = ? AND registration_generation = 1",
			arrayOf<Any?>(requireNotNull(wal.wallTimeMs) + 1L, SourceKind.CELL.stableCode),
		)

		val result = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID }).project(ordinal)

		(result as AmbientCellProjectionResult.Unverifiable).reason shouldBe
			AmbientCellProjectionUnverifiableReason.REGISTRATION_MISMATCH
		database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).size shouldBe 0
	}

	@Test
	fun `ambient Cell rejects open registrations from a prior boot`() = runTest {
		val ordinal = DurableSourceEventSinkFactory(ingress).unbound.admit(
			cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 110L),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>().admissionOrdinals.single()

		val result = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { "different-boot" }).project(ordinal)

		(result as AmbientCellProjectionResult.Unverifiable).reason shouldBe
			AmbientCellProjectionUnverifiableReason.REGISTRATION_MISMATCH
		database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).size shouldBe 0
	}

	@Test
	fun `ambient Cell cannot materialize a rehashed negative source sequence`() = runTest {
		val admitted = DurableSourceEventSinkFactory(ingress).unbound.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 110L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		val ordinal = admitted.admissionOrdinals.single()
		val original = requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(ordinal))
		val negative = original.copy(sourceSequence = -1L)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_event_wal SET source_sequence = ?, integrity_identity = ? WHERE admission_ordinal = ?",
			arrayOf<Any?>(-1L, negative.calculatedIntegrityIdentity(), ordinal))
		val result = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined, BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID }).project(ordinal)
		(result as AmbientCellProjectionResult.Unverifiable).reason shouldBe AmbientCellProjectionUnverifiableReason.WAL_SHAPE
		database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).size shouldBe 0
	}

	@Test
	fun `ambient Cell deletion fence blocks surviving WAL replay`() = runTest {
		val admitted = DurableSourceEventSinkFactory(ingress).unbound.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 110L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		val ordinal = admitted.admissionOrdinals.single()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_evidence_state SET deleted_source_event_high_water_ordinal = ?", arrayOf(ordinal))
		val result = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined, BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID }).project(ordinal)
		(result as AmbientCellProjectionResult.Unverifiable).reason shouldBe AmbientCellProjectionUnverifiableReason.RETENTION_AUTHORITY_MISMATCH
		database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).size shouldBe 0
	}

	@Test
	fun `ambient Cell reconstructs authorization from immutable demand members`() = runTest {
		val admitted = DurableSourceEventSinkFactory(ingress).unbound.admit(cellDeliveryCandidate(1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 110L))
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		val ordinal = admitted.admissionOrdinals.single()
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_demand SET persistence_eligible = 0 WHERE source_kind = ?", arrayOf(SourceKind.CELL.stableCode))
		val result = AmbientCellFactProjector(database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined, BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID }).project(ordinal)
		(result as AmbientCellProjectionResult.Unverifiable).reason shouldBe AmbientCellProjectionUnverifiableReason.AUTHORIZATION_MISMATCH
		database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).size shouldBe 0
	}


	@Test
	fun `Cell provider replay across runtime generations allocates one Room sequence`() = runTest {
		val sink = DurableSourceEventSinkFactory(ingress).unbound
		val first = cellDeliveryCandidate(
			registrationGeneration = 1L,
			authorizationRevision = 1L,
			physicalConfigurationFingerprint = FIRST_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 110L,
		)

		val durable = sink.admit(first)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()

		replaceCellRuntimeGeneration()
		val replay = cellDeliveryCandidate(
			registrationGeneration = 2L,
			authorizationRevision = 2L,
			physicalConfigurationFingerprint = SECOND_PHYSICAL_CONFIGURATION,
			receivedElapsedRealtimeNanos = 210L,
		)
		first.identity shouldBe replay.identity

		val duplicate = sink.admit(replay)
			.shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()

		durable.admissionOrdinals shouldBe listOf(1L)
		duplicate.existingAdmissionOrdinals shouldBe durable.admissionOrdinals
		database.sourceEventWalDao().countAll() shouldBe 1L
		val stored = database.sourceEventWalDao().eventsAfter(0L, 10).single()
		stored.sourceKind shouldBe SourceKind.CELL.stableCode
		stored.sourceInstanceId shouldBe CELL_SOURCE_INSTANCE_ID
		stored.registrationGeneration shouldBe 1L
		stored.sourceSequence shouldBe 0L
		stored.deliveryIdentity shouldBe first.identity.value
		val registrationState = requireNotNull(
			database.sourceRegistrationStateDao().get(
				SourceKind.CELL.stableCode,
				CELL_OWNER_SCOPE,
			),
		)
		registrationState.registrationGeneration shouldBe 2L
		registrationState.nextSequence shouldBe 1L
		database.sourceBrokerDao().registration(SourceKind.CELL.stableCode, 1L)
			?.providerProcessIncarnationId shouldBe "cell-process-before"
		database.sourceBrokerDao().registration(SourceKind.CELL.stableCode, 2L)
			?.providerProcessIncarnationId shouldBe "cell-process-after"
	}

	@Test
	fun `shared Cell controller drives real WAL projector and Room reader`() = runTest {
		installAmbientCellProduct()
		val physical = mockk<CellSourceRuntime>()
		every { physical.capabilities } returns MutableStateFlow(
			SourceCapabilities(true, false, false, null, null),
		)
		val sinkFactory = DurableSourceEventSinkFactory(ingress)
		var admissionOrdinal: Long? = null
		coEvery { physical.refreshShared(any(), any(), null) } returns null
		coEvery { physical.reconfigure(any<CellPlan>(), any<SourceEventSink>()) } coAnswers {
			val handoff = secondArg<SourceEventSink>().admit(
				cellDeliveryCandidate(
					1L,
					1L,
					FIRST_PHYSICAL_CONFIGURATION,
					110L,
					RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
					"Europe/Prague",
				),
			).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
			admissionOrdinal = handoff.admissionOrdinals.single()
			SourceApplyResult.Applied(
				AppliedSourcePlan(
					1L,
					1L,
					SourceKind.CELL,
					SourceInstanceId(CELL_SOURCE_INSTANCE_ID),
					1L,
					110L,
					SourceApplyStatus.APPLIED,
				),
			)
		}
		val controller = SharedCellSourceController(
			physical,
			SourceBroker(
				database,
				RoomTrackingRolloutStateStore(database, CELL_EXECUTABLE_LANE_CATALOG),
			),
			sinkFactory,
		)

		controller.reconcileAmbientJoin().shouldBeInstanceOf<AmbientCellRuntimeJoinResult.Active>()
		val originalOrdinal = requireNotNull(admissionOrdinal)
		val zoneOnlyReplay = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				111L,
				RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
				"UTC",
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()
		zoneOnlyReplay.existingAdmissionOrdinals shouldBe listOf(originalOrdinal)
		val versionFourReplay = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				112L,
				4,
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Duplicate>()
		versionFourReplay.existingAdmissionOrdinals shouldBe listOf(originalOrdinal)
		database.sourceEventWalDao().countAll() shouldBe 1L
		val originalPolicy = requireNotNull(
			database.sourcePolicyDao().policyAtRevision(1L, SourceKind.CELL.stableCode),
		)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				originalPolicy.copy(
					policyRevision = 2L,
					effectiveElapsedRealtimeNanos = 120L,
					changeReason = "UNRELATED_SOURCE_CHANGE",
				),
			),
		)
		database.sourcePolicyDao().compareAndSetAuthority(
			expectedBootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			expectedRevision = 1L,
			bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
			newRevision = 2L,
			legacySettingsFingerprint = null,
			updatedAtMs = 120L,
		) shouldBe 1
		AmbientCellFactProjector(
			database,
			DefaultSourcePayloadCodec(),
			Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID },
		).project(originalOrdinal)
			.shouldBeInstanceOf<AmbientCellProjectionResult.FactWritten>()
		val row = database.ambientCellFactDao().effectiveLocalOverWindow(0L, 1_000L, 10).single()

		row.fact.storedZoneId shouldBe "Europe/Prague"
		row.fact.sourceAdmissionOrdinal shouldBe admissionOrdinal
		val freshUnchanged = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				111L,
				RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
				"Europe/Prague",
				101L,
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		AmbientCellFactProjector(
			database,
			DefaultSourcePayloadCodec(),
			Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID },
		).project(freshUnchanged.admissionOrdinals.single())
			.shouldBeInstanceOf<AmbientCellProjectionResult.CoverageCompacted>()
		val identified = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L, 1L, FIRST_PHYSICAL_CONFIGURATION, 112L,
				CELL_WAL_PAYLOAD_VERSION, "Europe/Prague", 101L,
				towerIdentifierToken = "310:260:123:456",
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		val identifiedWal = requireNotNull(database.sourceEventWalDao()
			.getByAdmissionOrdinal(identified.admissionOrdinals.single()))
		val identifiedPayload = DefaultSourcePayloadCodec().decode(
			SourceKind.CELL, identifiedWal.payloadVersion, identifiedWal.payload,
		) as CellSnapshotPayload
		identifiedPayload.observations.single().identifierToken shouldBe "310:260:123:456"
		val identifiedResult = AmbientCellFactProjector(
			database, DefaultSourcePayloadCodec(), Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID },
		).project(identified.admissionOrdinals.single())
			.shouldBeInstanceOf<AmbientCellProjectionResult.FactWritten>()
		val identifiedChildren = database.radioObservationChildDao().cellBackgroundChildren(
			com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity.WRITER_ID,
			com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity.WRITER_VERSION,
			identifiedResult.logicalFactId, 1L, 10,
		)
		identifiedChildren.single().towerIdentity shouldBe "310:260:123:456"
		val gapDelivery = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				113L,
				RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
				"Europe/Prague",
				102L,
				subscriptionId = 1,
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		val gapOrdinal = gapDelivery.admissionOrdinals.single()
		val gapWal = requireNotNull(database.sourceEventWalDao().getByAdmissionOrdinal(gapOrdinal))
		val gapId = AmbientCellAuthorityIntegrity.digest(
			"ambient-cell-gap-v1",
			gapWal.eventId,
			1L,
			0L,
		)
		val footprint = AmbientCellFactIntegrity.createReplayFootprint(
			AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP,
			gapId,
			0L,
			0L,
			1L,
			200L,
		)
		database.ambientCellFactDao().insertReplayFootprints(listOf(footprint))
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_cell_replay_footprint SET effect_checksum = ? " +
				"WHERE footprint_kind = ? AND identity_digest = ? AND semantic_revision = 0",
			arrayOf(
				"0".repeat(64),
				AmbientCellReplayFootprintEntity.KIND_LOCAL_GAP,
				gapId,
			),
		)

		AmbientCellFactProjector(
			database,
			DefaultSourcePayloadCodec(),
			Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID },
		).project(gapOrdinal) shouldBe AmbientCellProjectionResult.Unverifiable(
			AmbientCellProjectionUnverifiableReason.REPLAY_FOOTPRINT_CORRUPT,
		)
		val pendingDeletion = sinkFactory.unbound.admit(
			cellDeliveryCandidate(
				1L,
				1L,
				FIRST_PHYSICAL_CONFIGURATION,
				114L,
				RADIO_OBSERVATION_ZONE_PAYLOAD_VERSION,
				"Europe/Prague",
				103L,
			),
		).shouldBeInstanceOf<SourceDeliveryAdmissionHandoff.Durable>()
		database.ambientCellFactDao().insertDeletionMarker(
			AmbientCellFactIntegrity.createDeletionMarker(
				0L,
				1L,
				1L,
				"TEST_CELL_DELETION",
				200L,
			),
		)
		AmbientCellFactProjector(
			database,
			DefaultSourcePayloadCodec(),
			Dispatchers.Unconfined,
			BootClockDomainProvider { BOOT_CLOCK_DOMAIN_ID },
		).project(pendingDeletion.admissionOrdinals.single()) shouldBe
			AmbientCellProjectionResult.Unverifiable(
				AmbientCellProjectionUnverifiableReason.DELETED_SCOPE,
			)
	}

	private suspend fun installAmbientCellProduct() {
		database.sourcePolicyDao().ensureAuthority(
			SourcePolicyAuthorityEntity(
				bootstrapState = SourcePolicyAuthorityEntity.STATE_ACTIVE,
				currentPolicyRevision = 1L,
				legacySettingsFingerprint = null,
				updatedAtMs = 1L,
			),
		)
		database.sourcePolicyDao().insertPolicies(
			listOf(
				SourcePolicyEntity(
					1L,
					SourceKind.CELL.stableCode,
					enabled = true,
					qosCode = 2,
					locationMinTimeSeconds = null,
					locationMinDistanceMeters = null,
					locationRequiredAccuracyMeters = null,
					capturePersistenceEligible = false,
					controlPersistenceEligible = false,
					ambientPersistenceEligible = true,
					captureConsentEpoch = null,
					controlConsentEpoch = null,
					ambientConsentEpoch = 1L,
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = 50L,
					effectiveWallTimeMs = 50L,
					changeReason = "TEST_AMBIENT_CHAIN",
				),
			),
		)
		database.sourcePolicyDao().insertConsentEpochs(
			listOf(
				SourceConsentEpochEntity(
					SourceKind.CELL.stableCode,
					SourceBrokerPurpose.AMBIENT_PRODUCT,
					1L,
					eligible = true,
					persistenceEligible = true,
					policyRevision = 1L,
					effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
					effectiveElapsedRealtimeNanos = 50L,
					effectiveWallTimeMs = 50L,
					changeReason = "TEST_AMBIENT_CHAIN",
				),
			),
		)
		database.applyAmbientCellRetentionDecision(
			AmbientRadioRetentionDecision.GrantLiveAmbient(
				"privacy:cell:ambient:v1",
				0L,
				1L,
				1L,
				BOOT_CLOCK_DOMAIN_ID,
				50L,
				50L,
			),
		)
		database.ambientCellFactDao().insertAuthority(
			AmbientCellAuthorityIntegrity.create(
				1L,
				AmbientCellAuthorityEntity.STATE_ACTIVE,
				1L,
				1L,
				"privacy:cell:ambient:v1",
				1L,
				0L,
				0L,
				BOOT_CLOCK_DOMAIN_ID,
				50L,
				50L,
				1L,
				"ambient-cell-owner",
				1L,
				CELL_DEMAND_ID,
			),
		)
	}

	private fun cellDeliveryCandidate(
		registrationGeneration: Long,
		authorizationRevision: Long,
		physicalConfigurationFingerprint: String,
		receivedElapsedRealtimeNanos: Long,
		payloadVersion: Int = 1,
		observationZoneId: String? = null,
		providerTimestampNanos: Long = PROVIDER_TIMESTAMP_NANOS,
		subscriptionId: Int? = null,
		towerIdentifierToken: String = WITHHELD_RADIO_IDENTIFIER_TOKEN,
		observationsOverride: List<CellObservationEvidence>? = null,
	): SourceDeliveryCandidate {
		val observations = observationsOverride ?: listOf(
			CellObservationEvidence(
				identifierToken = towerIdentifierToken,
				radioType = "LTE",
				registered = true,
				signalLevelDbm = -91,
				providerTimestampNanos = providerTimestampNanos,
			),
		)
		val evidence = SourceEvidenceCandidate(
			providerDedupKey = null,
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.CELL,
			sourceInstanceId = SourceInstanceId(CELL_SOURCE_INSTANCE_ID),
			registrationGeneration = registrationGeneration,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
			authorizationRevision = authorizationRevision,
			registrationPurposeEligibilityMask = SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			registrationEligibilityFingerprint = ambientEligibilityFingerprint,
			// The atomic Room ingress owns the only source-sequence allocation.
			sourceSequence = 0L,
			configRevision = authorizationRevision,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = BOOT_CLOCK_DOMAIN_ID,
			observedElapsedRealtimeNanos = observations.maxOf { requireNotNull(it.providerTimestampNanos) },
			receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
			wallTimeMs = 100L,
			wallTimeUncertaintyMs = 1L,
			capturedCollectedDataEpoch = 0L,
			acquiredAtMs = 100L,
			quality = SourceQuality(),
			payloadVersion = payloadVersion,
			payload = CellSnapshotPayload(
				subscriptionId = subscriptionId,
				observations = observations,
				refreshOutcome = CellRefreshOutcome.CALLBACK,
				observationZoneId = observationZoneId,
			),
		)
		return SourceDeliveryCandidate(
			identity = cellProviderDeliveryIdentity(
				BOOT_CLOCK_DOMAIN_ID,
				observations,
				if (payloadVersion >= CELL_WAL_PAYLOAD_VERSION) {
					CELL_WAL_PAYLOAD_VERSION
				} else {
					LEGACY_CELL_WAL_PAYLOAD_VERSION
				},
			),
			units = listOf(
				SourceDeliveryUnit(
					unitIndex = 0,
					evidence = evidence,
					observedIntervalStartElapsedRealtimeNanos = observations.minOf {
						requireNotNull(it.providerTimestampNanos)
					},
				),
			),
		)
	}

	private suspend fun installCellCaptureLane() {
		database.sourceProjectionStateDao().installProductLane(
			SourceProductProjectionLaneEntity(
				sourceKind = SourceKind.CELL.stableCode,
				bindingGeneration = 1L,
				projectionId = CELL_PROJECTION_ID,
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

	private suspend fun installCellDemand() {
		database.sourceBrokerDao().insertDemands(
			listOf(
				SourceDemandEntity(
					demandId = CELL_DEMAND_ID,
					consumerId = "app:cell-ingress-test",
					sourceKind = SourceKind.CELL.stableCode,
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

	private suspend fun installCellRegistrationGeneration(
		generation: Long,
		authorizationRevision: Long,
		processIncarnationId: String,
		physicalConfigurationFingerprint: String,
		acceptedElapsedRealtimeNanos: Long,
	) {
		if (generation == 1L) {
			database.sourceRegistrationStateDao().insertIfAbsent(
				SourceRegistrationStateEntity(
					sourceKind = SourceKind.CELL.stableCode,
					ownerScope = CELL_OWNER_SCOPE,
					sourceInstanceId = CELL_SOURCE_INSTANCE_ID,
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
				sourceKind = SourceKind.CELL.stableCode,
				registrationGeneration = generation,
				sourceInstanceId = CELL_SOURCE_INSTANCE_ID,
				ownerScope = CELL_OWNER_SCOPE,
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
		installCellAuthorization(generation, authorizationRevision, acceptedElapsedRealtimeNanos)
	}

	private suspend fun installCellAuthorization(
		generation: Long,
		authorizationRevision: Long,
		effectiveElapsedRealtimeNanos: Long,
	) {
		val rows = SourceBrokerAuthorization.rows(
			sourceKind = SourceKind.CELL.stableCode,
			registrationGeneration = generation,
			authorizationRevision = authorizationRevision,
			demands = database.sourceBrokerDao().demandsByIds(listOf(CELL_DEMAND_ID)),
			effectiveBootId = BOOT_CLOCK_DOMAIN_ID,
			effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = effectiveElapsedRealtimeNanos,
		)
		ambientEligibilityFingerprint = rows.first().authorizationFingerprint
		database.sourceBrokerDao().insertAuthorizations(rows)
	}

	private suspend fun replaceCellRuntimeGeneration() {
		database.sourceBrokerDao().finishRegistration(
			sourceKind = SourceKind.CELL.stableCode,
			registrationGeneration = 1L,
			sourceInstanceId = CELL_SOURCE_INSTANCE_ID,
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs = 200L,
			retiredElapsedRealtimeNanos = 200L,
			failureCode = "PROCESS_REPLACED",
		) shouldBe 1
		installCellRegistrationGeneration(
			generation = 2L,
			authorizationRevision = 2L,
			processIncarnationId = "cell-process-after",
			physicalConfigurationFingerprint = SECOND_PHYSICAL_CONFIGURATION,
			acceptedElapsedRealtimeNanos = 200L,
		)
		val current = requireNotNull(
			database.sourceRegistrationStateDao().get(SourceKind.CELL.stableCode, CELL_OWNER_SCOPE),
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
		const val PROVIDER_TIMESTAMP_NANOS = 100L
		const val CELL_SOURCE_INSTANCE_ID = "cell-source-instance"
		const val CELL_DEMAND_ID = "cell-ambient-demand"
		const val FIRST_PHYSICAL_CONFIGURATION = "cell-physical-before"
		const val SECOND_PHYSICAL_CONFIGURATION = "cell-physical-after"
		const val CELL_PROJECTION_ID = "cell-ingress-test"
		val CELL_OWNER_SCOPE = "source-broker:${SourceKind.CELL.stableCode}"
		val CELL_EXECUTABLE_LANE_CATALOG = ExecutableSourceLaneCatalog.explicit(
			ExecutableSourceLaneBinding(
				source = SourceKind.CELL,
				bindingGeneration = 1L,
				projectionId = CELL_PROJECTION_ID,
				projectionVersion = 1,
				captureModes = setOf(CaptureReachabilityMode.AMBIENT),
			),
		)
	}
}

private class CellIngressLifecycleStore : CollectedDataLifecycleStore {
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

private class CellIngressStartupGate : TrackingStartupGate {
	override val isReady: Boolean = true
	override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult =
		TrackingStartupResult.Ready(
			legacyRecoveryPartial = false,
			liveCompletedThroughOrdinal = 0L,
		)
}

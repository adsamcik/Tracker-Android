package com.adsamcik.tracker.tracker.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimePermissionReconcilerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `permission losses map only to their protected sources`() {
		lostSourceAuthorizations(
			ALL_GRANTED,
			ALL_GRANTED.copy(hasActivityPermission = false),
		) shouldContainExactlyInAnyOrder setOf(SourceKind.ACTIVITY, SourceKind.STEPS)

		lostSourceAuthorizations(
			ALL_GRANTED,
			ALL_GRANTED.copy(
				hasPreciseLocationPermission = false,
				hasWifiScanPermission = false,
				hasCellScanPermission = false,
			),
		) shouldContainExactlyInAnyOrder setOf(
			SourceKind.LOCATION,
			SourceKind.WIFI,
			SourceKind.CELL,
		)

		lostSourceAuthorizations(
			ALL_GRANTED,
			ALL_GRANTED.copy(hasBackgroundLocationPermission = false),
		) shouldBe setOf(SourceKind.LOCATION)

		lostSourceAuthorizations(
			ALL_GRANTED,
			ALL_GRANTED.copy(
				hasReadPhonePermission = false,
				hasCellScanPermission = false,
			),
		) shouldBe setOf(SourceKind.CELL)
	}

	@Test
	fun `loss fences affected demands and authorizations at one boundary without generation churn`() = runTest {
		insertActiveSources()
		val subject = reconciler(ALL_GRANTED)
		val lostActivity = ALL_GRANTED.copy(hasActivityPermission = false)
		val boundary = RuntimePermissionFenceBoundary("boot-7", 200L, 2_000L)

		subject.changes.test {
			val result = requireNotNull(subject.reconcileAt(lostActivity, boundary))
			awaitItem() shouldBe result
			result.fencedSources shouldContainExactlyInAnyOrder
				setOf(SourceKind.ACTIVITY, SourceKind.STEPS)
			result.fenceBoundary shouldBe boundary
			cancelAndIgnoreRemainingEvents()
		}

		SourceKind.entries.forEach { source ->
			val demand = database.sourceBrokerDao().activeDemands(source.stableCode).single()
			val expectedStatus = if (source in setOf(SourceKind.ACTIVITY, SourceKind.STEPS)) {
				SourceDemandEntity.STATUS_RETIRING
			} else {
				SourceDemandEntity.STATUS_ACTIVE
			}
			demand.status shouldBe expectedStatus
			demand.sourcePolicyRevision shouldBe 9L
			demand.consentEpoch shouldBe 12L
			database.sourceBrokerDao().registration(source.stableCode, 1L)?.status shouldBe
				ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		}

		setOf(SourceKind.ACTIVITY, SourceKind.STEPS).forEach { source ->
			database.sourceBrokerDao().authorizationAt(
				source.stableCode,
				1L,
				"boot-7",
				199L,
			).toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
			database.sourceBrokerDao().authorizationAt(
				source.stableCode,
				1L,
				"boot-7",
				200L,
			).toAuthorizationSnapshotOrNull()?.isDenied shouldBe true
		}
		database.sourceBrokerDao().maximumAuthorizationRevision(SourceKind.PRESSURE.stableCode) shouldBe 1L
		database.sourceBrokerDao().latestAuthorization(SourceKind.PRESSURE.stableCode, 1L)
			.toAuthorizationSnapshotOrNull()?.isDenied shouldBe false
	}

	@Test
	fun `precise grant emits re-evaluation without changing demand policy or authorization`() = runTest {
		insertActiveSources()
		val approximate = ALL_GRANTED.copy(
			hasPreciseLocationPermission = false,
			hasWifiScanPermission = false,
			hasCellScanPermission = false,
		)
		val subject = reconciler(approximate)

		val change = requireNotNull(
			subject.reconcileAt(
				ALL_GRANTED,
				RuntimePermissionFenceBoundary("boot-7", 300L, 3_000L),
			),
		)

		change.fencedSources shouldBe emptySet()
		change.fenceBoundary shouldBe null
		SourceKind.entries.forEach { source ->
			val demand = database.sourceBrokerDao().activeDemands(source.stableCode).single()
			demand.status shouldBe SourceDemandEntity.STATUS_ACTIVE
			demand.sourcePolicyRevision shouldBe 9L
			demand.consentEpoch shouldBe 12L
			database.sourceBrokerDao().maximumAuthorizationRevision(source.stableCode) shouldBe 1L
		}
	}

	@Test
	fun `identical callback is ignored and does not rotate authorization`() = runTest {
		insertActiveSources()
		val subject = reconciler(ALL_GRANTED)

		subject.reconcileAt(
			ALL_GRANTED,
			RuntimePermissionFenceBoundary("boot-7", 400L, 4_000L),
		) shouldBe null

		SourceKind.entries.forEach { source ->
			database.sourceBrokerDao().maximumAuthorizationRevision(source.stableCode) shouldBe 1L
		}
	}

	private fun reconciler(initial: RuntimePermissionSnapshot) = RuntimePermissionReconciler(
		initialSnapshot = initial,
		database = database,
		bootClockDomainProvider = object : BootClockDomainProvider {
			override fun current(): String = "boot-7"
		},
	)

	private suspend fun insertActiveSources() {
		SourceKind.entries.forEach { source ->
			val demand = demand(source)
			database.sourceBrokerDao().insertDemands(listOf(demand))
			database.sourceBrokerDao().insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = source.stableCode,
					registrationGeneration = 1L,
					sourceInstanceId = "${source.name.lowercase()}-provider-1",
					ownerScope = "source-broker:${source.stableCode}",
					providerResidency = if (source == SourceKind.ACTIVITY) {
						ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
					} else ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
					providerProcessIncarnationId = if (source == SourceKind.ACTIVITY) null else "test-process",
					clockDomainId = "boot-7",
					physicalConfigurationFingerprint = "${source.name.lowercase()}-config",
					collectedDataEpoch = 0L,
					status = ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
					reservedAtMs = 90L,
					reservedElapsedRealtimeNanos = 90L,
					acceptedAtMs = 100L,
					acceptedElapsedRealtimeNanos = 100L,
					retiredAtMs = null,
					retiredElapsedRealtimeNanos = null,
					failureCode = null,
				),
			)
			database.sourceBrokerDao().insertAuthorizations(
				SourceBrokerAuthorization.rows(
					sourceKind = source.stableCode,
					registrationGeneration = 1L,
					authorizationRevision = 1L,
					demands = listOf(demand),
					effectiveBootId = "boot-7",
					effectiveElapsedRealtimeNanos = 100L,
					effectiveWallTimeMs = 1_000L,
				),
			)
		}
	}

	private fun demand(source: SourceKind) = SourceDemandEntity(
		demandId = "demand-${source.name.lowercase()}",
		consumerId = "app:ambient:${source.name.lowercase()}",
		sourceKind = source.stableCode,
		purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = 9L,
		consentEpoch = 12L,
		persistenceEligible = false,
		qosCode = 2,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 15_000L,
		requestedBootId = "boot-7",
		requestedElapsedRealtimeNanos = 100L,
		requestedAtMs = 1_000L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private companion object {
		val ALL_GRANTED = RuntimePermissionSnapshot(
			hasLocationPermission = true,
			hasPreciseLocationPermission = true,
			hasCoarseLocationPermission = true,
			hasBackgroundLocationPermission = true,
			hasActivityPermission = true,
			hasReadPhonePermission = true,
			hasWifiScanPermission = true,
			hasCellScanPermission = true,
		)
	}
}

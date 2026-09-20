package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSourcePlanStoreTest {
	private lateinit var database: AppDatabase
	private lateinit var store: RoomSourcePlanStore

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.inMemoryBuilder(context)
			.allowMainThreadQueries()
			.build()
		store = RoomSourcePlanStore(database, SourcePlanCodec())
	}

	@After
	fun tearDown() {
		database.close()
	}

	@Test
	fun `round trip retains policy binding and collision cannot change it`() = runTest {
		val plan = SemanticAcquisitionPlanFactory().create(
			settings = TrackingParamsState(sourcePolicyRevision = 7),
			revision = 1,
			createdAtMs = 100,
			environment = SourcePlanEnvironment(LocationBackend.FRAMEWORK, true, setOf(1)),
		)
		store.persistDesired(plan, DesiredPlanStatus.DESIRED)

		store.load(plan.revision)?.sourcePolicyRevision shouldBe 7
		shouldThrow<IllegalStateException> {
			store.persistDesired(
				plan.copy(sourcePolicyRevision = 8),
				DesiredPlanStatus.APPLYING,
			)
		}
		database.sourcePlanStateDao().revision(plan.revision)?.status shouldBe
			DesiredPlanStatus.DESIRED.name
		database.sourcePlanStateDao().revision(plan.revision)?.sourcePolicyRevision shouldBe 7
	}

	@Test
	fun `requested fused plan remains separate from durable framework effective plan`() = runTest {
		val requested = locationPlan(LocationBackend.FUSED)
		val effective = locationPlan(LocationBackend.FRAMEWORK)
		store.persistDesired(requested, DesiredPlanStatus.APPLYING)
		store.saveApplied(
			state = AppliedSourcePlan(
				desiredRevision = requested.revision,
				appliedRevision = requested.revision,
				source = SourceKind.LOCATION,
				sourceInstanceId = SourceInstanceId("location-effective"),
				registrationGeneration = 9L,
				appliedAtElapsedRealtimeNanos = 500L,
				status = SourceApplyStatus.DEGRADED,
			),
			effectivePlan = effective.plans.getValue(SourceKind.LOCATION),
			updatedAtMs = 1_000L,
		)

		(store.load(requested.revision)?.plans?.get(SourceKind.LOCATION) as LocationPlan)
			.backend shouldBe LocationBackend.FUSED
		val restartedStore = RoomSourcePlanStore(database, SourcePlanCodec())
		val applied = restartedStore.loadApplied(requested.revision)
			.shouldBeInstanceOf<AppliedPlanRead.Available>()
		(applied.plan.plans.getValue(SourceKind.LOCATION) as LocationPlan).backend shouldBe
			LocationBackend.FRAMEWORK
		database.sourcePlanStateDao().appliedStates().single().let { state ->
			state.sourceInstanceId shouldBe "location-effective"
			state.registrationGeneration shouldBe 9L
			state.appliedPayloadVersion shouldBe SourcePlanCodec.FORMAT_VERSION
			state.appliedPayloadChecksum?.length shouldBe 64
		}
	}

	@Test
	fun `unknown or corrupt effective plan encoding fails closed`() = runTest {
		val requested = locationPlan(LocationBackend.FUSED)
		val effective = locationPlan(LocationBackend.FRAMEWORK)
		store.persistDesired(requested, DesiredPlanStatus.APPLYING)
		store.saveApplied(
			state = AppliedSourcePlan(
				desiredRevision = requested.revision,
				appliedRevision = requested.revision,
				source = SourceKind.LOCATION,
				sourceInstanceId = SourceInstanceId("location-effective"),
				registrationGeneration = 9L,
				appliedAtElapsedRealtimeNanos = 500L,
				status = SourceApplyStatus.DEGRADED,
			),
			effectivePlan = effective.plans.getValue(SourceKind.LOCATION),
			updatedAtMs = 1_000L,
		)
		val stored = database.sourcePlanStateDao().appliedStates().single()

		database.sourcePlanStateDao().saveAppliedState(
			stored.copy(appliedPayloadVersion = SourcePlanCodec.FORMAT_VERSION + 1),
		)
		store.loadApplied(requested.revision)
			.shouldBeInstanceOf<AppliedPlanRead.Invalid>()

		database.sourcePlanStateDao().saveAppliedState(
			stored.copy(
				appliedPayload = requireNotNull(stored.appliedPayload).copyOf().also { payload ->
					payload[payload.lastIndex] = (payload.last() + 1).toByte()
				},
			),
		)
		store.loadApplied(requested.revision)
			.shouldBeInstanceOf<AppliedPlanRead.Invalid>()
	}

	@Test
	fun `unexpected current revision is corruption rather than historical state`() = runTest {
		val active = locationPlan(LocationBackend.FRAMEWORK, revision = 1L)
		val requested = locationPlan(LocationBackend.FUSED, revision = 2L)
		store.persistDesired(active, DesiredPlanStatus.EFFECTIVE)
		store.persistDesired(requested, DesiredPlanStatus.APPLYING)
		store.saveApplied(
			state = AppliedSourcePlan(
				desiredRevision = active.revision,
				appliedRevision = active.revision,
				source = SourceKind.LOCATION,
				sourceInstanceId = SourceInstanceId("location-active"),
				registrationGeneration = 1L,
				appliedAtElapsedRealtimeNanos = 500L,
				status = SourceApplyStatus.APPLIED,
			),
			effectivePlan = active.plans.getValue(SourceKind.LOCATION),
			updatedAtMs = 1_000L,
		)
		val stored = database.sourcePlanStateDao().appliedStates().single()
		database.sourcePlanStateDao().saveAppliedState(
			stored.copy(sourceInstanceId = null),
		)

		store.loadApplied(requested.revision) shouldBe
			AppliedPlanRead.Invalid("APPLIED_SOURCE_PLAN_CURRENT_REVISION_MISMATCH")
	}

	@Test
	fun `committing a narrower current set atomically removes obsolete source rows`() = runTest {
		val broad = locationAndStepsPlan(revision = 1L)
		store.persistDesired(broad, DesiredPlanStatus.APPLYING)
		broad.plans.values.forEach { plan ->
			store.saveApplied(
				state = AppliedSourcePlan(
					desiredRevision = broad.revision,
					appliedRevision = broad.revision,
					source = plan.source,
					sourceInstanceId = SourceInstanceId("${plan.source.name.lowercase()}-one"),
					registrationGeneration = plan.source.stableCode.toLong(),
					appliedAtElapsedRealtimeNanos = 500L,
					status = SourceApplyStatus.APPLIED,
				),
				effectivePlan = plan,
				updatedAtMs = 1_000L,
			)
		}
		database.withTransaction {
			store.commitAppliedInTransaction(broad, DesiredPlanStatus.EFFECTIVE)
		}.shouldBeInstanceOf<AppliedPlanRead.Available>()

		val narrow = locationPlan(LocationBackend.FRAMEWORK, revision = 2L)
		store.persistDesired(narrow, DesiredPlanStatus.APPLYING)
		store.saveApplied(
			state = AppliedSourcePlan(
				desiredRevision = narrow.revision,
				appliedRevision = narrow.revision,
				source = SourceKind.LOCATION,
				sourceInstanceId = SourceInstanceId("location-two"),
				registrationGeneration = 2L,
				appliedAtElapsedRealtimeNanos = 1_500L,
				status = SourceApplyStatus.APPLIED,
			),
			effectivePlan = narrow.plans.getValue(SourceKind.LOCATION),
			updatedAtMs = 2_000L,
		)

		database.withTransaction {
			store.commitAppliedInTransaction(narrow, DesiredPlanStatus.EFFECTIVE)
		}.shouldBeInstanceOf<AppliedPlanRead.Available>()
			.plan.plans.keys shouldBe setOf(SourceKind.LOCATION)
		database.sourcePlanStateDao().appliedStates()
			.map { state -> state.sourceKind } shouldBe listOf(SourceKind.LOCATION.stableCode)
	}

	private fun locationPlan(
		backend: LocationBackend,
		revision: Long = 1L,
	) = AcquisitionPlanRevision(
		revision = revision,
		planId = "location-$backend-$revision",
		createdAtMs = 100L,
		plans = mapOf(
			SourceKind.LOCATION to LocationPlan(
				revision = revision,
				backend = backend,
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 10_000L,
				minimumUpdateIntervalMs = 5_000L,
				minimumDisplacementMeters = 5f,
				maximumBatchDelayMs = 20_000L,
				preciseLocationAvailable = true,
			),
		),
		sourcePolicyRevision = 7L,
	)

	private fun locationAndStepsPlan(revision: Long) = AcquisitionPlanRevision(
		revision = revision,
		planId = "location-steps-$revision",
		createdAtMs = revision * 100L,
		plans = mapOf(
			SourceKind.LOCATION to LocationPlan(
				revision = revision,
				backend = LocationBackend.FRAMEWORK,
				mode = LocationMode.BALANCED,
				requestedIntervalMs = 10_000L,
				minimumUpdateIntervalMs = 5_000L,
				minimumDisplacementMeters = 5f,
				maximumBatchDelayMs = 20_000L,
				preciseLocationAvailable = true,
			),
			SourceKind.STEPS to StepsPlan(
				revision = revision,
				enabled = true,
				maximumReportLatencyMs = 60_000L,
				projectionCheckpointIntervalMs = 15_000L,
				movementPolicyNeedsLowLatency = false,
			),
		),
		sourcePolicyRevision = 7L,
	)
}

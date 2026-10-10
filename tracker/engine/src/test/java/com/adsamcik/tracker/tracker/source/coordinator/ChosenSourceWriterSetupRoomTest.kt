package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.api.DefaultManualTrackingStartReadinessReader
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.test.assertFailsWith
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room and fixed source transitions; no actual providers, legacy writer or startup flight. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChosenSourceWriterSetupRoomTest {
	private lateinit var database: AppDatabase
	private lateinit var catalog: ExecutableSourceLaneCatalog
	private val clock = FixedClock(1_000L, 1_000L)
	private val gate = FixtureGate()
	private val queries = CopyOnWriteArrayList<String>()
	private var laneShapeRead: CompletableDeferred<Unit>? = null

	@Before fun setUp() {
		database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext<Application>())
			.allowMainThreadQueries().setQueryExecutor(Executor(Runnable::run))
			.setQueryCallback({ sql, _ ->
				queries += sql
				if ("writer-setup-lane-shape" in sql) laneShapeRead?.complete(Unit)
			}, Executor(Runnable::run)).build()
		catalog = ExecutableSourceLaneCatalog()
	}
	@After fun tearDown() = database.close()

	@Test
	fun `each chosen nonlocation source prepares its final writer without recording or demanding siblings`() = runBlocking {
		gate.ready = true
		seedOwners()
		val setup = setup()
		for (source in SourceKind.entries.filter { it != SourceKind.LOCATION }) {
			setup.ensure(setOf(source)).sources shouldBe mapOf(source to ChosenSourceWriterStatus.READY)
			val lane = requireNotNull(database.sourceProjectionStateDao().activeProductLane(source.stableCode))
			lane.bindingGeneration shouldBe 2L
			lane.captureModeMask shouldBe 3L
			lane.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
			for (other in SourceKind.entries) database.sourceBrokerDao().activeDemands(other.stableCode) shouldBe emptyList()
			database.sourceEventWalDao().maximumAdmissionOrdinal() shouldBe null
		}
		setup.ensure(SourceKind.entries.filter { it != SourceKind.LOCATION }.toSet()).readySources.size shouldBe 5
	}

	@Test
	fun `original canonical writers settle and final generation keeps original lanes`() = runBlocking {
		gate.ready = true
		seedOwners()
		val factory = factory()
		for (spec in SourceWriterTransitionSpec.entries) {
			val engine = factory.create(spec)
			engine.installInertCandidate(revision(), 10L).shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
			engine.activateCandidate(revision(), 11L).shouldBeInstanceOf<SourceWriterTransitionResult.Applied>()
			setup().ensure(setOf(spec.source)).readySources shouldBe setOf(spec.source)
			val old = requireNotNull(database.sourceProjectionStateDao().productLane(spec.source.stableCode,
				1L, spec.binding.projectionId, spec.binding.projectionVersion))
			old.status shouldBe SourceProductProjectionLaneEntity.STATUS_RETIRED
			old.terminalDisposition shouldBe SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN
			old.contiguousAdmissionOrdinal shouldBe old.captureAdmissionCutoffOrdinal
			database.sourceDestinationOwnerDao().get(spec.source.stableCode, spec.destination)?.ownerGeneration shouldBe 4L
		}
	}

	@Test
	fun `original canonical Steps uses existing owner arithmetic to prepare preferred writer`() = runBlocking {
		gate.ready = true
		seedOwners()
		val store = RoomTrackingRolloutStateStore(database, catalog)
		store.load()
		store.installInertShadowLane(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1, revision() + 1L, 10L)
		val steps = steps()
		steps.activateCandidate(revision(), 11L).shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		setup().ensure(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
		val old = requireNotNull(database.sourceProjectionStateDao().productLane(SourceKind.STEPS.stableCode,
			1L, ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1.projectionId,
			ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1.projectionVersion))
		old.status shouldBe SourceProductProjectionLaneEntity.STATUS_RETIRED
		database.sourceDestinationOwnerDao().get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION)?.ownerGeneration shouldBe 4L
	}

	@Test
	fun `one source repair debt leaves an independently ready chosen source usable`() = runBlocking {
		gate.ready = true
		seedOwners()
		val location = mockk<ProtectedLocationWriterSetupCoordinator> {
			coEvery { ensureFinalWriter(any()) } returns SourceWriterTransitionResult.Blocked(SourceKind.LOCATION,
				SourceWriterTransitionPhase.ACTIVATE_CANDIDATE, SourceWriterTransitionBlocker.DESTINATION_OWNER_CONFLICT)
		}
		val result = setup(location = location).ensure(setOf(SourceKind.LOCATION, SourceKind.STEPS))
		result.readySources shouldBe setOf(SourceKind.STEPS)
		result.sources[SourceKind.LOCATION] shouldBe ChosenSourceWriterStatus.REPAIR_REQUIRED
		database.sourceProjectionStateDao().activeProductLane(SourceKind.LOCATION.stableCode) shouldBe null
	}

	@Test
	fun `cold not ready preparation returns Pending without trying to reconcile itself`() = runBlocking {
		gate.ready = false
		setup().ensure(setOf(SourceKind.STEPS)).sources shouldBe mapOf(SourceKind.STEPS to ChosenSourceWriterStatus.PENDING)
		gate.reconciliations.get() shouldBe 0
		database.trackingRolloutStateDao().get() shouldBe null
		database.sourceProjectionStateDao().allActiveProductLanes() shouldBe emptyList()
	}

	@Test
	fun `existing Ready publication wakes pending setup with no database change`() = runBlocking {
		seedOwners()
		val policies = stepsPolicies()
		val setup = setup(policies)
		val initial = CompletableDeferred<List<String>>()
		val next = CompletableDeferred<List<String>>()
		val collect = launch {
			setup.stateChanges().collect { key -> if (!initial.isCompleted) initial.complete(key) else next.complete(key) }
		}
		try {
			withTimeout(5_000L) { initial.await() }.first { it.startsWith("ready:") } shouldBe "ready:false:0"
			gate.reconciliations.get() shouldBe 0
			gate.ready = true
			gate.readyPublications.emit(Unit)
			withTimeout(5_000L) { next.await() }.first { it.startsWith("ready:") } shouldBe "ready:true:0"
			setup.ensure(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
		} finally { collect.cancelAndJoin() }
	}

	@Test
	fun `ready final lane fingerprint ignores ordinary sample cursor changes`() = runBlocking {
		gate.ready = true
		seedOwners()
		val setup = setup(stepsPolicies())
		setup.ensure(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
		val original = withTimeout(5_000L) { setup.stateChanges().first() }
		// Explicit cursor fixture; this proves stable setup wakeup identity, not ingress or product qualification.
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_product_projection_lane SET contiguous_admission_ordinal = 10, updated_at_ms = 1100 WHERE source_kind = ?",
			arrayOf(SourceKind.STEPS.stableCode))
		withTimeout(5_000L) { setup.stateChanges().first() } shouldBe original
		setup.readReadiness(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
	}

	@Test
	fun `ready observation filters real WAL and cursor invalidations before readiness queries`() = runBlocking(Dispatchers.Unconfined) {
		gate.ready = true
		seedOwners()
		val setup = setup(stepsPolicies(), dispatcher = Dispatchers.Unconfined)
		setup.ensure(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
		val initial = CompletableDeferred<Unit>()
		val afterMutation = CompletableDeferred<Unit>()
		val collector = launch {
			setup.stateChanges().collect { key ->
				when (key.first { it.startsWith("ready:") }) {
					"ready:true:0" -> initial.complete(Unit)
					"ready:true:1" -> afterMutation.complete(Unit)
				}
			}
		}
		try {
			withTimeout(5_000L) { initial.await() }
			yield()
			queries.clear()
			laneShapeRead = CompletableDeferred()
			database.withTransaction {
				val ordinal = appendWal("ready-shape", SourceKind.STEPS)
				database.openHelper.writableDatabase.execSQL(
					"UPDATE source_product_projection_lane SET contiguous_admission_ordinal = ?, updated_at_ms = 1100 WHERE source_kind = ?",
					arrayOf<Any>(ordinal, SourceKind.STEPS.stableCode))
			}
			// Reached lightweight Room lane query; the cursor shape stays unchanged.
			withTimeout(5_000L) { requireNotNull(laneShapeRead).await() }
			yield()
			queries.count { it.normalizedSql() == "select * from source_product_projection_lane where status = 'active'" } shouldBe 0
			// A real readiness publication still reaches exactly one readiness read, without losing the observer.
			gate.generation = 1L
			gate.readyPublications.emit(Unit)
			withTimeout(5_000L) { afterMutation.await() }
			queries.count { it.normalizedSql() == "select * from source_product_projection_lane where status = 'active'" } shouldBe 1
		} finally { laneShapeRead = null; collector.cancelAndJoin() }
	}

	@Test
	fun `pending original Steps drain progress wakes preparation without changing selected sources`() = runBlocking(Dispatchers.Unconfined) {
		gate.ready = true
		seedOwners()
		val store = RoomTrackingRolloutStateStore(database, catalog)
		store.load()
		store.installInertShadowLane(ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS_V1, revision() + 1L, 10L)
		steps().activateCandidate(revision(), 11L).shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		val ordinal = appendWal("pending-drain", SourceKind.STEPS)
		val setup = setup(stepsPolicies(), dispatcher = Dispatchers.Unconfined)
		setup.ensure(setOf(SourceKind.STEPS)).pendingSources shouldBe setOf(SourceKind.STEPS)
		val first = CompletableDeferred<List<String>>()
		val progressed = CompletableDeferred<List<String>>()
		val collector = launch {
			setup.stateChanges().collect { key ->
				if (!first.isCompleted) first.complete(key)
				else if (key != first.await()) progressed.complete(key)
			}
		}
		try {
			withTimeout(5_000L) { first.await() }.any { it.startsWith("status:") && it.endsWith(":PENDING") } shouldBe true
			// Existing projection completion is the explicit fixture boundary; no fabricated provider or product is claimed.
			database.openHelper.writableDatabase.execSQL(
				"UPDATE source_product_projection_lane SET contiguous_admission_ordinal = ?, updated_at_ms = 1100 WHERE source_kind = ?",
				arrayOf<Any>(ordinal, SourceKind.STEPS.stableCode))
			withTimeout(5_000L) { progressed.await() }
			setup.ensure(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
			database.sourceEventWalDao().getByAdmissionOrdinal(ordinal)?.eventId shouldBe "pending-drain"
		} finally { collector.cancelAndJoin() }
	}

	@Test
	fun `manual display reads the accepted snapshot without repairing a conflicting owner`() = runBlocking {
		gate.ready = true
		seedOwners()
		val policies = stepsPolicies()
		val setup = setup(policies)
		setup.ensure(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
		database.openHelper.writableDatabase.execSQL(
			"UPDATE source_destination_owner SET owner = 'unrecognized-test-owner' WHERE source_kind = ?",
			arrayOf(SourceKind.STEPS.stableCode))
		val beforeRollout = database.trackingRolloutStateDao().get()
		val beforeLane = database.sourceProjectionStateDao().allActiveProductLanes()
		val beforeOwner = database.sourceDestinationOwnerDao().get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION)
		val status = setup.readReadiness(setOf(SourceKind.STEPS))
		status.readySources shouldBe emptySet()
		status.rolloutRevision shouldBe requireNotNull(beforeRollout).revision
		// Actual production display reader; Android capability values are the Robolectric boundary.
		val reader = DefaultManualTrackingStartReadinessReader(ApplicationProvider.getApplicationContext<Application>(),
			Provider { gate }, Provider { policies }, Provider { setup }, Provider { mockk<TrackingParamsRepository>() })
		reader.readPreflight().selection?.captureConsentEpochs?.keys?.single()?.name shouldBe "STEPS"
		database.trackingRolloutStateDao().get() shouldBe beforeRollout
		database.sourceProjectionStateDao().allActiveProductLanes() shouldBe beforeLane
		database.sourceDestinationOwnerDao().get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION) shouldBe beforeOwner
	}

	@Test
	fun `failed Room setup observation recovers original ready source after dependency restoration and Ready publication`() = runBlocking(Dispatchers.Unconfined) {
		gate.ready = true
		seedOwners()
		val setup = setup(stepsPolicies(), dispatcher = Dispatchers.Unconfined)
		setup.ensure(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
		val originalLane = database.sourceProjectionStateDao().allActiveProductLanes()
		val originalOwner = database.sourceDestinationOwnerDao().get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION)
		val recovered = CompletableDeferred<List<String>>()
		val observed = CopyOnWriteArrayList<List<String>>()
		// Real Room dependency fault; originals are retained under a temporary test-only table name.
		database.openHelper.writableDatabase.execSQL("ALTER TABLE source_projection_registration RENAME TO setup_observation_registration_backup")
		gate.ready = false
		val collector = launch {
			setup.stateChanges().collect { key -> observed += key; recovered.complete(key) }
		}
		try {
			withTimeout(5_000L) {
				while (gate.readyWaits.get() == 0 || gate.readyPublications.subscriptionCount.value == 0) yield()
			}
			observed shouldBe emptyList()
			gate.reconciliations.get() shouldBe 0
			database.openHelper.writableDatabase.execSQL("ALTER TABLE setup_observation_registration_backup RENAME TO source_projection_registration")
			gate.ready = true
			gate.readyPublications.emit(Unit)
			withTimeout(5_000L) { recovered.await() }.any {
				it == "status:${SourceKind.STEPS.stableCode}:READY"
			} shouldBe true
			database.sourceProjectionStateDao().allActiveProductLanes() shouldBe originalLane
			database.sourceDestinationOwnerDao().get(StepsWriterDestination.SOURCE, StepsWriterDestination.DESTINATION) shouldBe originalOwner
			database.sourceEventWalDao().maximumAdmissionOrdinal() shouldBe null
			database.sourceBrokerDao().activeDemands(SourceKind.STEPS.stableCode) shouldBe emptyList()
		} finally {
			collector.cancelAndJoin()
			if (!gate.ready) database.openHelper.writableDatabase.execSQL(
				"ALTER TABLE setup_observation_registration_backup RENAME TO source_projection_registration")
		}
	}

	@Test
	fun `disposing failed setup observation cancels signalled recovery and cannot resume on later Ready`() = runBlocking(Dispatchers.Unconfined) {
		gate.ready = true
		seedOwners()
		val setup = setup(stepsPolicies(), dispatcher = Dispatchers.Unconfined)
		setup.ensure(setOf(SourceKind.STEPS)).readySources shouldBe setOf(SourceKind.STEPS)
		database.openHelper.writableDatabase.execSQL("ALTER TABLE source_projection_registration RENAME TO setup_observation_registration_backup")
		gate.ready = false
		val observed = CopyOnWriteArrayList<List<String>>()
		val collector = launch { setup.stateChanges().collect { observed += it } }
		try {
			withTimeout(5_000L) {
				while (gate.readyWaits.get() == 0 || gate.readyPublications.subscriptionCount.value == 0) yield()
			}
			collector.cancelAndJoin()
			collector.isCancelled shouldBe true
			gate.readyPublications.subscriptionCount.value shouldBe 0
			database.openHelper.writableDatabase.execSQL("ALTER TABLE setup_observation_registration_backup RENAME TO source_projection_registration")
			gate.ready = true
			gate.readyPublications.emit(Unit)
			yield()
			observed shouldBe emptyList()
			gate.readyWaits.get() shouldBe 1
			gate.reconciliations.get() shouldBe 0
		} finally {
			collector.cancelAndJoin()
			if (!gate.ready) database.openHelper.writableDatabase.execSQL(
				"ALTER TABLE setup_observation_registration_backup RENAME TO source_projection_registration")
		}
	}

	@Test
	fun `source cancellation is propagated rather than turned into observation retry`() = runBlocking {
		val policies = stepsPolicies()
		val attempts = AtomicInteger()
		val cancelledPolicy = object : SourcePolicyRepository by policies {
			override val states: Flow<SourcePolicyAuthorityState> = flow {
				attempts.incrementAndGet()
				throw CancellationException("source observation cancellation fixture")
			}
		}
		assertFailsWith<CancellationException> { setup(cancelledPolicy).stateChanges().first() }
		attempts.get() shouldBe 1
		gate.readyWaits.get() shouldBe 0
		gate.reconciliations.get() shouldBe 0
	}

	/** Sealed Room WAL fixture; the provider admission and native materializer are outside these cases. */
	private suspend fun appendWal(id: String, source: SourceKind): Long {
		val row = SourceEventWalEntity(eventId = id, providerDedupKey = null,
			logicalTrackingId = null, serviceRunId = null, sourceKind = source.stableCode,
			sourceInstanceId = id, registrationGeneration = 1L, sourceSequence = 1L,
			configRevision = 1L, planAttribution = 0, clockDomainId = "test-boot",
			observedElapsedNanos = 1L, receivedElapsedNanos = 1L, wallTimeMs = 1L,
			wallTimeUncertaintyMs = 0L, capturedCollectedDataEpoch = 0L, acquiredAtMs = 1L,
			qualityFlags = 0L, qualityConfidence = null, payloadVersion = 1,
			payload = byteArrayOf(1), payloadChecksum = "", createdAtMs = 1L)
		return database.sourceEventWalDao().insertIgnoringDuplicate(row.copy(
			payloadChecksum = row.calculatedPayloadChecksum(), integrityIdentity = row.calculatedIntegrityIdentity()))
	}
	private fun String.normalizedSql() = replace(Regex("\\s+"), " ").trim().lowercase()

	private suspend fun stepsPolicies(): SourcePolicyRepository {
		val policies = RoomSourcePolicyRepository(database) { SourcePolicyEffectiveTime("test-boot", 1_000L, 1_000L) }
		policies.bootstrapFromLegacy(TrackingParamsState(locationEnabled = false, activityEnabled = false,
			barometerEnabled = false, stepsEnabled = true, legacySettingsMigrationCompleted = true))
		return policies
	}
	private fun setup(policies: SourcePolicyRepository = mockk(), location: ProtectedLocationWriterSetupCoordinator = mockk(),
		dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) =
		ChosenSourceWriterSetup(database, catalog, RoomTrackingRolloutStateStore(database, catalog), factory(), steps(),
			location, policies, Provider { gate }, clock, TestDispatchersProvider(dispatcher), Provider { mockk() }, Provider { mockk() })
	private fun factory() = SourceWriterTransitionEngineFactory.forTest(database, catalog,
		SourceWriterTransitionTestDependencies(gate, clock = clock))
	private fun steps() = StepsWriterTransitionEngine.forTest(database, catalog,
		StepsWriterTransitionTestDependencies(gate, clock = clock))
	private suspend fun revision() = RoomTrackingRolloutStateStore(database, catalog).load().revision
	private suspend fun seedOwners() {
		database.sourceEvidenceStateDao().ensure(SourceEvidenceState())
		for (spec in SourceWriterTransitionSpec.entries) spec.initialOwner?.let { owner ->
			database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(spec.source.stableCode,
				spec.destination, owner, 1L, 0L))
		}
		database.sourceDestinationOwnerDao().insertIfAbsent(SourceDestinationOwnerEntity(StepsWriterDestination.SOURCE,
			StepsWriterDestination.DESTINATION, StepsWriterDestination.LEGACY_OWNER, 1L, 0L))
	}
	private class FixtureGate : TrackingStartupGate {
		var ready = false
		var generation = 0L
		override val currentGeneration get() = generation
		val readyPublications = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
		val reconciliations = AtomicInteger()
		val readyWaits = AtomicInteger()
		override val isReady get() = ready
		override val runtimeReadyChanges: Flow<Unit> get() = readyPublications
		override suspend fun awaitReady(): TrackingStartupResult.Ready {
			readyWaits.incrementAndGet()
			if (!ready) readyPublications.first { ready }
			return TrackingStartupResult.Ready(false, 0L)
		}
		override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
			reconciliations.incrementAndGet()
			check(ready) { "A not-ready setup must not reconcile the initializer that owns its readiness" }
			return TrackingStartupResult.Ready(false, 0L)
		}
	}
}

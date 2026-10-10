package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticFailureCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticFailureReason
import com.adsamcik.tracker.tracker.api.retryingTrackingSettingsObservation
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.SharedWifiSourceController
import com.adsamcik.tracker.tracker.source.runtime.SharedCellSourceController
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Small ordinary preparation shared by existing startup and Start callers; never a sensor request. */
@Singleton
class ChosenSourceWriterSetup @Inject internal constructor(
	private val database: AppDatabase,
	private val catalog: ExecutableSourceLaneCatalog,
	private val rolloutStore: RoomTrackingRolloutStateStore,
	private val transitions: SourceWriterTransitionEngineFactory,
	private val steps: StepsWriterTransitionEngine,
	private val location: ProtectedLocationWriterSetupCoordinator,
	private val policyRepository: SourcePolicyRepository,
	private val startupGate: Provider<TrackingStartupGate>,
	private val clock: Clock,
	private val dispatchers: DispatchersProvider,
	private val wifi: Provider<SharedWifiSourceController>,
	private val cell: Provider<SharedCellSourceController>,
) {
	suspend fun ensureCurrentChosenSources(): ChosenSourceWriterSetupResult {
		if (!startupGate.get().isReady) return ChosenSourceWriterSetupResult(emptyMap(), policyAvailable = false)
		val state = policyRepository.currentState() as? SourcePolicyAuthorityState.Active
			?: return ChosenSourceWriterSetupResult(emptyMap(), policyAvailable = false)
		// CONTROL alone never selects an Activity capture writer. Ambient does not grant capture consent.
		val chosen = state.snapshot.policies.values.filter {
			it.capturePersistenceEligible || it.ambientPersistenceEligible
		}.mapTo(linkedSetOf()) { it.source.toSetupSource() }
		return ensure(chosen)
	}

	suspend fun ensure(chosen: Set<SourceKind>): ChosenSourceWriterSetupResult = withContext(dispatchers.io) {
		if (chosen.isEmpty()) return@withContext ChosenSourceWriterSetupResult(emptyMap())
		if (!startupGate.get().isReady) return@withContext pending(chosen)
		try { rolloutStore.load() } catch (cancelled: CancellationException) { throw cancelled }
		catch (_: Exception) { return@withContext pending(chosen) }
		val results = linkedMapOf<SourceKind, ChosenSourceWriterStatus>()
		for (source in chosen.sortedBy { it.stableCode }) {
			currentCoroutineContext().ensureActive()
			results[source] = try {
				if (!prepareOriginalRadioCallbackBoundary(source)) {
					results[source] = ChosenSourceWriterStatus.PENDING
					continue
				}
				val now = clock.currentTimeMillis().coerceAtLeast(0L)
				val status = when (source) {
					SourceKind.LOCATION -> location.ensureFinalWriter(now).setupStatus()
					SourceKind.STEPS -> steps.preparePreferredWriter(now).setupStatus()
					else -> transitions.create(requireNotNull(SourceWriterTransitionSpec.entries.singleOrNull { it.source == source }))
						.prepareFinalKnownWriter(now).setupStatus()
				}
				if (status == ChosenSourceWriterStatus.READY) readReadiness(setOf(source)).sources.getValue(source) else status
			} catch (cancelled: CancellationException) { throw cancelled }
			catch (_: Exception) { ChosenSourceWriterStatus.PENDING }
		}
		ChosenSourceWriterSetupResult(results)
	}

	/** Existing resident owner only; End/recovery remains responsible for active capture and cold registrations. */
	private suspend fun prepareOriginalRadioCallbackBoundary(source: SourceKind): Boolean {
		if (source != SourceKind.WIFI && source != SourceKind.CELL) return true
		val needsPrefix = database.withTransaction {
			val spec = SourceWriterTransitionSpec.entries.single { it.source == source }
			val lane = database.sourceProjectionStateDao().activeProductLane(source.stableCode)
			val sessions = database.sourceSessionDao()
			lane != null && catalog.bindingFor(lane) == spec.binding &&
				!sessions.hasLifecycleBoundaryBlocker() && !sessions.hasIncompleteServiceRun() &&
				!sessions.hasNonterminalLatestLifecycleAction() &&
				database.sourceBrokerDao().activeDemands(source.stableCode).none { it.purpose == SourceBrokerPurpose.SESSION_CAPTURE } &&
				!database.isSharedRadioSessionCaptureAdmissionFenced(source.stableCode)
		}
		if (!needsPrefix) return true
		// This outer bound includes controller/lifecycle mutexes and enqueue, before the actor's own deadline starts.
		return withTimeoutOrNull(RADIO_CALLBACK_PREPARATION_TIMEOUT_MS) {
			val deadline = android.os.SystemClock.elapsedRealtimeNanos() + RADIO_CALLBACK_PREPARATION_TIMEOUT_MS * 1_000_000L
			if (source == SourceKind.WIFI) wifi.get().prepareSessionCaptureBoundary(deadline)
			else cell.get().prepareSessionCaptureBoundary(deadline)
		} ?: false
	}

	/** Read-only status; display callers never run a transition or open a provider. */
	suspend fun readReadiness(chosen: Set<SourceKind>): ChosenSourceWriterSetupResult = withContext(dispatchers.io) {
		database.withTransaction { readReadinessInTransaction(chosen) }
	}

	private suspend fun readReadinessInTransaction(chosen: Set<SourceKind>): ChosenSourceWriterSetupResult {
		val rollout = database.trackingRolloutStateDao().get()?.decodeCurrentModelOrNull() ?: return pending(chosen)
		val lanes = database.sourceProjectionStateDao().allActiveProductLanes()
		return ChosenSourceWriterSetupResult(chosen.associateWith { source ->
			val selected = lanes.filter { it.sourceKind == source.stableCode }
			if (selected.size > 1) ChosenSourceWriterStatus.REPAIR_REQUIRED else {
				val lane = selected.singleOrNull()
				val binding = lane?.let(catalog::bindingFor)
				if (lane != null && binding != null && binding.captureModeMask == 3L &&
					lane.captureAdmissionCutoffOrdinal == null && lane.terminalDisposition == null && lane.terminalAtMs == null &&
					lane.isCanonicalCaptureAuthorizedBy(database, rollout, catalog)) ChosenSourceWriterStatus.READY
				else if (lane != null && binding == null) ChosenSourceWriterStatus.REPAIR_REQUIRED
				else ChosenSourceWriterStatus.PENDING
			}
		}, rolloutRevision = rollout.revision)
	}

	/** The existing purpose observer needs progress subscriptions only while preparation is pending. */
	@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
	fun stateChanges(): Flow<List<String>> = policyRepository.states.map { state ->
		(state as? SourcePolicyAuthorityState.Active)?.snapshot?.policies?.values
			?.filter { it.capturePersistenceEligible || it.ambientPersistenceEligible }
			?.mapTo(linkedSetOf()) { it.source.toSetupSource() }
	}.distinctUntilChanged().flatMapLatest { chosen ->
		if (chosen == null) flowOf(listOf("policy-unavailable")) else combine(
			merge(startupGate.get().runtimeReadyChanges.onStart { emit(Unit) },
				database.invalidationTracker.createFlow("tracking_rollout_state", "source_destination_owner",
					emitInitialState = true).map { Unit }),
			laneSetupChanges(chosen),
			database.invalidationTracker.createFlow("source_evidence_state", emitInitialState = true)
				.map { database.sourceEvidenceStateDao().get()?.collectedDataEpoch }.distinctUntilChanged(),
		) { _, _, _ -> database.withTransaction { wakeupKey(chosen) } }
			.distinctUntilChanged().flatMapLatest { key ->
				if (key.none { it.startsWith("status:") && it.endsWith(":PENDING") }) flowOf(key)
				else database.invalidationTracker.createFlow("logical_tracking_session", "source_service_run",
					"lifecycle_desired_action", "provider_registration_generation", "source_authorization",
					"source_demand", "pending_signal", "source_product_projection_lane", "source_event_wal",
					emitInitialState = true).map { database.withTransaction { wakeupKey(chosen) } }
					.onStart { emit(key) }
			}
	}.distinctUntilChanged()
		.retryingTrackingSettingsObservation(
			onFailure = {
				TrackerDiagnosticLog.failure(TrackerDiagnosticFailureCode.SOURCE_POLICY_OBSERVATION_FAILED,
					TrackingDiagnosticFailureReason.POLICY_READ_FAILURE)
			},
			waitBeforeRetry = {
				val gate = startupGate.get()
				if (gate.isReady) delay(SETUP_OBSERVATION_RETRY_DELAY_MS) else gate.awaitReady()
			},
		).flowOn(dispatchers.io)

	/** A cursor/time update is cheap to observe but must not cause a readiness transaction. */
	private fun laneSetupChanges(chosen: Set<SourceKind>): Flow<List<String>> =
		database.invalidationTracker.createFlow("source_product_projection_lane", "source_projection_registration",
			emitInitialState = true).map {
			if (chosen.isEmpty()) emptyList() else buildList {
				val codes = chosen.sortedBy { it.stableCode }.map { it.stableCode }
				database.query(SimpleSQLiteQuery("/* writer-setup-lane-shape */ " +
					"SELECT source_kind,binding_generation,projection_id,projection_version,capture_mode_mask," +
					"product_stage,activated_rollout_revision,activation_ordinal,capture_admission_cutoff_ordinal," +
					"retention_required,status,terminal_disposition,terminal_at_ms," +
					"CASE WHEN contiguous_admission_ordinal >= activation_ordinal - 1 THEN 1 ELSE 0 END," +
					"EXISTS(SELECT 1 FROM source_projection_registration registration " +
					"WHERE registration.projection_id = lane.projection_id AND " +
					"registration.projection_version = lane.projection_version) " +
					"FROM source_product_projection_lane lane WHERE source_kind IN (${codes.joinToString { "?" }}) " +
					"AND status = 'ACTIVE' ORDER BY source_kind,binding_generation", codes.toTypedArray())).use { rows ->
					while (rows.moveToNext()) add((0 until rows.columnCount).joinToString("|") { column ->
						if (rows.isNull(column)) "-" else rows.getString(column).let { "${it.length}:$it" }
					})
				}
			}
		}.distinctUntilChanged()

	private suspend fun wakeupKey(chosen: Set<SourceKind>): List<String> = buildList {
		add("ready:${startupGate.get().isReady}:${startupGate.get().currentGeneration}")
		val readiness = readReadinessInTransaction(chosen)
		for (source in chosen.sortedBy { it.stableCode }) add("status:${source.stableCode}:${readiness.sources[source]}")
		add("epoch:${database.sourceEvidenceStateDao().get()?.collectedDataEpoch}")
		val pending = readiness.pendingSources
		if (pending.isEmpty()) return@buildList
		val sessions = database.sourceSessionDao()
		add("idle:${!sessions.hasLifecycleBoundaryBlocker() && !sessions.hasIncompleteServiceRun() && !sessions.hasNonterminalLatestLifecycleAction()}")
		add("pending:${database.pendingSignalDao().countAll() > 0}")
		val highWater = maxOf(database.sourceEventWalDao().admissionAllocatorHighWater(),
			database.sourceEvidenceStateDao().get()?.deletedSourceEventHighWaterOrdinal ?: 0L)
		val codes = pending.map { it.stableCode }
		database.query(SimpleSQLiteQuery("SELECT source_kind,binding_generation,product_stage,status,capture_admission_cutoff_ordinal," +
			"contiguous_admission_ordinal,projection_id,projection_version,capture_mode_mask,terminal_disposition " +
			"FROM source_product_projection_lane WHERE source_kind IN (${codes.joinToString { "?" }}) " +
			"ORDER BY source_kind,binding_generation", codes.toTypedArray())).use { rows ->
			while (rows.moveToNext()) {
				val target = if (rows.isNull(4)) highWater else rows.getLong(4)
				add("lane:${rows.getInt(0)}:${rows.getLong(1)}:${rows.getString(2)}:${rows.getString(3)}:" +
					"${rows.getLong(5) >= target}:${rows.getString(6)}:${rows.getInt(7)}:${rows.getLong(8)}:${rows.getString(9)}")
			}
		}
		for (source in pending.sortedBy { it.stableCode }) add("fence:${source.stableCode}:" + when (source) {
			SourceKind.LOCATION -> database.isLocationSessionCaptureAdmissionFenced()
			SourceKind.WIFI, SourceKind.CELL -> database.isSharedRadioSessionCaptureAdmissionFenced(source.stableCode)
			else -> database.isSourceCaptureAdmissionFenced(source.stableCode)
		})
	}

	private fun pending(chosen: Set<SourceKind>) = ChosenSourceWriterSetupResult(chosen.associateWith { ChosenSourceWriterStatus.PENDING })

	private companion object {
		const val RADIO_CALLBACK_PREPARATION_TIMEOUT_MS = 3_000L
		// Same failure-only observation backoff as the existing purpose/settings observer.
		const val SETUP_OBSERVATION_RETRY_DELAY_MS = 250L
	}
}

enum class ChosenSourceWriterStatus { READY, PENDING, REPAIR_REQUIRED }

data class ChosenSourceWriterSetupResult(
	val sources: Map<SourceKind, ChosenSourceWriterStatus>,
	val policyAvailable: Boolean = true,
	/** Returned only by the same read-only Room readiness snapshot. */
	val rolloutRevision: Long? = null,
) {
	val readySources: Set<SourceKind> get() = sources.filterValues { it == ChosenSourceWriterStatus.READY }.keys
	val pendingSources: Set<SourceKind> get() = sources.filterValues { it == ChosenSourceWriterStatus.PENDING }.keys
}

private fun SourceWriterTransitionResult.setupStatus(): ChosenSourceWriterStatus = when (this) {
	is SourceWriterTransitionResult.Applied, is SourceWriterTransitionResult.AlreadyApplied -> ChosenSourceWriterStatus.READY
	is SourceWriterTransitionResult.Busy, is SourceWriterTransitionResult.StartupUnavailable -> ChosenSourceWriterStatus.PENDING
	is SourceWriterTransitionResult.Blocked -> if (blocker.name in SETUP_PENDING_BLOCKERS) ChosenSourceWriterStatus.PENDING
		else ChosenSourceWriterStatus.REPAIR_REQUIRED
}
private fun StepsWriterTransitionResult.setupStatus(): ChosenSourceWriterStatus = when (this) {
	is StepsWriterTransitionResult.Applied, is StepsWriterTransitionResult.AlreadyApplied -> ChosenSourceWriterStatus.READY
	is StepsWriterTransitionResult.Busy, is StepsWriterTransitionResult.StartupUnavailable -> ChosenSourceWriterStatus.PENDING
	is StepsWriterTransitionResult.Blocked -> if (blocker.name in SETUP_PENDING_BLOCKERS) ChosenSourceWriterStatus.PENDING
		else ChosenSourceWriterStatus.REPAIR_REQUIRED
}
private val SETUP_PENDING_BLOCKERS = setOf("ROLLOUT_REVISION_CHANGED", "LIFECYCLE_NOT_IDLE", "SERVICE_RUN_NOT_FINALIZED",
	"LIFECYCLE_ACTION_NOT_TERMINAL", "CAPTURE_CALLBACK_BARRIER_OPEN", "LEGACY_WRITER_NOT_QUIESCENT",
	"LEGACY_STEPS_WRITER_NOT_QUIESCENT", "STEPS_COMMAND_PENDING", "SHADOW_CURSOR_BEHIND", "ROLLBACK_CURSOR_BEHIND", "SESSION_LEASE_LOST")
private fun TrackingSourceComponent.toSetupSource(): SourceKind = when (this) {
	TrackingSourceComponent.LOCATION -> SourceKind.LOCATION
	TrackingSourceComponent.ACTIVITY -> SourceKind.ACTIVITY
	TrackingSourceComponent.STEPS -> SourceKind.STEPS
	TrackingSourceComponent.PRESSURE -> SourceKind.PRESSURE
	TrackingSourceComponent.WIFI -> SourceKind.WIFI
	TrackingSourceComponent.CELL -> SourceKind.CELL
}

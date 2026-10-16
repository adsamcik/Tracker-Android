package com.adsamcik.tracker.tracker.service

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsCapability
import com.adsamcik.tracker.tracker.source.ambient.steps.AndroidAmbientStepsCapabilityResolver
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Session metadata over the existing all-day provider; never acquires or copies observations. */
@Singleton
class ProviderStepsSessionLifecycle @Inject constructor(
	@ApplicationContext private val context: Context,
	private val databaseProvider: Provider<AppDatabase>,
	private val settings: TrackingParamsRepository,
	private val capabilities: AndroidAmbientStepsCapabilityResolver,
	private val startupProvider: Provider<TrackingStartupGate>,
	private val clock: Clock,
	private val boot: BootClockDomainProvider,
	private val dispatchers: DispatchersProvider,
	private val commands: TrackingLifecycleCommandAuthority,
) {
	private val startup: TrackingStartupGate get() = startupProvider.get()
	internal suspend fun isAvailable(): Boolean {
		if (context.hasStepCounterSensor) {
			return false
		}
		val chosen = settings.data.first()
		return chosen.stepsEnabled && chosen.ambientStepsEnabled &&
			capabilities.resolve() is AmbientStepsCapability.ReadyForRegistration
	}

	internal suspend fun continuationRequest(): com.adsamcik.tracker.tracker.api.TrackingStartRequest? =
		withContext(dispatchers.io) {
			val row = databaseProvider.get().sourceSessionDao().activeSession() ?: return@withContext null
			val command = TrackingStartCommand(row.lifecycleLeaseGeneration.takeIf { it > 0L } ?: return@withContext null)
			val matchesSession = row.sessionMode == LogicalTrackingSessionEntity.MODE_PROVIDER_STEPS &&
				row.state == "ACTIVE" && row.currentServiceRunId == null && row.clockDomainId == boot.current()
			if (!matchesSession || !commands.isAcceptedStart(command) || commands.resolveStart(command) !=
					com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition.Allowed) {
				return@withContext null
			}
			com.adsamcik.tracker.tracker.api.TrackingStartRequest(command, isUserInitiated = true, isAmbient = false,
				providerStepsContinuation = com.adsamcik.tracker.tracker.api.ProviderStepsSessionContinuation(
					row.logicalTrackingId, row.lifecycleRevision, command.generation, row.clockDomainId))
		}

	/** Caller already holds the same startup preparation boundary as a physical Start. */
	internal suspend fun prepare(commandGeneration: Long, origin: SessionStartOrigin,
		startupGeneration: Long, rolloutRevision: Long): TrackingStartPreparationResult =
		withContext(dispatchers.io) {
			require(origin == SessionStartOrigin.MANUAL_FOREGROUND_START)
			val database = databaseProvider.get()
			database.withTransaction {
				val dao = database.sourceSessionDao()
				if (dao.activeSession() != null) {
					return@withTransaction TrackingStartPreparationResult.AlreadyActive
				}
				val id = "provider-steps:${UUID.randomUUID()}"
				val now = clock.currentTimeMillis()
				dao.insertSession(LogicalTrackingSessionEntity(
					logicalTrackingId = id, state = "STARTING", lifecycleRevision = 1L,
					desiredPlanRevision = 0L, rolloutRevision = rolloutRevision, startOrigin = origin.name,
					clockDomainId = boot.current(), startedAtMs = now,
					startedElapsedNanos = clock.elapsedRealtimeNanos(), cutoffAtMs = null,
					cutoffElapsedNanos = null, completedAtMs = null, finalAdmissionOrdinal = null,
					failureCode = null, sessionMode = LogicalTrackingSessionEntity.MODE_PROVIDER_STEPS,
					// This organization lifetime uses the shared command gate, never a provider lease.
					lifecycleLeaseGeneration = commandGeneration, lifecycleBootId = boot.current(),
				))
				database.organizationAnnotationDao().insert(OrganizationAnnotationEntity(
					localId = UUID.randomUUID().toString(), revision = 1L, createdAtMs = now,
					updatedAtMs = now, recordingAdapterKey = "logical:$id", startTimeMs = null,
					endTimeMs = null, displayZoneId = ZoneId.systemDefault().id,
					name = null, activityLabelId = null, hidden = false,
				))
				TrackingStartPreparationResult.Prepared(PreparedTrackingStartToken(id), startupGeneration,
					preparedStartIsUserInitiatedHint = origin == SessionStartOrigin.MANUAL_FOREGROUND_START,
					requiresForegroundService = false)
			}
		}

	internal suspend fun accept(token: PreparedTrackingStartToken, commandGeneration: Long): Boolean =
		withContext(dispatchers.io) {
			if (!isAvailable()) {
				return@withContext false
			}
			val database = databaseProvider.get()
			database.withTransaction {
				val dao = database.sourceSessionDao()
				val row = dao.session(token.value) ?: return@withTransaction false
				if (!matches(row, commandGeneration) || row.state != "STARTING" ||
					row.clockDomainId != boot.current()) {
					return@withTransaction false
				}
				dao.updateSession(row.copy(state = "ACTIVE", lifecycleRevision = row.lifecycleRevision + 1L)) == 1
			}
		}

	internal suspend fun compensate(token: PreparedTrackingStartToken, commandGeneration: Long,
		failure: String): Boolean = withContext(dispatchers.io) {
		val database = databaseProvider.get()
		database.withTransaction {
			val dao = database.sourceSessionDao()
			val row = dao.session(token.value) ?: return@withTransaction false
			if (!matches(row, commandGeneration) || row.state !in setOf("STARTING", "ACTIVE")) {
				return@withTransaction false
			}
			database.organizationAnnotationDao().byAdapter("logical:${row.logicalTrackingId}")?.let { annotation ->
				database.organizationAnnotationDao().update(annotation.copy(hidden = true,
					revision = annotation.revision + 1L, updatedAtMs = clock.currentTimeMillis()))
			}
			dao.updateSession(row.copy(state = "FAILED", lifecycleRevision = row.lifecycleRevision + 1L,
				cutoffAtMs = row.startedAtMs, cutoffElapsedNanos = row.startedElapsedNanos,
				completedAtMs = clock.currentTimeMillis(), failureCode = failure)) == 1
		}
	}

	/** Called while the existing STOP command gate excludes a newer Start. No provider is stopped. */
	internal suspend fun close(command: TrackingStopCommand): Boolean = withContext(dispatchers.io) {
		val database = databaseProvider.get()
		database.withTransaction {
			val dao = database.sourceSessionDao()
			val row = dao.activeSession()?.takeIf {
				it.sessionMode == LogicalTrackingSessionEntity.MODE_PROVIDER_STEPS && it.currentServiceRunId == null &&
					it.lifecycleLeaseGeneration > 0L && it.lifecycleLeaseGeneration < command.generation
			} ?: return@withTransaction false
			if (row.state != "ACTIVE" || !commands.isAcceptedStart(TrackingStartCommand(row.lifecycleLeaseGeneration))) {
				database.organizationAnnotationDao().byAdapter("logical:${row.logicalTrackingId}")?.let { annotation ->
					database.organizationAnnotationDao().update(annotation.copy(hidden = true,
						revision = annotation.revision + 1L, updatedAtMs = clock.currentTimeMillis()))
				}
				return@withTransaction dao.updateSession(row.copy(state = "FAILED",
					lifecycleRevision = row.lifecycleRevision + 1L, cutoffAtMs = row.startedAtMs,
					cutoffElapsedNanos = row.startedElapsedNanos, completedAtMs = clock.currentTimeMillis(),
					failureCode = "START_SUPERSEDED_BY_STOP")) == 1
			}
			val now = clock.currentTimeMillis().coerceAtLeast(row.startedAtMs)
			val end = command.requestedAtEpochMs.coerceIn(row.startedAtMs, now)
			val elapsed = command.requestedElapsedRealtimeNanos?.takeIf {
				command.requestedBootId == row.clockDomainId && it >= row.startedElapsedNanos
			}
			dao.updateSession(row.copy(state = "FINALIZED", lifecycleRevision = row.lifecycleRevision + 1L,
				cutoffAtMs = end, cutoffElapsedNanos = elapsed, completedAtMs = now)) == 1
		}
	}

	/**
	 * Observes accepted provider-only Steps sessions while startup is ready; emits null when none is
	 * usable.
	 */
	@OptIn(ExperimentalCoroutinesApi::class)
	fun observeSessionInfo(): Flow<TrackerSessionInfo?> = startup.runtimeReadyChanges.flatMapLatest {
		if (!startup.isReady) {
			return@flatMapLatest flowOf(null)
		}
		val generation = startup.currentGeneration
		withContext(dispatchers.io) { databaseProvider.get() }.invalidationTracker
			.createFlow("logical_tracking_session", "source_service_run", emitInitialState = true)
			.combine(commands.acceptedStartGeneration) { _, _ -> Unit }.map {
				startup.withReadyGenerationOperation(generation) {
					withContext(dispatchers.io) {
						databaseProvider.get().sourceSessionDao().activeSession()?.takeIf { row ->
							isAcceptedOrganization(row)
						}?.let { row -> TrackerSessionInfo(
							row.startOrigin == SessionStartOrigin.MANUAL_FOREGROUND_START.name,
							isProviderStepsSession = true)
						}
					}
				}
			}.catch { failure ->
				if (failure is CancellationException) {
					throw failure
				}
				emit(null)
			}
	}.distinctUntilChanged()

	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private suspend fun isAcceptedOrganization(row: LogicalTrackingSessionEntity): Boolean {
		if (row.sessionMode == LogicalTrackingSessionEntity.MODE_PROVIDER_STEPS && row.state == "ACTIVE") {
			return commands.isAcceptedStart(TrackingStartCommand(row.lifecycleLeaseGeneration))
		}
		if (row.state !in setOf("STARTING", "ACTIVE")) {
			return false
		}
		return isAcceptedProviderJoin(row)
	}

	private suspend fun isAcceptedProviderJoin(row: LogicalTrackingSessionEntity): Boolean {
		val dao = databaseProvider.get().sourceSessionDao()
		val run = row.currentServiceRunId?.let { dao.serviceRun(it) } ?: return false
		val manifest = dao.manifest(row.logicalTrackingId, run.preparedManifestRevision) ?: return false
		val matchesJoin = manifest.changeReason == "PROVIDER_STEPS_JOIN" && manifest.serviceRunId == run.serviceRunId &&
			row.currentManifestRevision == run.preparedManifestRevision && run.startCommandGeneration > 0L
		return matchesJoin && commands.isAcceptedStart(TrackingStartCommand(run.startCommandGeneration))
	}

	private fun matches(row: LogicalTrackingSessionEntity, generation: Long): Boolean =
		row.sessionMode == LogicalTrackingSessionEntity.MODE_PROVIDER_STEPS &&
			row.currentServiceRunId == null && row.lifecycleLeaseGeneration == generation
}

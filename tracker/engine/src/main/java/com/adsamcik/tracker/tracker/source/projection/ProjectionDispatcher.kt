package com.adsamcik.tracker.tracker.source.projection

import androidx.room.withTransaction
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

@Singleton
// One dispatcher owns projection registration, checkpoint and outbox transaction consistency.
@Suppress("TooManyFunctions")
class ProjectionDispatcher @Inject constructor(
	private val database: AppDatabase,
	projections: Set<@JvmSuppressWildcards Projection>,
) {
	private val projections = projections.sortedBy(Projection::id)
	private val globalProjections = this.projections.filterNot { it.isSourceLocalAmbient() }
	private val ambientMutexes = AMBIENT_SOURCES.associateWith { Mutex() }

	suspend fun registerAll(activationOrdinal: Long) {
		require(activationOrdinal > 0L)
		globalProjections.forEach { projection ->
			registerProjection(projection, activationOrdinal)
		}
	}

	/** A source-local background consumer never joins session settlement's global continuation. */
	internal suspend fun minimumGlobalCheckpoint(): Long? =
		database.sourceProjectionStateDao().minimumActiveGlobalCheckpoint(
			this.projections.filter { it.isSourceLocalAmbient() }.map { it.id },
		)

	/** Installs only retained-data consumers before the startup gate admits provider callbacks. */
	internal suspend fun registerAmbientRadioProjections() {
		for (projection in projections.filter { it.isSourceLocalAmbient() }) {
			registerProjection(projection, ambientActivationOrdinal())
		}
	}

	/**
	 * Uses the existing durable checkpoint and dispatcher for one finite source high-water snapshot.
	 * Each source has its own lock; a failed sibling never joins this source's retry path.
	 */
	internal suspend fun drainAmbientRadioProjection(
		source: SourceKind,
		ingress: DurableSourceIngress,
	): CoordinatorDrainResult {
		val mutex = ambientMutex(source)
		return mutex.withLock {
			val projection = ambientProjection(source)
			registerProjection(projection, ambientActivationOrdinal())
			val target = ambientHighWaterOrdinal()
			var cursor = projectionCheckpoint(projection)
			var dispatched = 0
			while (cursor < target) {
				currentCoroutineContext().ensureActive()
				val batch = try {
					ingress.committedSourceBatch(source, cursor, target, AMBIENT_BATCH_SIZE)
				} catch (corrupt: CorruptSourceEventException) {
					check(corrupt.sourceKind == source.stableCode && corrupt.admissionOrdinal in (cursor + 1L)..target)
					quarantineRawProjectionEvent(projection, corrupt.admissionOrdinal,
						corrupt.failureCode, requireContiguousOrdinal = false)
					cursor = maxOf(cursor, corrupt.admissionOrdinal)
					continue
				}
				if (batch.isEmpty()) {
					cursor = advanceProjectionAcrossIrrelevantOrdinals(projection.id, projection.version, target)
					break
				}
				for (event in batch) {
					check(event.evidence.source == source && event.admissionOrdinal in (cursor + 1L)..target)
					val result = dispatchProjectionWithSourceOrdinalGap(projection.id, projection.version, event)
					if (!result.complete) return@withLock CoordinatorDrainResult.ProjectionFailed(
						projectionCheckpoint(projection), dispatched, projection.id, event.admissionOrdinal,
					)
					cursor = event.admissionOrdinal
					dispatched++
				}
				yield()
			}
			CoordinatorDrainResult.Complete(cursor, dispatched)
		}
	}

	private fun ambientMutex(source: SourceKind): Mutex = ambientMutexes.getValue(source)

	private suspend fun ambientHighWaterOrdinal(): Long = database.withTransaction {
		maxOf(database.sourceEventWalDao().maximumAdmissionOrdinal() ?: 0L,
			database.sourceEvidenceStateDao().get()?.deletedSourceEventHighWaterOrdinal ?: 0L)
	}

	private suspend fun projectionCheckpoint(projection: Projection): Long = requireNotNull(
		database.sourceProjectionStateDao().checkpoint(projection.id, projection.version),
	).contiguousAdmissionOrdinal

	private fun ambientProjection(source: SourceKind): Projection = when (source) {
		SourceKind.WIFI -> projection(AmbientWifiFactRevisionEntity.WRITER_ID, AmbientWifiFactRevisionEntity.WRITER_VERSION)
		SourceKind.CELL -> projection(AmbientCellFactRevisionEntity.WRITER_ID, AmbientCellFactRevisionEntity.WRITER_VERSION)
		SourceKind.ACTIVITY, SourceKind.PRESSURE -> projection(
			AllDaySensorProjection.writerId(source), AllDaySensorProjection.VERSION,
		)
		else -> error("Not a source-local all-day projection")
	}

	private fun Projection.isSourceLocalAmbient(): Boolean =
		this is AllDaySensorProjection ||
		id == AmbientWifiFactRevisionEntity.WRITER_ID && version == AmbientWifiFactRevisionEntity.WRITER_VERSION ||
			id == AmbientCellFactRevisionEntity.WRITER_ID && version == AmbientCellFactRevisionEntity.WRITER_VERSION

	/** Retained callbacks can predate the first registration; their immutable purpose rules still apply. */
	private suspend fun ambientActivationOrdinal(): Long = database.withTransaction {
		val deleted = database.sourceEvidenceStateDao().get()?.deletedSourceEventHighWaterOrdinal ?: 0L
		check(deleted < Long.MAX_VALUE)
		maxOf(database.legacyV27ProjectionDrainDao().liveActivationOrdinal() ?: 1L, deleted + 1L)
	}

	suspend fun dispatch(event: AdmittedSourceEvent<out SourcePayload>): ProjectionDispatchResult {
		val failures = mutableListOf<ProjectionFailure>()
		val quarantined = mutableListOf<ProjectionQuarantine>()
		for (projection in globalProjections) {
			val result = dispatchProjection(projection, event, requireContiguousOrdinal = true)
			result.quarantine?.let(quarantined::add)
			result.failure?.let {
				failures += it
				break
			}
		}
		return ProjectionDispatchResult(event.admissionOrdinal, failures, quarantined)
	}

	internal suspend fun registerProjection(
		projectionId: String,
		projectionVersion: Int,
		activationOrdinal: Long,
	) {
		registerProjection(projection(projectionId, projectionVersion), activationOrdinal)
	}

	internal suspend fun dispatchProjectionWithSourceOrdinalGap(
		projectionId: String,
		projectionVersion: Int,
		event: AdmittedSourceEvent<out SourcePayload>,
	): ProjectionDispatchResult {
		val result = dispatchProjection(
			projection(projectionId, projectionVersion),
			event,
			requireContiguousOrdinal = false,
		)
		return ProjectionDispatchResult(
			admissionOrdinal = event.admissionOrdinal,
			failures = listOfNotNull(result.failure),
			quarantined = listOfNotNull(result.quarantine),
		)
	}

	internal suspend fun advanceProjectionAcrossIrrelevantOrdinals(
		projectionId: String,
		projectionVersion: Int,
		throughOrdinal: Long,
	): Long = database.withTransaction {
		require(throughOrdinal >= 0L)
		val projection = projection(projectionId, projectionVersion)
		val dao = database.sourceProjectionStateDao()
		val checkpoint = requireNotNull(dao.checkpoint(projection.id, projection.version)) {
			"Projection ${projection.id} is not registered"
		}
		if (throughOrdinal <= checkpoint.contiguousAdmissionOrdinal) {
			return@withTransaction checkpoint.contiguousAdmissionOrdinal
		}
		dao.saveCheckpoint(
			checkpoint.copy(
				contiguousAdmissionOrdinal = throughOrdinal,
				updatedAtMs = System.currentTimeMillis(),
			),
		)
		throughOrdinal
	}

	private suspend fun registerProjection(projection: Projection, activationOrdinal: Long) {
		require(activationOrdinal > 0L)
		database.withTransaction {
			val dao = database.sourceProjectionStateDao()
			check(dao.productLanesByProjection(projection.id, projection.version).isEmpty()) {
				"Projection ${projection.id}:${projection.version} is reserved by a source-local product lane"
			}
			val registration = dao.registration(projection.id, projection.version)
			if (registration == null) {
				dao.register(
					SourceProjectionRegistrationEntity(
						projectionId = projection.id,
						projectionVersion = projection.version,
						activationOrdinal = activationOrdinal,
						retentionRequired = projection.retentionRequired,
						status = STATUS_ACTIVE,
						createdAtMs = System.currentTimeMillis(),
					),
				)
				dao.saveCheckpoint(
					SourceProjectionCheckpointEntity(
						projectionId = projection.id,
						projectionVersion = projection.version,
						contiguousAdmissionOrdinal = activationOrdinal - 1L,
						stateVersion = 1,
						updatedAtMs = System.currentTimeMillis(),
					),
				)
			} else if (registration.retentionRequired && !projection.retentionRequired) {
				// A non-retention implementation may demote an existing registration in place. This
				// keeps the Activity control projection from remaining a WAL pin after upgrade while
				// preserving its original activation/checkpoint boundary.
				database.openHelper.writableDatabase.execSQL(
					"UPDATE source_projection_registration SET retention_required = 0 " +
						"WHERE projection_id = ? AND projection_version = ?",
					arrayOf<Any>(
						projection.id,
						projection.version,
					),
				)
				check(
					dao.registration(projection.id, projection.version)?.retentionRequired == false,
				) { "Unable to demote projection ${projection.id}:${projection.version} retention" }
			}
		}
	}

	private suspend fun dispatchProjection(
		projection: Projection,
		event: AdmittedSourceEvent<out SourcePayload>,
		requireContiguousOrdinal: Boolean,
	): SingleProjectionDispatchResult {
		val failure = runCatchingNonCancellation {
			database.withTransaction {
				val dao = database.sourceProjectionStateDao()
				val checkpoint = requireNotNull(dao.checkpoint(projection.id, projection.version)) {
					"Projection ${projection.id} is not registered"
				}
				if (event.admissionOrdinal <= checkpoint.contiguousAdmissionOrdinal) return@withTransaction
				if (requireContiguousOrdinal) {
					check(event.admissionOrdinal == checkpoint.contiguousAdmissionOrdinal + 1L) {
						"Projection ${projection.id} cannot skip admission ordinal " +
							"${checkpoint.contiguousAdmissionOrdinal + 1L}"
					}
				}
				projection.apply(
					event,
					RoomProjectionContext(database, projection, event.admissionOrdinal),
				)
				dao.deleteFailure(projection.id, projection.version, event.admissionOrdinal)
				dao.saveCheckpoint(
					checkpoint.copy(
						contiguousAdmissionOrdinal = event.admissionOrdinal,
						updatedAtMs = System.currentTimeMillis(),
					),
				)
			}
		}.exceptionOrNull() ?: return SingleProjectionDispatchResult()

		val attempts = (database.sourceProjectionStateDao()
			.failure(projection.id, projection.version, event.admissionOrdinal)?.attemptCount ?: 0) + 1
		// Identity collisions are authority failures, not poison input. Retained canonical
		// projections may never advance past one because doing so would accept the foreign owner.
		// A non-retention control projection instead suppresses the attempted local effect and
		// terminally quarantines the input so optional automation cannot pin the global WAL.
		//
		// Every other exception is retryable unless projection code explicitly marks the input as
		// deterministic poison. Retention controls WAL pruning; it is not evidence that an arbitrary
		// storage or infrastructure failure can be skipped safely.
		val terminal = when (failure) {
			is ProjectionOutboxIdentityCollisionException -> !projection.retentionRequired
			is ProjectionPoisonException ->
				!projection.retentionRequired || attempts >= projection.maximumAttemptsPerEvent
			else -> false
		}
		val failureCode = when (failure) {
			is ProjectionPoisonException -> failure.failureCode
			else -> failure::class.java.simpleName
		}
		database.withTransaction {
			val dao = database.sourceProjectionStateDao()
			dao.saveFailure(
				SourceProjectionFailureEntity(
					projectionId = projection.id,
					projectionVersion = projection.version,
					admissionOrdinal = event.admissionOrdinal,
					attemptCount = attempts,
					failureCode = failureCode,
					terminal = terminal,
					lastAttemptAtMs = System.currentTimeMillis(),
				),
			)
			if (terminal) {
				val checkpoint = requireNotNull(dao.checkpoint(projection.id, projection.version))
				if (event.admissionOrdinal > checkpoint.contiguousAdmissionOrdinal) {
					dao.saveCheckpoint(
						checkpoint.copy(
							contiguousAdmissionOrdinal = event.admissionOrdinal,
							updatedAtMs = System.currentTimeMillis(),
						),
					)
				}
			}
		}
		return if (terminal) {
			SingleProjectionDispatchResult(
				quarantine = ProjectionQuarantine(
					projection.id,
					projection.version,
					event.admissionOrdinal,
					attempts,
					failureCode,
				),
			)
		} else {
			SingleProjectionDispatchResult(
				failure = ProjectionFailure(
					projection.id,
					projection.version,
					event.admissionOrdinal,
					attempts,
					failure,
				),
			)
		}
	}

	private fun projection(id: String, version: Int): Projection =
		projections.singleOrNull { projection -> projection.id == id && projection.version == version }
			?: error("Projection $id/$version is not in the production projection set")

	/**
	 * Permanently quarantines a raw event that failed integrity or decoding before any projection
	 * received it. The operation is idempotent across crashes: projections already past the ordinal
	 * are left unchanged, while each remaining contiguous checkpoint advances with a durable failure.
	 */
	suspend fun quarantineRawEvent(admissionOrdinal: Long, failureCode: String) {
		require(admissionOrdinal > 0L)
		require(failureCode.isNotBlank())
		for (projection in globalProjections) {
			quarantineRawProjectionEvent(projection, admissionOrdinal, failureCode, requireContiguousOrdinal = true)
		}
	}

	private suspend fun quarantineRawProjectionEvent(
		projection: Projection,
		admissionOrdinal: Long,
		failureCode: String,
		requireContiguousOrdinal: Boolean,
	) {
		database.withTransaction {
			val dao = database.sourceProjectionStateDao()
			val checkpoint = requireNotNull(dao.checkpoint(projection.id, projection.version)) {
				"Projection ${projection.id} is not registered"
			}
			if (admissionOrdinal <= checkpoint.contiguousAdmissionOrdinal) return@withTransaction
			check(!requireContiguousOrdinal || admissionOrdinal == checkpoint.contiguousAdmissionOrdinal + 1L) {
				"Raw quarantine cannot skip admission ordinal " +
					"${checkpoint.contiguousAdmissionOrdinal + 1L} for ${projection.id}"
			}
			dao.saveFailure(
				SourceProjectionFailureEntity(
					projectionId = projection.id,
					projectionVersion = projection.version,
					admissionOrdinal = admissionOrdinal,
					attemptCount = 1,
					failureCode = failureCode,
					terminal = true,
					lastAttemptAtMs = System.currentTimeMillis(),
				),
			)
			dao.saveCheckpoint(
				checkpoint.copy(
					contiguousAdmissionOrdinal = admissionOrdinal,
					updatedAtMs = System.currentTimeMillis(),
				),
			)
		}
	}

	private companion object {
		val AMBIENT_SOURCES = listOf(SourceKind.WIFI, SourceKind.CELL, SourceKind.ACTIVITY, SourceKind.PRESSURE)
		const val STATUS_ACTIVE = "ACTIVE"
		const val AMBIENT_BATCH_SIZE = 64
	}
}

data class ProjectionDispatchResult(
	val admissionOrdinal: Long,
	val failures: List<ProjectionFailure>,
	val quarantined: List<ProjectionQuarantine>,
) {
	val complete: Boolean get() = failures.isEmpty()
}

private data class SingleProjectionDispatchResult(
	val failure: ProjectionFailure? = null,
	val quarantine: ProjectionQuarantine? = null,
)

data class ProjectionFailure(
	val projectionId: String,
	val projectionVersion: Int,
	val admissionOrdinal: Long,
	val attemptCount: Int,
	val cause: Throwable,
)

data class ProjectionQuarantine(
	val projectionId: String,
	val projectionVersion: Int,
	val admissionOrdinal: Long,
	val attemptCount: Int,
	val failureCode: String,
)

private class RoomProjectionContext(
	private val database: AppDatabase,
	private val projection: Projection,
	private val admissionOrdinal: Long,
) : ProjectionContext {
	override suspend fun recordOutbox(effect: ProjectionOutboxEffect) {
		val dao = database.sourceProjectionStateDao()
		val candidate = SourceProjectionOutboxEntity(
			stableId = effect.stableId,
			projectionId = projection.id,
			projectionVersion = projection.version,
			admissionOrdinal = admissionOrdinal,
			effectKind = effect.kind,
			payloadVersion = effect.payloadVersion,
			payload = effect.payload,
			createdAtMs = System.currentTimeMillis(),
			deliveredAtMs = null,
		)
		if (dao.insertOutbox(candidate) != INSERT_IGNORED) return
		val existing = dao.outbox(effect.stableId)
		if (existing == null || !existing.sameImmutableEffect(candidate)) {
			throw ProjectionOutboxIdentityCollisionException(effect.stableId)
		}
	}

	override suspend fun loadJoinState(key: String): ByteArray? =
		database.sourceProjectionStateDao()
			.joinState(projection.id, projection.version, key)
			?.payload

	override suspend fun saveJoinState(
		key: String,
		payload: ByteArray,
		minimumRequiredOrdinal: Long?,
		logicalTrackingId: String?,
		payloadVersion: Int,
	) {
		val requiredOrdinal = minimumRequiredOrdinal ?: admissionOrdinal
		require(requiredOrdinal in 1..admissionOrdinal) {
			"Join-state retention ordinal must reference admitted input"
		}
		require(payloadVersion > 0)
		database.sourceProjectionStateDao().saveJoinState(
			SourceProjectionJoinStateEntity(
				projectionId = projection.id,
				projectionVersion = projection.version,
				stateKey = key,
				logicalTrackingId = logicalTrackingId,
				minimumRequiredOrdinal = requiredOrdinal,
				payloadVersion = payloadVersion,
				payload = payload,
				updatedAtMs = System.currentTimeMillis(),
			),
		)
	}

	override suspend fun removeJoinState(key: String) {
		database.sourceProjectionStateDao().deleteJoinState(projection.id, projection.version, key)
	}
}

class ProjectionOutboxIdentityCollisionException(
	val stableId: String,
) : IllegalStateException("Projection outbox identity collision: $stableId")

/** Explicit signal that projection input is deterministically invalid rather than transiently failed. */
class ProjectionPoisonException(
	val failureCode: String,
	cause: Throwable? = null,
) : IllegalArgumentException(failureCode, cause) {
	init {
		require(failureCode.isNotBlank())
	}
}

private fun SourceProjectionOutboxEntity.sameImmutableEffect(
	other: SourceProjectionOutboxEntity,
): Boolean = stableId == other.stableId &&
	projectionId == other.projectionId &&
	projectionVersion == other.projectionVersion &&
	admissionOrdinal == other.admissionOrdinal &&
	effectKind == other.effectKind &&
	payloadVersion == other.payloadVersion &&
	payload.contentEquals(other.payload)

private const val INSERT_IGNORED = -1L

package com.adsamcik.tracker.tracker.source.projection

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionCheckpointEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionRegistrationEntity
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectionDispatcher @Inject constructor(
	private val database: AppDatabase,
	projections: Set<@JvmSuppressWildcards Projection>,
) {
	private val projections = projections.sortedBy(Projection::id)

	suspend fun registerAll(activationOrdinal: Long) {
		require(activationOrdinal > 0L)
		projections.forEach { projection ->
			database.withTransaction {
				val dao = database.sourceProjectionStateDao()
				if (dao.registration(projection.id, projection.version) == null) {
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
				}
			}
		}
	}

	suspend fun dispatch(event: AdmittedSourceEvent<out SourcePayload>): ProjectionDispatchResult {
		val failures = mutableListOf<ProjectionFailure>()
		val quarantined = mutableListOf<ProjectionQuarantine>()
		for (projection in projections) {
			val failure = runCatching {
				database.withTransaction {
					val dao = database.sourceProjectionStateDao()
					val checkpoint = requireNotNull(dao.checkpoint(projection.id, projection.version)) {
						"Projection ${projection.id} is not registered"
					}
					if (event.admissionOrdinal <= checkpoint.contiguousAdmissionOrdinal) return@withTransaction
					check(event.admissionOrdinal == checkpoint.contiguousAdmissionOrdinal + 1L) {
						"Projection ${projection.id} cannot skip admission ordinal " +
							"${checkpoint.contiguousAdmissionOrdinal + 1L}"
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
			}.exceptionOrNull()
			if (failure != null) {
				val attempts = (database.sourceProjectionStateDao()
					.failure(projection.id, projection.version, event.admissionOrdinal)?.attemptCount ?: 0) + 1
				val terminal = !projection.retentionRequired || attempts >= projection.maximumAttemptsPerEvent
				database.withTransaction {
					val dao = database.sourceProjectionStateDao()
					dao.saveFailure(SourceProjectionFailureEntity(
						projectionId = projection.id,
						projectionVersion = projection.version,
						admissionOrdinal = event.admissionOrdinal,
						attemptCount = attempts,
						failureCode = failure::class.java.simpleName,
						terminal = terminal,
						lastAttemptAtMs = System.currentTimeMillis(),
					))
					if (terminal) {
						val checkpoint = requireNotNull(dao.checkpoint(projection.id, projection.version))
						dao.saveCheckpoint(
							checkpoint.copy(
								contiguousAdmissionOrdinal = event.admissionOrdinal,
								updatedAtMs = System.currentTimeMillis(),
							),
						)
					}
				}
				if (terminal) {
					quarantined += ProjectionQuarantine(
						projection.id,
						projection.version,
						event.admissionOrdinal,
						attempts,
						failure::class.java.simpleName,
					)
				} else {
					failures += ProjectionFailure(
						projection.id,
						projection.version,
						event.admissionOrdinal,
						attempts,
						failure,
					)
					break
				}
			}
		}
		return ProjectionDispatchResult(event.admissionOrdinal, failures, quarantined)
	}

	private companion object {
		const val STATUS_ACTIVE = "ACTIVE"
	}
}

data class ProjectionDispatchResult(
	val admissionOrdinal: Long,
	val failures: List<ProjectionFailure>,
	val quarantined: List<ProjectionQuarantine>,
) {
	val complete: Boolean get() = failures.isEmpty()
}

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
		database.sourceProjectionStateDao().insertOutbox(
			SourceProjectionOutboxEntity(
				stableId = effect.stableId,
				projectionId = projection.id,
				projectionVersion = projection.version,
				admissionOrdinal = admissionOrdinal,
				effectKind = effect.kind,
				payloadVersion = effect.payloadVersion,
				payload = effect.payload,
				createdAtMs = System.currentTimeMillis(),
				deliveredAtMs = null,
			),
		)
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

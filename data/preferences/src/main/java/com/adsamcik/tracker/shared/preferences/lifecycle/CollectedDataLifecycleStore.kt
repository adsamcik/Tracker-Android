package com.adsamcik.tracker.shared.preferences.lifecycle

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationLease
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityOperationPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Durable, non-collected metadata that defines which collected-data writes may
 * still be accepted.
 *
 * It deliberately lives outside the collected Room database: a full deletion
 * removes that database, while this state must survive in order to invalidate
 * in-flight work and WAL entries captured before the deletion.
 */
data class CollectedDataLifecycleSnapshot(
	val epoch: Long,
	val retainedFromMs: Long?,
) {
	/** True only when a captured write still belongs to the current history. */
	fun accepts(capturedEpoch: Long, acquiredAtMs: Long): Boolean =
		capturedEpoch == epoch && (retainedFromMs == null || acquiredAtMs >= retainedFromMs)
}

/**
 * The privacy lifecycle for collected data.
 *
 * Implementations must make each transition atomic and monotonic.  Consumers
 * capture [snapshot] before work and re-check it before publishing results.
 */
interface CollectedDataLifecycleStore {
	val snapshots: Flow<CollectedDataLifecycleSnapshot>

	suspend fun snapshot(): CollectedDataLifecycleSnapshot

	/**
	 * Invalidates all work captured before this call and rejects records acquired
	 * before [deletedAtMs] in the new epoch.
	 */
	suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot

	/**
	 * Idempotently advances to the exact epoch reserved by a durable deletion operation.
	 * Production implementations bind the epoch to [operationId] in the same DataStore edit.
	 */
	suspend fun beginFullDeletion(
		operationId: String,
		targetEpoch: Long,
		deletedAtMs: Long,
	): CollectedDataLifecycleSnapshot {
		require(operationId.isNotBlank())
		val current = snapshot()
		if (current.epoch == targetEpoch) return current
		check(Math.addExact(current.epoch, 1L) == targetEpoch) {
			"Collected-data deletion target epoch is not the next lifecycle epoch"
		}
		return beginFullDeletion(deletedAtMs).also { advanced ->
			check(advanced.epoch == targetEpoch) {
				"Collected-data deletion advanced to an unexpected lifecycle epoch"
			}
		}
	}

	/**
	 * Advances the lower retention boundary.  The boundary never moves backwards,
	 * so loosening a retention preference cannot resurrect delayed old signals.
	 */
	suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot

	/**
	 * Advances the retention floor while the caller holds the shared retention authority permit.
	 * The permit lets a worker commit the matching Room guard and reissue authority before another
	 * lifecycle transition can observe an intermediate state.
	 */
	suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
		permit: RetentionAuthorityOperationPermit,
	): CollectedDataLifecycleSnapshot = advanceRetainedFrom(
		operationId = "retention-floor:$retainedFromMs",
		retainedFromMs = retainedFromMs,
		permit = permit,
	)

	suspend fun advanceRetainedFrom(
		operationId: String,
		retainedFromMs: Long,
		permit: RetentionAuthorityOperationPermit,
	): CollectedDataLifecycleSnapshot = permit.commitDataStoreMutation(operationId) {
		advanceRetainedFrom(retainedFromMs)
	}
}

suspend fun CollectedDataLifecycleStore.advanceRetainedFromWithPermit(
	retainedFromMs: Long,
	permit: RetentionAuthorityOperationPermit,
	operationId: String = "retention-floor:$retainedFromMs",
): CollectedDataLifecycleSnapshot = advanceRetainedFrom(operationId, retainedFromMs, permit)

private val Context.collectedDataLifecycleDataStore: DataStore<Preferences> by preferencesDataStore(
	name = "collected_data_lifecycle",
)

class DefaultCollectedDataLifecycleStore(
	context: Context,
	private val retentionAuthorityOperationLease: RetentionAuthorityOperationLease =
		RetentionAuthorityOperationLease(),
) : CollectedDataLifecycleStore {
	private val dataStore = context.applicationContext.collectedDataLifecycleDataStore

	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = dataStore.data
		.map(Preferences::toSnapshot)
		.distinctUntilChanged()

	override suspend fun snapshot(): CollectedDataLifecycleSnapshot =
		dataStore.data.first().toSnapshot()

	override suspend fun beginFullDeletion(
		deletedAtMs: Long,
	): CollectedDataLifecycleSnapshot {
		require(deletedAtMs >= 0L)
		return retentionAuthorityOperationLease.withPermit { permit ->
			val operationId = "legacy-full-deletion:$deletedAtMs"
			permit.commitDataStoreMutation(
				retentionAuthorityOperationLease,
				operationId,
			) {
				beginFullDeletionUnlocked(
					operationId = operationId,
					targetEpoch = null,
					deletedAtMs = deletedAtMs,
				)
			}
		}
	}

	override suspend fun beginFullDeletion(
		operationId: String,
		targetEpoch: Long,
		deletedAtMs: Long,
	): CollectedDataLifecycleSnapshot {
		require(operationId.isNotBlank())
		require(targetEpoch > 0L)
		require(deletedAtMs >= 0L)
		return retentionAuthorityOperationLease.withPermit { permit ->
			permit.commitDataStoreMutation(
				retentionAuthorityOperationLease,
				"full-deletion:$operationId",
			) {
				beginFullDeletionUnlocked(operationId, targetEpoch, deletedAtMs)
			}
		}
	}

	override suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
	): CollectedDataLifecycleSnapshot =
		retentionAuthorityOperationLease.withPermit { permit ->
			val operationId = "retention-floor:$retainedFromMs"
			permit.commitDataStoreMutation(
				retentionAuthorityOperationLease,
				operationId,
			) {
				advanceRetainedFromUnlocked(operationId, retainedFromMs)
			}
		}

	override suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
		permit: RetentionAuthorityOperationPermit,
	): CollectedDataLifecycleSnapshot =
		advanceRetainedFrom("retention-floor:$retainedFromMs", retainedFromMs, permit)

	override suspend fun advanceRetainedFrom(
		operationId: String,
		retainedFromMs: Long,
		permit: RetentionAuthorityOperationPermit,
	): CollectedDataLifecycleSnapshot =
		permit.commitDataStoreMutation(retentionAuthorityOperationLease, operationId) {
			advanceRetainedFromUnlocked(operationId, retainedFromMs)
		}

	private suspend fun beginFullDeletionUnlocked(
		operationId: String,
		targetEpoch: Long?,
		deletedAtMs: Long,
	): CollectedDataLifecycleSnapshot {
		var updated: CollectedDataLifecycleSnapshot? = null
		dataStore.edit { preferences ->
			val current = preferences.toSnapshot()
			val lastOperationId = preferences[LAST_FULL_DELETION_OPERATION_ID_KEY]
			val lastTargetEpoch = preferences[LAST_FULL_DELETION_TARGET_EPOCH_KEY]
			val lastDeletedAtMs = preferences[LAST_FULL_DELETION_DELETED_AT_MS_KEY]
			val next = if (lastOperationId == operationId) {
				check(lastTargetEpoch == current.epoch && lastDeletedAtMs == deletedAtMs) {
					"Collected-data deletion operation identity was reused with different inputs"
				}
				if (targetEpoch != null) {
					check(current.epoch == targetEpoch) {
						"Collected-data deletion operation acknowledgement has another target epoch"
					}
				}
				current
			} else {
				val nextEpoch = targetEpoch ?: Math.addExact(current.epoch, 1L)
				check(Math.addExact(current.epoch, 1L) == nextEpoch) {
					"Collected-data deletion operation does not own the target lifecycle epoch"
				}
				current.copy(
					epoch = nextEpoch,
					retainedFromMs = current.retainedFromMs
						?.let { maxOf(it, deletedAtMs) }
						?: deletedAtMs,
				)
			}
			preferences.writeSnapshot(next)
			preferences[LAST_FULL_DELETION_OPERATION_ID_KEY] = operationId
			preferences[LAST_FULL_DELETION_TARGET_EPOCH_KEY] = next.epoch
			preferences[LAST_FULL_DELETION_DELETED_AT_MS_KEY] = deletedAtMs
			updated = next
		}
		return checkNotNull(updated)
	}

	private suspend fun advanceRetainedFromUnlocked(
		operationId: String,
		retainedFromMs: Long,
	): CollectedDataLifecycleSnapshot {
		require(operationId.isNotBlank())
		require(retainedFromMs >= 0L)
		var updated: CollectedDataLifecycleSnapshot? = null
		dataStore.edit { preferences ->
			val current = preferences.toSnapshot()
			val lastOperationId = preferences[LAST_RETENTION_FLOOR_OPERATION_ID_KEY]
			val lastRequestedFloor = preferences[LAST_RETENTION_FLOOR_VALUE_KEY]
			val next = if (lastOperationId == operationId) {
				check(lastRequestedFloor == retainedFromMs) {
					"Retention-floor operation identity was reused with another boundary"
				}
				current
			} else {
				current.copy(
					retainedFromMs = current.retainedFromMs
						?.let { maxOf(it, retainedFromMs) }
						?: retainedFromMs,
				)
			}
			preferences.writeSnapshot(next)
			preferences[LAST_RETENTION_FLOOR_OPERATION_ID_KEY] = operationId
			preferences[LAST_RETENTION_FLOOR_VALUE_KEY] = retainedFromMs
			updated = next
		}
		return checkNotNull(updated)
	}

	private companion object {
		const val INITIAL_EPOCH = 0L
		val EPOCH_KEY = longPreferencesKey("epoch")
		val RETAINED_FROM_KEY = longPreferencesKey("retained_from_ms")
		val LAST_FULL_DELETION_OPERATION_ID_KEY =
			stringPreferencesKey("last_full_deletion_operation_id")
		val LAST_FULL_DELETION_TARGET_EPOCH_KEY =
			longPreferencesKey("last_full_deletion_target_epoch")
		val LAST_FULL_DELETION_DELETED_AT_MS_KEY =
			longPreferencesKey("last_full_deletion_deleted_at_ms")
		val LAST_RETENTION_FLOOR_OPERATION_ID_KEY =
			stringPreferencesKey("last_retention_floor_operation_id")
		val LAST_RETENTION_FLOOR_VALUE_KEY =
			longPreferencesKey("last_retention_floor_value_ms")
	}
}

/** Clears lifecycle metadata between isolated Android tests. */
suspend fun resetCollectedDataLifecycleForTests(context: Context) {
	context.applicationContext.collectedDataLifecycleDataStore.edit { it.clear() }
}

private fun Preferences.toSnapshot(): CollectedDataLifecycleSnapshot =
	CollectedDataLifecycleSnapshot(
		epoch = this[longPreferencesKey("epoch")] ?: 0L,
		retainedFromMs = this[longPreferencesKey("retained_from_ms")],
	)

private fun androidx.datastore.preferences.core.MutablePreferences.writeSnapshot(
	value: CollectedDataLifecycleSnapshot,
) {
	this[longPreferencesKey("epoch")] = value.epoch
	if (value.retainedFromMs == null) {
		remove(longPreferencesKey("retained_from_ms"))
	} else {
		this[longPreferencesKey("retained_from_ms")] = value.retainedFromMs
	}
}

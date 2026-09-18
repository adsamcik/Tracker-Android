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
	): CollectedDataLifecycleSnapshot = advanceRetainedFrom(retainedFromMs)
}

suspend fun CollectedDataLifecycleStore.advanceRetainedFromWithPermit(
	retainedFromMs: Long,
	permit: RetentionAuthorityOperationPermit,
): CollectedDataLifecycleSnapshot =
	if (this is DefaultCollectedDataLifecycleStore) {
		advanceRetainedFrom(retainedFromMs, permit)
	} else {
		advanceRetainedFrom(retainedFromMs)
	}

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
	): CollectedDataLifecycleSnapshot =
		retentionAuthorityOperationLease.withOperation {
			updateUnlocked { current ->
				check(current.epoch < Long.MAX_VALUE) {
					"Collected-data deletion epoch is exhausted"
				}
				current.copy(
					epoch = current.epoch + 1L,
					retainedFromMs = current.retainedFromMs
						?.let { maxOf(it, deletedAtMs) }
						?: deletedAtMs,
				)
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
		return retentionAuthorityOperationLease.withOperation {
			var updated: CollectedDataLifecycleSnapshot? = null
			dataStore.edit { preferences ->
				val current = preferences.toSnapshot()
				val lastOperationId = preferences[LAST_FULL_DELETION_OPERATION_ID_KEY]
				val next = when {
					current.epoch == targetEpoch && lastOperationId == operationId -> current
					Math.addExact(current.epoch, 1L) == targetEpoch -> current.copy(
						epoch = targetEpoch,
						retainedFromMs = current.retainedFromMs
							?.let { maxOf(it, deletedAtMs) }
							?: deletedAtMs,
					)
					else -> error(
						"Collected-data deletion operation does not own the target lifecycle epoch",
					)
				}
				preferences[EPOCH_KEY] = next.epoch
				if (next.retainedFromMs == null) {
					preferences.remove(RETAINED_FROM_KEY)
				} else {
					preferences[RETAINED_FROM_KEY] = next.retainedFromMs
				}
				preferences[LAST_FULL_DELETION_OPERATION_ID_KEY] = operationId
				updated = next
			}
			checkNotNull(updated)
		}
	}

	override suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
	): CollectedDataLifecycleSnapshot =
		retentionAuthorityOperationLease.withOperation {
			advanceRetainedFromUnlocked(retainedFromMs)
		}

	override suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
		permit: RetentionAuthorityOperationPermit,
	): CollectedDataLifecycleSnapshot {
		retentionAuthorityOperationLease.requireOwned(permit)
		return advanceRetainedFromUnlocked(retainedFromMs)
	}

	private suspend fun advanceRetainedFromUnlocked(
		retainedFromMs: Long,
	): CollectedDataLifecycleSnapshot = updateUnlocked { current ->
		current.copy(
			retainedFromMs = current.retainedFromMs
				?.let { maxOf(it, retainedFromMs) }
				?: retainedFromMs,
		)
	}

	private suspend fun updateUnlocked(
		transform: (CollectedDataLifecycleSnapshot) -> CollectedDataLifecycleSnapshot,
	): CollectedDataLifecycleSnapshot {
		var updated: CollectedDataLifecycleSnapshot? = null
		dataStore.edit { preferences ->
			val next = transform(preferences.toSnapshot())
			preferences[EPOCH_KEY] = next.epoch
			if (next.retainedFromMs == null) {
				preferences.remove(RETAINED_FROM_KEY)
			} else {
				preferences[RETAINED_FROM_KEY] = next.retainedFromMs
			}
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

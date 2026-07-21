package com.adsamcik.tracker.shared.preferences.lifecycle

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
	 * Advances the lower retention boundary.  The boundary never moves backwards,
	 * so loosening a retention preference cannot resurrect delayed old signals.
	 */
	suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot
}

private val Context.collectedDataLifecycleDataStore: DataStore<Preferences> by preferencesDataStore(
	name = "collected_data_lifecycle",
)

class DefaultCollectedDataLifecycleStore(
	context: Context,
) : CollectedDataLifecycleStore {
	private val dataStore = context.applicationContext.collectedDataLifecycleDataStore

	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = dataStore.data
		.map(Preferences::toSnapshot)
		.distinctUntilChanged()

	override suspend fun snapshot(): CollectedDataLifecycleSnapshot =
		dataStore.data.first().toSnapshot()

	override suspend fun beginFullDeletion(
		deletedAtMs: Long,
	): CollectedDataLifecycleSnapshot = update { current ->
		check(current.epoch < Long.MAX_VALUE) {
			"Collected-data deletion epoch is exhausted"
		}
		current.copy(
			epoch = current.epoch + 1L,
			retainedFromMs = current.retainedFromMs?.let { maxOf(it, deletedAtMs) } ?: deletedAtMs,
		)
	}

	override suspend fun advanceRetainedFrom(
		retainedFromMs: Long,
	): CollectedDataLifecycleSnapshot = update { current ->
		current.copy(
			retainedFromMs = current.retainedFromMs?.let { maxOf(it, retainedFromMs) } ?: retainedFromMs,
		)
	}

	private suspend fun update(
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

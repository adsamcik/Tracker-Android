package com.adsamcik.tracker.shared.preferences.onboarding

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

// Contract:
// Inputs: Proto DataStore file, legacy SharedPreferences for one-time migration.
// Outputs: Flow<Boolean> (completion state); mutation via suspend setters.
// Failure: Corruption -> emit default (not completed); logged (debug). No exceptions leak outward.

/** Immutable snapshot of onboarding state. */
data class OnboardingCompletionState(
    val completed: Boolean,
    val completedTime: Long
)

/** Repository boundary exposing onboarding completion state as reactive Flow plus mutation APIs. */
interface OnboardingRepository {
    /** Continuous stream of onboarding completion state. */
    val isCompleted: Flow<Boolean>
    
    /** Full state including timestamp. */
    val state: Flow<OnboardingCompletionState>
    
    /** Mark onboarding as completed with current timestamp. */
    suspend fun markCompleted()

    /** Ensure legacy migration is performed. Call this before observing state. */
    suspend fun ensureInitialized()
}

private object OnboardingStateSerializer : Serializer<OnboardingStateProto> {
    override val defaultValue: OnboardingStateProto = OnboardingStateProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): OnboardingStateProto = try {
        OnboardingStateProto.parseFrom(input)
    } catch (e: Exception) {
        Log.w("OnboardingState", "Corruption while reading onboarding proto – using defaults", e)
        defaultValue
    }

    override suspend fun writeTo(t: OnboardingStateProto, output: OutputStream) { t.writeTo(output) }
}

private val Context.onboardingDataStore: DataStore<OnboardingStateProto> by dataStore(
    fileName = "onboarding_state.pb",
    serializer = OnboardingStateSerializer
)

/** Default DataStore-backed implementation. */
class DefaultOnboardingRepository(
    private val context: Context,
    private val io: CoroutineDispatcher
) : OnboardingRepository {

    override val state: Flow<OnboardingCompletionState> = context.onboardingDataStore.data
        .map { proto ->
            OnboardingCompletionState(
                completed = proto.completed,
                completedTime = proto.completedTime
            )
        }

    override val isCompleted: Flow<Boolean> = state.map { it.completed }

    override suspend fun markCompleted() {
        withContext(io) {
            context.onboardingDataStore.updateData { current ->
                current.toBuilder()
                    .setCompleted(true)
                    .setCompletedTime(System.currentTimeMillis())
                    .build()
            }
        }
    }

    override suspend fun ensureInitialized() {
        withContext(io) {
            android.util.Log.d("Startup", "ensureInitialized: Starting migration check")
            ensureMigrated()
            android.util.Log.d("Startup", "ensureInitialized: Migration check done")
        }
    }

    private suspend fun ensureMigrated() {
        context.onboardingDataStore.updateData { current ->
            if (current.legacyMigrated) return@updateData current

            // One-time import from legacy SharedPreferences
            // Note: We do this inside updateData to ensure atomicity,
            // but we need to be careful about blocking.
            // SharedPreferences I/O is disk I/O, but updateData blocks the DataStore writer.
            // This is acceptable for a one-time migration.
            val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
            val legacyCompleted = prefs.getBoolean("completed", false)
            val legacyTime = prefs.getLong("completed_time", 0L)

            current.toBuilder()
                .setCompleted(legacyCompleted)
                .setCompletedTime(legacyTime)
                .setLegacyMigrated(true)
                .build()
        }
    }
}

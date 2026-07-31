package com.adsamcik.tracker.shared.preferences.onboarding

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.datastore.migrations.SharedPreferencesMigration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    } catch (_: Exception) {
        defaultValue
    }

    override suspend fun writeTo(t: OnboardingStateProto, output: OutputStream) { t.writeTo(output) }
}

private const val ONBOARDING_SHARED_PREFS_NAME = "onboarding"
private const val ONBOARDING_COMPLETED_KEY = "completed"
private const val ONBOARDING_COMPLETED_TIME_KEY = "completed_time"

private val Context.onboardingDataStore: DataStore<OnboardingStateProto> by dataStore(
    fileName = "onboarding_state.pb",
    serializer = OnboardingStateSerializer,
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(
                context = context,
                sharedPreferencesName = ONBOARDING_SHARED_PREFS_NAME,
                keysToMigrate = setOf(ONBOARDING_COMPLETED_KEY, ONBOARDING_COMPLETED_TIME_KEY),
                shouldRunMigration = { current -> !current.legacyMigrated }
            ) { prefs, current ->
                current.toBuilder()
                    .setCompleted(prefs.getBoolean(ONBOARDING_COMPLETED_KEY, false))
                    .setCompletedTime(prefs.getLong(ONBOARDING_COMPLETED_TIME_KEY, 0L))
                    .setLegacyMigrated(true)
                    .build()
            },
            onboardingLegacyMarkerMigration()
        )
    }
)

internal fun resetOnboardingForTests() {
    resetDataStoreDelegate(
        fileClassName = "com.adsamcik.tracker.shared.preferences.onboarding.OnboardingRepositoryKt",
        delegateFieldName = "onboardingDataStore\$delegate"
    )
}

private fun onboardingLegacyMarkerMigration(): DataMigration<OnboardingStateProto> =
    object : DataMigration<OnboardingStateProto> {
        override suspend fun shouldMigrate(currentData: OnboardingStateProto): Boolean =
            !currentData.legacyMigrated

        override suspend fun migrate(currentData: OnboardingStateProto): OnboardingStateProto =
            currentData.toBuilder()
                .setLegacyMigrated(true)
                .build()

        override suspend fun cleanUp() = Unit
    }

private fun resetDataStoreDelegate(fileClassName: String, delegateFieldName: String) {
    val fileClass = Class.forName(fileClassName)
    val delegateField = fileClass.getDeclaredField(delegateFieldName).apply { isAccessible = true }
    val delegate = delegateField.get(null)
    val instanceField = delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
    instanceField.set(delegate, null)
}

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
            context.onboardingDataStore.data.first()
        }
    }
}

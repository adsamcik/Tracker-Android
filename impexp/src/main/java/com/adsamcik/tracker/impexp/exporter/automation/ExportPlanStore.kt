package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import androidx.annotation.Keep
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.impexp.exporter.proto.ExportPlansProto
import com.adsamcik.tracker.shared.base.time.Clock
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Contract:
// Inputs: Proto DataStore backing file storing ExportPlansProto
// Outputs: Flow of domain plans plus atomic create/update/delete operations
// Failure: Data corruption falls back to default value (empty list) and logs via default JVM logger.

private object ExportPlansSerializer : Serializer<ExportPlansProto> {
    override val defaultValue: ExportPlansProto = ExportPlansProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): ExportPlansProto = try {
        ExportPlansProto.parseFrom(input)
    } catch (error: Exception) {
        // Corruption fallback: return default to avoid crashing background workers.
        defaultValue
    }

    override suspend fun writeTo(t: ExportPlansProto, output: OutputStream) {
        t.writeTo(output)
    }
}

private val Context.exportPlansDataStore: DataStore<ExportPlansProto> by dataStore(
    fileName = "export_backup_plans.pb",
    serializer = ExportPlansSerializer
)

/**
 * Repository managing persisted export backup plans and exposing reactive updates.
 */
@Keep
class ExportPlanStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock
) {
    private val dataStore = context.applicationContext.exportPlansDataStore

    /** Flow emitting the latest list of plans, ordered by insertion order. */
    val plans: Flow<List<ExportBackupPlan>> = dataStore.data.map { proto ->
        proto.plansList.map { it.toDomain() }
    }

    /** Retrieve a single plan reactively. */
    fun plan(planId: ExportPlanId): Flow<ExportBackupPlan?> = plans.map { list ->
        list.firstOrNull { it.id == planId }
    }

    /** Create a brand-new plan from the provided draft. */
    suspend fun create(draft: ExportBackupPlanDraft): ExportBackupPlan = withContext(ioDispatcher) {
        var createdPlan: ExportBackupPlan? = null
        dataStore.updateData { current ->
            val nextId = current.nextIdentifier()
            val now = clock.currentTimeMillis()
            val plan = draft.materialize(ExportPlanId(nextId), createdAt = now, updatedAt = now)
            createdPlan = plan
            current.toBuilder()
                .setNextPlanId(nextId + 1)
                .addPlans(plan.toProto())
                .build()
        }
        createdPlan ?: throw IllegalStateException("Plan creation failed")
    }

    /** Replace the persisted definition of an existing plan. */
    suspend fun update(plan: ExportBackupPlan) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.plansList.indexOfFirst { it.id == plan.id.value }
            if (index < 0) return@updateData current
            val refreshed = plan.copy(updatedAtMillis = clock.currentTimeMillis())
            builder.setPlans(index, refreshed.toProto())
            builder.build()
        }
    }

    /** Delete the plan with the provided identifier. */
    suspend fun delete(planId: ExportPlanId) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.plansList.indexOfFirst { it.id == planId.value }
            if (index >= 0) {
                builder.removePlans(index)
            }
            builder.build()
        }
    }

    /** Toggle plan enabled flag. */
    suspend fun setEnabled(planId: ExportPlanId, enabled: Boolean) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.plansList.indexOfFirst { it.id == planId.value }
            if (index < 0) return@updateData current
            val plan = builder.getPlans(index).toDomain().copy(enabled = enabled)
            builder.setPlans(index, plan.copy(updatedAtMillis = clock.currentTimeMillis()).toProto())
            builder.build()
        }
    }

    /** Fetch a snapshot of the plan with the provided identifier. */
    suspend fun getPlan(planId: ExportPlanId): ExportBackupPlan? = withContext(ioDispatcher) {
        val proto = dataStore.data.first()
        proto.plansList.firstOrNull { it.id == planId.value }?.toDomain()
    }
}

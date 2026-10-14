package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.adsamcik.tracker.impexp.exporter.proto.ExportPlansProto
import com.adsamcik.tracker.impexp.exporter.proto.UnfinishedExportFileProto
import com.adsamcik.tracker.shared.base.time.Clock
import com.google.protobuf.InvalidProtocolBufferException
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
// Failure: Unreadable storage is reported; existing bytes are never replaced with an empty list.

internal object ExportPlansSerializer : Serializer<ExportPlansProto> {
    override val defaultValue: ExportPlansProto = ExportPlansProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): ExportPlansProto = try {
        ExportPlansProto.parseFrom(input)
    } catch (error: InvalidProtocolBufferException) {
        throw CorruptionException("Unable to read export storage", error)
    }

    override suspend fun writeTo(t: ExportPlansProto, output: OutputStream) {
        t.writeTo(output)
    }
}

private val Context.exportPlansDataStore: DataStore<ExportPlansProto> by dataStore(
    fileName = "export_backup_plans.pb",
    serializer = ExportPlansSerializer
)

/** An external destination whose failed export could not be removed. */
data class UnfinishedExportFile(val uri: String, val name: String) {
    init {
        val parsed = Uri.parse(uri)
        require(parsed.scheme == "content" && !parsed.authority.isNullOrBlank())
        require(name.isNotBlank())
    }
}

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

    val unfinishedFiles: Flow<List<UnfinishedExportFile>> = dataStore.data.map { proto ->
        proto.unfinishedFilesList.map { UnfinishedExportFile(it.uri, it.name) }
    }

    /** The URI identifies one file, independent of its name or the export's date range. */
    suspend fun rememberUnfinishedFile(file: UnfinishedExportFile) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.unfinishedFilesList.indexOfFirst { it.uri == file.uri }
            val saved = UnfinishedExportFileProto.newBuilder().setUri(file.uri).setName(file.name).build()
            if (index < 0) builder.addUnfinishedFiles(saved) else builder.setUnfinishedFiles(index, saved)
            builder.build()
        }
    }

    /** Called only after that exact file has been removed. Clear-all deliberately keeps notices. */
    suspend fun forgetUnfinishedFile(uri: String) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            for (index in builder.unfinishedFilesCount - 1 downTo 0) {
                if (builder.getUnfinishedFiles(index).uri == uri) builder.removeUnfinishedFiles(index)
            }
            builder.build()
        }
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
            val existing = builder.getPlans(index).toDomain()
            val refreshed = plan.copy(
                enabled = plan.enabled && !(existing.databaseScopeReviewRequired &&
                    plan.format == ExportFormat.DATABASE),
                databaseScopeReviewRequired = existing.databaseScopeReviewRequired &&
                    plan.format == ExportFormat.DATABASE,
                updatedAtMillis = clock.currentTimeMillis(),
            )
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
            val existing = builder.getPlans(index).toDomain()
            if (enabled && existing.databaseScopeReviewRequired) return@updateData current
            val plan = existing.copy(enabled = enabled)
            builder.setPlans(index, plan.copy(updatedAtMillis = clock.currentTimeMillis()).toProto())
            builder.build()
        }
    }

    /** Only a deliberate scope-review action may resume a paused legacy Database plan. */
    suspend fun reviewDatabaseScopeAndEnable(planId: ExportPlanId) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.plansList.indexOfFirst { it.id == planId.value }
            if (index < 0) return@updateData current
            val plan = builder.getPlans(index).toDomain()
            if (plan.format != ExportFormat.DATABASE || !plan.databaseScopeReviewRequired) {
                return@updateData current
            }
            builder.setPlans(index, plan.copy(
                enabled = true,
                databaseScopeReviewRequired = false,
                updatedAtMillis = clock.currentTimeMillis(),
            ).toProto())
            builder.build()
        }
    }

    /** Fetch a snapshot of the plan with the provided identifier. */
    suspend fun getPlan(planId: ExportPlanId): ExportBackupPlan? = withContext(ioDispatcher) {
        val proto = dataStore.data.first()
        proto.plansList.firstOrNull { it.id == planId.value }?.toDomain()
    }

    /**
     * Update the watermark for a plan after a successful incremental export.
     * Only modifies watermark fields; does not touch [ExportBackupPlan.updatedAtMillis].
     */
    suspend fun updateWatermark(
        planId: ExportPlanId,
        watermarkMs: Long,
        watermarkId: Long,
        completedAt: Long,
        recordCount: Int,
    ) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.plansList.indexOfFirst { it.id == planId.value }
            if (index < 0) return@updateData current
            val existing = builder.getPlans(index).toDomain()
            val advances = watermarkMs > existing.lastWatermarkMs ||
                (watermarkMs == existing.lastWatermarkMs && watermarkId > existing.lastWatermarkId)
            if (!advances) return@updateData current
            val plan = existing.copy(
                lastWatermarkMs = watermarkMs,
                lastWatermarkId = watermarkId,
                lastCompletedAt = completedAt,
                lastRecordCount = recordCount,
            )
            builder.setPlans(index, plan.toProto())
            builder.build()
        }
    }

    /** Reset watermark for a single plan so the next export performs a full scan. */
    suspend fun resetWatermark(planId: ExportPlanId) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.plansList.indexOfFirst { it.id == planId.value }
            if (index < 0) return@updateData current
            val plan = builder.getPlans(index).toDomain().copy(
                lastWatermarkMs = 0L,
                lastWatermarkId = 0L,
                lastCompletedAt = 0L,
                lastRecordCount = 0,
            )
            builder.setPlans(index, plan.toProto())
            builder.build()
        }
    }

    /** Reset watermarks for all plans (e.g., after data purge). */
    suspend fun resetAllWatermarks() = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            for (i in 0 until builder.plansCount) {
                val plan = builder.getPlans(i).toDomain().copy(
                    lastWatermarkMs = 0L,
                    lastWatermarkId = 0L,
                    lastCompletedAt = 0L,
                    lastRecordCount = 0,
                )
                builder.setPlans(i, plan.toProto())
            }
            builder.build()
        }
    }

    /** Set [ExportBackupPlan.incrementalEnabled] for a single plan. */
    suspend fun setIncrementalEnabled(planId: ExportPlanId, enabled: Boolean) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.plansList.indexOfFirst { it.id == planId.value }
            if (index < 0) return@updateData current
            val plan = builder.getPlans(index).toDomain().copy(incrementalEnabled = enabled)
            builder.setPlans(index, plan.toProto())
            builder.build()
        }
    }

    /** Set [ExportBackupPlan.incrementalEnabled] for all existing plans. */
    suspend fun setAllIncrementalEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            for (i in 0 until builder.plansCount) {
                val plan = builder.getPlans(i).toDomain().copy(incrementalEnabled = enabled)
                builder.setPlans(i, plan.toProto())
            }
            builder.build()
        }
    }
}

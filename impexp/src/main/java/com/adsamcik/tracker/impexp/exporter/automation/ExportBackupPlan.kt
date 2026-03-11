package com.adsamcik.tracker.impexp.exporter.automation

import android.net.Uri
import com.adsamcik.tracker.impexp.exporter.proto.AfterSessionCadenceProto
import com.adsamcik.tracker.impexp.exporter.proto.DocumentTreeDestinationProto
import com.adsamcik.tracker.impexp.exporter.proto.EntireHistoryScopeProto
import androidx.annotation.Keep
import com.adsamcik.tracker.impexp.exporter.proto.ExportBackupPlanProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportCadenceProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportDestinationProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportFormatProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportPlansProto
import com.adsamcik.tracker.impexp.exporter.proto.ExportScopeProto
import com.adsamcik.tracker.impexp.exporter.proto.FixedWindowScopeProto
import com.adsamcik.tracker.impexp.exporter.proto.IntervalCadenceProto
import com.adsamcik.tracker.impexp.exporter.proto.IntervalUnitProto
import com.adsamcik.tracker.impexp.exporter.proto.LastSessionScopeProto
import com.adsamcik.tracker.impexp.exporter.proto.PrivateStorageDestinationProto
import com.adsamcik.tracker.impexp.exporter.proto.RollingWindowScopeProto
import com.adsamcik.tracker.impexp.exporter.proto.WindowUnitProto
import java.time.LocalTime

/**
 * Stable identifier for persisted export backup plans.
 */
@JvmInline
value class ExportPlanId(val value: Long)

/**
 * Supported export file formats for automated backups.
 */
enum class ExportFormat {
    GPX,
    KML,
    DATABASE,
    JSON;

    /** Stable identifier used by [com.adsamcik.tracker.impexp.format.FormatRegistry]. */
    val formatId: String
        get() = when (this) {
            GPX -> "gpx"
            KML -> "kml"
            DATABASE -> "db"
            JSON -> "json"
        }

    internal fun toProto(): ExportFormatProto = when (this) {
        GPX -> ExportFormatProto.EXPORT_FORMAT_GPX
        KML -> ExportFormatProto.EXPORT_FORMAT_KML
        DATABASE -> ExportFormatProto.EXPORT_FORMAT_DATABASE
        JSON -> ExportFormatProto.EXPORT_FORMAT_JSON
    }

    internal companion object {
        fun fromProto(proto: ExportFormatProto): ExportFormat = when (proto) {
            ExportFormatProto.EXPORT_FORMAT_KML -> KML
            ExportFormatProto.EXPORT_FORMAT_DATABASE -> DATABASE
            ExportFormatProto.EXPORT_FORMAT_JSON -> JSON
            ExportFormatProto.EXPORT_FORMAT_GPX,
            ExportFormatProto.EXPORT_FORMAT_UNSPECIFIED,
            ExportFormatProto.UNRECOGNIZED -> GPX
        }
    }
}

/**
 * Cadence at which a backup plan should run.
 */
sealed interface ExportCadence {
    data object AfterSession : ExportCadence

    data class Interval(
        val unit: IntervalUnit,
        val every: Int = 1,
        val atTime: LocalTime? = null,
        val debounceDuplicates: Boolean = true
    ) : ExportCadence {
        init {
            require(every > 0) { "Interval cadence must run at least every 1 unit" }
        }
    }

    enum class IntervalUnit { DAY, WEEK, MONTH, YEAR, CUSTOM_DAYS }
}

/**
 * Scope of data to include for a plan execution.
 */
sealed interface ExportScope {
    data object LastSession : ExportScope

    data class RollingWindow(val unit: WindowUnit, val count: Int) : ExportScope {
        init {
            require(count > 0) { "Rolling window must be positive" }
        }
    }

    data class FixedWindow(val startEpochMillis: Long, val endEpochMillis: Long) : ExportScope

    data object EntireHistory : ExportScope

    enum class WindowUnit { DAY, WEEK, MONTH, YEAR }
}

/**
 * Destination configuration for storing export files.
 */
sealed interface ExportDestination {
    val fileNamePrefix: String?
    val shareAfterExport: Boolean

    data class PrivateStorage(
        val relativeDirectory: String = "exports",
        override val fileNamePrefix: String? = null,
        override val shareAfterExport: Boolean = false
    ) : ExportDestination

    data class DocumentTree(
        val treeUri: Uri,
        val subdirectory: String? = null,
        override val fileNamePrefix: String? = null,
        override val shareAfterExport: Boolean = false
    ) : ExportDestination
}

/**
 * Persisted backup plan definition.
 */
@Keep
data class ExportBackupPlan(
    val id: ExportPlanId,
    val name: String,
    val format: ExportFormat,
    val cadence: ExportCadence,
    val scope: ExportScope,
    val destination: ExportDestination,
    val enabled: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

/**
 * Draft used when creating or editing a backup plan before persistence.
 */
@Keep
data class ExportBackupPlanDraft(
    val name: String,
    val format: ExportFormat,
    val cadence: ExportCadence,
    val scope: ExportScope,
    val destination: ExportDestination,
    val enabled: Boolean = true
) {
    fun materialize(id: ExportPlanId, createdAt: Long, updatedAt: Long): ExportBackupPlan {
        val resolvedName = name.ifBlank { "${format.name} backup" }
        return ExportBackupPlan(id, resolvedName, format, cadence, scope, destination, enabled, createdAt, updatedAt)
    }
}

internal fun ExportBackupPlanProto.toDomain(): ExportBackupPlan {
    return ExportBackupPlan(
        id = ExportPlanId(id.takeIf { it != 0L } ?: 0L),
        name = name.ifBlank { "Plan $id" },
        format = ExportFormat.Companion.fromProto(format),
        cadence = cadence?.toDomain() ?: ExportCadence.AfterSession,
        scope = scope?.toDomain() ?: ExportScope.RollingWindow(ExportScope.WindowUnit.DAY, 1),
        destination = destination?.toDomain() ?: ExportDestination.PrivateStorage(),
        enabled = enabled,
        createdAtMillis = createdAtEpochMillis,
        updatedAtMillis = updatedAtEpochMillis
    )
}

internal fun ExportBackupPlan.toProto(): ExportBackupPlanProto {
    val cadenceProto = cadence.toProto()
    val scopeProto = scope.toProto()
    val destinationProto = destination.toProto()
    return ExportBackupPlanProto.newBuilder()
        .setId(id.value)
        .setName(name)
        .setFormat(format.toProto())
        .setCadence(cadenceProto)
        .setScope(scopeProto)
        .setDestination(destinationProto)
        .setEnabled(enabled)
        .setCreatedAtEpochMillis(createdAtMillis)
        .setUpdatedAtEpochMillis(updatedAtMillis)
        .build()
}

internal fun ExportCadence.toProto(): ExportCadenceProto {
    return when (this) {
        ExportCadence.AfterSession -> ExportCadenceProto.newBuilder()
            .setAfterSession(AfterSessionCadenceProto.getDefaultInstance())
            .build()
        is ExportCadence.Interval -> ExportCadenceProto.newBuilder()
            .setInterval(
                IntervalCadenceProto.newBuilder()
                    .setUnit(unit.toProto())
                    .setEvery(every)
                    .setAtHour(atTime?.hour ?: 0)
                    .setAtMinute(atTime?.minute ?: 0)
                    .setHasTimeOfDay(atTime != null)
                    .build()
            )
            .build()
    }
}

private fun ExportCadenceProto.toDomain(): ExportCadence {
    return when (cadenceTypeCase) {
        ExportCadenceProto.CadenceTypeCase.AFTER_SESSION -> ExportCadence.AfterSession
        ExportCadenceProto.CadenceTypeCase.INTERVAL -> interval.toDomain()
        ExportCadenceProto.CadenceTypeCase.CADENCETYPE_NOT_SET, null -> ExportCadence.AfterSession
    }
}

private fun IntervalCadenceProto.toDomain(): ExportCadence.Interval {
    val time = if (hasTimeOfDay && atHour in 0..23 && atMinute in 0..59) {
        LocalTime.of(atHour, atMinute)
    } else {
        null
    }
    return ExportCadence.Interval(
        unit = unit.toDomain(),
        every = every.coerceAtLeast(1),
        atTime = time
    )
}

private fun ExportCadence.IntervalUnit.toProto(): IntervalUnitProto = when (this) {
    ExportCadence.IntervalUnit.DAY -> IntervalUnitProto.INTERVAL_UNIT_DAY
    ExportCadence.IntervalUnit.WEEK -> IntervalUnitProto.INTERVAL_UNIT_WEEK
    ExportCadence.IntervalUnit.MONTH -> IntervalUnitProto.INTERVAL_UNIT_MONTH
    ExportCadence.IntervalUnit.YEAR -> IntervalUnitProto.INTERVAL_UNIT_YEAR
    ExportCadence.IntervalUnit.CUSTOM_DAYS -> IntervalUnitProto.INTERVAL_UNIT_CUSTOM_DAYS
}

private fun IntervalUnitProto.toDomain(): ExportCadence.IntervalUnit = when (this) {
    IntervalUnitProto.INTERVAL_UNIT_WEEK -> ExportCadence.IntervalUnit.WEEK
    IntervalUnitProto.INTERVAL_UNIT_MONTH -> ExportCadence.IntervalUnit.MONTH
    IntervalUnitProto.INTERVAL_UNIT_YEAR -> ExportCadence.IntervalUnit.YEAR
    IntervalUnitProto.INTERVAL_UNIT_CUSTOM_DAYS -> ExportCadence.IntervalUnit.CUSTOM_DAYS
    IntervalUnitProto.INTERVAL_UNIT_DAY,
    IntervalUnitProto.INTERVAL_UNIT_UNSPECIFIED,
    IntervalUnitProto.UNRECOGNIZED -> ExportCadence.IntervalUnit.DAY
}

internal fun ExportScope.toProto(): ExportScopeProto {
    return when (this) {
        ExportScope.LastSession -> ExportScopeProto.newBuilder()
            .setLastSession(LastSessionScopeProto.getDefaultInstance())
            .build()
        is ExportScope.RollingWindow -> ExportScopeProto.newBuilder()
            .setRollingWindow(
                RollingWindowScopeProto.newBuilder()
                    .setUnit(unit.toProto())
                    .setCount(count)
                    .build()
            )
            .build()
        is ExportScope.FixedWindow -> ExportScopeProto.newBuilder()
            .setFixedWindow(
                FixedWindowScopeProto.newBuilder()
                    .setStartEpochMillis(startEpochMillis)
                    .setEndEpochMillis(endEpochMillis)
                    .build()
            )
            .build()
        ExportScope.EntireHistory -> ExportScopeProto.newBuilder()
            .setEntireHistory(EntireHistoryScopeProto.getDefaultInstance())
            .build()
    }
}

private fun ExportScopeProto.toDomain(): ExportScope {
    return when (scopeTypeCase) {
        ExportScopeProto.ScopeTypeCase.LAST_SESSION -> ExportScope.LastSession
        ExportScopeProto.ScopeTypeCase.ROLLING_WINDOW -> ExportScope.RollingWindow(
            rollingWindow.unit.toDomain(),
            rollingWindow.count.coerceAtLeast(1)
        )
        ExportScopeProto.ScopeTypeCase.FIXED_WINDOW -> ExportScope.FixedWindow(
            fixedWindow.startEpochMillis,
            fixedWindow.endEpochMillis
        )
        ExportScopeProto.ScopeTypeCase.ENTIRE_HISTORY -> ExportScope.EntireHistory
        ExportScopeProto.ScopeTypeCase.SCOPETYPE_NOT_SET, null -> ExportScope.RollingWindow(ExportScope.WindowUnit.DAY, 1)
    }
}

private fun ExportScope.WindowUnit.toProto(): WindowUnitProto = when (this) {
    ExportScope.WindowUnit.DAY -> WindowUnitProto.WINDOW_UNIT_DAY
    ExportScope.WindowUnit.WEEK -> WindowUnitProto.WINDOW_UNIT_WEEK
    ExportScope.WindowUnit.MONTH -> WindowUnitProto.WINDOW_UNIT_MONTH
    ExportScope.WindowUnit.YEAR -> WindowUnitProto.WINDOW_UNIT_YEAR
}

private fun WindowUnitProto.toDomain(): ExportScope.WindowUnit = when (this) {
    WindowUnitProto.WINDOW_UNIT_WEEK -> ExportScope.WindowUnit.WEEK
    WindowUnitProto.WINDOW_UNIT_MONTH -> ExportScope.WindowUnit.MONTH
    WindowUnitProto.WINDOW_UNIT_YEAR -> ExportScope.WindowUnit.YEAR
    WindowUnitProto.WINDOW_UNIT_DAY,
    WindowUnitProto.WINDOW_UNIT_UNSPECIFIED,
    WindowUnitProto.UNRECOGNIZED -> ExportScope.WindowUnit.DAY
}

internal fun ExportDestination.toProto(): ExportDestinationProto {
    val builder = ExportDestinationProto.newBuilder()
        .setFileNamePrefix(fileNamePrefix.orEmpty())
        .setShareAfterExport(shareAfterExport)
    when (this) {
        is ExportDestination.PrivateStorage -> builder.privateStorage = PrivateStorageDestinationProto.newBuilder()
            .setRelativeDirectory(relativeDirectory)
            .build()
        is ExportDestination.DocumentTree -> builder.documentTree = DocumentTreeDestinationProto.newBuilder()
            .setTreeUri(treeUri.toString())
            .setSubdirectory(subdirectory.orEmpty())
            .build()
    }
    return builder.build()
}

private fun ExportDestinationProto.toDomain(): ExportDestination {
    val prefix = fileNamePrefix.takeIf { it.isNotBlank() }
    return when (destinationTypeCase) {
        ExportDestinationProto.DestinationTypeCase.PRIVATE_STORAGE -> ExportDestination.PrivateStorage(
            relativeDirectory = privateStorage.relativeDirectory.ifBlank { "exports" },
            fileNamePrefix = prefix,
            shareAfterExport = shareAfterExport
        )
        ExportDestinationProto.DestinationTypeCase.DOCUMENT_TREE -> {
            val uri = documentTree.treeUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            if (uri != null) {
                ExportDestination.DocumentTree(
                    treeUri = uri,
                    subdirectory = documentTree.subdirectory.ifBlank { null },
                    fileNamePrefix = prefix,
                    shareAfterExport = shareAfterExport
                )
            } else {
                ExportDestination.PrivateStorage(fileNamePrefix = prefix, shareAfterExport = shareAfterExport)
            }
        }
        ExportDestinationProto.DestinationTypeCase.DESTINATIONTYPE_NOT_SET, null -> ExportDestination.PrivateStorage(
            fileNamePrefix = prefix,
            shareAfterExport = shareAfterExport
        )
    }
}

internal fun ExportPlansProto.nextIdentifier(): Long {
    return if (nextPlanId == 0L) 1L else nextPlanId
}

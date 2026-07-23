package com.adsamcik.tracker.app.tracebox

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.tracebox.api.ApprovalToken
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PackageResult
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.TraceboxHealth
import dev.tracebox.api.generated.GeneratedDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracker sends Tracebox only one application-owned structural breadcrumb on an explicit opt-in.
 * It never forwards location, Wi-Fi, cell, sensor, account, user-entered text, or app identifiers.
 */
@Singleton
class TrackerTraceboxController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : TraceboxController {
    private val handle = TraceboxStartup.requireHandle(context.applicationContext as Application)
    private val mutableState = MutableStateFlow(
        TraceboxUiState(TraceboxAvailability.INITIALIZING, enabled = false),
    )
    private var preview: dev.tracebox.api.PackagePreview? = null
    private var approvedPackage: DiagnosticPackage? = null

    override val state: StateFlow<TraceboxUiState> = mutableState

    init {
        applicationScope.launch {
            combine(handle.health, handle.readiness) { health, _ -> health }.collect { health ->
                mutableState.update { current ->
                    current.copy(
                        availability = health.toTrackerAvailability(),
                        enabled = when (health) {
                            TraceboxHealth.DISABLED, TraceboxHealth.INITIALIZING, TraceboxHealth.CLOSED -> false
                            TraceboxHealth.READY, TraceboxHealth.DEGRADED, TraceboxHealth.DELETING -> current.enabled
                        },
                    )
                }
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        applicationScope.launch(Dispatchers.IO) {
            val update = handle.updateProfile(
                if (enabled) DiagnosticsProfile.STANDARD_DIAGNOSTICS else DiagnosticsProfile.DISABLED,
            )
            if (update == PolicyUpdateResult.SUCCESS && enabled) {
                GeneratedDiagnostics.breadcrumb(
                    handle.diagnostics,
                    code = TRACKER_OPT_IN_BREADCRUMB,
                    monotonic_time_ns = SystemClock.elapsedRealtimeNanos().toULong(),
                )
            }
            if (!enabled && update == PolicyUpdateResult.SUCCESS) {
                handle.delete(DeleteRequest.ALL_TRACEBOX_DATA)
            }
            mutableState.update { current ->
                current.copy(
                    enabled = enabled && update == PolicyUpdateResult.SUCCESS,
                    exportState = TraceboxExportState.IDLE,
                    preview = null,
                    result = if (update == PolicyUpdateResult.SUCCESS) {
                        TraceboxOperationResult.PROFILE_UPDATED
                    } else {
                        TraceboxOperationResult.FAILED
                    },
                )
            }
        }
    }

    override fun prepareStandardPackage() {
        applicationScope.launch(Dispatchers.IO) {
            when (val prepared = handle.packages.prepare(PackageRequest.STANDARD)) {
                is PackagePreparationResult.Ready -> {
                    preview = prepared.preview
                    val disclosure = prepared.preview.disclosure
                    mutableState.update {
                        it.copy(
                            exportState = TraceboxExportState.PREVIEW_READY,
                            preview = TraceboxPreview(
                                disclosure.includedValueCount,
                                disclosure.includedBytes,
                                disclosure.sourceProcessCount,
                                disclosure.rawArtifactCount == 0,
                                disclosure.plaintextDigestSha256.copyOf(),
                            ),
                            result = TraceboxOperationResult.NONE,
                        )
                    }
                }

                PackagePreparationResult.NotReady, PackagePreparationResult.Rejected -> {
                    mutableState.update { it.copy(exportState = TraceboxExportState.FAILED, result = TraceboxOperationResult.FAILED) }
                }
            }
        }
    }

    override fun approvalIntent(): Intent? =
        preview?.let { handle.packages.approvalIntent(context, it) }

    override fun acceptApprovalResult(result: Intent?) {
        val approval = ApprovalToken.fromActivityResult(result) ?: run {
            mutableState.update { it.copy(exportState = TraceboxExportState.CANCELLED) }
            return
        }
        when (val created = handle.packages.create(PackageRequest.STANDARD, approval)) {
            is PackageResult.Created -> {
                approvedPackage = created.diagnosticPackage
                mutableState.update { it.copy(exportState = TraceboxExportState.APPROVED) }
            }

            PackageResult.NotReady, PackageResult.Rejected -> {
                mutableState.update { it.copy(exportState = TraceboxExportState.FAILED, result = TraceboxOperationResult.FAILED) }
            }
        }
    }

    override fun shareIntent(): Intent? = approvedPackage?.shareIntent(context)

    override fun createSaveIntent(): Intent? = approvedPackage?.createSaveIntent()

    override fun save(destination: Uri) {
        val packageToSave = approvedPackage ?: return
        applicationScope.launch(Dispatchers.IO) {
            val result = packageToSave.save(context, destination)
            mutableState.update {
                when (result) {
                    is SavePackageResult.Complete -> it.copy(
                        exportState = TraceboxExportState.SAVED,
                        result = TraceboxOperationResult.SAVE_COMPLETE,
                    )

                    is SavePackageResult.PartialCopyWarning -> it.copy(
                        exportState = TraceboxExportState.CANCELLED,
                        result = TraceboxOperationResult.SAVE_PARTIAL,
                    )

                    is SavePackageResult.Failed -> it.copy(
                        exportState = TraceboxExportState.FAILED,
                        result = TraceboxOperationResult.FAILED,
                    )
                }
            }
        }
    }

    override fun cancelPackage() {
        applicationScope.launch(Dispatchers.IO) {
            approvedPackage?.deleteStaging()
            approvedPackage = null
            preview = null
            mutableState.update {
                it.copy(
                    exportState = TraceboxExportState.CANCELLED,
                    preview = null,
                )
            }
        }
    }

    override fun deleteAllTraceboxData() {
        applicationScope.launch(Dispatchers.IO) {
            val report = handle.delete(DeleteRequest.ALL_TRACEBOX_DATA)
            approvedPackage = null
            preview = null
            mutableState.update {
                it.copy(
                    enabled = false,
                    exportState = TraceboxExportState.IDLE,
                    preview = null,
                    result = if (report == DeleteReport.COMPLETE) {
                        TraceboxOperationResult.DATA_DELETED
                    } else {
                        TraceboxOperationResult.FAILED
                    },
                )
            }
        }
    }

    private fun TraceboxHealth.toTrackerAvailability(): TraceboxAvailability = when (this) {
        TraceboxHealth.INITIALIZING -> TraceboxAvailability.INITIALIZING
        TraceboxHealth.DISABLED -> TraceboxAvailability.DISABLED
        TraceboxHealth.READY -> TraceboxAvailability.READY
        TraceboxHealth.DEGRADED -> TraceboxAvailability.DEGRADED
        TraceboxHealth.DELETING -> TraceboxAvailability.DELETING
        TraceboxHealth.CLOSED -> TraceboxAvailability.DEGRADED
    }

    private companion object {
        const val TRACKER_OPT_IN_BREADCRUMB = 0x5452434bU
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TraceboxRuntimeModule {
    @Binds
    abstract fun bindTraceboxController(controller: TrackerTraceboxController): TraceboxController
}

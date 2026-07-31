package com.adsamcik.tracker.app.tracebox

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.tracebox.api.ApprovalToken
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PackageResult
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.Readiness
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.TraceboxHealth
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TraceboxDiagnosticsState(
    val readiness: Readiness,
    val health: TraceboxHealth,
    val operationInProgress: Boolean,
    val packageReady: Boolean,
) {
    val enabled: Boolean
        get() = health != TraceboxHealth.DISABLED && health != TraceboxHealth.CLOSED
}

enum class TraceboxPolicyResult {
    COMPLETE,
    RESTRICTED,
    PARTIAL,
    FAILED,
}

enum class TraceboxDeleteResult {
    COMPLETE,
    PENDING_FAILURE,
    REJECTED,
}

sealed interface TraceboxPackagePreparation {
    data class Ready(
        val approvalIntent: Intent,
        val includedValueCount: Int,
        val includedBytes: Long,
    ) : TraceboxPackagePreparation

    data object NotReady : TraceboxPackagePreparation
    data object Rejected : TraceboxPackagePreparation
}

enum class TraceboxPackageCreationResult {
    CREATED,
    APPROVAL_CANCELLED,
    REJECTED,
    NOT_READY,
}

sealed interface TraceboxPackageSaveResult {
    data class Complete(val bytesWritten: Long) : TraceboxPackageSaveResult
    data class Partial(val bytesWritten: Long, val cancelled: Boolean) : TraceboxPackageSaveResult
    data object Failed : TraceboxPackageSaveResult
    data object NotReady : TraceboxPackageSaveResult
}

sealed interface TraceboxPackageShareResult {
    data class Ready(val chooserIntent: Intent) : TraceboxPackageShareResult
    data object NotReady : TraceboxPackageShareResult
}

interface TraceboxDiagnosticsOperations {
    val state: StateFlow<TraceboxDiagnosticsState>

    suspend fun setEnabled(enabled: Boolean): TraceboxPolicyResult

    suspend fun deleteAllData(): TraceboxDeleteResult

    suspend fun preparePackage(): TraceboxPackagePreparation

    suspend fun createApprovedPackage(resultData: Intent?): TraceboxPackageCreationResult

    fun createSaveIntent(): Intent?

    suspend fun save(destination: Uri): TraceboxPackageSaveResult

    suspend fun share(): TraceboxPackageShareResult
}

/**
 * Serializes every blocking Tracebox operation on Tracker's injected IO dispatcher.
 */
@Singleton
class TraceboxDiagnosticsController private constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatchersProvider,
    @ApplicationScope applicationScope: CoroutineScope,
    private val handle: dev.tracebox.api.TraceboxHandle,
) : TraceboxDiagnosticsOperations {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dispatchers: DispatchersProvider,
        @ApplicationScope applicationScope: CoroutineScope,
        handleProvider: TrackerTraceboxHandleProvider,
    ) : this(
        context = context,
        dispatchers = dispatchers,
        applicationScope = applicationScope,
        handle = handleProvider.handle,
    )

    private val operationMutex = Mutex()
    private val operationInProgress = MutableStateFlow(false)
    private val packageReady = MutableStateFlow(false)
    private val currentPackage = AtomicReference<DiagnosticPackage?>(null)

    override val state: StateFlow<TraceboxDiagnosticsState> = combine(
        handle.readiness,
        handle.health,
        operationInProgress,
        packageReady,
        ::TraceboxDiagnosticsState,
    ).stateIn(
        applicationScope,
        SharingStarted.Eagerly,
        TraceboxDiagnosticsState(
            readiness = handle.readiness.value,
            health = handle.health.value,
            operationInProgress = false,
            packageReady = false,
        ),
    )

    override suspend fun setEnabled(enabled: Boolean): TraceboxPolicyResult = serialized {
        retireCurrentPackage()
        when (
            handle.updateProfile(
                if (enabled) {
                    DiagnosticsProfile.STANDARD_DIAGNOSTICS
                } else {
                    DiagnosticsProfile.DISABLED
                },
            )
        ) {
            PolicyUpdateResult.SUCCESS -> TraceboxPolicyResult.COMPLETE
            PolicyUpdateResult.LOCAL_ONLY_RESTRICTED -> TraceboxPolicyResult.RESTRICTED
            PolicyUpdateResult.PARTIAL -> TraceboxPolicyResult.PARTIAL
            PolicyUpdateResult.FAILED -> TraceboxPolicyResult.FAILED
        }
    }

    override suspend fun deleteAllData(): TraceboxDeleteResult = serialized {
        retireCurrentPackage()
        when (handle.delete(DeleteRequest.ALL_TRACEBOX_DATA)) {
            DeleteReport.COMPLETE -> TraceboxDeleteResult.COMPLETE
            DeleteReport.PENDING_FAILURE -> TraceboxDeleteResult.PENDING_FAILURE
            DeleteReport.REJECTED -> TraceboxDeleteResult.REJECTED
        }
    }

    override suspend fun preparePackage(): TraceboxPackagePreparation = serialized {
        retireCurrentPackage()
        when (val prepared = handle.packages.prepare(PackageRequest.STANDARD)) {
            is PackagePreparationResult.Ready -> {
                val approvalIntent =
                    handle.packages.approvalIntent(context, prepared.preview)
                        ?: return@serialized TraceboxPackagePreparation.Rejected
                TraceboxPackagePreparation.Ready(
                    approvalIntent = approvalIntent,
                    includedValueCount = prepared.preview.disclosure.includedValueCount,
                    includedBytes = prepared.preview.disclosure.includedBytes,
                )
            }

            PackagePreparationResult.NotReady -> TraceboxPackagePreparation.NotReady
            PackagePreparationResult.Rejected -> TraceboxPackagePreparation.Rejected
        }
    }

    override suspend fun createApprovedPackage(resultData: Intent?): TraceboxPackageCreationResult =
        serialized {
            retireCurrentPackage()
            val approval = ApprovalToken.fromActivityResult(resultData)
                ?: return@serialized TraceboxPackageCreationResult.APPROVAL_CANCELLED
            when (val result = handle.packages.create(PackageRequest.STANDARD, approval)) {
                is PackageResult.Created -> {
                    currentPackage.set(result.diagnosticPackage)
                    packageReady.value = true
                    TraceboxPackageCreationResult.CREATED
                }

                PackageResult.Rejected -> TraceboxPackageCreationResult.REJECTED
                PackageResult.NotReady -> TraceboxPackageCreationResult.NOT_READY
            }
        }

    override fun createSaveIntent(): Intent? = currentPackage.get()?.createSaveIntent()

    override suspend fun save(destination: Uri): TraceboxPackageSaveResult = serialized {
        val diagnosticPackage = currentPackage.get()
            ?: return@serialized TraceboxPackageSaveResult.NotReady
        when (val result = diagnosticPackage.save(context, destination)) {
            is SavePackageResult.Complete ->
                TraceboxPackageSaveResult.Complete(result.bytesWritten)

            is SavePackageResult.PartialCopyWarning ->
                TraceboxPackageSaveResult.Partial(result.bytesWritten, result.cancelled)

            is SavePackageResult.Failed -> TraceboxPackageSaveResult.Failed
        }
    }

    override suspend fun share(): TraceboxPackageShareResult = serialized {
        val chooser = currentPackage.get()?.shareIntent(context)
            ?: return@serialized TraceboxPackageShareResult.NotReady
        TraceboxPackageShareResult.Ready(chooser)
    }

    private suspend fun <T> serialized(action: () -> T): T =
        withContext(dispatchers.io) {
            operationMutex.withLock {
                operationInProgress.value = true
                try {
                    action()
                } finally {
                    operationInProgress.value = false
                }
            }
        }

    private fun retireCurrentPackage() {
        currentPackage.getAndSet(null)?.deleteStaging()
        packageReady.value = false
    }

    internal companion object {
        fun createForTest(
            context: Context,
            dispatchers: DispatchersProvider,
            applicationScope: CoroutineScope,
            handle: dev.tracebox.api.TraceboxHandle,
        ): TraceboxDiagnosticsController = TraceboxDiagnosticsController(
            context = context,
            dispatchers = dispatchers,
            applicationScope = applicationScope,
            handle = handle,
        )
    }
}

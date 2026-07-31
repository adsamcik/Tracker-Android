package com.adsamcik.tracker.app.tracebox

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import dev.tracebox.api.ApprovalToken
import dev.tracebox.api.DeleteReport
import dev.tracebox.api.DeleteRequest
import dev.tracebox.api.DiagnosticContext
import dev.tracebox.api.DiagnosticPackage
import dev.tracebox.api.DiagnosticPackages
import dev.tracebox.api.Diagnostics
import dev.tracebox.api.DiagnosticsProfile
import dev.tracebox.api.PackageDisclosure
import dev.tracebox.api.PackagePreparationResult
import dev.tracebox.api.PackagePreview
import dev.tracebox.api.PackageRequest
import dev.tracebox.api.PackageResult
import dev.tracebox.api.PolicyUpdateResult
import dev.tracebox.api.Readiness
import dev.tracebox.api.SavePackageResult
import dev.tracebox.api.SharePackageResult
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.TraceboxHealth
import dev.tracebox.api.generated.GeneratedEventId
import dev.tracebox.api.generated.GeneratedRecord
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraceboxDiagnosticsControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `profile results retain Tracebox convergence semantics`() = runTest {
        val handle = FakeTraceboxHandle().apply {
            policyResult = PolicyUpdateResult.LOCAL_ONLY_RESTRICTED
        }
        val controller = controller(handle)

        controller.setEnabled(false) shouldBe TraceboxPolicyResult.RESTRICTED
        handle.requestedProfile shouldBe DiagnosticsProfile.DISABLED

        handle.policyResult = PolicyUpdateResult.PARTIAL
        controller.setEnabled(true) shouldBe TraceboxPolicyResult.PARTIAL
        handle.requestedProfile shouldBe DiagnosticsProfile.STANDARD_DIAGNOSTICS
    }

    @Test
    fun `approved package supports real review save and share handoffs`() = runTest {
        val diagnosticPackage = FakeDiagnosticPackage()
        val handle = FakeTraceboxHandle().apply {
            packages.preparation = PackagePreparationResult.Ready(packagePreview())
            packages.created = PackageResult.Created(diagnosticPackage)
        }
        val controller = controller(handle)

        val preparation = controller.preparePackage()
        preparation shouldBe TraceboxPackagePreparation.Ready(
            approvalIntent = handle.packages.approval,
            includedValueCount = 7,
            includedBytes = 2048,
        )

        val approval = ApprovalToken.resultIntent(ByteArray(32) { 1 })
        controller.createApprovedPackage(approval) shouldBe
            TraceboxPackageCreationResult.CREATED
        controller.state.first { it.packageReady }.packageReady shouldBe true
        controller.createSaveIntent() shouldBe diagnosticPackage.saveIntent

        val destination = Uri.parse("content://test/tracebox.tbdiag")
        diagnosticPackage.saveResult = SavePackageResult.Complete(2048)
        controller.save(destination) shouldBe TraceboxPackageSaveResult.Complete(2048)
        diagnosticPackage.savedDestination shouldBe destination

        controller.share() shouldBe
            TraceboxPackageShareResult.Ready(diagnosticPackage.shareChooser)
    }

    @Test
    fun `whole deletion retires package staging and preserves pending result`() = runTest {
        val diagnosticPackage = FakeDiagnosticPackage()
        val handle = FakeTraceboxHandle().apply {
            packages.created = PackageResult.Created(diagnosticPackage)
            deleteReport = DeleteReport.PENDING_FAILURE
        }
        val controller = controller(handle)
        controller.createApprovedPackage(
            ApprovalToken.resultIntent(ByteArray(32) { 2 }),
        )
        controller.state.first { it.packageReady }

        controller.deleteAllData() shouldBe TraceboxDeleteResult.PENDING_FAILURE
        diagnosticPackage.deleteStagingCalls shouldBe 1
        handle.deleteRequest shouldBe DeleteRequest.ALL_TRACEBOX_DATA
        controller.state.first { !it.packageReady }.packageReady shouldBe false
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        handle: TraceboxHandle,
    ): TraceboxDiagnosticsController = TraceboxDiagnosticsController.createForTest(
        context = context,
        dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
        applicationScope = backgroundScope,
        handle = handle,
    )

    private fun packagePreview() = PackagePreview(
        PackageDisclosure(
            includedValueCount = 7,
            includedBytes = 2048,
            privacyClasses = emptySet(),
            transformations = emptySet(),
            omissionReasons = emptySet(),
            sourceTimeRangeMillis = null,
            sourceProcessCount = 1,
            plaintextDigestSha256 = ByteArray(32),
            rawArtifactCount = 0,
            warnings = emptySet(),
        ),
    )

    private class FakeTraceboxHandle : TraceboxHandle {
        override val diagnostics = object : Diagnostics {
            override fun eventEnabled(eventId: GeneratedEventId): Boolean = true

            override fun record(value: GeneratedRecord, context: DiagnosticContext?) = Unit
        }
        override val readiness: StateFlow<Readiness> = MutableStateFlow(Readiness.DURABLE)
        override val health: StateFlow<TraceboxHealth> = MutableStateFlow(TraceboxHealth.READY)
        override val packages = FakeDiagnosticPackages()
        var policyResult = PolicyUpdateResult.SUCCESS
        var requestedProfile: DiagnosticsProfile? = null
        var deleteReport = DeleteReport.COMPLETE
        var deleteRequest: DeleteRequest? = null

        override fun updateProfile(profile: DiagnosticsProfile): PolicyUpdateResult {
            requestedProfile = profile
            return policyResult
        }

        override fun delete(request: DeleteRequest): DeleteReport {
            deleteRequest = request
            return deleteReport
        }

        override fun close() = Unit
    }

    private class FakeDiagnosticPackages : DiagnosticPackages {
        val approval = Intent("tracebox.approval")
        var preparation: PackagePreparationResult = PackagePreparationResult.NotReady
        var created: PackageResult = PackageResult.NotReady

        override fun prepare(request: PackageRequest): PackagePreparationResult = preparation

        override fun approvalIntent(context: Context, preview: PackagePreview): Intent = approval

        override fun create(request: PackageRequest, approval: ApprovalToken): PackageResult =
            created
    }

    private class FakeDiagnosticPackage : DiagnosticPackage {
        override val plaintextDigestSha256 = ByteArray(32)
        override val sizeBytes = 2048L
        override val receipt: StateFlow<SharePackageResult> =
            MutableStateFlow(SharePackageResult.NOT_STARTED)
        val shareChooser = Intent("tracebox.share")
        val saveIntent = Intent("tracebox.save")
        var saveResult: SavePackageResult = SavePackageResult.Complete(sizeBytes)
        var savedDestination: Uri? = null
        var deleteStagingCalls = 0

        override fun shareIntent(context: Context): Intent = shareChooser

        override fun createSaveIntent(): Intent = saveIntent

        override fun save(
            context: Context,
            destination: Uri,
            isCancelled: () -> Boolean,
        ): SavePackageResult {
            savedDestination = destination
            return saveResult
        }

        override fun deleteStaging(): Boolean {
            deleteStagingCalls += 1
            return true
        }
    }
}

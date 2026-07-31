package com.adsamcik.tracker.app.settings.tracebox

import android.app.Activity
import android.content.Intent
import android.net.Uri
import app.cash.turbine.test
import com.adsamcik.tracker.app.tracebox.TraceboxDeleteResult
import com.adsamcik.tracker.app.tracebox.TraceboxDiagnosticsOperations
import com.adsamcik.tracker.app.tracebox.TraceboxDiagnosticsState
import com.adsamcik.tracker.app.tracebox.TraceboxPackageCreationResult
import com.adsamcik.tracker.app.tracebox.TraceboxPackagePreparation
import com.adsamcik.tracker.app.tracebox.TraceboxPackageSaveResult
import com.adsamcik.tracker.app.tracebox.TraceboxPackageShareResult
import com.adsamcik.tracker.app.tracebox.TraceboxPolicyResult
import dev.tracebox.api.Readiness
import dev.tracebox.api.TraceboxHealth
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraceboxDiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var controller: FakeTraceboxDiagnostics
    private lateinit var viewModel: TraceboxDiagnosticsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = FakeTraceboxDiagnostics()
        viewModel = TraceboxDiagnosticsViewModel(controller)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `profile update delegates and reports durable completion`() = runTest(dispatcher) {
        controller.policyResult = TraceboxPolicyResult.COMPLETE

        viewModel.setEnabled(false)
        advanceUntilIdle()

        controller.requestedEnabled shouldBe false
        viewModel.message.value shouldBe TraceboxUiMessage.DiagnosticsDisabled
    }

    @Test
    fun `package review launches exact Tracebox approval intent`() = runTest(dispatcher) {
        val approvalIntent = Intent("tracebox.review")
        controller.preparation = TraceboxPackagePreparation.Ready(
            approvalIntent = approvalIntent,
            includedValueCount = 7,
            includedBytes = 2048,
        )

        viewModel.effects.test {
            viewModel.preparePackage()
            advanceUntilIdle()

            awaitItem() shouldBe TraceboxUiEffect.Review(approvalIntent)
            viewModel.message.value shouldBe TraceboxUiMessage.ReviewReady(7, 2048)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `approval result is handed to package creation`() = runTest(dispatcher) {
        val resultData = Intent("tracebox.approved")
        controller.creationResult = TraceboxPackageCreationResult.CREATED

        viewModel.onApprovalResult(Activity.RESULT_OK, resultData)
        advanceUntilIdle()

        controller.approvalResultData shouldBe resultData
        viewModel.message.value shouldBe TraceboxUiMessage.PackageCreated
    }

    @Test
    fun `save and share preserve bounded outcomes`() = runTest(dispatcher) {
        val destination = Uri.parse("content://test/tracebox.zip")
        val chooser = Intent("tracebox.share")
        controller.saveResult = TraceboxPackageSaveResult.Partial(
            bytesWritten = 73,
            cancelled = false,
        )
        controller.shareResult = TraceboxPackageShareResult.Ready(chooser)

        viewModel.onSaveDestination(destination)
        advanceUntilIdle()
        controller.savedDestination shouldBe destination
        viewModel.message.value shouldBe TraceboxUiMessage.SavePartial(73, false)

        viewModel.effects.test {
            viewModel.sharePackage()
            advanceUntilIdle()
            awaitItem() shouldBe TraceboxUiEffect.Share(chooser)
            viewModel.onShareChooserOpened()
            viewModel.message.value shouldBe TraceboxUiMessage.ShareChooserOpened
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `whole Tracebox deletion reports pending handler data honestly`() = runTest(dispatcher) {
        controller.deleteResult = TraceboxDeleteResult.PENDING_FAILURE

        viewModel.deleteAllData()
        advanceUntilIdle()

        controller.deleteCalls shouldBe 1
        viewModel.message.value shouldBe TraceboxUiMessage.DeletePending
    }

    private class FakeTraceboxDiagnostics : TraceboxDiagnosticsOperations {
        override val state = MutableStateFlow(
            TraceboxDiagnosticsState(
                readiness = Readiness.DURABLE,
                health = TraceboxHealth.READY,
                operationInProgress = false,
                packageReady = false,
            ),
        )

        var policyResult = TraceboxPolicyResult.COMPLETE
        var requestedEnabled: Boolean? = null
        var deleteResult = TraceboxDeleteResult.COMPLETE
        var deleteCalls = 0
        var preparation: TraceboxPackagePreparation = TraceboxPackagePreparation.NotReady
        var creationResult = TraceboxPackageCreationResult.NOT_READY
        var approvalResultData: Intent? = null
        var saveIntent: Intent? = null
        var saveResult: TraceboxPackageSaveResult = TraceboxPackageSaveResult.NotReady
        var savedDestination: Uri? = null
        var shareResult: TraceboxPackageShareResult = TraceboxPackageShareResult.NotReady

        override suspend fun setEnabled(enabled: Boolean): TraceboxPolicyResult {
            requestedEnabled = enabled
            return policyResult
        }

        override suspend fun deleteAllData(): TraceboxDeleteResult {
            deleteCalls += 1
            return deleteResult
        }

        override suspend fun preparePackage(): TraceboxPackagePreparation = preparation

        override suspend fun createApprovedPackage(
            resultData: Intent?,
        ): TraceboxPackageCreationResult {
            approvalResultData = resultData
            return creationResult
        }

        override fun createSaveIntent(): Intent? = saveIntent

        override suspend fun save(destination: Uri): TraceboxPackageSaveResult {
            savedDestination = destination
            return saveResult
        }

        override suspend fun share(): TraceboxPackageShareResult = shareResult
    }
}

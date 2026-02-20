package com.adsamcik.tracker.app.settings

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DebugSettingsViewModelTest {

    private fun createViewModel() = DebugSettingsViewModel()

    // =========================================================================
    // Initial state
    // =========================================================================

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `showDeleteDataDialog is initially false`() = runTest {
            val vm = createViewModel()
            vm.showDeleteDataDialog.first() shouldBe false
        }

        @Test
        fun `showDummyDataDialog is initially false`() = runTest {
            val vm = createViewModel()
            vm.showDummyDataDialog.first() shouldBe false
        }
    }

    // =========================================================================
    // Delete data dialog
    // =========================================================================

    @Nested
    @DisplayName("Delete data dialog")
    inner class DeleteDataDialog {

        @Test
        fun `showDeleteDataDialog sets state to true`() = runTest {
            val vm = createViewModel()
            vm.showDeleteDataDialog()
            vm.showDeleteDataDialog.first() shouldBe true
        }

        @Test
        fun `hideDeleteDataDialog sets state to false`() = runTest {
            val vm = createViewModel()
            vm.showDeleteDataDialog()
            vm.hideDeleteDataDialog()
            vm.showDeleteDataDialog.first() shouldBe false
        }

        @Test
        fun `show then hide then show toggles correctly`() = runTest {
            val vm = createViewModel()

            vm.showDeleteDataDialog()
            vm.showDeleteDataDialog.first() shouldBe true

            vm.hideDeleteDataDialog()
            vm.showDeleteDataDialog.first() shouldBe false

            vm.showDeleteDataDialog()
            vm.showDeleteDataDialog.first() shouldBe true
        }
    }

    // =========================================================================
    // Dummy data dialog
    // =========================================================================

    @Nested
    @DisplayName("Dummy data dialog")
    inner class DummyDataDialog {

        @Test
        fun `showDummyDataDialog sets state to true`() = runTest {
            val vm = createViewModel()
            vm.showDummyDataDialog()
            vm.showDummyDataDialog.first() shouldBe true
        }

        @Test
        fun `hideDummyDataDialog sets state to false`() = runTest {
            val vm = createViewModel()
            vm.showDummyDataDialog()
            vm.hideDummyDataDialog()
            vm.showDummyDataDialog.first() shouldBe false
        }

        @Test
        fun `show then hide then show toggles correctly`() = runTest {
            val vm = createViewModel()

            vm.showDummyDataDialog()
            vm.showDummyDataDialog.first() shouldBe true

            vm.hideDummyDataDialog()
            vm.showDummyDataDialog.first() shouldBe false

            vm.showDummyDataDialog()
            vm.showDummyDataDialog.first() shouldBe true
        }
    }

    // =========================================================================
    // Dialog independence
    // =========================================================================

    @Nested
    @DisplayName("Dialog independence")
    inner class DialogIndependence {

        @Test
        fun `showing delete dialog does not affect dummy dialog`() = runTest {
            val vm = createViewModel()
            vm.showDeleteDataDialog()
            vm.showDeleteDataDialog.first() shouldBe true
            vm.showDummyDataDialog.first() shouldBe false
        }

        @Test
        fun `showing dummy dialog does not affect delete dialog`() = runTest {
            val vm = createViewModel()
            vm.showDummyDataDialog()
            vm.showDummyDataDialog.first() shouldBe true
            vm.showDeleteDataDialog.first() shouldBe false
        }

        @Test
        fun `both dialogs can be shown independently`() = runTest {
            val vm = createViewModel()
            vm.showDeleteDataDialog()
            vm.showDummyDataDialog()
            vm.showDeleteDataDialog.first() shouldBe true
            vm.showDummyDataDialog.first() shouldBe true
        }

        @Test
        fun `hiding one dialog does not affect the other`() = runTest {
            val vm = createViewModel()
            vm.showDeleteDataDialog()
            vm.showDummyDataDialog()

            vm.hideDeleteDataDialog()
            vm.showDeleteDataDialog.first() shouldBe false
            vm.showDummyDataDialog.first() shouldBe true
        }
    }
}

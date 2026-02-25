package com.adsamcik.tracker.app.settings

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DebugSettingsViewModelTest {

    private fun createViewModel() = DebugSettingsViewModel()

    @Nested
    @DisplayName("Initial state")
    inner class InitialState {

        @Test
        fun `showDeleteDataDialog is initially false`() = runTest {
            val vm = createViewModel()
            vm.showDeleteDataDialog.first() shouldBe false
        }
    }

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
}

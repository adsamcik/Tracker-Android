package com.adsamcik.tracker.app.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.shared.base.R as BaseR
import com.adsamcik.tracker.shared.utils.compose.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Composable
fun TestDataScreen(
    viewModel: TestDataViewModel = hiltViewModel(),
) {
    if (!BuildConfig.DEBUG) return

    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var sessionCountInput by remember { mutableStateOf(DEFAULT_SESSION_COUNT.toString()) }
    var pendingAction by remember { mutableStateOf<TestDataAction?>(null) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("debug_test_data_screen"),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Test Data",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "DEBUG BUILD ONLY — seed synthetic local sessions or reset local state for QC.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = sessionCountInput,
                onValueChange = { value -> sessionCountInput = value.filter(Char::isDigit).take(3) },
                label = { Text("Seed N sessions") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debug_test_data_seed_count_input"),
            )

            Button(
                onClick = { viewModel.seedSessions(sessionCountInput) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debug_test_data_seed_btn"),
            ) {
                BusyAwareButtonText(state.isBusy, "Seed synthetic sessions")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.toggleHasTrackedFlag(true) },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("debug_test_data_has_tracked_true_btn"),
                ) {
                    Text("Set has tracked")
                }
                OutlinedButton(
                    onClick = { pendingAction = TestDataAction.ClearHasTracked },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("debug_test_data_has_tracked_false_btn"),
                ) {
                    Text("Clear history")
                }
            }

            OutlinedButton(
                onClick = { pendingAction = TestDataAction.ResetOnboarding },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debug_test_data_reset_onboarding_btn"),
            ) {
                Text("Reset onboarding")
            }

            OutlinedButton(
                onClick = { pendingAction = TestDataAction.ResetPreferences },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debug_test_data_reset_preferences_btn"),
            ) {
                Text("Reset all preferences")
            }

            OutlinedButton(
                onClick = { pendingAction = TestDataAction.ResetAllData },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("debug_test_data_reset_all_data_btn"),
            ) {
                Text("Reset all local test data")
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("debug_test_data_status"),
                )
            }
        }
    }

    val action = pendingAction
    ConfirmDialog(
        visible = action != null,
        title = action?.title,
        message = action?.message.orEmpty(),
        confirmLabel = context.getString(BaseR.string.generic_yes),
        dismissLabel = context.getString(BaseR.string.generic_no),
        onConfirm = {
            when (action) {
                TestDataAction.ResetOnboarding -> viewModel.resetOnboarding()
                TestDataAction.ResetPreferences -> viewModel.resetAllPreferences()
                TestDataAction.ResetAllData -> viewModel.resetAllData()
                TestDataAction.ClearHasTracked -> viewModel.toggleHasTrackedFlag(false)
                null -> Unit
            }
        },
        onDismiss = { pendingAction = null },
    )
}

@Composable
private fun BusyAwareButtonText(isBusy: Boolean, text: String) {
    if (isBusy) {
        CircularProgressIndicator(
            modifier = Modifier.padding(end = 8.dp),
            strokeWidth = 2.dp,
        )
    }
    Text(text)
}

@HiltViewModel
class TestDataViewModel @Inject constructor(
    private val seeder: TestDataSeeder,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TestDataUiState())
    val uiState: StateFlow<TestDataUiState> = _uiState.asStateFlow()

    fun seedSessions(countInput: String) {
        val count = countInput.toIntOrNull()?.coerceIn(1, MAX_SESSION_COUNT) ?: DEFAULT_SESSION_COUNT
        runSeederAction(successMessage = "Seeded $count synthetic session(s).") {
            seeder.seedSessions(count = count)
        }
    }

    fun resetOnboarding() {
        runSeederAction(successMessage = "Onboarding reset to incomplete.") {
            seeder.resetOnboarding()
        }
    }

    fun resetAllPreferences() {
        runSeederAction(successMessage = "Preferences/DataStore files reset. Relaunch if a screen cached old state.") {
            seeder.resetAllPreferences()
        }
    }

    fun resetAllData() {
        runSeederAction(successMessage = "Collected data and preferences reset.") {
            seeder.resetAllData()
        }
    }

    fun toggleHasTrackedFlag(value: Boolean) {
        val message = if (value) {
            "Dashboard history state enabled with one synthetic session."
        } else {
            "Dashboard history cleared."
        }
        runSeederAction(successMessage = message) {
            seeder.toggleHasTrackedFlag(value)
        }
    }

    private fun runSeederAction(
        successMessage: String,
        block: suspend () -> Unit,
    ) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }
            val result = runCatching { block() }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    message = result.fold(
                        onSuccess = { successMessage },
                        onFailure = { error -> "Debug action failed: ${error.message ?: error::class.simpleName}" },
                    ),
                )
            }
        }
    }
}

data class TestDataUiState(
    val isBusy: Boolean = false,
    val message: String? = null,
)

private enum class TestDataAction(
    val title: String,
    val message: String,
) {
    ResetOnboarding(
        title = "Reset onboarding?",
        message = "This marks onboarding incomplete for this debug build.",
    ),
    ResetPreferences(
        title = "Reset preferences?",
        message = "This clears local SharedPreferences and known Proto DataStore files.",
    ),
    ResetAllData(
        title = "Reset all local test data?",
        message = "This clears collected tracking data, preferences, and onboarding state.",
    ),
    ClearHasTracked(
        title = "Clear tracking history?",
        message = "This deletes collected local tracking data so the dashboard can show its empty state.",
    ),
}

private const val DEFAULT_SESSION_COUNT = 5
private const val MAX_SESSION_COUNT = 50

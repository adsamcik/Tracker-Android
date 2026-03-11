package com.adsamcik.tracker.app.activity.licenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.license.LicenseObject
import com.adsamcik.tracker.license.ResourceLicenseObject
import com.adsamcik.tracker.shared.utils.activity.ComposeDetailActivity
import de.psdev.licensesdialog.model.Notice
import org.json.JSONObject
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

class ThirdPartyLicensesActivity : ComposeDetailActivity() {

    override fun onConfigure(configuration: Configuration) {
        configuration.title = getString(R.string.settings_licenses_title)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        var uiState by remember { mutableStateOf<LicenseUiState>(LicenseUiState.Loading) }
        var selectedNotice by remember { mutableStateOf<Notice?>(null) }

        LaunchedEffect(Unit) {
            uiState = loadLicenseUiState()
        }

        when (val state = uiState) {
            LicenseUiState.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = getString(R.string.settings_licenses_loading),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            is LicenseUiState.Message -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            is LicenseUiState.Ready -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.settings_licenses_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    items(state.licenses, key = { it.name }) { license ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clickable { selectedNotice = license.notice },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = license.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                license.notice.license?.let { resolvedLicense ->
                                    val licenseName = resolvedLicense.getName()
                                    if (licenseName.isNotBlank()) {
                                        Text(
                                            text = licenseName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        selectedNotice?.let { notice ->
            AlertDialog(
                onDismissRequest = { selectedNotice = null },
                confirmButton = {
                    TextButton(onClick = { selectedNotice = null }) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                },
                title = {
                    Text(text = notice.name)
                },
                text = {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            notice.copyright?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            notice.url?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = notice.license?.readFullTextFromResources(context).orEmpty(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            )
        }
    }

    private fun loadLicenseUiState(): LicenseUiState {
        return try {
            val metadata = readRawResourceText(R.raw.third_party_license_metadata).trim()
            if (metadata.isBlank()) {
                LicenseUiState.Message(getString(R.string.settings_licenses_unavailable))
            } else if (metadata.startsWith("Licenses are only provided")) {
                LicenseUiState.Message(getString(R.string.settings_licenses_variant_unavailable))
            } else {
                val licenses = JSONObject(metadata)
                    .let { json ->
                        buildList {
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val name = keys.next()
                                val entry = json.getJSONObject(name)
                                add(
                                    ResourceLicenseObject(
                                        name = name,
                                        from = entry.getInt("start"),
                                        length = entry.getInt("length"),
                                        resources = resources
                                    )
                                )
                            }
                        }
                    }
                    .sortedBy { it.name.lowercase(Locale.getDefault()) }

                if (licenses.isEmpty()) {
                    LicenseUiState.Message(getString(R.string.settings_licenses_unavailable))
                } else {
                    LicenseUiState.Ready(licenses)
                }
            }
        } catch (_: Exception) {
            LicenseUiState.Message(getString(R.string.settings_licenses_unavailable))
        }
    }

    private fun readRawResourceText(rawRes: Int): String {
        return resources.openRawResource(rawRes).use { inputStream ->
            InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
                reader.readText()
            }
        }
    }
}

private sealed interface LicenseUiState {
    data object Loading : LicenseUiState
    data class Ready(val licenses: List<LicenseObject>) : LicenseUiState
    data class Message(val message: String) : LicenseUiState
}

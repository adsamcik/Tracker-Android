package com.adsamcik.tracker.app.activity.licenses

import androidx.annotation.RawRes
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.license.LicenseObject
import com.adsamcik.tracker.license.ResourceLicenseObject
import com.adsamcik.tracker.license.TraceboxThirdPartyNotice
import com.adsamcik.tracker.shared.utils.activity.ComposeDetailActivity
import org.json.JSONObject
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max

class ThirdPartyLicensesActivity : ComposeDetailActivity() {

    override fun onConfigure(configuration: Configuration) {
        configuration.title = getString(R.string.settings_licenses_title)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        var uiState by remember { mutableStateOf<LicenseUiState>(LicenseUiState.Loading) }
        var selectedLicenseName by rememberSaveable { mutableStateOf<String?>(null) }

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
                                .clickable { selectedLicenseName = license.name },
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

        val selectedNotice = (uiState as? LicenseUiState.Ready)
            ?.licenses
            ?.firstOrNull { it.name == selectedLicenseName }
            ?.notice

        selectedNotice?.let { notice ->
            val licenseText = remember(notice) {
                notice.license?.readFullTextFromResources(context).orEmpty().trim()
            }
            // The oss-licenses-plugin sometimes stores a URL (e.g.
            // https://api.github.com/licenses/apache-2.0) as the license "full text" when it
            // couldn't fetch the canonical SPDX body. Detect that shape so we can render a
            // friendlier fallback instead of a one-line dialog with just a URL.
            val licenseIsUrlOnly = remember(licenseText) {
                licenseText.isNotEmpty() &&
                    licenseText.lineSequence().all { it.isBlank() || it.trim().isHttpUrl() }
            }
            val resolvedLicenseName = notice.license?.getName().orEmpty().ifBlank {
                inferLicenseNameFromUrl(licenseText)
                    ?: inferLicenseNameFromUrl(notice.url.orEmpty())
                    ?: ""
            }
            AlertDialog(
                onDismissRequest = { selectedLicenseName = null },
                confirmButton = {
                    TextButton(onClick = { selectedLicenseName = null }) {
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
                            if (resolvedLicenseName.isNotBlank()) {
                                Text(
                                    text = resolvedLicenseName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            notice.url?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            when {
                                licenseText.isEmpty() -> Text(
                                    text = stringResource(R.string.settings_licenses_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                licenseIsUrlOnly -> Text(
                                    text = stringResource(R.string.settings_licenses_text_at_url, licenseText),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                else -> Text(
                                    text = licenseText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    private fun String.isHttpUrl(): Boolean {
        return startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
    }

    private fun inferLicenseNameFromUrl(url: String): String? {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            "apache-2.0" in lower || "apache2" in lower -> "Apache License 2.0"
            "mit" in lower -> "MIT License"
            "bsd-3" in lower -> "BSD 3-Clause License"
            "bsd-2" in lower -> "BSD 2-Clause License"
            "gpl-3" in lower -> "GNU GPL v3"
            "gpl-2" in lower -> "GNU GPL v2"
            "lgpl-3" in lower -> "GNU LGPL v3"
            "lgpl-2" in lower -> "GNU LGPL v2.1"
            "epl-2" in lower -> "Eclipse Public License 2.0"
            "mpl-2" in lower -> "Mozilla Public License 2.0"
            else -> null
        }
    }

    private fun loadLicenseUiState(): LicenseUiState {
        val traceboxNotice = runCatching {
            TraceboxThirdPartyNotice.load(resources)
        }.getOrElse {
            return LicenseUiState.Message(getString(R.string.settings_licenses_unavailable))
        }
        val bundledLicenses = loadBundledLicenses(
            metadataRawRes = R.raw.third_party_license_metadata,
            licenseTextRawRes = R.raw.third_party_licenses
        )
        if (bundledLicenses != null) {
            return LicenseUiState.Ready(withTraceboxNotice(bundledLicenses, traceboxNotice))
        }

        // Debug/source variants may intentionally omit the generated Maven license catalog, but
        // the notices for Tracebox's embedded native binary remain mandatory and always visible.
        return LicenseUiState.Ready(listOf(traceboxNotice))
    }

    private fun withTraceboxNotice(
        licenses: List<LicenseObject>,
        traceboxNotice: LicenseObject
    ): List<LicenseObject> = (licenses + traceboxNotice)
        .distinctBy { it.name }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

    private fun loadBundledLicenses(
        @RawRes metadataRawRes: Int,
        @RawRes licenseTextRawRes: Int
    ): List<LicenseObject>? {
        return runCatching {
            val metadata = readRawResourceText(metadataRawRes).trim()
            val licenseText = readRawResourceText(licenseTextRawRes).trim()
            if (metadata.isBlank() || licenseText.isBlank() || isVariantPlaceholder(licenseText)) {
                return null
            }

            val parsedLicenses = if (metadata.startsWith("{")) {
                parseJsonLicenseMetadata(metadata, licenseTextRawRes)
            } else {
                parseLineLicenseMetadata(metadata, licenseTextRawRes)
            }

            parsedLicenses
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun parseJsonLicenseMetadata(
        metadata: String,
        @RawRes licenseTextRawRes: Int
    ): List<LicenseObject> {
        return JSONObject(metadata).let { json ->
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
                            resources = resources,
                            licenseTextRawRes = licenseTextRawRes
                        )
                    )
                }
            }
        }
    }

    private fun parseLineLicenseMetadata(
        metadata: String,
        @RawRes licenseTextRawRes: Int
    ): List<LicenseObject> {
        return metadata.lineSequence()
            .mapNotNull { line ->
                LICENSE_METADATA_LINE.matchEntire(line.trim())?.destructured?.let { (start, length, name) ->
                    ResourceLicenseObject(
                        name = name,
                        from = start.toInt(),
                        length = max(length.toInt(), 0),
                        resources = resources,
                        licenseTextRawRes = licenseTextRawRes
                    )
                }
            }
            .toList()
    }

    private fun isVariantPlaceholder(licenseText: String): Boolean {
        return licenseText.startsWith(PLACEHOLDER_LICENSE_PREFIX, ignoreCase = true)
    }

    private fun readRawResourceText(rawRes: Int): String {
        return resources.openRawResource(rawRes).use { inputStream ->
            InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
                reader.readText()
            }
        }
    }

    private companion object {
        val LICENSE_METADATA_LINE = Regex("""^(\d+):(\d+)\s+(.+)$""")
        const val PLACEHOLDER_LICENSE_PREFIX =
            "Licenses are only provided in build variants"
    }
}

private sealed interface LicenseUiState {
    data object Loading : LicenseUiState
    data class Ready(val licenses: List<LicenseObject>) : LicenseUiState
    data class Message(val message: String) : LicenseUiState
}

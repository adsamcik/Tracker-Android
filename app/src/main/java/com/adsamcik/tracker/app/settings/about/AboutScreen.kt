package com.adsamcik.tracker.app.settings.about

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.licenses.ThirdPartyLicensesActivity
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.PrivacyPolicyDialog
import com.adsamcik.tracker.app.settings.components.SettingsRowDivider
import com.adsamcik.tracker.app.settings.components.SettingsSummaryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    var showPrivacyPolicy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_navigate_back),
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .testTag("about_screen"),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_title),
                    icon = Icons.Default.Info,
                ) {
                    SettingsSummaryItem(
                        title = stringResource(R.string.about_app_name_label),
                        value = stringResource(R.string.settings_about_app_title),
                        icon = Icons.Default.Info,
                        modifier = Modifier.testTag("about_screen_app_name"),
                    )
                    SettingsRowDivider()
                    SettingsSummaryItem(
                        title = stringResource(R.string.about_version_label),
                        value = stringResource(
                            R.string.about_version_format,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        modifier = Modifier.testTag("about_screen_version"),
                    )
                    SettingsRowDivider()
                    SettingsSummaryItem(
                        title = stringResource(R.string.about_build_commit_label),
                        value = buildCommitOrNull() ?: stringResource(R.string.about_commit_unavailable),
                        icon = Icons.Default.Code,
                        modifier = Modifier.testTag("about_screen_build_commit"),
                    )
                }
            }

            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_author),
                    icon = Icons.Default.Person,
                ) {
                    SettingsSummaryItem(
                        title = stringResource(R.string.about_author),
                        value = stringResource(R.string.about_author_value),
                        icon = Icons.Default.Person,
                        modifier = Modifier.testTag("about_screen_author"),
                    )
                    SettingsRowDivider()
                    SettingsSummaryItem(
                        title = stringResource(R.string.about_email),
                        value = stringResource(R.string.about_email_value),
                        icon = Icons.Default.Email,
                        modifier = Modifier.testTag("about_screen_email"),
                    )
                    SettingsRowDivider()
                    SettingsSummaryItem(
                        title = stringResource(R.string.about_github),
                        value = stringResource(R.string.about_github_value),
                        icon = Icons.Default.Code,
                        modifier = Modifier.testTag("about_screen_github_link"),
                    )
                }
            }

            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_attributions_title),
                    icon = Icons.Default.Place,
                ) {
                    SettingsSummaryItem(
                        title = stringResource(R.string.about_attribution_geonames_label),
                        value = stringResource(R.string.about_attribution_geonames_value),
                        icon = Icons.Default.Place,
                        modifier = Modifier.testTag("about_screen_attribution_geonames"),
                    )
                    SettingsRowDivider()
                    SettingsSummaryItem(
                        title = stringResource(R.string.about_attribution_osm_label),
                        value = stringResource(R.string.about_attribution_osm_value),
                        icon = Icons.Default.Map,
                        modifier = Modifier.testTag("about_screen_attribution_osm"),
                    )
                }
            }

            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_links_title),
                    icon = Icons.AutoMirrored.Filled.Article,
                ) {
                    SettingsItem(
                        title = stringResource(R.string.settings_privacy_policy_title),
                        icon = Icons.Default.PrivacyTip,
                        modifier = Modifier.testTag("about_screen_privacy_policy"),
                        onClick = { showPrivacyPolicy = true },
                    )
                    SettingsRowDivider()
                    SettingsItem(
                        title = stringResource(R.string.settings_licenses_title),
                        icon = Icons.AutoMirrored.Filled.Article,
                        modifier = Modifier.testTag("about_screen_open_source_licenses"),
                        onClick = {
                            context.startActivity(Intent(context, ThirdPartyLicensesActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(
            onDismissRequest = { showPrivacyPolicy = false },
        )
    }
}

private fun buildCommitOrNull(): String? = runCatching {
    BuildConfig::class.java.getField("GIT_COMMIT").get(null) as? String
}.getOrNull()
    ?.takeIf { it.isNotBlank() }

package com.adsamcik.tracker.app.settings.about

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
                ) {
                    AboutValue(
                        label = stringResource(R.string.about_app_name_label),
                        value = stringResource(R.string.settings_about_app_title),
                        modifier = Modifier.testTag("about_screen_app_name"),
                    )
                    AboutValue(
                        label = stringResource(R.string.about_version_label),
                        value = stringResource(
                            R.string.about_version_format,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                        modifier = Modifier.testTag("about_screen_version"),
                    )
                    AboutValue(
                        label = stringResource(R.string.about_build_commit_label),
                        value = buildCommitOrNull() ?: stringResource(R.string.about_commit_unavailable),
                        modifier = Modifier.testTag("about_screen_build_commit"),
                    )
                }
            }

            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_author),
                ) {
                    AboutValue(
                        label = stringResource(R.string.about_author),
                        value = stringResource(R.string.about_author_value),
                        icon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                        modifier = Modifier.testTag("about_screen_author"),
                    )
                    AboutValue(
                        label = stringResource(R.string.about_email),
                        value = stringResource(R.string.about_email_value),
                        icon = {
                            Icon(Icons.Default.Email, contentDescription = null)
                        },
                        modifier = Modifier.testTag("about_screen_email"),
                    )
                    AboutValue(
                        label = stringResource(R.string.about_github),
                        value = stringResource(R.string.about_github_value),
                        icon = {
                            Icon(Icons.Default.Code, contentDescription = null)
                        },
                        modifier = Modifier.testTag("about_screen_github_link"),
                    )
                }
            }

            item {
                SettingsGroupCard(
                    title = stringResource(R.string.about_links_title),
                ) {
                    SettingsItem(
                        title = stringResource(R.string.settings_privacy_policy_title),
                        icon = Icons.Default.PrivacyTip,
                        modifier = Modifier.testTag("about_screen_privacy_policy"),
                        onClick = { showPrivacyPolicy = true },
                    )
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

@Composable
private fun AboutValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon?.invoke() ?: Icon(Icons.Default.Info, contentDescription = null)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun buildCommitOrNull(): String? = runCatching {
    BuildConfig::class.java.getField("GIT_COMMIT").get(null) as? String
}.getOrNull()
    ?.takeIf { it.isNotBlank() }

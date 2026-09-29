package com.adsamcik.tracker.app.settings.tracking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.adsamcik.tracker.app.settings.components.PrivacyPolicyDialog
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme

/** Opens the same bundled privacy policy used by onboarding and Settings. */
class HealthConnectPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                PrivacyPolicyDialog(onDismissRequest = ::finish)
            }
        }
    }
}

package com.adsamcik.tracker.app.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.tracking.HealthConnectPermissionsRationaleActivity
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectManifestTest {
    @Test fun readStepsAndRationaleAreDeclaredForPermissionRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        packageInfo.requestedPermissions.orEmpty()
            .contains("android.permission.health.READ_STEPS") shouldBe true
        val rationale = context.packageManager.getActivityInfo(
            ComponentName(context, HealthConnectPermissionsRationaleActivity::class.java),
            0,
        )
        rationale.exported shouldBe true
        val permissionUsage = context.packageManager.getActivityInfo(
            ComponentName(
                context.packageName,
                "com.adsamcik.tracker.app.settings.tracking.HealthConnectPermissionUsageActivity",
            ),
            0,
        )
        permissionUsage.exported shouldBe true
        permissionUsage.permission shouldBe "android.permission.START_VIEW_PERMISSION_USAGE"
        context.resources.getStringArray(R.array.health_permissions).toList() shouldBe
            listOf("androidx.health.permission.Steps.READ")
    }
}

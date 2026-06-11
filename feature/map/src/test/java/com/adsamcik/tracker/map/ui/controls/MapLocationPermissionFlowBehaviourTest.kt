package com.adsamcik.tracker.map.ui.controls

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLocationPermissionFlowBehaviourTest {

	@Test
	fun `has permission is already granted without system dialog or settings`() {
		val activity = permissionActivity(
			granted = true,
			shouldShowRationale = false,
		)

		resolveMapLocationPermissionRequestDecision(
			hasPermission = activity.context.hasFineOrCoarseLocationPermission(),
		) shouldBe MapLocationPermissionDecision.AlreadyGranted
	}

	@Test
	fun `never requested location permission requests system dialog instead of opening settings`() {
		val activity = permissionActivity(
			granted = false,
			shouldShowRationale = false,
		)

		activity.shouldShowRationale shouldBe false
		resolveMapLocationPermissionRequestDecision(
			hasPermission = activity.context.hasFineOrCoarseLocationPermission(),
		) shouldBe MapLocationPermissionDecision.RequestSystemDialog
	}

	@Test
	fun `denied once with rationale still requests system dialog`() {
		val activity = permissionActivity(
			granted = false,
			shouldShowRationale = true,
		)

		activity.shouldShowRationale shouldBe true
		resolveMapLocationPermissionRequestDecision(
			hasPermission = activity.context.hasFineOrCoarseLocationPermission(),
		) shouldBe MapLocationPermissionDecision.RequestSystemDialog
	}

	@Test
	fun `denied callback with rationale remains in request-system-dialog state`() {
		val activity = permissionActivity(
			granted = false,
			shouldShowRationale = true,
		)

		resolveMapLocationPermissionCallbackDecision(
			granted = false,
			shouldShowRationale = activity.shouldShowRationale,
		) shouldBe MapLocationPermissionDecision.RequestSystemDialog
	}

	@Test
	fun `permanent denial callback opens settings only after a denied launcher result`() {
		val activity = permissionActivity(
			granted = false,
			shouldShowRationale = false,
		)

		resolveMapLocationPermissionCallbackDecision(
			granted = false,
			shouldShowRationale = activity.shouldShowRationale,
		) shouldBe MapLocationPermissionDecision.OpenSettings
	}

	private fun permissionActivity(
		granted: Boolean,
		shouldShowRationale: Boolean,
	): PermissionFixture {
		val context = ApplicationProvider.getApplicationContext<Context>()
		val permissions = arrayOf(
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		)
		val shadowApplication = shadowOf(context.applicationContext as Application)
		if (granted) {
			shadowApplication.grantPermissions(*permissions)
		} else {
			shadowApplication.denyPermissions(*permissions)
		}
		val shadowPackageManager = shadowOf(context.packageManager)
		permissions.forEach { permission ->
			shadowPackageManager.setShouldShowRequestPermissionRationale(permission, shouldShowRationale)
		}
		return PermissionFixture(
			context = context,
			shouldShowRationale = shouldShowRationale,
		)
	}

	private data class PermissionFixture(
		val context: Context,
		val shouldShowRationale: Boolean,
	)

	private fun Context.hasFineOrCoarseLocationPermission(): Boolean =
		ContextCompat.checkSelfPermission(
			this,
			Manifest.permission.ACCESS_FINE_LOCATION,
		) == PackageManager.PERMISSION_GRANTED ||
			ContextCompat.checkSelfPermission(
				this,
				Manifest.permission.ACCESS_COARSE_LOCATION,
			) == PackageManager.PERMISSION_GRANTED

}

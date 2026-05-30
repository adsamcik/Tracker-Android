package com.adsamcik.tracker.map.ui.controls

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
@DisplayName("Map location permission flow decisions")
class MapLocationPermissionFlowBehaviourTest {

	@Test
	fun `has permission is already granted without system dialog or settings`() {
		val activity = permissionActivity(
			granted = true,
			shouldShowRationale = false,
		)

		resolveMapLocationPermissionRequestDecision(
			hasPermission = activity.hasFineOrCoarseLocationPermission(),
		) shouldBe MapLocationPermissionDecision.AlreadyGranted
	}

	@Test
	fun `never requested location permission requests system dialog instead of opening settings`() {
		val activity = permissionActivity(
			granted = false,
			shouldShowRationale = false,
		)

		activity.shouldShowAnyMapLocationRationale() shouldBe false
		resolveMapLocationPermissionRequestDecision(
			hasPermission = activity.hasFineOrCoarseLocationPermission(),
		) shouldBe MapLocationPermissionDecision.RequestSystemDialog
	}

	@Test
	fun `denied once with rationale still requests system dialog`() {
		val activity = permissionActivity(
			granted = false,
			shouldShowRationale = true,
		)

		activity.shouldShowAnyMapLocationRationale() shouldBe true
		resolveMapLocationPermissionRequestDecision(
			hasPermission = activity.hasFineOrCoarseLocationPermission(),
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
			shouldShowRationale = activity.shouldShowAnyMapLocationRationale(),
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
			shouldShowRationale = activity.shouldShowAnyMapLocationRationale(),
		) shouldBe MapLocationPermissionDecision.OpenSettings
	}

	private fun permissionActivity(
		granted: Boolean,
		shouldShowRationale: Boolean,
	): Activity {
		val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
		val permissions = arrayOf(
			Manifest.permission.ACCESS_FINE_LOCATION,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		)
		val shadowApplication = shadowOf(activity.application)
		val shadowActivity = shadowOf(activity)
		if (granted) {
			shadowApplication.grantPermissions(*permissions)
			shadowActivity.grantPermissions(*permissions)
		} else {
			shadowApplication.denyPermissions(*permissions)
			shadowActivity.denyPermissions(*permissions)
		}
		val shadowPackageManager = shadowOf(activity.packageManager)
		permissions.forEach { permission ->
			shadowPackageManager.setShouldShowRequestPermissionRationale(permission, shouldShowRationale)
		}
		return activity
	}

	private fun Context.hasFineOrCoarseLocationPermission(): Boolean =
		ContextCompat.checkSelfPermission(
			this,
			Manifest.permission.ACCESS_FINE_LOCATION,
		) == PackageManager.PERMISSION_GRANTED ||
			ContextCompat.checkSelfPermission(
				this,
				Manifest.permission.ACCESS_COARSE_LOCATION,
			) == PackageManager.PERMISSION_GRANTED

	private fun Activity.shouldShowAnyMapLocationRationale(): Boolean =
		ActivityCompat.shouldShowRequestPermissionRationale(
			this,
			Manifest.permission.ACCESS_FINE_LOCATION,
		) || ActivityCompat.shouldShowRequestPermissionRationale(
			this,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		)
}

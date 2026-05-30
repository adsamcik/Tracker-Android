package com.adsamcik.tracker.map.ui.controls

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adsamcik.tracker.map.R

@Stable
internal class MapLocationPermissionFlow(
	val isGranted: Boolean,
	val requestLocationAccess: () -> Unit,
)

@Composable
internal fun rememberMapLocationPermissionFlow(
	onPermissionGranted: () -> Unit = {},
): MapLocationPermissionFlow {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val currentOnPermissionGranted by rememberUpdatedState(onPermissionGranted)
	var isGranted by rememberSaveable { mutableStateOf(context.hasMapLocationPermission()) }

	fun updateGranted(granted: Boolean) {
		if (granted && !isGranted) {
			currentOnPermissionGranted()
		}
		isGranted = granted
	}

	fun openSettings() {
		Toast.makeText(
			context,
			context.getString(R.string.map_location_permission_opening_settings),
			Toast.LENGTH_SHORT,
		).show()
		val intent = Intent(
			Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
			Uri.fromParts("package", context.packageName, null),
		)
		if (context !is Activity) {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(intent)
	}

	val permissionLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions(),
	) { result ->
		val granted = result.values.any { it }
		updateGranted(granted)
		when (
			resolveMapLocationPermissionCallbackDecision(
				granted = granted,
				shouldShowRationale = context.findActivity()?.shouldShowMapLocationRationale(),
			)
		) {
			MapLocationPermissionDecision.AlreadyGranted -> Unit
			MapLocationPermissionDecision.RequestSystemDialog -> Unit
			MapLocationPermissionDecision.OpenSettings -> openSettings()
		}
	}

	DisposableEffect(lifecycleOwner, context) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				updateGranted(context.hasMapLocationPermission())
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}

	val requestLocationAccess = remember(context) {
		{
			when (resolveMapLocationPermissionRequestDecision(context.hasMapLocationPermission())) {
				MapLocationPermissionDecision.AlreadyGranted -> updateGranted(true)
				MapLocationPermissionDecision.OpenSettings -> openSettings()
				MapLocationPermissionDecision.RequestSystemDialog -> {
					permissionLauncher.launch(
						arrayOf(
							Manifest.permission.ACCESS_FINE_LOCATION,
							Manifest.permission.ACCESS_COARSE_LOCATION,
						)
					)
				}
			}
		}
	}

	return MapLocationPermissionFlow(
		isGranted = isGranted,
		requestLocationAccess = requestLocationAccess,
	)
}

private fun Context.hasMapLocationPermission(): Boolean =
	ContextCompat.checkSelfPermission(
		this,
		Manifest.permission.ACCESS_FINE_LOCATION,
	) == PackageManager.PERMISSION_GRANTED ||
		ContextCompat.checkSelfPermission(
			this,
			Manifest.permission.ACCESS_COARSE_LOCATION,
		) == PackageManager.PERMISSION_GRANTED

private fun Activity.shouldShowMapLocationRationale(): Boolean =
	ActivityCompat.shouldShowRequestPermissionRationale(
		this,
		Manifest.permission.ACCESS_FINE_LOCATION,
	) || ActivityCompat.shouldShowRequestPermissionRationale(
		this,
		Manifest.permission.ACCESS_COARSE_LOCATION,
	)

internal enum class MapLocationPermissionDecision {
	RequestSystemDialog,
	OpenSettings,
	AlreadyGranted,
}

internal fun resolveMapLocationPermissionRequestDecision(
	hasPermission: Boolean,
): MapLocationPermissionDecision =
	if (hasPermission) {
		MapLocationPermissionDecision.AlreadyGranted
	} else {
		MapLocationPermissionDecision.RequestSystemDialog
	}

internal fun resolveMapLocationPermissionCallbackDecision(
	granted: Boolean,
	shouldShowRationale: Boolean?,
): MapLocationPermissionDecision = when {
	granted -> MapLocationPermissionDecision.AlreadyGranted
	shouldShowRationale == false -> MapLocationPermissionDecision.OpenSettings
	else -> MapLocationPermissionDecision.RequestSystemDialog
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
	is Activity -> this
	is ContextWrapper -> baseContext.findActivity()
	else -> null
}

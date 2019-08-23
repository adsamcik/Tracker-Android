package com.adsamcik.tracker.common.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.CallSuper
import com.adsamcik.tracker.common.extension.hasLocationPermission
import com.adsamcik.tracker.common.style.StyleController
import com.adsamcik.tracker.common.style.StyleManager
import com.google.android.gms.location.LocationServices

abstract class CoreUIActivity : CoreActivity() {
	private var themeLocationRequestCode = 4513

	protected val styleController: StyleController = StyleManager.createController()

	@CallSuper
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		initializeColors()
	}

	@CallSuper
	override fun onDestroy() {
		StyleManager.recycleController(styleController)
		super.onDestroy()
	}

	@CallSuper
	override fun onPause() {
		styleController.isSuspended = true
		super.onPause()
	}

	@CallSuper
	override fun onResume() {
		styleController.isSuspended = false
		super.onResume()
	}

	private fun initializeColors() {
		StyleManager.initializeFromPreferences(this)
		initializeSunriseSunset()
	}

	@CallSuper
	override fun onRequestPermissionsResult(
			requestCode: Int,
			permissions: Array<out String>,
			grantResults: IntArray
	) {
		if (requestCode == themeLocationRequestCode) {
			if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
				initializeSunriseSunset()
			}
		}
	}

	private fun initializeSunriseSunset() {
		val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

		if (hasLocationPermission) {
			fusedLocationClient.lastLocation.addOnCompleteListener {
				if (it.isSuccessful) {
					val loc = it.result
					if (loc != null) StyleManager.setLocation(loc)
				}
			}
		} else if (Build.VERSION.SDK_INT >= 23) {
			requestPermissions(
					arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
					themeLocationRequestCode
			)
		}
	}
}

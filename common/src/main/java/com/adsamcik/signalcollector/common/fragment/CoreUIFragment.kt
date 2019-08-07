package com.adsamcik.signalcollector.common.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.CallSuper
import com.adsamcik.signalcollector.common.extension.hasLocationPermission
import com.adsamcik.signalcollector.common.style.StyleController
import com.adsamcik.signalcollector.common.style.StyleManager
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

abstract class CoreUIFragment : CoreFragment() {
	private var themeLocationRequestCode = 4513
	private val job = SupervisorJob()

	protected val styleController: StyleController = StyleManager.createController()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

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

	private fun initializeColors() {
		//StyleManager.initializeFromPreferences(requireContext())
		//initializeSunriseSunset()
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

	@CallSuper
	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		if (requestCode == themeLocationRequestCode) {
			if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
				initializeSunriseSunset()
			}
		}
	}

	private fun initializeSunriseSunset() {
		val context = requireContext()
		val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

		if (context.hasLocationPermission) {
			fusedLocationClient.lastLocation.addOnCompleteListener {
				if (it.isSuccessful) {
					it.result?.let { location -> StyleManager.setLocation(location) }
				}
			}
		} else if (Build.VERSION.SDK_INT >= 23) {
			requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), themeLocationRequestCode)
		}
	}
}
package com.adsamcik.signalcollector.common.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorManager
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

abstract class CoreUIActivity : CoreActivity() {
	private var themeLocationRequestCode = 4513
	private val job = SupervisorJob()

	protected val colorController: ColorController = ColorManager.createController()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	@CallSuper
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		initializeColors()
	}

	@CallSuper
	override fun onDestroy() {
		ColorManager.recycleController(colorController)
		super.onDestroy()
	}

	private fun initializeColors() {
		ColorManager.initializeFromPreferences(this)
		initializeSunriseSunset()
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
		val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
			fusedLocationClient.lastLocation.addOnCompleteListener {
				if (it.isSuccessful) {
					val loc = it.result
					if (loc != null) ColorManager.setLocation(loc)
				}
			}
		} else if (Build.VERSION.SDK_INT >= 23) {
			requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), themeLocationRequestCode)
		}
	}
}
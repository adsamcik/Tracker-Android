package com.adsamcik.signalcollector.app.activity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.adsamcik.draggable.*
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.app.Tips
import com.adsamcik.signalcollector.common.Constants
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.misc.extension.dpAsPx
import com.adsamcik.signalcollector.common.misc.extension.guidelineEnd
import com.adsamcik.signalcollector.common.misc.extension.transaction
import com.adsamcik.signalcollector.common.misc.keyboard.NavBarPosition
import com.adsamcik.signalcollector.module.Module
import com.adsamcik.signalcollector.module.PayloadFragment
import com.adsamcik.signalcollector.notification.NotificationChannels
import com.adsamcik.signalcollector.tracker.fragment.FragmentTracker
import com.google.android.gms.location.LocationServices
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import kotlinx.android.synthetic.main.activity_ui.*


/**
 * MainActivity containing the core of the App
 * Users should spend most time in here.
 */
class MainActivity : AppCompatActivity() {
	private lateinit var colorController: ColorController
	private var themeLocationRequestCode = 4513

	private var navigationOffset = Int.MIN_VALUE

	private lateinit var trackerFragment: androidx.fragment.app.Fragment


	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_ui)

		if (Build.VERSION.SDK_INT >= 26)
			NotificationChannels.prepareChannels(this)

		if (Assist.checkPlayServices(this))
			ActivityService.requestAutoTracking(this, this::class)

		initializeColors()
		initializeButtons()
		initializeColorElements()

		trackerFragment = FragmentTracker()
		supportFragmentManager.transaction {
			replace(R.id.root, trackerFragment)
		}
	}

	override fun onStart() {
		super.onStart()
		root.post {
			Tips.showTips(this, Tips.HOME_TIPS) {}
		}
	}

	override fun onResume() {
		super.onResume()
		initializeButtonsPosition()
		initializeColors()
	}

	private fun initializeButtons() {
		val display = windowManager.defaultDisplay
		val realSize = Point()
		val size = Point()
		display.getRealSize(realSize)
		display.getSize(size)

		val splitInstallManager = SplitInstallManagerFactory.create(this)
		val installedModules = splitInstallManager.installedModules

		if (installedModules.contains(Module.STATISTICS.moduleName)) {
			val fragmentStatsClass = Module.STATISTICS.loadClass<PayloadFragment>("fragment.FragmentStats")

			button_stats.visibility = View.VISIBLE
			button_stats.dragAxis = DragAxis.X
			button_stats.setTarget(root, DragTargetAnchor.RightTop)
			button_stats.setTargetOffsetDp(Offset(56))
			button_stats.targetTranslationZ = 8.dpAsPx.toFloat()
			button_stats.extendTouchAreaBy(56.dpAsPx, 0, 0, 0)
			button_stats.onEnterStateListener = { _, state, _, _ ->
				if (state == DraggableImageButton.State.TARGET)
					hideBottomLayer()
			}
			button_stats.onLeaveStateListener = { _, state ->
				if (state == DraggableImageButton.State.TARGET)
					showBottomLayer()
			}

			val statsPayload = DraggablePayload(this, fragmentStatsClass, root, root)
			statsPayload.width = MATCH_PARENT
			statsPayload.height = MATCH_PARENT
			statsPayload.initialTranslation = Point(-size.x, 0)
			statsPayload.backgroundColor = Color.WHITE
			statsPayload.targetTranslationZ = 7.dpAsPx.toFloat()
			statsPayload.destroyPayloadAfter = 15 * Constants.SECOND_IN_MILLISECONDS
			button_stats.addPayload(statsPayload)
		} else {
			button_stats.visibility = View.GONE
		}

		if (installedModules.contains(Module.GAME.moduleName)) {
			button_game.visibility = View.VISIBLE
			button_game.dragAxis = DragAxis.X
			button_game.setTarget(root, DragTargetAnchor.LeftTop)
			button_game.setTargetOffsetDp(Offset(-56))
			button_game.targetTranslationZ = 8.dpAsPx.toFloat()
			button_game.extendTouchAreaBy(0, 0, 56.dpAsPx, 0)
			button_game.onEnterStateListener = { _, state, _, _ ->
				if (state == DraggableImageButton.State.TARGET)
					hideBottomLayer()
			}
			button_game.onLeaveStateListener = { _, state ->
				if (state == DraggableImageButton.State.TARGET)
					showBottomLayer()
			}

			val fragmentGameClass = Module.GAME.loadClass<PayloadFragment>("fragment.FragmentGame")

			val gamePayload = DraggablePayload(this, fragmentGameClass, root, root)
			gamePayload.width = MATCH_PARENT
			gamePayload.height = MATCH_PARENT
			gamePayload.initialTranslation = Point(size.x, 0)
			gamePayload.backgroundColor = Color.WHITE
			gamePayload.targetTranslationZ = 7.dpAsPx.toFloat()
			gamePayload.destroyPayloadAfter = 15 * Constants.SECOND_IN_MILLISECONDS
			//gamePayload.onInitialized = { colorController.watchView(ColorView(it.view!!, 1, recursive = true, rootIsBackground = true)) }

			button_game.addPayload(gamePayload)
		} else {
			button_game.visibility = View.GONE
		}


		if (installedModules.contains(Module.MAP.moduleName)) {
			button_map.visibility = View.VISIBLE
			button_map.extendTouchAreaBy(32.dpAsPx)
			button_map.onEnterStateListener = { _, state, _, _ ->
				if (state == DraggableImageButton.State.TARGET) {
					hideBottomLayer()
					hideMiddleLayer()
				}
			}
			button_map.onLeaveStateListener = { _, state ->
				if (state == DraggableImageButton.State.TARGET) {
					if (button_game.state != DraggableImageButton.State.TARGET && button_stats.state != DraggableImageButton.State.TARGET)
						showBottomLayer()

					showMiddleLayer()
				}
			}

			val fragmentMapClass = Module.MAP.loadClass<PayloadFragment>("fragment.FragmentMap")

			val mapPayload = DraggablePayload(this, fragmentMapClass, root, root)
			mapPayload.width = MATCH_PARENT
			mapPayload.height = MATCH_PARENT
			mapPayload.initialTranslation = Point(0, realSize.y)
			mapPayload.backgroundColor = Color.WHITE
			mapPayload.setTranslationZ(16.dpAsPx.toFloat())
			mapPayload.destroyPayloadAfter = 30 * Constants.SECOND_IN_MILLISECONDS

			button_map.addPayload(mapPayload)
		} else {
			button_map.visibility = View.GONE
		}

		//todo fix behavior for snackbar, currently it does not work properly with guideline for some reason
		/*val params = root.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
		params.behavior = NavigationGuidelinesOffsetBehavior(navigation_guideline)
		root.layoutParams = params
		root.requestLayout()*/
	}

	private fun hideBottomLayer() {
		trackerFragment.view?.visibility = View.GONE
	}

	private fun showBottomLayer() {
		trackerFragment.view?.visibility = View.VISIBLE
	}

	private fun hideMiddleLayer() {
		button_game.visibility = View.GONE
		button_stats.visibility = View.GONE

		if (button_stats.state == DraggableImageButton.State.TARGET)
			button_stats.payloads.forEach { it.wrapper?.visibility = View.GONE }

		if (button_game.state == DraggableImageButton.State.TARGET)
			button_game.payloads.forEach { it.wrapper?.visibility = View.GONE }
	}

	private fun showMiddleLayer() {
		button_game.visibility = View.VISIBLE
		button_stats.visibility = View.VISIBLE

		if (button_stats.state == DraggableImageButton.State.TARGET)
			button_stats.payloads.forEach { it.wrapper?.visibility = View.VISIBLE }

		if (button_game.state == DraggableImageButton.State.TARGET)
			button_game.payloads.forEach { it.wrapper?.visibility = View.VISIBLE }
	}

	private fun initializeButtonsPosition() {
		if (navigationOffset == Int.MIN_VALUE) {
			navigationOffset = navigation_guideline.guidelineEnd
		}

		val (position, navDim) = Assist.navbarSize(this)
		if (navDim.x > navDim.y)
			navDim.x = 0
		else
			navDim.y = 0

		navigation_guideline.setGuidelineEnd(navigationOffset + navDim.y)

		when (position) {
			NavBarPosition.RIGHT -> root.setPadding(0, 0, navDim.x, 0)
			NavBarPosition.LEFT -> root.setPadding(navDim.x, 0, 0, 0)
			else -> root.setPadding(0, 0, 0, 0)
		}
	}

	private fun initializeColorElements() {
		colorController = ColorManager.createController()

		colorController.watchView(ColorView(root, 0, recursive = false, rootIsBackground = true, ignoreRoot = false))

		colorController.watchView(ColorView(button_stats, 1, recursive = false, rootIsBackground = false, ignoreRoot = false, backgroundIsForeground = true))
		colorController.watchView(ColorView(button_map, 1, recursive = false, rootIsBackground = false, ignoreRoot = false, backgroundIsForeground = true))
		colorController.watchView(ColorView(button_game, 1, recursive = false, rootIsBackground = false, ignoreRoot = false, backgroundIsForeground = true))

		ColorManager.ensureUpdate()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)

		button_map.saveFragments(outState)
		button_stats.saveFragments(outState)
		button_game.saveFragments(outState)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)

		button_map.restoreFragments(savedInstanceState)
		button_stats.restoreFragments(savedInstanceState)
		button_game.restoreFragments(savedInstanceState)
	}

	override fun onDestroy() {
		super.onDestroy()
		ColorManager.recycleController(colorController)
	}

	private fun initializeColors() {
		ColorManager.initializeFromPreferences(this)
		initializeSunriseSunset()
	}

	private fun initializeSunriseSunset() {
		val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
			fusedLocationClient.lastLocation.addOnCompleteListener {
				if (it.isSuccessful) {
					val loc = it.result
					if (loc != null)
						ColorManager.setLocation(loc)
				}
			}
		} else if (Build.VERSION.SDK_INT >= 23)
			requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), themeLocationRequestCode)
	}

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		return if (!Tips.isActive && root.touchDelegate.onTouchEvent(event))
			true
		else
			super.dispatchTouchEvent(event)
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		if (requestCode == themeLocationRequestCode) {
			if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
				initializeSunriseSunset()
		}
	}

	override fun onBackPressed() {
		when {
			button_map.state == DraggableImageButton.State.TARGET -> button_map.moveToState(DraggableImageButton.State.INITIAL, true)
			button_stats.state == DraggableImageButton.State.TARGET -> button_stats.moveToState(DraggableImageButton.State.INITIAL, true)
			button_game.state == DraggableImageButton.State.TARGET -> button_game.moveToState(DraggableImageButton.State.INITIAL, true)
			else -> super.onBackPressed()
		}
	}
}

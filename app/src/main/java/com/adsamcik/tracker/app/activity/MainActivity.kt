package com.adsamcik.tracker.app.activity

import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.adsamcik.draggable.DragAxis
import com.adsamcik.draggable.DragTargetAnchor
import com.adsamcik.draggable.DraggableImageButton
import com.adsamcik.draggable.DraggablePayload
import com.adsamcik.draggable.Offset
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.HomeIntroduction
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.activity.CoreUIActivity
import com.adsamcik.tracker.common.assist.DisplayAssist
import com.adsamcik.tracker.common.dialog.FirstRunDialogBuilder
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.extension.guidelineEnd
import com.adsamcik.tracker.common.extension.transaction
import com.adsamcik.tracker.common.introduction.IntroductionManager
import com.adsamcik.tracker.common.keyboard.NavBarPosition
import com.adsamcik.tracker.common.module.FirstRun
import com.adsamcik.tracker.common.module.ModuleClassLoader
import com.adsamcik.tracker.common.preferences.Preferences
import com.adsamcik.tracker.common.style.StyleView
import com.adsamcik.tracker.common.style.SystemBarStyle
import com.adsamcik.tracker.common.style.SystemBarStyleView
import com.adsamcik.tracker.module.AppFirstRun
import com.adsamcik.tracker.module.Module
import com.adsamcik.tracker.module.PayloadFragment
import com.adsamcik.tracker.tracker.ui.fragment.FragmentTracker
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import kotlinx.android.synthetic.main.activity_ui.*


/**
 * MainActivity containing the core of the App
 * Users should spend most time in here.
 */
@Suppress("TooManyFunctions")
class MainActivity : CoreUIActivity() {
	private var navigationOffset = Int.MIN_VALUE

	private lateinit var trackerFragment: androidx.fragment.app.Fragment

	override fun onCreate(savedInstanceState: Bundle?) {
		setTheme(R.style.AppTheme_Translucent)
		initializeSystemBars()
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_ui)

		initializeButtons()
		initializeColorElements()

		trackerFragment = FragmentTracker()
		supportFragmentManager.transaction {
			replace(R.id.tracker_placeholder, trackerFragment)
		}
	}

	override fun onStart() {
		super.onStart()
		if (!com.adsamcik.tracker.common.preferences.Preferences.getPref(this).getBooleanRes(R.string.settings_first_run_key, false)) {
			firstRun()
		} else {
			uiIntroduction()
		}
	}

	private fun uiIntroduction() {
		root.post {
			IntroductionManager.showIntroduction(this, HomeIntroduction())
		}
	}

	private fun firstRun() {
		FirstRunDialogBuilder().apply {
			val modules = ModuleClassLoader.getEnabledModuleNames(this@MainActivity)
			addData(AppFirstRun())
			modules.forEach {
				try {
					val firstRunClass = ModuleClassLoader.loadModuleClass<FirstRun>(
							it,
							"${it.capitalize()}FirstRun"
					)
					addData(firstRunClass.newInstance())
				} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
					//do nothing, it's fine
				}
			}
			onFirstRunFinished = {
				com.adsamcik.tracker.common.preferences.Preferences.getPref(this@MainActivity).edit {
					setBoolean(R.string.settings_first_run_key, true)
				}
				uiIntroduction()
			}
		}.run {
			show(this@MainActivity)
		}
	}

	override fun onResume() {
		super.onResume()
		initializeButtonsPosition()
	}

	@Suppress("MagicNumber")
	private fun initializeStatsButton(size: Point) {
		val fragmentStatsClass = Module.STATISTICS.loadClass<PayloadFragment>("fragment.FragmentStats")

		button_stats.visibility = View.VISIBLE
		button_stats.dragAxis = DragAxis.X
		button_stats.setTarget(root, DragTargetAnchor.RightTop)
		button_stats.setTargetOffsetDp(Offset(56))
		button_stats.targetTranslationZ = 8.dp.toFloat()
		button_stats.extendTouchAreaBy(56.dp, 0, 40.dp, 0)
		button_stats.onEnterStateListener = { _, state, _, _ ->
			if (state == DraggableImageButton.State.TARGET) hideBottomLayer()
		}
		button_stats.onLeaveStateListener = { _, state ->
			if (state == DraggableImageButton.State.TARGET) showBottomLayer()
		}

		val statsPayload = DraggablePayload(this, fragmentStatsClass, root, root)
		statsPayload.width = MATCH_PARENT
		statsPayload.height = MATCH_PARENT
		statsPayload.initialTranslation = Point(-size.x, 0)
		statsPayload.backgroundColor = Color.WHITE
		statsPayload.targetTranslationZ = 7.dp.toFloat()
		statsPayload.destroyPayloadAfter = 15 * Time.SECOND_IN_MILLISECONDS
		button_stats.addPayload(statsPayload)
	}

	@Suppress("MagicNumber")
	private fun initializeMapButton(realSize: Point) {
		button_map.visibility = View.VISIBLE
		button_map.extendTouchAreaBy(32.dp)
		button_map.onEnterStateListener = { _, state, _, _ ->
			if (state == DraggableImageButton.State.TARGET) {
				hideBottomLayer()
				hideMiddleLayer()
			}
		}
		button_map.onLeaveStateListener = { _, state ->
			if (state == DraggableImageButton.State.TARGET) {
				if (button_game.state != DraggableImageButton.State.TARGET &&
						button_stats.state != DraggableImageButton.State.TARGET) {
					showBottomLayer()
				}

				showMiddleLayer()
			}
		}

		val fragmentMapClass = Module.MAP.loadClass<PayloadFragment>("fragment.FragmentMap")

		val mapPayload = DraggablePayload(this, fragmentMapClass, root, root)
		mapPayload.width = MATCH_PARENT
		mapPayload.height = MATCH_PARENT
		mapPayload.initialTranslation = Point(0, realSize.y)
		mapPayload.backgroundColor = Color.WHITE
		mapPayload.setTranslationZ(16.dp.toFloat())
		mapPayload.destroyPayloadAfter = 30 * Time.SECOND_IN_MILLISECONDS

		button_map.addPayload(mapPayload)
	}

	@Suppress("MagicNumber")
	private fun initializeGameButton(size: Point) {
		button_game.visibility = View.VISIBLE
		button_game.dragAxis = DragAxis.X
		button_game.setTarget(root, DragTargetAnchor.LeftTop)
		button_game.setTargetOffsetDp(Offset(-56))
		button_game.targetTranslationZ = 8.dp.toFloat()
		button_game.extendTouchAreaBy(0, 0, 56.dp, 0)
		button_game.onEnterStateListener = { _, state, _, _ ->
			if (state == DraggableImageButton.State.TARGET) {
				hideBottomLayer()
			}
		}
		button_game.onLeaveStateListener = { _, state ->
			if (state == DraggableImageButton.State.TARGET) showBottomLayer()
		}

		val fragmentGameClass = Module.GAME.loadClass<PayloadFragment>("fragment.FragmentGame")

		val gamePayload = DraggablePayload(this, fragmentGameClass, root, root)
		gamePayload.width = MATCH_PARENT
		gamePayload.height = MATCH_PARENT
		gamePayload.initialTranslation = Point(size.x, 0)
		gamePayload.backgroundColor = Color.WHITE
		gamePayload.targetTranslationZ = 7.dp.toFloat()
		gamePayload.destroyPayloadAfter = 15 * Time.SECOND_IN_MILLISECONDS

		button_game.addPayload(gamePayload)
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
			initializeStatsButton(size)
		} else {
			button_stats.visibility = View.GONE
		}

		if (installedModules.contains(Module.GAME.moduleName)) {
			initializeGameButton(size)
		} else {
			button_game.visibility = View.GONE
		}

		if (installedModules.contains(Module.MAP.moduleName)) {
			initializeMapButton(realSize)
		} else {
			button_map.visibility = View.GONE
		}

		initializeExclusionZones()

		//todo fix behavior for snackbar, currently it does not work properly with guideline for some reason
		/*val params = root.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
		params.behavior = NavigationGuidelinesOffsetBehavior(navigation_guideline)
		root.layoutParams = params
		root.requestLayout()*/
	}

	private fun initializeExclusionZones() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			root.doOnNextLayout {
				fun addExclusion(view: View, exclusions: MutableList<Rect>) {
					if (view.isVisible) {
						val outRect = Rect()
						view.getGlobalVisibleRect(outRect)
						exclusions.add(outRect)
					}
				}

				val exclusions = mutableListOf<Rect>()
				addExclusion(button_stats, exclusions)
				addExclusion(button_game, exclusions)

				root.systemGestureExclusionRects = exclusions
			}
		}
	}

	private fun hideBottomLayer() {
		trackerFragment.view?.visibility = View.GONE
		trackerFragment.onPause()
	}

	private fun showBottomLayer() {
		trackerFragment.view?.visibility = View.VISIBLE
		trackerFragment.onResume()
	}

	private fun hideMiddleLayer() {
		button_game.visibility = View.GONE
		button_stats.visibility = View.GONE

		if (button_stats.state == DraggableImageButton.State.TARGET) {
			button_stats.payloads.forEach { it.wrapper?.visibility = View.GONE }
		}

		if (button_game.state == DraggableImageButton.State.TARGET) {
			button_game.payloads.forEach { it.wrapper?.visibility = View.GONE }
		}
	}

	private fun showMiddleLayer() {
		button_game.visibility = View.VISIBLE
		button_stats.visibility = View.VISIBLE

		if (button_stats.state == DraggableImageButton.State.TARGET) {
			button_stats.payloads.forEach { it.wrapper?.visibility = View.VISIBLE }
		}

		if (button_game.state == DraggableImageButton.State.TARGET) {
			button_game.payloads.forEach { it.wrapper?.visibility = View.VISIBLE }
		}
	}

	private fun initializeButtonsPosition() {
		if (navigationOffset == Int.MIN_VALUE) {
			navigationOffset = navigation_guideline.guidelineEnd
		}

		val (position, navDim) = DisplayAssist.getNavigationBarSize(this)
		if (navDim.x > navDim.y) {
			navDim.x = 0
		} else {
			navDim.y = 0
		}

		navigation_guideline.setGuidelineEnd(navigationOffset + navDim.y)

		when (position) {
			NavBarPosition.RIGHT -> root.setPadding(0, 0, navDim.x, 0)
			NavBarPosition.LEFT -> root.setPadding(navDim.x, 0, 0, 0)
			else -> root.setPadding(0, 0, 0, 0)
		}
	}

	private fun initializeSystemBars() {
		styleController.watchNotificationBar(
				SystemBarStyleView(
						window,
						layer = 1,
						style = SystemBarStyle.Transparent
				)
		)

		styleController.watchNavigationBar(
				SystemBarStyleView(
						window,
						layer = 1,
						style = SystemBarStyle.Transparent
				)
		)
	}

	private fun initializeColorElements() {
		styleController.watchView(StyleView(button_stats, 1, maxDepth = 0, isInverted = true))
		styleController.watchView(StyleView(button_map, 1, maxDepth = 0, isInverted = true))
		styleController.watchView(StyleView(button_game, 1, maxDepth = 0, isInverted = true))
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

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		return if (!IntroductionManager.anyShown && root.touchDelegate?.onTouchEvent(event) == true) {
			true
		} else {
			super.dispatchTouchEvent(event)
		}
	}

	override fun onBackPressed() {
		when {
			button_map.state == DraggableImageButton.State.TARGET -> button_map.moveToState(
					DraggableImageButton.State.INITIAL, true
			)
			button_stats.state == DraggableImageButton.State.TARGET -> button_stats.moveToState(
					DraggableImageButton.State.INITIAL, true
			)
			button_game.state == DraggableImageButton.State.TARGET -> button_game.moveToState(
					DraggableImageButton.State.INITIAL, true
			)
			else -> super.onBackPressed()
		}
	}
}


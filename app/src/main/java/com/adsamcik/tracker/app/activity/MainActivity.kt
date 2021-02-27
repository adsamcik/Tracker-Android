package com.adsamcik.tracker.app.activity

import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.adsamcik.draggable.DragAxis
import com.adsamcik.draggable.DragTargetAnchor
import com.adsamcik.draggable.DraggableImageButton
import com.adsamcik.draggable.DraggablePayload
import com.adsamcik.draggable.Offset
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.HomeIntroduction
import com.adsamcik.tracker.module.AppFirstRun
import com.adsamcik.tracker.module.Module
import com.adsamcik.tracker.module.PayloadFragment
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.guidelineEnd
import com.adsamcik.tracker.shared.base.extension.transaction
import com.adsamcik.tracker.shared.base.misc.NavBarPosition
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.activity.CoreUIActivity
import com.adsamcik.tracker.shared.utils.dialog.FirstRunDialogBuilder
import com.adsamcik.tracker.shared.utils.introduction.IntroductionManager
import com.adsamcik.tracker.shared.utils.module.FirstRun
import com.adsamcik.tracker.shared.utils.module.ModuleClassLoader
import com.adsamcik.tracker.shared.utils.permission.PermissionManager
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.shared.utils.style.SystemBarStyle
import com.adsamcik.tracker.shared.utils.style.SystemBarStyleView
import com.adsamcik.tracker.tracker.ui.fragment.FragmentTracker
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import java.util.*


/**
 * MainActivity containing the core of the App
 * Users should spend most time in here.
 */
@Suppress("TooManyFunctions")
class MainActivity : CoreUIActivity() {
	private var navigationOffset = Int.MIN_VALUE

	private lateinit var trackerFragment: androidx.fragment.app.Fragment

	private val root: ViewGroup by lazy { findViewById(R.id.root) }

	private val buttonStats: DraggableImageButton by lazy { findViewById(R.id.button_stats) }
	private val buttonGame: DraggableImageButton by lazy { findViewById(R.id.button_game) }
	private val buttonMap: DraggableImageButton by lazy { findViewById(R.id.button_map) }

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
		if (!Preferences.getPref(this).getBooleanRes(R.string.settings_first_run_key, false)) {
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
		FirstRunDialogBuilder().let { builder ->
			builder.addData(AppFirstRun())
			ModuleClassLoader.invokeInEachActiveModule<FirstRun>(this@MainActivity) {
				builder.addData(it)
			}
			builder.onFirstRunFinished = {
				Preferences.getPref(this@MainActivity).edit {
					setBoolean(R.string.settings_first_run_key, true)
				}
				PermissionManager.checkActivityPermissions(this@MainActivity) {}
				uiIntroduction()
			}
			builder.show(this@MainActivity)
		}
	}

	override fun onResume() {
		super.onResume()
		initializeButtonsPosition()
	}

	@Suppress("MagicNumber")
	private fun initializeStatsButton(size: Point) {
		val fragmentStatsClass = Module.STATISTICS.loadClass<PayloadFragment>("fragment.FragmentStats")

		buttonStats.apply {
			visibility = View.VISIBLE
			dragAxis = DragAxis.X
			setTarget(root, DragTargetAnchor.RightTop)
			setTargetOffsetDp(Offset(56))
			targetTranslationZ = 8.dp.toFloat()
			extendTouchAreaBy(56.dp, 0, 40.dp, 0)
			onEnterStateListener = { _, state, _, _ ->
				if (state == DraggableImageButton.State.TARGET) hideBottomLayer()
			}
			onLeaveStateListener = { _, state ->
				if (state == DraggableImageButton.State.TARGET) showBottomLayer()
			}

			DraggablePayload(this@MainActivity, fragmentStatsClass, root, root).apply {
				width = MATCH_PARENT
				height = MATCH_PARENT
				initialTranslation = Point(-size.x, 0)
				backgroundColor = Color.WHITE
				targetTranslationZ = 7.dp.toFloat()
				destroyPayloadAfter = 15 * Time.SECOND_IN_MILLISECONDS
			}.let { payload ->
				addPayload(payload)
			}
		}
	}

	@Suppress("MagicNumber")
	private fun initializeMapButton(realSize: Point) {
		buttonMap.apply {
			visibility = View.VISIBLE
			extendTouchAreaBy(32.dp)
			onEnterStateListener = { _, state, _, _ ->
				if (state == DraggableImageButton.State.TARGET) {
					hideBottomLayer()
					hideMiddleLayer()
				}
			}
			onLeaveStateListener = { _, state ->
				if (state == DraggableImageButton.State.TARGET) {
					if (buttonGame.state != DraggableImageButton.State.TARGET &&
							buttonStats.state != DraggableImageButton.State.TARGET) {
						showBottomLayer()
					}

					showMiddleLayer()
				}
			}

			val fragmentMapClass = Module.MAP.loadClass<PayloadFragment>("fragment.FragmentMap")

			DraggablePayload(this@MainActivity, fragmentMapClass, root, root).apply {
				width = MATCH_PARENT
				height = MATCH_PARENT
				initialTranslation = Point(0, realSize.y)
				backgroundColor = Color.WHITE
				setTranslationZ(16.dp.toFloat())
				destroyPayloadAfter = 30 * Time.SECOND_IN_MILLISECONDS
			}.let { payload ->
				addPayload(payload)
			}
		}
	}

	@Suppress("MagicNumber")
	private fun initializeGameButton(size: Point) {
		buttonGame.apply {
			visibility = View.VISIBLE
			dragAxis = DragAxis.X
			setTarget(root, DragTargetAnchor.LeftTop)
			setTargetOffsetDp(Offset(-56))
			targetTranslationZ = 8.dp.toFloat()
			extendTouchAreaBy(0, 0, 56.dp, 0)
			onEnterStateListener = { _, state, _, _ ->
				if (state == DraggableImageButton.State.TARGET) {
					hideBottomLayer()
				}
			}
			onLeaveStateListener = { _, state ->
				if (state == DraggableImageButton.State.TARGET) showBottomLayer()
			}

			val fragmentGameClass = Module.GAME.loadClass<PayloadFragment>("fragment.FragmentGame")

			DraggablePayload(this@MainActivity, fragmentGameClass, root, root).apply {
				width = MATCH_PARENT
				height = MATCH_PARENT
				initialTranslation = Point(size.x, 0)
				backgroundColor = Color.WHITE
				targetTranslationZ = 7.dp.toFloat()
				destroyPayloadAfter = 15 * Time.SECOND_IN_MILLISECONDS
			}.let { payload ->
				addPayload(payload)
			}
		}
	}

	private fun initializeButtons() {
		val realSize = DisplayAssist.getRealArea(this).toPoint()
		val size = DisplayAssist.getUsableArea(this).toPoint()

		val splitInstallManager = SplitInstallManagerFactory.create(this)
		val installedModules = splitInstallManager.installedModules

		if (installedModules.contains(Module.STATISTICS.moduleName)) {
			initializeStatsButton(size)
		} else {
			buttonStats.visibility = View.GONE
		}

		if (installedModules.contains(Module.GAME.moduleName)) {
			initializeGameButton(size)
		} else {
			buttonGame.visibility = View.GONE
		}

		if (installedModules.contains(Module.MAP.moduleName)) {
			initializeMapButton(realSize)
		} else {
			buttonMap.visibility = View.GONE
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
				addExclusion(buttonStats, exclusions)
				addExclusion(buttonGame, exclusions)

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
		buttonGame.visibility = View.GONE
		buttonStats.visibility = View.GONE

		if (buttonStats.state == DraggableImageButton.State.TARGET) {
			buttonStats.payloads.forEach { it.wrapper?.visibility = View.GONE }
		}

		if (buttonGame.state == DraggableImageButton.State.TARGET) {
			buttonGame.payloads.forEach { it.wrapper?.visibility = View.GONE }
		}
	}

	private fun showMiddleLayer() {
		buttonGame.visibility = View.VISIBLE
		buttonStats.visibility = View.VISIBLE

		if (buttonStats.state == DraggableImageButton.State.TARGET) {
			buttonStats.payloads.forEach { it.wrapper?.visibility = View.VISIBLE }
		}

		if (buttonGame.state == DraggableImageButton.State.TARGET) {
			buttonGame.payloads.forEach { it.wrapper?.visibility = View.VISIBLE }
		}
	}

	private fun initializeButtonsPosition() {
		val navGuideline = findViewById<Guideline>(R.id.navigation_guideline)
		if (navigationOffset == Int.MIN_VALUE) {
			navigationOffset = navGuideline.guidelineEnd
		}

		val (position, navDim) = DisplayAssist.getNavigationBarSize(this)
		if (navDim.x > navDim.y) {
			navDim.x = 0
		} else {
			navDim.y = 0
		}

		navGuideline.setGuidelineEnd(navigationOffset + navDim.y)

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
		styleController.watchView(StyleView(buttonStats, 1, maxDepth = 0, isInverted = true))
		styleController.watchView(StyleView(buttonMap, 1, maxDepth = 0, isInverted = true))
		styleController.watchView(StyleView(buttonGame, 1, maxDepth = 0, isInverted = true))
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)

		buttonMap.saveFragments(outState)
		buttonStats.saveFragments(outState)
		buttonGame.saveFragments(outState)
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)

		buttonMap.restoreFragments(savedInstanceState)
		buttonStats.restoreFragments(savedInstanceState)
		buttonGame.restoreFragments(savedInstanceState)
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
			buttonMap.state == DraggableImageButton.State.TARGET -> buttonMap.moveToState(
					DraggableImageButton.State.INITIAL, true
			)
			buttonStats.state == DraggableImageButton.State.TARGET -> buttonStats.moveToState(
					DraggableImageButton.State.INITIAL, true
			)
			buttonGame.state == DraggableImageButton.State.TARGET -> buttonGame.moveToState(
					DraggableImageButton.State.INITIAL, true
			)
			else -> super.onBackPressed()
		}
	}
}


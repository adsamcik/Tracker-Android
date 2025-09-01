package com.adsamcik.tracker.app.activity

import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.adsamcik.draggable.DragAxis
import com.adsamcik.draggable.DragTargetAnchor
import com.adsamcik.draggable.DraggableImageButton
import com.adsamcik.draggable.DraggablePayload
import com.adsamcik.draggable.Offset
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import com.adsamcik.tracker.app.ui.theme.AppTheme
import com.adsamcik.tracker.module.Module
import com.adsamcik.tracker.module.PayloadFragment
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.DisplayAssist
import com.adsamcik.tracker.shared.base.extension.dp as px
import com.adsamcik.tracker.shared.base.extension.guidelineEnd
import com.adsamcik.tracker.shared.base.extension.transaction
import com.adsamcik.tracker.shared.base.misc.NavBarPosition
import com.adsamcik.tracker.shared.utils.activity.CoreUIActivity
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.shared.utils.style.SystemBarStyle
import com.adsamcik.tracker.shared.utils.style.SystemBarStyleView
import com.adsamcik.tracker.tracker.ui.fragment.FragmentTracker

/**
 * MainActivity containing the core of the App
 * Users should spend most time in here.
 */
@Suppress("TooManyFunctions")
class MainActivity : CoreUIActivity() {
	private var navigationOffset = Int.MIN_VALUE

	private var trackerFragment: androidx.fragment.app.Fragment? = null

	private val root: ViewGroup by lazy { findViewById(R.id.root) }

	private val buttonStats: DraggableImageButton by lazy { findViewById(R.id.button_stats) }
	private val buttonGame: DraggableImageButton by lazy { findViewById(R.id.button_game) }
	private val buttonMap: DraggableImageButton by lazy { findViewById(R.id.button_map) }

	override fun onCreate(savedInstanceState: Bundle?) {
		setTheme(R.style.AppTheme_Translucent)
		initializeSystemBars()
		super.onCreate(savedInstanceState)

		if (BuildConfig.COMPOSE_MAIN) {
			setContent {
				ComposeMain()
			}
		} else {
			setContentView(R.layout.activity_ui)
			initializeButtons()
			initializeColorElements()
			initializeButtonsPosition()

			trackerFragment =
				supportFragmentManager.findFragmentByTag(FragmentTracker::class.java.simpleName)
			if (trackerFragment == null) {
				trackerFragment = FragmentTracker()
				supportFragmentManager.transaction {
					replace(
						R.id.tracker_placeholder,
						requireNotNull(trackerFragment),
						FragmentTracker::class.java.simpleName
					)
				}
			}

			onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() {
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
						else -> {
							// If no custom behavior, allow the default behavior
							isEnabled = false
							onBackPressedDispatcher.onBackPressed()
						}
					}
				}
			})
		}
	}

	override fun onStart() {
		super.onStart()
		
		// Check if onboarding is completed using the helper method
		if (!OnboardingActivity.isOnboardingCompleted(this)) {
			// Launch onboarding flow
			val intent = OnboardingActivity.createIntent(this)
			startActivity(intent)
			// Don't finish() here - let onboarding complete and return
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		root.post {
			val openGame = intent.getBooleanExtra("openGame", false)
			if (openGame) {
				buttonGame.moveToState(DraggableImageButton.State.TARGET, false)
			}
		}
	}

	@Suppress("MagicNumber")
	private fun initializeStatsButton(size: Point) {
		val fragmentStatsClass =
			Module.STATISTICS.loadClass<PayloadFragment>("fragment.FragmentStats")

		buttonStats.apply {
			visibility = View.VISIBLE
			dragAxis = DragAxis.X
			setTarget(root, DragTargetAnchor.RightTop)
			setTargetOffsetDp(Offset(56))
			targetTranslationZ = 8.px.toFloat()
			extendTouchAreaBy(56.px, 0, 40.px, 0)
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
				targetTranslationZ = 7.px.toFloat()
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
			extendTouchAreaBy(32.px)
			onEnterStateListener = { _, state, _, _ ->
				if (state == DraggableImageButton.State.TARGET) {
					hideBottomLayer()
					hideMiddleLayer()
				}
			}
			onLeaveStateListener = { _, state ->
				if (state == DraggableImageButton.State.TARGET) {
					if (buttonGame.state != DraggableImageButton.State.TARGET &&
						buttonStats.state != DraggableImageButton.State.TARGET
					) {
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
				setTranslationZ(16.px.toFloat())
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
			targetTranslationZ = 8.px.toFloat()
			extendTouchAreaBy(0, 0, 56.px, 0)
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
				targetTranslationZ = 7.px.toFloat()
				destroyPayloadAfter = 15 * Time.SECOND_IN_MILLISECONDS
			}.let { payload ->
				addPayload(payload)
			}
		}
	}

	private fun initializeButtons() {
		val realSize = DisplayAssist.getRealArea(this).toPoint()
		val size = DisplayAssist.getUsableArea(this).toPoint()

		// All modules are now static; initialize buttons unconditionally
		initializeStatsButton(size)
		initializeGameButton(size)
		initializeMapButton(realSize)

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
		val trackerFragment = requireNotNull(trackerFragment)
		trackerFragment.view?.visibility = View.GONE
		trackerFragment.onPause()
	}

	private fun showBottomLayer() {
		val trackerFragment = requireNotNull(trackerFragment)
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
		val navGuideline = findViewById<Guideline>(R.id.navigation_guideline) ?: return

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
		return if (root.touchDelegate?.onTouchEvent(event) == true) {
			true
		} else {
			super.dispatchTouchEvent(event)
		}
	}

	// Compose shell hosting existing fragments (v1)
	@Composable
	private fun ComposeMain() {
		AppTheme {
			Surface(color = MaterialTheme.colorScheme.background) {
				var selected by remember { mutableStateOf("map") }

				Box(Modifier.fillMaxSize()) {
					// Tracker background (existing fragment)
					AndroidViewBinding(com.adsamcik.tracker.databinding.ActivityUiBinding::inflate)

					// Bottom navigation with center prominence and parity-leaning animations
					Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
						// Slightly raise the bar when Map is active
						val barElevation by animateDpAsState(
							if (selected == "map") 10.dp else 4.dp,
							animationSpec = spring(stiffness = Spring.StiffnessLow), label = "bar-elev"
						)
						Surface(tonalElevation = barElevation) {
							Row(
								Modifier
									.fillMaxWidth()
									.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
									.navigationBarsPadding()
									.padding(horizontal = 16.dp, vertical = 12.dp),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically
							) {
								// Left: Stats
								val statsScale by animateFloatAsState(
									if (selected == "stats") 1.1f else 0.95f,
									animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "stats-scale"
								)
								val statsAlpha by animateFloatAsState(
									if (selected == "map") 0.9f else 1f,
									animationSpec = spring(stiffness = Spring.StiffnessLow), label = "stats-alpha"
								)
								IconButton(onClick = { selected = "stats" }, modifier = Modifier.scale(statsScale).alpha(statsAlpha)) {
									Icon(Icons.Filled.BarChart, contentDescription = "Stats")
								}

								// Center: Map (prominent)
								val mapScale by animateFloatAsState(
									if (selected == "map") 1.25f else 1.0f,
									animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.6f), label = "map-scale"
								)
								val mapLift by animateDpAsState(
									if (selected == "map") 6.dp else 0.dp,
									animationSpec = spring(stiffness = Spring.StiffnessLow), label = "map-lift"
								)
								Box(
									Modifier
										.size(72.dp)
										.padding(bottom = mapLift)
										.shadow(elevation = if (selected == "map") 8.dp else 0.dp, shape = MaterialTheme.shapes.large),
									contentAlignment = Alignment.Center
								) {
									IconButton(
										onClick = { selected = "map" },
										modifier = Modifier.size(64.dp).scale(mapScale)
									) {
										Icon(Icons.Filled.Map, contentDescription = "Map")
									}
								}

								// Right: Game
								val gameScale by animateFloatAsState(
									if (selected == "game") 1.1f else 0.95f,
									animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "game-scale"
								)
								val gameAlpha by animateFloatAsState(
									if (selected == "map") 0.9f else 1f,
									animationSpec = spring(stiffness = Spring.StiffnessLow), label = "game-alpha"
								)
								IconButton(onClick = { selected = "game" }, modifier = Modifier.scale(gameScale).alpha(gameAlpha)) {
									Icon(Icons.Filled.VideogameAsset, contentDescription = "Game")
								}
							}
						}
					}
				}
			}
		}
	}
}


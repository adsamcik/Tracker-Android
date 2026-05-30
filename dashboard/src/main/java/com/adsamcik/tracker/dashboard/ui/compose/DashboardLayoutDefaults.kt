package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.MainNavigationLayout

internal object DashboardLayoutDefaults {
	/** Height of the tracking pill (96dp) plus the floating-action margin (16dp). */
	val PillClearance = 112.dp
	private val FloatingActionMargin = 16.dp

	/**
	 * Visual breathing space between the last item in the dashboard's scrollable
	 * area and the top edge of the tracking pill. Matches the value used by
	 * `bottomNavSafeClearance` for plain top-level screens (Game, Stats) so the
	 * dashboard's stack-aware variant is consistent with the rest of the app.
	 */
	private val TrackingPillTopMargin = 28.dp

	/**
	 * Trailing inset for the pill at [androidx.compose.ui.Alignment.BottomEnd].
	 * Keeps the pill clear of the screen edge so the elevation shadow reads cleanly.
	 */
	val FloatingActionEndMargin = FloatingActionMargin

	/**
	 * Bottom `contentPadding` for the dashboard's scrollable area, sized so that
	 * the last card clears the floating tracking pill (which sits above the
	 * floating navigation bar in [MainNavigationLayout.BottomBar] mode).
	 *
	 * # What this reserves
	 *
	 * - [PillClearance] (`112.dp = 96.dp pill height + 16.dp floating-action margin`):
	 *   the tracking pill itself plus the margin between the pill and the bottom
	 *   edge of the dashboard surface.
	 * - [TrackingPillTopMargin] (`28.dp`): visual breathing room between the top
	 *   of the pill and the last item in the scroll area.
	 * - `bottomInset`: defense-in-depth against the dashboard surface not fully
	 *   covering the system bottom inset (gesture handle, soft nav bar). Already
	 *   covered by `MainRoot`'s NavHost-level `96.dp + navBarInset` reservation
	 *   in [MainNavigationLayout.BottomBar] mode, but kept here so the dashboard
	 *   remains self-sufficient if that upstream reservation ever changes.
	 *
	 * # What this does NOT reserve
	 *
	 * The sibling [floatingActionBottomPadding] KDoc documents the same anti-pattern
	 * that previously plagued this function: `MainRoot.kt` already reserves
	 * `96.dp + navBarInset` at the `NavHost` level for the floating navigation
	 * bar. Adding `AppDimensions.FloatingNavBarClearance` (120dp) on top — as this
	 * function used to do — double-counted that reservation and pushed the cards
	 * up by ~120dp of dead space (see commit `9d1b1980d` and round-7 R1/R2 reviews).
	 *
	 * The dashboard differs from Game/Stats because of the floating tracking pill
	 * that sits above the nav pill — that's why this function still adds
	 * `PillClearance` on top of [TrackingPillTopMargin], while
	 * `bottomNavSafeClearance` (used by Game/Stats) does not.
	 *
	 * @param navigationLayout current top-level navigation layout.
	 * @param bottomInset value reported by
	 *   `WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()`
	 *   (or the larger of safeDrawing and navigationBars insets, as DashboardScreen
	 *   currently passes).
	 * @param isLandscape kept for call-site compatibility; the formula is the
	 *   same in portrait and landscape because `MainRoot` reserves the same
	 *   `96.dp + navBarInset` in both orientations.
	 */
	@Suppress("UnusedParameter")
	fun contentBottomClearance(
		navigationLayout: MainNavigationLayout,
		bottomInset: Dp = 0.dp,
		isLandscape: Boolean = false,
	): Dp = when (navigationLayout) {
		MainNavigationLayout.SideRail -> PillClearance + bottomInset
		// BottomBar (portrait or landscape): MainRoot's NavHost reservation already
		// covers the floating nav bar, so we only need to clear the tracking pill
		// that sits inside the dashboard surface, plus a small visual margin and
		// the system inset (defense-in-depth, see KDoc above).
		MainNavigationLayout.BottomBar -> PillClearance + TrackingPillTopMargin + bottomInset
	}

	/**
	 * Bottom padding for the floating tracking pill when it is rendered as an overlay
	 * inside the dashboard surface.
	 *
	 * The outer `MainRoot` already reserves `bottomPadding = 96.dp + navBarInset` at the
	 * NavHost level for the floating navigation bar, so the dashboard surface ends just
	 * above that bar. The pill only needs a small margin from the bottom edge of that
	 * surface — adding `FloatingNavBarClearance` here would double-count the reservation
	 * and push the pill halfway up the screen.
	 *
	 * In landscape the bottom bar is shorter and the outer reservation matches, so the
	 * same margin applies. In SideRail layouts the nav rail is on the side rather than
	 * the bottom, so the pill anchors directly above the system nav inset (which the
	 * NavHost padding does not reserve in that mode).
	 */
	@Suppress("UnusedParameter")
	fun floatingActionBottomPadding(
		bottomInset: Dp = 0.dp,
		isLandscape: Boolean = false,
		navigationLayout: MainNavigationLayout = MainNavigationLayout.BottomBar,
	): Dp = when (navigationLayout) {
		MainNavigationLayout.SideRail -> FloatingActionMargin + bottomInset
		// Outer NavHost padding already accounts for the floating nav bar (96dp +
		// systemNavInset). The dashboard's overlay Box ends just above that bar, so
		// we only need a small margin to sit cleanly above the bar instead of
		// touching it. Landscape uses the same value since the outer reservation in
		// landscape also collapses to the bar's footprint.
		else -> FloatingActionMargin
	}
}


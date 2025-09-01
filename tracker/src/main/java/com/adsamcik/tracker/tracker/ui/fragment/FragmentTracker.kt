package com.adsamcik.tracker.tracker.ui.fragment

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.base.misc.SnackMaker
import com.adsamcik.tracker.shared.preferences.PreferencesAssist
import com.adsamcik.tracker.shared.utils.fragment.CorePermissionFragment
import com.adsamcik.tracker.shared.utils.permission.PermissionData
import com.adsamcik.tracker.shared.utils.permission.PermissionRequest
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.service.TrackerService
import com.adsamcik.tracker.tracker.ui.TrackerViewModel
import com.adsamcik.tracker.tracker.ui.compose.TrackerDashboard

/**
 * Compose-based Fragment that displays current tracking information
 */
@Deprecated("Use TrackerRoute() composable instead of FragmentTracker", ReplaceWith("TrackerRoute()"))
class FragmentTracker : CorePermissionFragment() {
	private lateinit var viewModel: TrackerViewModel

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel = ViewModelProvider(this)[TrackerViewModel::class.java]
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
        return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
					// Use Material 3 defaults; app-level AppTheme wraps the whole UI in Compose host
					TrackerDashboard(
						viewModel = viewModel,
						onSettingsClick = {
							context.startActivity("com.adsamcik.tracker.preference.activity.SettingsActivity") {
								flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
							}
						},
						onRequestPermission = { permission ->
							requestPermissions(
								PermissionRequest
									.with(requireContext())
									.permission(
										PermissionData(permission) { getString(com.adsamcik.tracker.shared.utils.R.string.permission_rationale_location) }
									)
									.onResult { }
									.build()
							)
						},
						onToggleTracking = { enable ->
							val activity = requireActivity() as FragmentActivity
							if (TrackerService.sessionInfo.value?.isInitiatedByUser == false && !enable) {
								// Lock when stopping auto-tracking
								TrackerLocker.lockTimeLock(
									activity,
									com.adsamcik.tracker.shared.base.Time.MINUTE_IN_MILLISECONDS * 60
								)
								activity.findViewById<View>(android.R.id.content)?.let { root ->
									SnackMaker(root).addMessage(
										activity.resources.getQuantityString(
											R.plurals.notification_auto_tracking_lock,
											60, 60
										)
									)
								}
							} else {
								toggleCollecting(activity, enable)
							}
						}
					)
			}
		}
	}

	override fun onStart() {
		super.onStart()

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			requestPermissions(
				PermissionRequest
					.with(requireContext())
					.permission(
						PermissionData(
							Manifest.permission.POST_NOTIFICATIONS
						) { "" }
					)
					.onResult { }
					.build()
			)
		}
	}

	private fun startTracking(activity: FragmentActivity) {
		if (!Assist.isGNSSEnabled(activity)) {
			activity.findViewById<View>(android.R.id.content)?.let { root ->
				SnackMaker(root)
					.addMessage(
						messageRes = com.adsamcik.tracker.shared.base.R.string.error_gnss_not_enabled,
						priority = SnackMaker.SnackbarPriority.IMPORTANT,
						actionRes = com.adsamcik.tracker.shared.base.R.string.generic_enable,
						onActionClick = {
							val locationOptionsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
							startActivity(locationOptionsIntent)
						}
					)
			}
		} else if (!PreferencesAssist.hasAnythingToTrack(activity)) {
			activity.findViewById<View>(android.R.id.content)?.let { root ->
				SnackMaker(root).addMessage(R.string.error_nothing_to_track)
			}
		} else {
			TrackerServiceApi.startService(activity, isUserInitiated = true)
		}
	}

	private fun stopTracking(activity: FragmentActivity) {
		TrackerServiceApi.stopService(activity)
	}

	private fun toggleCollecting(activity: FragmentActivity, enable: Boolean) {
		val isActive = TrackerServiceApi.isActive
		if (isActive == enable) return

		TrackerTimerManager.checkTimerPermissions(activity) {
			if (it.isSuccess) {
				if (!isActive) startTracking(activity) else stopTracking(activity)
			}
		}
	}
}

// Compose entry point for Tracker feature (replacement for FragmentTracker)
@Composable
fun TrackerRoute() {
	val context = LocalContext.current
	// ViewModel scoped to the current NavBackStackEntry/Activity
	val viewModel: TrackerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

	Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
			// Request notification permission on first composition on T+
			LaunchedEffect(Unit) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					(context as? Activity)?.let { act ->
						androidx.core.app.ActivityCompat.requestPermissions(
							act,
							arrayOf(Manifest.permission.POST_NOTIFICATIONS),
							0
						)
					}
				}
			}

			TrackerDashboard(
				viewModel = viewModel,
				onSettingsClick = {
					context.startActivity("com.adsamcik.tracker.preference.activity.SettingsActivity") {
						flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
					}
				},
				onRequestPermission = { permission ->
					(context as? Activity)?.let { act ->
						androidx.core.app.ActivityCompat.requestPermissions(
							act,
							arrayOf(permission),
							0
						)
					}
				},
				onToggleTracking = { enable ->
					(context as? FragmentActivity)?.let { activity ->
						if (TrackerService.sessionInfo.value?.isInitiatedByUser == false && !enable) {
							// Lock when stopping auto-tracking
							TrackerLocker.lockTimeLock(
								activity,
								com.adsamcik.tracker.shared.base.Time.MINUTE_IN_MILLISECONDS * 60
							)
							activity.findViewById<View>(android.R.id.content)?.let { root ->
								SnackMaker(root).addMessage(
									activity.resources.getQuantityString(
										R.plurals.notification_auto_tracking_lock,
										60, 60
									)
								)
							}
						} else {
							toggleCollecting(activity, enable)
						}
					}
				}
			)
		}
	}

private fun startTracking(activity: FragmentActivity) {
	if (!Assist.isGNSSEnabled(activity)) {
		activity.findViewById<View>(android.R.id.content)?.let { root ->
			SnackMaker(root)
				.addMessage(
					messageRes = com.adsamcik.tracker.shared.base.R.string.error_gnss_not_enabled,
					priority = SnackMaker.SnackbarPriority.IMPORTANT,
					actionRes = com.adsamcik.tracker.shared.base.R.string.generic_enable,
					onActionClick = {
						val locationOptionsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
						activity.startActivity(locationOptionsIntent)
					}
				)
		}
	} else if (!PreferencesAssist.hasAnythingToTrack(activity)) {
		activity.findViewById<View>(android.R.id.content)?.let { root ->
			SnackMaker(root).addMessage(R.string.error_nothing_to_track)
		}
	} else {
		TrackerServiceApi.startService(activity, isUserInitiated = true)
	}
}

private fun stopTracking(activity: FragmentActivity) {
	TrackerServiceApi.stopService(activity)
}

private fun toggleCollecting(activity: FragmentActivity, enable: Boolean) {
	val isActive = TrackerServiceApi.isActive
	if (isActive == enable) return

	TrackerTimerManager.checkTimerPermissions(activity) {
		if (it.isSuccess) {
			if (!isActive) startTracking(activity) else stopTracking(activity)
		}
	}
}


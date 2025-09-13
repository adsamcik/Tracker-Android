package com.adsamcik.tracker.preference.pages

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.R as BaseR
import com.adsamcik.tracker.app.activity.debug.CrashManagerActivity
import com.adsamcik.tracker.app.activity.debug.CrashViewerActivity
import com.adsamcik.tracker.app.activity.debug.LogViewerActivity
import com.adsamcik.tracker.app.activity.debug.StatusActivity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.debug.DummyDataSeeder
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.base.notification.Notifications
import com.adsamcik.tracker.shared.preferences.Preferences
// Copilot: First migration step away from MaterialDialog – simple confirm uses ConfirmDialog composable.
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
// Removed unused semantics/test imports from previous draft.
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
// No list usage in this file; removed LazyColumn imports.
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import com.adsamcik.tracker.shared.utils.compose.ConfirmDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Random

/**
 * Page with debug preferences.
 */
internal class DebugPage : PreferencePage {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	override fun onExit(caller: PreferenceFragmentCompat): Unit = Unit

	override fun onEnter(caller: PreferenceFragmentCompat) {
			// Hide developer-only dummy data option in non-debug builds (guarded lookup)
			caller.preferenceScreen
				?.findPreference<Preference>(caller.getString(R.string.settings_generate_dummy_data_key))
				?.isVisible = BuildConfig.DEBUG

		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_activity_status_key))
			?.setOnPreferenceClickListener {
				it.context.startActivity<StatusActivity> { }
				false
			}

			// Optional hello world notification preference
			caller.preferenceScreen
				?.findPreference<Preference>(caller.getString(R.string.settings_hello_world_key))
				?.setOnPreferenceClickListener {
					val context = it.context
					val resources = context.resources
					val color = ContextCompat.getColor(context, R.color.color_primary)
					val rng = Random(Time.nowMillis)
					val facts = resources.getStringArray(R.array.lorem_ipsum_facts)
					val notificationBuilder = NotificationCompat.Builder(
						context,
						resources.getString(BaseR.string.channel_other_id)
					)
						.setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals_launcher)
						.setColor(color)
						.setLights(color, 2000, 5000)
						.setContentTitle(resources.getString(R.string.did_you_know))
						.setContentText(facts[rng.nextInt(facts.size)])
						.setWhen(Time.nowMillis)
					val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
					notificationManager.notify(Notifications.uniqueNotificationId(), notificationBuilder.build())
					false
				}

			// Dummy data generation preference -> Compose state machine dialogs
			caller.preferenceScreen
				?.findPreference<Preference>(caller.getString(R.string.settings_generate_dummy_data_key))
				?.setOnPreferenceClickListener { pref ->
					val context = pref.context
					val activity = caller.requireActivity()
					val decor = activity.window.decorView as? android.view.ViewGroup
					if (decor != null) {
						val host = ComposeView(context).apply {
							setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
							setContent {
								var state by remember { mutableStateOf<DummyDataSeedState>(DummyDataSeedState.FirstConfirm) }
								val localScope = remember { scope }
								when (val s = state) {
									DummyDataSeedState.FirstConfirm -> AlertDialog(
										modifier = Modifier.testTag("dummyDataDialogFirstConfirm"),
										onDismissRequest = { state = DummyDataSeedState.Dismissed },
										title = { Text(text = context.getString(BaseR.string.alert_confirm_generic)) },
										text = { Text(text = context.getString(R.string.settings_generate_dummy_data_title)) },
										confirmButton = {
											Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
												state = DummyDataSeedState.Seeding
												localScope.launch {
													val probe = DummyDataSeeder.seedIfEmpty(context)
													withContext(Dispatchers.Main) {
														state = if (probe.inserted) {
															DummyDataSeedState.Result(success = true)
														} else if (probe.reason == "not-empty") {
															DummyDataSeedState.SecondConfirm
														} else {
															DummyDataSeedState.Result(success = false)
														}
													}
												}
											}) { Text(text = context.getString(BaseR.string.generic_yes)) }
										},
										dismissButton = {
											TextButton(onClick = { state = DummyDataSeedState.Dismissed }) { Text(text = context.getString(BaseR.string.generic_no)) }
										}
									)

									DummyDataSeedState.Seeding -> AlertDialog(
										modifier = Modifier.testTag("dummyDataDialogProgress"),
										onDismissRequest = { /* disabled during seeding */ },
										title = { Text(text = context.getString(R.string.settings_generate_dummy_data_title)) },
										text = {
											Row(verticalAlignment = Alignment.CenterVertically) {
												CircularProgressIndicator(modifier = Modifier.size(24.dp))
												Spacer(Modifier.width(16.dp))
												Text(text = context.getString(R.string.dummy_data_generation_title_progress))
											}
										},
										confirmButton = {},
										dismissButton = {}
									)

									DummyDataSeedState.SecondConfirm -> AlertDialog(
										modifier = Modifier.testTag("dummyDataDialogSecondConfirm"),
										onDismissRequest = { state = DummyDataSeedState.Dismissed },
										title = { Text(text = context.getString(R.string.dummy_data_second_confirm_title)) },
										text = { Text(text = context.getString(R.string.dummy_data_second_confirm_message)) },
										confirmButton = {
											Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), onClick = {
												state = DummyDataSeedState.Seeding
												localScope.launch {
													val forced = DummyDataSeeder.seed(context)
													withContext(Dispatchers.Main) {
														state = DummyDataSeedState.Result(success = forced.inserted)
													}
												}
											}) { Text(text = context.getString(BaseR.string.generic_yes)) }
										},
										dismissButton = {
											TextButton(onClick = { state = DummyDataSeedState.Dismissed }) { Text(text = context.getString(BaseR.string.generic_no)) }
										}
									)

									is DummyDataSeedState.Result -> AlertDialog(
										modifier = Modifier.testTag("dummyDataDialogResult_${if (s.success) "success" else "failure"}"),
										onDismissRequest = { state = DummyDataSeedState.Dismissed },
										title = { Text(text = context.getString(if (s.success) R.string.dummy_data_generation_success else R.string.dummy_data_generation_failed)) },
										text = { /* message only in title */ },
										confirmButton = {
											TextButton(onClick = { state = DummyDataSeedState.Dismissed }) { Text(text = context.getString(BaseR.string.generic_ok)) }
										},
										dismissButton = {}
									)

									DummyDataSeedState.Dismissed -> {
										// Remove host view exactly once when dismissed.
										LaunchedEffect(Unit) { decor.removeView(this@apply) }
									}
								}
							}
						}
						decor.addView(host)
					}
					false
				}

		// Crash list viewer
		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_crash_viewer_key))
			?.setOnPreferenceClickListener { pref ->
				pref.context.startActivity<CrashViewerActivity> {}
				false
			}

		// Export crashes activity
		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_export_crashes_key))
			?.setOnPreferenceClickListener { pref ->
				pref.context.startActivity<CrashManagerActivity> {}
				false
			}

		// Clear crashes (same CrashManager target currently)
		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_clear_crashes_key))
			?.setOnPreferenceClickListener { pref ->
				pref.context.startActivity<CrashManagerActivity> {}
				false
			}

		// Test crash
		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_test_crash_key))
			?.setOnPreferenceClickListener {
				throw RuntimeException("Test crash from debug menu - ${System.currentTimeMillis()}")
			}
	}

}

// Dialog state machine representing progress through dummy data seeding flow.
private sealed interface DummyDataSeedState {
	data object FirstConfirm : DummyDataSeedState
	data object Seeding : DummyDataSeedState
	data object SecondConfirm : DummyDataSeedState
	data class Result(val success: Boolean) : DummyDataSeedState
	data object Dismissed : DummyDataSeedState
}


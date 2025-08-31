package com.adsamcik.tracker.preference.pages

import android.app.NotificationManager
import android.content.Context
import android.util.TypedValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.debug.CrashManagerActivity
import com.adsamcik.tracker.app.activity.debug.CrashViewerActivity
import com.adsamcik.tracker.app.activity.debug.LogViewerActivity
import com.adsamcik.tracker.app.activity.debug.StatusActivity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.debug.DummyDataSeeder
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.base.notification.Notifications
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.dialog.alertDialog
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.callbacks.onShow
import com.afollestad.materialdialogs.actions.getActionButton
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

/**
 * Page with debug preferences.
 */
internal class DebugPage : PreferencePage {
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

			// This preference may not exist on all builds/layouts; set handler only if present
			caller.preferenceScreen
				?.findPreference<Preference>(caller.getString(R.string.settings_hello_world_key))
				?.setOnPreferenceClickListener {
			val context = it.context
			val resources = context.resources
			val helloWorld = context.getString(R.string.dev_notification_dummy)
			val color = ContextCompat.getColor(context, R.color.color_primary)
			val rng = Random(Time.nowMillis)
			val facts = resources.getStringArray(R.array.lorem_ipsum_facts)
			val notificationBuilder = NotificationCompat.Builder(
					context,
					resources.getString(R.string.channel_other_id)
			)
					.setSmallIcon(R.drawable.ic_signals)
					.setTicker(helloWorld)
					.setColor(color)
					.setLights(color, 2000, 5000)
					.setContentTitle(resources.getString(R.string.did_you_know))
					.setContentText(facts[rng.nextInt(facts.size)])
					.setWhen(Time.nowMillis)
			val notificationManager = it.context.getSystemService(
					Context.NOTIFICATION_SERVICE
			) as NotificationManager
			notificationManager.notify(
					Notifications.uniqueNotificationId(),
					notificationBuilder.build()
			)
				false
			}

		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_clear_preferences_key))
			?.setOnPreferenceClickListener { pref ->
					val context = pref.context
					MaterialDialog(context)
							.alertDialog(pref.title.toString()) {
								Preferences.getPref(context).edit {
									clear()
								}
							}
							.show()

					false
				}

		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_log_list_activity_key))
			?.setOnPreferenceClickListener {
					it.context.startActivity<LogViewerActivity> { }
					false
				}

		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_crash_viewer_key))
			?.setOnPreferenceClickListener {
					it.context.startActivity<CrashViewerActivity> { }
					false
				}

		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_export_crashes_key))
			?.setOnPreferenceClickListener { pref ->
					val context = pref.context
					context.startActivity<CrashManagerActivity> { }
					false
				}

		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_clear_crashes_key))
			?.setOnPreferenceClickListener { pref ->
					val context = pref.context
					context.startActivity<CrashManagerActivity> { }
					false
				}

		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_test_crash_key))
			?.setOnPreferenceClickListener { pref ->
					// Create a test crash for debugging purposes
					throw RuntimeException("Test crash from debug menu - ${System.currentTimeMillis()}")
				}

		caller.preferenceScreen
			?.findPreference<Preference>(caller.getString(R.string.settings_generate_dummy_data_key))
			?.setOnPreferenceClickListener { pref ->
					val context = pref.context
					MaterialDialog(context)
						.title(text = context.getString(com.adsamcik.tracker.shared.base.R.string.alert_confirm_generic))
						.alertDialog(context.getString(R.string.settings_generate_dummy_data_title)) {
							// First confirm
							@OptIn(DelicateCoroutinesApi::class)
							GlobalScope.launch(Dispatchers.Default) {
								val probe = DummyDataSeeder.seedIfEmpty(context)
								if (probe.inserted) {
									launch(Dispatchers.Main) {
										MaterialDialog(context).show {
											message(text = context.getString(R.string.dummy_data_generation_success))
											positiveButton(text = context.getString(com.adsamcik.tracker.shared.base.R.string.generic_ok))
										}
									}
								} else if (probe.reason == "not-empty") {
									// Second destructive confirm when DB not empty
									launch(Dispatchers.Main) {
										MaterialDialog(context).show {
											title(text = context.getString(R.string.dummy_data_second_confirm_title))
											message(text = context.getString(R.string.dummy_data_second_confirm_message))
											positiveButton(text = context.getString(com.adsamcik.tracker.shared.base.R.string.generic_yes)) { dialog ->
												@OptIn(DelicateCoroutinesApi::class)
												GlobalScope.launch(Dispatchers.Default) {
													val forced = DummyDataSeeder.seed(context)
													launch(Dispatchers.Main) {
														MaterialDialog(context).show {
															message(text = if (forced.inserted) context.getString(R.string.dummy_data_generation_success) else context.getString(R.string.dummy_data_generation_failed))
															positiveButton(text = context.getString(com.adsamcik.tracker.shared.base.R.string.generic_ok))
														}
													}
												}
											}
											negativeButton(text = context.getString(com.adsamcik.tracker.shared.base.R.string.generic_no))
											onShow {
												val tvBg = TypedValue()
												val hasBg = context.theme.resolveAttribute(
													R.attr.colorError,
													tvBg,
													true
												)
												val tvFg = TypedValue()
												val hasFg = context.theme.resolveAttribute(
													R.attr.colorOnError,
													tvFg,
													true
												)
												val bg = if (hasBg) tvBg.data else ContextCompat.getColor(context, R.color.error)
												val fg = if (hasFg) tvFg.data else Color.WHITE
												val btn = getActionButton(WhichButton.POSITIVE)
												btn.backgroundTintList = ColorStateList.valueOf(bg)
												btn.setTextColor(fg)
											}
										}
									}
								} else {
									launch(Dispatchers.Main) {
										MaterialDialog(context).show {
											message(text = context.getString(R.string.dummy_data_generation_failed))
											positiveButton(text = context.getString(com.adsamcik.tracker.shared.base.R.string.generic_ok))
										}
									}
								}
							}
						}
						.onShow { dialog ->
							val tvBg = TypedValue()
							val hasBg = context.theme.resolveAttribute(
								R.attr.colorError,
								tvBg,
								true
							)
							val tvFg = TypedValue()
							val hasFg = context.theme.resolveAttribute(
								R.attr.colorOnError,
								tvFg,
								true
							)
							val bg = if (hasBg) tvBg.data else ContextCompat.getColor(context, R.color.error)
							val fg = if (hasFg) tvFg.data else Color.WHITE
							val btn = dialog.getActionButton(WhichButton.POSITIVE)
							btn.backgroundTintList = ColorStateList.valueOf(bg)
							btn.setTextColor(fg)
						}
						.show()
					false
				}
	}

}


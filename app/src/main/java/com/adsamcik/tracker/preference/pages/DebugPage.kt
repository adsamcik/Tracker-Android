package com.adsamcik.tracker.preference.pages

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.debug.LogViewerActivity
import com.adsamcik.tracker.app.activity.debug.StatusActivity
import com.adsamcik.tracker.shared.base.notification.Notifications
import com.adsamcik.tracker.preference.findPreference
import com.adsamcik.tracker.preference.setOnClickListener
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.startActivity
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.dialog.alertDialog
import com.afollestad.materialdialogs.MaterialDialog
import java.util.*

/**
 * Page with debug preferences.
 */
internal class DebugPage : PreferencePage {
	override fun onExit(caller: PreferenceFragmentCompat): Unit = Unit

	override fun onEnter(caller: PreferenceFragmentCompat) {
		caller.setOnClickListener(R.string.settings_activity_status_key) {
			it.context.startActivity<StatusActivity> { }
		}

		caller.findPreference(R.string.settings_hello_world_key).setOnPreferenceClickListener {
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

		caller.findPreference(R.string.settings_clear_preferences_key)
				.setOnPreferenceClickListener { pref ->
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

		caller.findPreference(R.string.settings_log_list_activity_key)
				.setOnPreferenceClickListener {
					it.context.startActivity<LogViewerActivity> { }
					false
				}
	}

}


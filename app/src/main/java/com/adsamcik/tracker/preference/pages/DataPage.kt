package com.adsamcik.tracker.preference.pages

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.R
import com.adsamcik.tracker.dataimport.DataImport
import com.adsamcik.tracker.preference.findPreference
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.exception.PermissionException
import com.adsamcik.tracker.shared.base.extension.hasExternalStorageReadPermission
import com.afollestad.materialdialogs.MaterialDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Page with data options, such as import, export and clear.
 */
internal class DataPage : PreferencePage {
	override fun onEnter(caller: PreferenceFragmentCompat) {
		with(caller) {
			initializeImport(requireActivity(), findPreference(R.string.settings_import_key))

			initializeDelete(findPreference(R.string.settings_remove_all_collected_data_key))
		}
	}

	private fun initializeDelete(deletePreference: Preference) {
		deletePreference.setOnPreferenceClickListener { preference ->
			val context = preference.context
			MaterialDialog(context).show {
				title(text = context.getString(R.string.alert_confirm_generic))
				message(
						text = context.getString(
								R.string.alert_confirm,
								context.getString(R.string.settings_remove_all_collected_data_title)
						)
				)

				positiveButton {
					GlobalScope.launch(Dispatchers.Default) {
						AppDatabase.deleteAllCollectedData(context)
					}
				}
				negativeButton { it.dismiss() }
			}
			true
		}
	}

	private fun initializeImport(activity: Activity, importPreference: Preference) {
		importPreference.apply {
			val dataImport = DataImport()
			val supportedExtensions = dataImport.supportedImporterExtensions

			if (supportedExtensions.isEmpty()) {
				setSummary(R.string.settings_import_no_types)
				isEnabled = false
			} else {
				val archiveExtensions = dataImport
						.supportedArchiveExtractorExtensions
						.joinToString(separator = SEPARATOR)

				val fileExtensions = supportedExtensions.joinToString(separator = SEPARATOR)

				summary = importPreference.context.getString(
						R.string.settings_import_summary,
						fileExtensions,
						archiveExtensions
				)

				setOnPreferenceClickListener {
					if (requireImportPermissions(activity)) {
						openImportDialog(it.context)
					}
					true
				}
			}
		}
	}

	private fun requireImportPermissions(activity: Activity): Boolean {
		return when {
			validateImportPermissions(activity) -> true
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
				activity.requestPermissions(
						arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
						PERMISSION_READ_EXTERNAL_REQUEST
				)
				false
			}
			else -> throw PermissionException("Permission to read external storage is missing")
		}
	}

	private fun validateImportPermissions(context: Context): Boolean {
		return context.hasExternalStorageReadPermission
	}

	private fun openImportDialog(context: Context) {
		DataImport().showImportDialog(context)
	}

	override fun onExit(caller: PreferenceFragmentCompat): Unit = Unit

	override fun onRequestPermissionsResult(
			context: Context,
			code: Int,
			result: Collection<Pair<String, Int>>
	) {
		when (code) {
			PERMISSION_READ_EXTERNAL_REQUEST -> {
				val isSuccessful = result.all { it.second == PackageManager.PERMISSION_GRANTED }

				if (isSuccessful) {
					openImportDialog(context)
				}
			}
		}
	}

	companion object {
		private const val PERMISSION_READ_EXTERNAL_REQUEST = 857854
		private const val SEPARATOR = ", "
	}
}


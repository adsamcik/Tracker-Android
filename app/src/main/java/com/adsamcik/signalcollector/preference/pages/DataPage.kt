package com.adsamcik.signalcollector.preference.pages

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.exception.PermissionException
import com.adsamcik.signalcollector.import.DataImport
import com.adsamcik.signalcollector.preference.findPreference

class DataPage : PreferencePage {
	override fun onEnter(caller: PreferenceFragmentCompat) {
		with(caller) {
			findPreference(R.string.settings_import_key).apply {
				val dataImport = DataImport()
				val supportedExtensions = dataImport.supportedImporterExtensions

				if (supportedExtensions.isEmpty()) {
					summary = getString(R.string.settings_import_no_types)
					isEnabled = false
				} else {
					val archiveExtensions = dataImport
							.supportedArchiveExtractorExtensions
							.joinToString(separator = SEPARATOR)

					val fileExtensions = supportedExtensions.joinToString(separator = SEPARATOR)

					summary = getString(R.string.settings_import_summary,
							fileExtensions,
							archiveExtensions)

					setOnPreferenceClickListener {
						if (requireImportPermissions(caller.requireActivity())) {
							openImportDialog(it.context)
						}
						true
					}
				}
			}
		}
	}

	private fun requireImportPermissions(activity: Activity): Boolean {
		return when {
			validateImportPermissions(activity) -> true
			Build.VERSION.SDK_INT >= 23 -> {
				activity.requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_READ_EXTERNAL_REQUEST)
				false
			}
			else -> throw PermissionException("Permission to read external storage is missing")
		}
	}

	private fun validateImportPermissions(context: Context): Boolean {
		return ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
	}

	private fun openImportDialog(context: Context) {
		DataImport().showImportDialog(context)
	}

	override fun onExit(caller: PreferenceFragmentCompat) {

	}

	override fun onRequestPermissionsResult(context: Context, code: Int, result: Collection<Pair<String, Int>>) {
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
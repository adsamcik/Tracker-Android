package com.adsamcik.tracker.preference.pages

import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.R
import com.adsamcik.tracker.impexp.importer.DataImport
import com.adsamcik.tracker.impexp.importer.DataImporter
import com.adsamcik.tracker.preference.findPreference
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.afollestad.materialdialogs.MaterialDialog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Page with data options, such as import, export and clear.
 */
internal class DataPage : PreferencePage {
	private lateinit var importRequest: ActivityResultLauncher<Intent>

	override fun onEnter(caller: PreferenceFragmentCompat) {
		with(caller) {
			initializeImport(findPreference(R.string.settings_import_key))

			initializeDelete(findPreference(R.string.settings_remove_all_collected_data_key))
		}
	}

	@OptIn(DelicateCoroutinesApi::class)
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

	private fun initializeImport(importPreference: Preference) {
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
					openImportDialog()
					true
				}
			}
		}
	}

	private fun openImportDialog() {
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
			// multi type does not work on Android default file selector
			type = "*/*"
		}
		importRequest.launch(intent)
	}

	override fun onExit(caller: PreferenceFragmentCompat): Unit = Unit

	override fun onRegisterForResult(activity: FragmentActivity) {
		importRequest = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult())
		{ result: ActivityResult ->
			if (result.resultCode == AppCompatActivity.RESULT_OK) {
				result.data?.data?.also { uri ->
					DataImporter.import(activity, uri)
				}
			}
		}
	}

	companion object {
		private const val SEPARATOR = ", "
	}
}


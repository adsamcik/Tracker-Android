package com.adsamcik.tracker.preference.pages

import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.R
import com.adsamcik.tracker.common.extension.startActivity
import com.adsamcik.tracker.export.DatabaseExportFile
import com.adsamcik.tracker.export.GpxExportFile
import com.adsamcik.tracker.export.KmlExportFile
import com.adsamcik.tracker.export.activity.ExportActivity
import com.adsamcik.tracker.preference.setOnClickListener

class ExportPage : PreferencePage {
	override fun onExit(caller: PreferenceFragmentCompat) {}

	override fun onEnter(caller: PreferenceFragmentCompat) {
		with(caller) {
			setOnClickListener(R.string.settings_export_gpx_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, GpxExportFile::class.java)
				}
			}

			setOnClickListener(R.string.settings_export_kml_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, KmlExportFile::class.java)
				}
			}

			setOnClickListener(R.string.settings_export_sqlite_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, DatabaseExportFile::class.java)
				}
			}
		}
	}

}

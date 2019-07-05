package com.adsamcik.signalcollector.preference.pages

import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.extension.startActivity
import com.adsamcik.signalcollector.export.DatabaseExportFile
import com.adsamcik.signalcollector.export.GpxExportFile
import com.adsamcik.signalcollector.export.KmlExportFile
import com.adsamcik.signalcollector.export.activity.ExportActivity
import com.adsamcik.signalcollector.preference.setOnClickListener

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
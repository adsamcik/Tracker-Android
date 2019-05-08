package com.adsamcik.signalcollector.preference.pages

import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.misc.extension.startActivity
import com.adsamcik.signalcollector.export.DatabaseExport
import com.adsamcik.signalcollector.export.GpxExport
import com.adsamcik.signalcollector.export.KmlExport
import com.adsamcik.signalcollector.export.activity.ExportActivity
import com.adsamcik.signalcollector.preference.setOnClickListener

class DataPage : PreferencePage {
	override fun onExit(caller: PreferenceFragmentCompat) {}

	override fun onEnter(caller: PreferenceFragmentCompat) {
		with(caller) {
			setOnClickListener(R.string.settings_export_gpx_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, GpxExport::class.java)
				}
			}

			setOnClickListener(R.string.settings_export_kml_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, KmlExport::class.java)
				}
			}

			setOnClickListener(R.string.settings_export_sqlite_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, DatabaseExport::class.java)
				}
			}
		}
	}

}
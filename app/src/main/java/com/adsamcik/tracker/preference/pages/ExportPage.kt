package com.adsamcik.tracker.preference.pages

import androidx.preference.PreferenceFragmentCompat
import com.adsamcik.tracker.R
import com.adsamcik.tracker.impexp.exporter.DatabaseExporter
import com.adsamcik.tracker.impexp.exporter.GpxExporter
import com.adsamcik.tracker.impexp.exporter.KmlExporter
import com.adsamcik.tracker.impexp.exporter.activity.ExportActivity
import com.adsamcik.tracker.preference.setOnClickListener
import com.adsamcik.tracker.shared.base.extension.startActivity

/**
 * Preference page for exports.
 */
internal class ExportPage : PreferencePage {
	override fun onExit(caller: PreferenceFragmentCompat) = Unit

	override fun onEnter(caller: PreferenceFragmentCompat) {
		with(caller) {
			setOnClickListener(R.string.settings_export_gpx_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, GpxExporter::class.java)
				}
			}

			setOnClickListener(R.string.settings_export_kml_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, KmlExporter::class.java)
				}
			}

			setOnClickListener(R.string.settings_export_sqlite_key) {
				startActivity<ExportActivity> {
					putExtra(ExportActivity.EXPORTER_KEY, DatabaseExporter::class.java)
				}
			}
		}
	}
}

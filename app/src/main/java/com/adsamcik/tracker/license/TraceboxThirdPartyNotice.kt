package com.adsamcik.tracker.license

import android.content.res.Resources
import de.psdev.licensesdialog.model.Notice
import dev.tracebox.R as TraceboxResources

/**
 * Notice bundled by Tracebox for the exact pinned native components embedded in its AAR.
 *
 * Keeping the canonical text in Tracebox means every host receives the same verified notices.
 * Tracker only adapts that resource to its existing third-party-license UI.
 */
internal object TraceboxThirdPartyNotice {
	const val DISPLAY_NAME = "Tracebox embedded Crashpad components"

	private val requiredSections = listOf(
		"crashpad",
		"mini_chromium",
		"linux_syscall_support",
		"zlib",
		"googletest",
		"chromium_buildtools",
	)

	fun load(resources: Resources): LicenseObject {
		val text = resources
			.openRawResource(TraceboxResources.raw.tracebox_third_party_notices)
			.bufferedReader(Charsets.UTF_8)
			.use { it.readText() }
		check(text.isNotBlank()) { "Tracebox third-party notices are empty" }
		requiredSections.forEach { section ->
			check("===== BEGIN $section =====" in text && "===== END $section =====" in text) {
				"Tracebox third-party notices are missing $section"
			}
		}
		return CustomLicense(
			name = DISPLAY_NAME,
			notice = Notice(
				DISPLAY_NAME,
				null,
				null,
				CustomResourceLicense(text),
			),
		)
	}
}

package com.adsamcik.tracker.license

import de.psdev.licensesdialog.model.Notice

interface LicenseObject {
	val name: String
	val notice: Notice
}

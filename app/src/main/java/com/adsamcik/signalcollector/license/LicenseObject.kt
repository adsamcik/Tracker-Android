package com.adsamcik.signalcollector.license

import de.psdev.licensesdialog.model.Notice

interface LicenseObject {
	val name: String
	val notice: Notice
}

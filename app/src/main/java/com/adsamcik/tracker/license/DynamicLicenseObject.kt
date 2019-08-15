package com.adsamcik.tracker.license

import de.psdev.licensesdialog.model.Notice

class DynamicLicenseObject(override val name: String, val builder: () -> Notice) : LicenseObject {
	override val notice: Notice get() = builder.invoke()
}


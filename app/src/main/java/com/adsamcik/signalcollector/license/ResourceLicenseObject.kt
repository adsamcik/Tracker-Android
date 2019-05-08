package com.adsamcik.signalcollector.license

import android.content.Context
import android.content.res.Resources
import androidx.annotation.RawRes
import com.adsamcik.signalcollector.R
import de.psdev.licensesdialog.licenses.ApacheSoftwareLicense20
import de.psdev.licensesdialog.licenses.License
import de.psdev.licensesdialog.licenses.MITLicense
import de.psdev.licensesdialog.model.Notice
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class ResourceLicenseObject(override val name: String, val from: Int, val length: Int, val resources: Resources) : LicenseObject {
	override val notice: Notice
		get() = resolveNotice()

	private fun openStream(@RawRes rawRes: Int): InputStream {
		return resources.openRawResource(rawRes)
	}

	private fun resolveNotice(): Notice {
		val lowerName = name.toLowerCase()
		val resolvedLicense = getLicense()
		if (lowerName.startsWith("stag"))
			return Notice(name,
					"https://github.com/vimeo/stag-java",
					"Copyright (c) 2016 Vimeo",
					resolvedLicense)

		return when (lowerName) {
			"slider" -> Notice("Slider",
					"https://github.com/adsamcik/Slider",
					"Copyright 2018 Adsamcik",
					resolvedLicense)
			"draggable" -> Notice("Draggable",
					"https://github.com/adsamcik/Draggable",
					"Copyright 2018 Adsamcik",
					resolvedLicense)
			"table" -> Notice("Table",
					"https://github.com/adsamcik/Table",
					"Copyright 2017 Adsamcik",
					resolvedLicense)
			"touchdelegate" -> Notice("Touch delegate",
					"https://github.com/adsamcik/TouchDelegate",
					"Copyright 2017 Adsamcik",
					resolvedLicense)
			"spotlight" -> Notice("Spotlight",
					"https://github.com/TakuSemba/Spotlight",
					"Copyright 2017 Taku Semba",
					resolvedLicense)
			"colorpicker" -> Notice("ColorPicker\n",
					"https://github.com/jaredrummler/ColorPicker",
					null,
					resolvedLicense)
			else -> {
				Notice(name, null, null, resolvedLicense)
			}
		}
	}


	private fun getLicense(): License {
		val licenseText = loadLicense()
		return if (licenseText.startsWith("http://www.apache.org/licenses/LICENSE-2.0") || licenseText.startsWith("https://api.github.com/licenses/apache-2.0"))
			ApacheSoftwareLicense20()
		else if (licenseText.startsWith("http://www.opensource.org/licenses/mit-license"))
			MITLicense()
		else
			CustomResourceLicense(licenseText)
	}

	private fun loadLicense(): String {
		val buffer = CharArray(length)

		openStream(R.raw.third_party_licenses).use {
			if (from > 0)
				it.skip(from.toLong())

			val reader = InputStreamReader(it, StandardCharsets.UTF_8)
			reader.read(buffer, 0, length)
			return String(buffer)
		}
	}
}

class CustomResourceLicense(val licenseText: String) : License() {
	override fun getUrl(): String = ""

	override fun getName(): String = ""

	override fun readSummaryTextFromResources(context: Context?): String = this.licenseText

	override fun getVersion(): String = ""

	override fun readFullTextFromResources(context: Context?): String = this.licenseText
}
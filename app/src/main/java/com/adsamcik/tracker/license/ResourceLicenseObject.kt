package com.adsamcik.tracker.license

import android.content.Context
import android.content.res.Resources
import androidx.annotation.RawRes
import com.adsamcik.tracker.R
import de.psdev.licensesdialog.licenses.License
import de.psdev.licensesdialog.model.Notice
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

class ResourceLicenseObject(
		override val name: String,
		val from: Int,
		val length: Int,
		val resources: Resources,
		@RawRes val licenseTextRawRes: Int = R.raw.third_party_licenses
) :
		LicenseObject {
	override val notice: Notice
		get() = resolveNotice()

	private fun openStream(@RawRes rawRes: Int): InputStream {
		return resources.openRawResource(rawRes)
	}

	private fun resolveNotice(): Notice {
		val lowerName = name.lowercase(Locale.getDefault())
		val resolvedLicense = getLicense()
		if (lowerName.startsWith("stag")) {
			return Notice(
					name,
					null,
					"Copyright (c) 2016 Vimeo",
					resolvedLicense
			)
		}

		return when (lowerName) {
			"slider" -> Notice(
					"Slider",
					null,
					"Copyright 2018 Adsamcik",
					resolvedLicense
			)
			"table" -> Notice(
					"Table",
					null,
					"Copyright 2017 Adsamcik",
					resolvedLicense
			)
			"touchdelegate" -> Notice(
					"Touch delegate",
					null,
					"Copyright 2017 Adsamcik",
					resolvedLicense
			)
			"spotlight" -> Notice(
					"Spotlight",
					null,
					"Copyright 2017 Taku Semba",
					resolvedLicense
			)
			"colorpicker" -> Notice(
					"ColorPicker\n",
					null,
					null,
					resolvedLicense
			)
			else -> Notice(name, null, null, resolvedLicense)
		}
	}


	private fun getLicense(): License {
		return CustomResourceLicense(loadLicense())
	}

	private fun loadLicense(): String {
		val buffer = CharArray(length)

		openStream(licenseTextRawRes).use {
			if (from > 0) it.skip(from.toLong())

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

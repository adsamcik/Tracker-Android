package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.support.annotation.RawRes
import android.view.ViewGroup
import android.widget.Button
import com.adsamcik.signalcollector.R
import com.adsamcik.signals.utilities.Preferences
import com.crashlytics.android.Crashlytics
import de.psdev.licensesdialog.LicensesDialog
import de.psdev.licensesdialog.licenses.ApacheSoftwareLicense20
import de.psdev.licensesdialog.licenses.License
import de.psdev.licensesdialog.licenses.MITLicense
import de.psdev.licensesdialog.model.Notice
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class LicenseActivity : DetailActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Preferences.setTheme(this)
        super.onCreate(savedInstanceState)
        val parent = createScrollableContentParent(true)

        val GSON = "Gson"
        addButton(parent, GSON).setOnClickListener { _ ->
            val notice = Notice(GSON, "https://github.com/google/gson", "Copyright 2008 Google Inc", ApacheSoftwareLicense20())
            LicensesDialog.Builder(this)
                    .setNotices(notice)
                    .build()
                    .show()
        }

        val OKHTTP = "OkHttp"
        addButton(parent, OKHTTP).setOnClickListener { _ ->
            val notice = Notice(OKHTTP, "https://github.com/square/okhttp", "Copyright 2016 Square, Inc.", ApacheSoftwareLicense20())
            LicensesDialog.Builder(this)
                    .setNotices(notice)
                    .build()
                    .show()
        }

        val LICENSE_DIALOG = "LicensesDialog"
        addButton(parent, LICENSE_DIALOG).setOnClickListener { _ ->
            val notice = Notice(LICENSE_DIALOG, "https://github.com/PSDev/LicensesDialog", "Copyright 2013-2017 Philip Schiffer", ApacheSoftwareLicense20())
            LicensesDialog.Builder(this)
                    .setNotices(notice)
                    .build()
                    .show()
        }

        val isMeta = resources.openRawResource(R.raw.third_party_license_metadata)

        val rMeta = BufferedReader(InputStreamReader(isMeta))

        try {
            rMeta.forEachLine { line ->
                val split = line.split(' ')
                val fromTo = split[0].split(':')
                val lObject = LicenseObject(split[1], fromTo[0].toInt() - 1, fromTo[1].toInt())
                val button = addButton(parent, lObject.name)
                addLicenseDialogListener(button, lObject)
            }
        } catch (e: IOException) {
            Crashlytics.logException(e)
        }

        setTitle(R.string.open_source_licenses)
    }

    private fun openStream(@RawRes rawRes: Int): InputStream {
        return resources.openRawResource(rawRes)
    }

    private fun readLicense(license: LicenseObject): String {
        val buffer = CharArray(license.length)
        val stream = openStream(R.raw.third_party_licenses)

        if (license.from > 0)
            stream.skip(license.from.toLong())

        val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
        reader.read(buffer, 0, license.length)
        reader.close()
        return String(buffer)
    }

    private fun addButton(parent: ViewGroup, name: String): Button {
        val button = Button(this)
        button.text = name
        parent.addView(button)
        return button
    }

    private fun addLicenseDialogListener(button: Button, license: LicenseObject) {
        button.setOnClickListener { _ ->
            val notice = resolveNotice(license)
            LicensesDialog.Builder(this)
                    .setNotices(notice)
                    .build()
                    .show()
        }
    }

    private fun resolveNotice(license: LicenseObject): Notice {
        val lowerName = license.name.toLowerCase()
        return when {
            lowerName.startsWith("stag") -> Notice(license.name, "https://github.com/vimeo/stag-java", "Copyright (c) 2016 Vimeo", MITLicense())
            lowerName == "appintro" -> Notice("AppIntro", "https://github.com/apl-devs/AppIntro", "Copyright 2015 Paolo Rotolo\n" + "Copyright 2016 Maximilian Narr", ApacheSoftwareLicense20())
            lowerName == "persistentcookiejar" -> Notice("PersistentCookieJar", "https://github.com/franmontiel/PersistentCookieJar", "Copyright 2016 Francisco José Montiel Navarro", ApacheSoftwareLicense20())
            else -> {
                val readLicense = readLicense(license)
                val resolvedLicense = resolveLicense(readLicense)
                if (resolvedLicense == null)
                    Notice(license.name, null, readLicense, null)
                else
                    Notice(license.name, null, null, resolvedLicense)
            }
        }
    }

    private fun resolveLicense(license: String): License? {
        return if (license.startsWith("http://www.apache.org/licenses/LICENSE-2.0") || license.startsWith("https://api.github.com/licenses/apache-2.0"))
            ApacheSoftwareLicense20()
        else if (license.startsWith("http://www.opensource.org/licenses/mit-license"))
            MITLicense()
        else
            null
    }

    class LicenseObject(val name: String, val from: Int, val length: Int)
}

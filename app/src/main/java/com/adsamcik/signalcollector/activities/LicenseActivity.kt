package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.utility.Preferences
import com.google.firebase.crash.FirebaseCrash
import de.psdev.licensesdialog.LicensesDialog
import de.psdev.licensesdialog.licenses.ApacheSoftwareLicense20
import de.psdev.licensesdialog.licenses.License
import de.psdev.licensesdialog.licenses.MITLicense
import de.psdev.licensesdialog.model.Notice
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

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
        val isLicense = resources.openRawResource(R.raw.third_party_licenses)
        val rMeta = BufferedReader(InputStreamReader(isMeta))
        val rLicense = BufferedReader(InputStreamReader(isLicense))

        try {
            rMeta.forEachLine { line ->
                val firstSpace = line.indexOf(' ')
                val length = line.length
                val name = line.substring(firstSpace + 1, length)
                val button = addButton(parent, name)
                addLicenseDialogListener(button, name, rLicense.readLine())
            }
        } catch (e: IOException) {
            FirebaseCrash.report(e)
        }

        setTitle(R.string.open_source_licenses)
    }

    private fun addButton(parent: ViewGroup, name: String): Button {
        val button = Button(this)
        button.text = name
        parent.addView(button)
        return button
    }

    private fun addLicenseDialogListener(button: Button, name: String, licenseURL: String) {
        button.setOnClickListener { _ ->
            val notice = resolveNotice(name, licenseURL)
            LicensesDialog.Builder(this)
                    .setNotices(notice)
                    .build()
                    .show()
        }
    }

    private fun resolveNotice(name: String, licenseURL: String): Notice {
        val lowerName = name.toLowerCase()
        return when {
            lowerName.startsWith("stag") -> Notice(name, "https://github.com/vimeo/stag-java", "Copyright (c) 2016 Vimeo", MITLicense())
            lowerName == "appintro" -> Notice("AppIntro", "https://github.com/apl-devs/AppIntro", "Copyright 2015 Paolo Rotolo\n" + "Copyright 2016 Maximilian Narr", ApacheSoftwareLicense20())
            lowerName == "persistentcookiejar" -> Notice("PersistentCookieJar", "https://github.com/franmontiel/PersistentCookieJar", "Copyright 2016 Francisco José Montiel Navarro", ApacheSoftwareLicense20())
            else -> {
                val license = resolveLicense(licenseURL)
                if (license == null)
                    Notice(name, null, licenseURL, null)
                else
                    Notice(name, null, null, license)
            }
        }
    }

    private fun resolveLicense(url: String): License? {
        return if (url.startsWith("http://www.apache.org/licenses/LICENSE-2.0") || url.startsWith("https://api.github.com/licenses/apache-2.0"))
            ApacheSoftwareLicense20()
        else if (url.startsWith("http://www.opensource.org/licenses/mit-license"))
            MITLicense()
        else
            null
    }
}

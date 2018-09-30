package com.adsamcik.signalcollector.activities

import android.os.Bundle
import androidx.annotation.RawRes
import android.view.ViewGroup
import android.widget.Button
import com.adsamcik.signalcollector.R
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

/**
 * LicenseActivity shows users all the open source licenses for dependencies this app uses.
 * For some specific libraries there are manually filled in URLs and copyrights. For most licenses it uses Google's automatically generated list.
 */
class LicenseActivity : DetailActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parent = createLinearScrollableContentParent(true)

        val gsonString = "Gson"
        addButton(parent, gsonString).setOnClickListener { _ ->
            val notice = Notice(gsonString, "https://github.com/google/gson", "Copyright 2008 Google Inc", ApacheSoftwareLicense20())
            LicensesDialog.Builder(this)
                    .setNotices(notice)
                    .build()
                    .show()
        }

        val okhttpString = "OkHttp"
        addButton(parent, okhttpString).setOnClickListener { _ ->
            val notice = Notice(okhttpString, "https://github.com/square/okhttp", "Copyright 2016 Square, Inc.", ApacheSoftwareLicense20())
            LicensesDialog.Builder(this)
                    .setNotices(notice)
                    .build()
                    .show()
        }

        val licenseDialogString = "LicensesDialog"
        addButton(parent, licenseDialogString).setOnClickListener { _ ->
            val notice = Notice(licenseDialogString, "https://github.com/PSDev/LicensesDialog", "Copyright 2013-2017 Philip Schiffer", ApacheSoftwareLicense20())
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
                val lObject = LicenseObject(split[1], fromTo[0].toInt(), fromTo[1].toInt())
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
        if (lowerName.startsWith("stag"))
            return Notice(license.name,
                    "https://github.com/vimeo/stag-java",
                    "Copyright (c) 2016 Vimeo",
                    resolveLicense(readLicense(license)))

        return when (lowerName) {
            "appintro" -> Notice("AppIntro",
                    "https://github.com/apl-devs/AppIntro",
                    "Copyright 2015 Paolo Rotolo\nCopyright 2016 Maximilian Narr",
                    resolveLicense(readLicense(license)))
            "persistentcookiejar" -> Notice("PersistentCookieJar",
                    "https://github.com/franmontiel/PersistentCookieJar",
                    "Copyright 2016 Francisco José Montiel Navarro",
                    resolveLicense(readLicense(license)))
            "slider" -> Notice("Slider",
                    "https://github.com/adsamcik/Slider",
                    "Copyright 2018 Adsamcik",
                    resolveLicense(readLicense(license)))
            "draggable" -> Notice("Draggable",
                    "https://github.com/adsamcik/Draggable",
                    "Copyright 2018 Adsamcik",
                    resolveLicense(readLicense(license)))
            "table" -> Notice("Table",
                    "https://github.com/adsamcik/Table",
                    "Copyright 2017 Adsamcik",
                    resolveLicense(readLicense(license)))
            "touchdelegate" -> Notice("Touch delegate",
                    "https://github.com/adsamcik/TouchDelegate",
                    "Copyright 2017 Adsamcik",
                    resolveLicense(readLicense(license)))
            "spotlight" -> Notice("Spotlight",
                    "https://github.com/TakuSemba/Spotlight",
                    "Copyright 2017 Taku Semba",
                    resolveLicense(readLicense(license)))
            "colorpicker" -> Notice("ColorPicker\n",
                    "https://github.com/jaredrummler/ColorPicker",
                    null,
                    resolveLicense(readLicense(license)))
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

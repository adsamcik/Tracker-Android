package com.adsamcik.tracker.license

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.debug.Reporter
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.misc.SnackMaker
import com.adsamcik.tracker.shared.utils.activity.DetailActivity
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
import de.psdev.licensesdialog.LicensesDialog
import de.psdev.licensesdialog.licenses.ApacheSoftwareLicense20
import de.psdev.licensesdialog.licenses.License
import de.psdev.licensesdialog.model.Notice
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * LicenseActivity shows users all the open source licenses for dependencies this app uses.
 * For some specific libraries there are manually filled in URLs and copyrights. For most licenses it uses Google's automatically generated list.
 */
class LicenseActivity : DetailActivity() {

	override fun onConfigure(configuration: Configuration) {
		configuration.elevation = 4.dp
		configuration.titleBarLayer = 1
		configuration.useColorControllerForContent = true
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val frameLayout = createFrameContentLayout(false)

		val adapter = Adapter()
		val recycler = RecyclerView(this)
				.apply {
					layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
					layoutManager = LinearLayoutManager(
							this@LicenseActivity
					).apply { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
					this.adapter = adapter
				}
				.also { frameLayout.addView(it) }


		val licenseDialogString = "LicensesDialog"

		CustomLicense(
				licenseDialogString, Notice(
				licenseDialogString, "https://github.com/PSDev/LicensesDialog",
				"Copyright 2013-2017 Philip Schiffer", ApacheSoftwareLicense20()
		)
		)
				.also { adapter.addLicense(it) }

		val materialCommunityIconTitle = "Material Design Community Icons"

		CustomLicense(
				materialCommunityIconTitle,
				Notice(materialCommunityIconTitle, "http://materialdesignicons.com/",
				       "Copyright (c) 2014, Austin Andrews", object : License() {
					override fun getUrl(): String =
							"https://scripts.sil.org/cms/scripts/page.php?item_id=OFL_web"

					override fun getName(): String = "SIL OPEN FONT LICENSE Version 1.1"

					override fun readSummaryTextFromResources(context: Context?): String =
							getContent(
									context,
									R.raw.material_community_icon_license
							)

					override fun getVersion(): String = "1.1"
					override fun readFullTextFromResources(context: Context?): String = getContent(
							context,
							R.raw.material_community_icon_license
					)

				})
		).also { adapter.addLicense(it) }

		val resources = resources
		val isMeta = resources.openRawResource(R.raw.third_party_license_metadata)

		val rMeta = BufferedReader(InputStreamReader(isMeta))

		try {
			rMeta.forEachLine { line ->
				val split = line.split(' ')
				val fromTo = split[0].split(':')
				val license = ResourceLicenseObject(
						split[1],
						fromTo[0].toInt(),
						fromTo[1].toInt(),
						resources
				)
				adapter.addLicense(license)
			}
		} catch (e: IOException) {
			Reporter.report(e)
		}

		adapter.notifyDataSetChanged()

		setTitle(R.string.open_source_licenses)

		styleController.watchRecyclerView(RecyclerStyleView(recycler, 0))
		styleController.watchView(StyleView(frameLayout, 0, 0))
	}

	private class Adapter : RecyclerView.Adapter<Adapter.ViewHolder>(),
			IViewChange {
		private val licenses = mutableListOf<LicenseObject>()

		override var onViewChangedListener: ((View) -> Unit)? = null

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val root = LayoutInflater.from(parent.context)
					.inflate(R.layout.layout_license_item, parent, false)
			return ViewHolder(root.findViewById(R.id.button), root)
		}

		override fun onViewAttachedToWindow(holder: ViewHolder) {
			super.onViewAttachedToWindow(holder)
			onViewChangedListener?.invoke(holder.itemView)
		}

		override fun getItemCount(): Int = licenses.size

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val license = licenses[position]
			holder.button.text = license.name
			holder.button.tag = license
			holder.button.setOnClickListener(this::onButtonClicked)
		}

		private fun onButtonClicked(view: View) {
			showLicense(view, view.tag as LicenseObject)
		}

		private fun showLicense(view: View, licenseObject: LicenseObject) {
			val isDialogCreated = tryWithReport {
				val notice = licenseObject.notice
				LicensesDialog.Builder(view.context)
						.setNotices(notice)
						.build()
						.show()
			}

			if (!isDialogCreated) {
				SnackMaker(view).addMessage(R.string.error_failed_to_open_license)
			}
		}

		fun addLicense(license: LicenseObject) {
			licenses.add(license)
		}

		private class ViewHolder(val button: AppCompatButton, root: View) : RecyclerView.ViewHolder(
				root
		)
	}
}


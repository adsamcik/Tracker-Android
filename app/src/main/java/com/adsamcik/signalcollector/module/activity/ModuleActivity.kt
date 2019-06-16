package com.adsamcik.signalcollector.module.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.activity.DetailActivity
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.color.IViewChange
import com.adsamcik.signalcollector.common.misc.extension.dp
import com.adsamcik.signalcollector.common.recycler.decoration.SimpleMarginDecoration
import com.adsamcik.signalcollector.module.Module
import com.adsamcik.signalcollector.module.ModuleInfo
import com.google.android.play.core.splitinstall.*
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.android.synthetic.main.activity_module.*

class ModuleActivity : DetailActivity() {
	private lateinit var manager: SplitInstallManager

	private lateinit var adapter: ModuleAdapter

	private val listener = SplitInstallStateUpdatedListener { state ->
		val langsInstall = state.languages().isNotEmpty()

		when (state.status()) {
			SplitInstallSessionStatus.DOWNLOADING -> {
				//  In order to see this, the application has to be uploaded to the Play Store.
				displayLoadingState(state, getString(R.string.module_download_progress,
						Assist.humanReadableByteCount(state.bytesDownloaded(), true),
						Assist.humanReadableByteCount(state.totalBytesToDownload(), true)))
			}
			SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> {
				/*
				  This may occur when attempting to download a sufficiently large module.
				  In order to see this, the application has to be uploaded to the Play Store.
				  Then features can be requested until the confirmation path is triggered.
				 */
				manager.startConfirmationDialogForResult(state, this, CONFIRMATION_REQUEST_CODE)
			}
			SplitInstallSessionStatus.INSTALLED -> {
				if (langsInstall) {
					//onSuccessfulLanguageLoad(names)
				} else {
					onLoadSuccess()
				}

				finish()
			}

			SplitInstallSessionStatus.INSTALLING -> displayLoadingState(
					state,
					getString(R.string.module_installing, state.moduleNames().joinToString())
			)
			SplitInstallSessionStatus.FAILED -> {
				toast(getString(R.string.module_error, state.moduleNames(), state.errorCode()))
			}
		}
	}

	private fun displayLoadingState(state: SplitInstallSessionState, message: String) {
		progress_layout.visibility = View.VISIBLE

		progress.max = state.totalBytesToDownload().toInt()
		progress.progress = state.bytesDownloaded().toInt()

		progress_title.text = message
	}

	private fun onLoadSuccess() {
		toast(getString(R.string.module_success))
		finish()
	}

	private fun toast(text: String) {
		Toast.makeText(this, text, Toast.LENGTH_LONG).show()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		inflateContent(R.layout.activity_module)

		setTitle(R.string.settings_module_group_title)

		manager = SplitInstallManagerFactory.create(this)

		val adapter = ModuleAdapter()


		colorController.watchView(ColorView(root, 0))
		colorController.watchRecyclerView(ColorView(recycler, 0))

		val moduleInfoList = Module.getActiveModuleInfo(manager)

		adapter.addModules(moduleInfoList)

		recycler.adapter = adapter
		this.adapter = adapter

		val layoutManager = LinearLayoutManager(this)
		recycler.layoutManager = layoutManager

		val edgeMargin = resources.getDimension(R.dimen.activity_horizontal_margin).toInt()
		recycler.addItemDecoration(SimpleMarginDecoration(16.dp, edgeMargin))

		recycler.post {
			val allVisible = layoutManager.findLastCompletelyVisibleItemPosition() == adapter.itemCount - 1
			if (allVisible)
				recycler.overScrollMode = View.OVER_SCROLL_NEVER
		}

		button_cancel.setOnClickListener { finish() }

		button_ok.setOnClickListener { updateModules() }
	}

	private fun updateModules() {
		val toInstall = adapter.modulesToInstall
		val toRemove = adapter.modulesToUninstall

		if (toInstall.isNotEmpty()) {
			val request = SplitInstallRequest.newBuilder()
			toInstall.forEach { request.addModule(it.module.moduleName) }
			manager.startInstall(request.build())
		}

		if (toRemove.isNotEmpty()) {
			manager.deferredUninstall(toRemove.map { it.module.moduleName })
		}

		if (toInstall.isEmpty())
			finish()
	}


	override fun onResume() {
		super.onResume()
		manager.registerListener(listener)
	}

	override fun onPause() {
		super.onPause()
		manager.unregisterListener(listener)
	}

	class ModuleAdapter : RecyclerView.Adapter<ModuleAdapter.ViewHolder>(), IViewChange {
		override var onViewChangedListener: ((View) -> Unit)? = null

		private val modules = mutableListOf<ModuleInfo>()

		val modulesToInstall get() = modules.filter { it.shouldBeInstalled.and(!it.isInstalled) }
		val modulesToUninstall get() = modules.filter { (!it.shouldBeInstalled).and(it.isInstalled) }

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_recycler_toggle_item, parent, false)
			val checkbox = view.findViewById<AppCompatCheckBox>(R.id.checkbox)
			return ViewHolder(view, checkbox)
		}

		override fun getItemCount(): Int = modules.size

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val info = modules[position]
			holder.checkbox.setText(info.module.titleRes)
			holder.checkbox.isChecked = info.isInstalled
			holder.checkbox.setOnCheckedChangeListener { _, isChecked -> info.shouldBeInstalled = isChecked }
			onViewChangedListener?.invoke(holder.itemView)
		}

		fun addModules(modules: Collection<ModuleInfo>) {
			this.modules.addAll(modules)
		}

		class ViewHolder(view: View, val checkbox: AppCompatCheckBox) : RecyclerView.ViewHolder(view)
	}

	companion object {
		private const val CONFIRMATION_REQUEST_CODE = 1
	}
}

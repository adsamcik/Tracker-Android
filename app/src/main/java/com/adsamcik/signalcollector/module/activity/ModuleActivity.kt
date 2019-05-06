package com.adsamcik.signalcollector.module.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.Assist
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.common.color.ColorSupervisor
import com.adsamcik.signalcollector.common.color.ColorView
import com.google.android.play.core.splitinstall.*
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.android.synthetic.main.activity_module.*

class ModuleActivity : AppCompatActivity() {
	private val activeModules = listOf(ModuleInfo("game", R.string.module_game_title), ModuleInfo("map", R.string.module_map_title), ModuleInfo("statistics", R.string.module_statistics_title))

	private lateinit var manager: SplitInstallManager

	private lateinit var colorManager: ColorManager

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
					onLoadSuccess(state.moduleNames())
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

	private fun onLoadSuccess(names: Collection<String>) {
		toast(getString(R.string.module_success))
		finish()
	}

	private fun toast(text: String) {
		Toast.makeText(this, text, Toast.LENGTH_LONG).show()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_module)

		colorManager = ColorSupervisor.createColorManager(this)
		manager = SplitInstallManagerFactory.create(this)

		val adapter = ModuleAdapter()


		colorManager.watchView(ColorView(root, 0))

		manager.installedModules.forEach { moduleName ->
			activeModules.find { it.name == moduleName }?.apply {
				isInstalled = true
				shouldBeInstalled = true
			}
		}

		adapter.addModules(activeModules)

		button_cancel.setOnClickListener { finish() }

		button_ok.setOnClickListener { updateModules() }
	}

	fun updateModules() {
		val toInstall = activeModules.filter { it.shouldBeInstalled.and(!it.isInstalled) }
		val toRemove = activeModules.filter { (!it.shouldBeInstalled).and(it.isInstalled) }

		if (toInstall.isNotEmpty()) {
			val request = SplitInstallRequest.newBuilder()
			toInstall.forEach { request.addModule(it.name) }
			manager.startInstall(request.build())

		}

		if (toRemove.isNotEmpty()) {
			manager.deferredUninstall(toRemove.map { it.name })
		}
	}


	override fun onResume() {
		super.onResume()
		manager.registerListener(listener)
	}

	override fun onPause() {
		super.onPause()
		manager.unregisterListener(listener)
	}

	class ModuleAdapter : RecyclerView.Adapter<ModuleAdapter.ViewHolder>() {
		private val modules = mutableListOf<ModuleInfo>()

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_recycler_toggle_item, parent, false)
			val checkbox = view.findViewById<AppCompatCheckBox>(R.id.checkbox)
			return ViewHolder(view, checkbox)
		}

		override fun getItemCount(): Int = modules.size

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val module = modules[position]
			holder.checkbox.setText(module.titleRes)
			holder.checkbox.isChecked = module.isInstalled
		}

		fun addModules(modules: Collection<ModuleInfo>) {
			this.modules.addAll(modules)
		}

		class ViewHolder(view: View, val checkbox: AppCompatCheckBox) : RecyclerView.ViewHolder(view)
	}

	data class ModuleInfo(val name: String, val titleRes: Int, var shouldBeInstalled: Boolean = false, var isInstalled: Boolean = false)

	companion object {
		private const val CONFIRMATION_REQUEST_CODE = 1
	}
}

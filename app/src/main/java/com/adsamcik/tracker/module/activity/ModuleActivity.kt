package com.adsamcik.tracker.module.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.widget.ContentLoadingProgressBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.R
import com.adsamcik.tracker.module.Module
import com.adsamcik.tracker.module.ModuleInfo
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.utils.activity.DetailActivity
// Legacy StyleController styling removed; rely on Material theme
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
// Dynamic feature delivery removed; modules are static

/**
 * Module activity for managing modules.
 */
class ModuleActivity : DetailActivity() {
	private lateinit var adapter: ModuleAdapter

	private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val rootContentView = inflateContent<ViewGroup>(R.layout.activity_module)

		setTitle(R.string.settings_module_group_title)

		val adapter = ModuleAdapter()

		val recycler = rootContentView.findViewById<RecyclerView>(R.id.recycler)

		// No-op: removed StyleController watchers

	val moduleInfoList = Module.getActiveModuleInfo(this)

		adapter.addModules(moduleInfoList)

		recycler.adapter = adapter
		this.adapter = adapter

		val layoutManager = LinearLayoutManager(this)
		recycler.layoutManager = layoutManager

		val edgeMargin = resources
				.getDimension(com.adsamcik.tracker.shared.utils.R.dimen.activity_horizontal_margin)
				.toInt()
		recycler.addItemDecoration(MarginDecoration(16.dp, edgeMargin))

		recycler.post {
			val allVisible = layoutManager.findLastCompletelyVisibleItemPosition() == adapter.itemCount - 1
			if (allVisible) {
				recycler.overScrollMode = View.OVER_SCROLL_NEVER
			}
		}

		findViewById<View>(R.id.button_cancel).setOnClickListener { finish() }

		findViewById<View>(R.id.button_ok).setOnClickListener { finish() }
	}

	private fun updateModules() { /* no-op in static build */ }


	override fun onResume() { super.onResume() }
	override fun onPause() { super.onPause() }

	class ModuleAdapter : RecyclerView.Adapter<ModuleAdapter.ViewHolder>(),
			IViewChange {
		override var onViewChangedListener: ((View) -> Unit)? = null

		private val modules = mutableListOf<ModuleInfo>()

	val modulesToInstall: List<ModuleInfo> get() = emptyList()
	val modulesToUninstall: List<ModuleInfo> get() = emptyList()

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
			val view = LayoutInflater.from(parent.context)
					.inflate(R.layout.layout_recycler_toggle_item, parent, false)
			val checkbox = view.findViewById<AppCompatCheckBox>(R.id.checkbox)
			return ViewHolder(view, checkbox)
		}

		override fun getItemCount(): Int = modules.size

		override fun onBindViewHolder(holder: ViewHolder, position: Int) {
			val info = modules[position]
			holder.checkbox.setText(info.module.titleRes)
			holder.checkbox.isChecked = info.isInstalled
			holder.checkbox.setOnCheckedChangeListener { _, isChecked -> info.shouldBeInstalled = isChecked }
		}

		override fun onViewAttachedToWindow(holder: ViewHolder) {
			super.onViewAttachedToWindow(holder)
			onViewChangedListener?.invoke(holder.itemView)
		}

		fun addModules(modules: Collection<ModuleInfo>) {
			this.modules.addAll(modules)
		}

		class ViewHolder(
				view: View,
				val checkbox: AppCompatCheckBox
		) : RecyclerView.ViewHolder(view)
	}

	companion object
}


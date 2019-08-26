package com.adsamcik.tracker.module

import android.content.Context
import com.adsamcik.tracker.R
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory

enum class Module {
	STATISTICS {
		override val enabled = true
		override val moduleName = "statistics"
		override val titleRes: Int = R.string.module_statistics_title
	},
	GAME {
		override val enabled = true
		override val moduleName = "game"
		override val titleRes: Int = R.string.module_game_title
	},
	MAP {
		override val enabled = true
		override val moduleName = "map"
		override val titleRes: Int = R.string.module_map_title
	};

	abstract val moduleName: String
	val modulePath: String get() = "$BASE_PATH.$moduleName"

	abstract val titleRes: Int
	abstract val enabled: Boolean

	@Throws(ClassNotFoundException::class)
	@Suppress("unchecked_cast")
	fun <T> loadClass(className: String): Class<T> =
			Class.forName("$modulePath.$className") as Class<T>


	companion object {
		private const val BASE_PATH = "com.adsamcik.tracker"

		fun getActiveModuleInfo(context: Context): List<ModuleInfo> {
			val manager = SplitInstallManagerFactory.create(context)
			return getActiveModuleInfo(manager)
		}

		fun getActiveModuleInfo(manager: SplitInstallManager): List<ModuleInfo> {
			val installedModules = manager.installedModules
			return values()
					.filter { it.enabled }
					.map { ModuleInfo(it) }
					.apply {
						forEach {
							if (installedModules.contains(it.module.moduleName)) {
								it.isInstalled = true
								it.shouldBeInstalled = true
							}
						}
					}
		}

		fun getModuleInfo(context: Context, module: Module): ModuleInfo {
			val manager = SplitInstallManagerFactory.create(context)
			return getModuleInfo(manager, module)
		}

		fun getModuleInfo(manager: SplitInstallManager, module: Module): ModuleInfo {
			val moduleInfo = ModuleInfo(module)
			if (manager.installedModules.contains(module.moduleName)) {
				moduleInfo.isInstalled = true
				moduleInfo.shouldBeInstalled = true
			}
			return moduleInfo
		}
	}
}

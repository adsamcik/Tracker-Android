package com.adsamcik.tracker.module

import android.content.Context
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.module.ModuleClassLoader
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory

enum class Module {
	STATISTICS {
		override val enabled: Boolean = true
		override val moduleName: String = "statistics"
		override val titleRes: Int = R.string.module_statistics_title
	},
	GAME {
		override val enabled: Boolean = true
		override val moduleName: String = "game"
		override val titleRes: Int = R.string.module_game_title
	},
	MAP {
		override val enabled: Boolean = true
		override val moduleName: String = "map"
		override val titleRes: Int = R.string.module_map_title
	};

	abstract val moduleName: String
	val modulePath: String get() = "$BASE_PATH.$moduleName"

	abstract val titleRes: Int
	abstract val enabled: Boolean

	@Throws(ClassNotFoundException::class)
	@Suppress("unchecked_cast")
	fun <T> loadClass(className: String): Class<T> =
			ModuleClassLoader.loadClass(moduleName, className)


	companion object {
		private const val BASE_PATH = "com.adsamcik.tracker"

		/**
		 * Returns information about all active modules.
		 */
		fun getActiveModuleInfo(context: Context): List<ModuleInfo> {
			val manager = SplitInstallManagerFactory.create(context)
			return getActiveModuleInfo(manager)
		}

		/**
		 * Returns information about all active modules.
		 */
		fun getActiveModuleInfo(manager: SplitInstallManager): List<ModuleInfo> {
			val installedModules = manager.installedModules
			return values()
					.asSequence()
					.filter { it.enabled }
					.map { ModuleInfo(it) }
					.toList()
					.onEach {
						if (installedModules.contains(it.module.moduleName)) {
							it.isInstalled = true
							it.shouldBeInstalled = true
						}
					}
		}

		/**
		 * Returns info for a specific module.
		 */
		fun getModuleInfo(context: Context, module: Module): ModuleInfo {
			val manager = SplitInstallManagerFactory.create(context)
			return getModuleInfo(manager, module)
		}

		/**
		 * Returns info for a specific module.
		 */
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

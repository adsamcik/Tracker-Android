package com.adsamcik.tracker.module

import android.content.Context
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.utils.module.ModuleClassLoader

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
			return values()
					.asSequence()
					.filter { it.enabled }
					.map { ModuleInfo(it, shouldBeInstalled = true, isInstalled = true) }
					.toList()
		}

		/**
		 * Returns information about all active modules.
		 * Kept for legacy API compatibility when a dynamic features manager used to be passed.
		 */
		fun getActiveModuleInfo(@Suppress("UNUSED_PARAMETER") manager: Any? = null): List<ModuleInfo> {
			return values()
				.asSequence()
				.filter { it.enabled }
				.map { ModuleInfo(it, shouldBeInstalled = true, isInstalled = true) }
				.toList()
		}

		/**
		 * Returns info for a specific module.
		 */
		fun getModuleInfo(@Suppress("UNUSED_PARAMETER") context: Context?, module: Module): ModuleInfo =
			ModuleInfo(module, shouldBeInstalled = true, isInstalled = true)

		/**
		 * Returns info for a specific module.
		 */
		fun getModuleInfo(@Suppress("UNUSED_PARAMETER") manager: Any?, module: Module): ModuleInfo =
			ModuleInfo(module, shouldBeInstalled = true, isInstalled = true)
	}
}

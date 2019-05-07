package com.adsamcik.signalcollector.module

import com.adsamcik.signalcollector.R

enum class Module {
	STATISTICS {
		override val moduleName = "statistics"
		override val titleRes: Int = R.string.module_statistics_title
	},
	GAME {
		override val moduleName = "game"
		override val titleRes: Int = R.string.module_game_title
	},
	MAP {
		override val moduleName = "map"
		override val titleRes: Int = R.string.module_map_title
	};

	abstract val moduleName: String
	val modulePath: String get() = "$BASE_PATH.$moduleName"

	abstract val titleRes: Int

	@Throws(ClassNotFoundException::class)
	@Suppress("unchecked_cast")
	fun <T> loadClass(className: String): Class<T> = Class.forName("$modulePath.$className") as Class<T>


	companion object {
		private const val BASE_PATH = "com.adsamcik.signalcollector"
	}
}
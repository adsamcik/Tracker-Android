package com.adsamcik.tracker.module

import com.adsamcik.tracker.R

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

	// Reflection-based class loading removed; modules are statically linked and referenced directly.


	companion object {
		private const val BASE_PATH = "com.adsamcik.tracker"
	}
}

package com.adsamcik.tracker.common.module

object ModuleClassLoader {
	fun <T> loadClass(moduleName: String, className: String): Class<T> {
		@Suppress("unchecked_cast")
		return Class.forName("$BASE_PATH.$moduleName.$className") as Class<T>
	}

	fun <T> newInstance(moduleName: String, className: String): T {
		return loadClass<T>(moduleName, className).newInstance()
	}

	private const val BASE_PATH = "com.adsamcik.tracker"
}

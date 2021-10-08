package com.adsamcik.tracker.shared.utils.module

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import java.util.*

/**
 * Class loader for modules. Automatically handles prepending paths to classes.
 */
object ModuleClassLoader {
	private const val BASE_PATH = "com.adsamcik.tracker"

	inline fun <reified T> invokeInEachActiveModule(context: Context, func: (T) -> Unit) {
		val activeModules = getEnabledModuleNames(context)

		activeModules.forEach { moduleName ->
			try {
				val classDefinition = loadClass<T>(
					moduleName = moduleName,
					className = "${
						moduleName.replaceFirstChar {
							if (it.isLowerCase()) {
								it.titlecase(
									Locale.ROOT
								)
							} else {
								it.toString()
							}
						}
					}${T::class.java.simpleName}"
				)
				func(classDefinition.newInstance())
			} catch (e: ClassNotFoundException) {
				//it's fine, do nothing
			} catch (e: InstantiationException) {
				Reporter.report(e)
			} catch (e: IllegalAccessException) {
				Reporter.report(e)
			} catch (e: ClassCastException) {
				Reporter.report(e)
			}
		}
	}

	/**
	 * Load class from module. Throws exceptions if issue occurs, see more at [Class.forName].
	 *
	 * @param moduleName Case-sensitive name of the module
	 * @param className Case-sensitive class path inside the module
	 * @return Returns [Class] instance of the desired class
	 *
	 */
	fun <T> loadClass(moduleName: String, className: String): Class<T> {
		@Suppress("unchecked_cast")
		return Class.forName("$BASE_PATH.$moduleName.$className") as Class<T>
	}

	/**
	 * Load module class from module. This is shortcut method for classes
	 * inside MODULE_PATH.module.CLASS_PATH. Throws exceptions if issue occurs,
	 * see more at [Class.forName].
	 *
	 * @param moduleName Case-sensitive name of the module
	 * @param className Case-sensitive class path inside the module
	 * @return Returns [Class] instance of the desired class
	 *
	 */
	fun <T> loadModuleClass(moduleName: String, className: String): Class<T> {
		return loadClass("${moduleName}.module", className)
	}

	/**
	 * Creates instance of class at given path. Throws exceptions if issue occurs,
	 * see more at [Class.forName].
	 *
	 * @param moduleName Case-sensitive name of the module
	 * @param className Case-sensitive class path inside the module
	 * @return Returns instance of the desired class
	 */
	fun <T> newInstance(moduleName: String, className: String): T {
		return loadClass<T>(moduleName, className).newInstance()
	}

	/**
	 * Returns list of all modules (static and dynamic) that are enabled in the app.
	 */
	fun getEnabledModuleNames(context: Context): List<String> {
		return getInstalledDynamicModuleNames(context) + getStaticModuleNames()
	}

	/**
	 * Returns list of installed dynamic modules.
	 */
	fun getInstalledDynamicModuleNames(context: Context): List<String> {
		return SplitInstallManagerFactory.create(context).installedModules.toList()
	}

	/**
	 * Returns list of select static modules.
	 */
	fun getStaticModuleNames(): List<String> {
		return listOf("activity", "tracker")
	}
}

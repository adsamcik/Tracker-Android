import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.kotlin

object Android {
	const val min = 21
	const val compile = 28
	const val target = compile
}


object Libraries {
	object Versions {
		const val constraintLayout = "2.0.0-alpha5"
		const val coreKtx = "1.1.0-alpha05"
		const val appcompat = "1.1.0-alpha04"
		const val room = "2.1.0-alpha07"
		const val kotlin = "1.3.31"
		const val dokka = "0.9.18"
		const val moshi = "1.8.0"
		const val work = "2.1.0-alpha01"
		const val lifecycle = "2.1.0-alpha04"
	}

	private fun DependencyHandler.implementation(name: String) = add("implementation", name)
	private fun DependencyHandler.kapt(name: String) = add("kapt", name)
	private fun DependencyHandler.androidTestImplementation(name: String) = add("androidTestImplementation", name)

	fun moshi(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.squareup.moshi:moshi:${Versions.moshi}")
			kapt("com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}")
		}
	}

	fun database(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			val roomBaseString = "androidx.room:room"
			implementation("$roomBaseString-runtime:${Versions.room}")
			kapt("$roomBaseString-compiler:${Versions.room}")
			implementation("$roomBaseString-ktx:${Versions.room}")
			androidTestImplementation("androidx.room:room-testing:${Versions.room}")
		}
	}

	fun core(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("androidx.appcompat:appcompat:${Versions.appcompat}")
			implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Versions.kotlin}")
			implementation("androidx.core:core-ktx:${Versions.coreKtx}")
			implementation("androidx.constraintlayout:constraintlayout:${Versions.constraintLayout}")
			implementation("androidx.lifecycle:lifecycle-extensions:${Versions.lifecycle}")
			implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.2.1")
			implementation("androidx.fragment:fragment:1.1.0-alpha07")
			implementation("com.google.android.material:material:1.1.0-alpha05")
			implementation("com.google.android.gms:play-services-base:16.1.0")

			kapt("androidx.lifecycle:lifecycle-compiler:${Versions.lifecycle}")

			androidTestImplementation("androidx.test:runner:1.1.1")
			androidTestImplementation("androidx.test.espresso:espresso-core:3.1.1")
		}
	}

	fun corePlugins(scope: org.gradle.plugin.use.PluginDependenciesSpec) {
		with(scope) {
			kotlin("android")
			kotlin("android.extensions")
			kotlin("kapt")
		}
	}

	fun work(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("androidx.work:work-runtime-ktx:${Versions.work}")
			androidTestImplementation("androidx.work:work-testing:${Versions.work}")
		}
	}

	fun map(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.google.android.gms:play-services-maps:16.1.0")
		}
	}

	fun draggable(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.adsamcik.android-components:draggable:0.14.1")
		}
	}
}
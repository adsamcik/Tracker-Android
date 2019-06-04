import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.kotlin

object Libraries {
	object Versions {
		const val constraintLayout = "2.0.0-beta1"
		const val coreKtx = "1.1.0-beta01"
		const val appcompat = "1.1.0-alpha05"
		const val room = "2.1.0-rc01"
		const val fragment = "1.1.0-alpha09"
		const val kotlin = "1.3.31"
		const val dokka = "0.9.18"
		const val moshi = "1.8.0"
		const val work = "2.1.0-alpha02"
		const val lifecycle = "2.2.0-alpha01"
		const val preference = "1.1.0-alpha05"
		const val material = "1.1.0-alpha07"

		const val maps = "16.1.0"
		const val location = "16.0.0"
		const val firebaseCore = "16.0.9"
		const val recyclerView = "1.1.0-alpha05"
		const val paging = "2.1.0"

		const val crashlytics = "2.10.1"

		const val fileChooser = "1.1.19"

		object Test {
			const val androidxTest = "1.2.0"
			const val espresso = "3.2.0"
		}
	}

	private fun DependencyHandler.api(name: String) = add("api", name)
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
			api("$roomBaseString-runtime:${Versions.room}")
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

			//Recycler
			implementation("com.adsamcik.android-components:recycler:0.4.0")
			implementation("androidx.recyclerview:recyclerview:${Versions.recyclerView}")
			implementation("android.arch.paging:runtime:${Versions.paging}")

			implementation("androidx.lifecycle:lifecycle-extensions:${Versions.lifecycle}")
			implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.2.1")
			implementation("androidx.fragment:fragment:${Versions.fragment}")
			implementation("com.google.android.material:material:${Versions.material}")
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
			implementation("com.google.android.gms:play-services-maps:${Versions.maps}")
		}
	}

	fun location(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.google.android.gms:play-services-location:${Versions.location}")
		}
	}

	fun crashlytics(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.google.firebase:firebase-core:${Versions.firebaseCore}")
			implementation("com.crashlytics.sdk.android:crashlytics:${Versions.crashlytics}")
		}
	}

	fun draggable(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.adsamcik.android-components:draggable:0.14.1")
		}
	}

	fun dateTimePicker(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.appeaser.sublimepickerlibrary:sublimepickerlibrary:2.1.2")
		}
	}

	fun preference(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("androidx.preference:preference:${Versions.preference}")
		}
	}

	fun fileChooser(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.obsez.android.lib.filechooser:filechooser:${Versions.fileChooser}")
		}
	}

	fun test(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			androidTestImplementation("androidx.test:runner:${Versions.Test.androidxTest}")
			androidTestImplementation("androidx.test:rules:${Versions.Test.androidxTest}")
			androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
			androidTestImplementation("androidx.test.ext:junit:1.1.1")
			androidTestImplementation("androidx.arch.core:core-testing:2.0.1")
			androidTestImplementation("com.jraska.livedata:testing-ktx:1.1.0")
			androidTestImplementation("androidx.test.espresso:espresso-core:${Versions.Test.espresso}")
			androidTestImplementation("androidx.test.espresso:espresso-contrib:${Versions.Test.espresso}")
		}
	}
}
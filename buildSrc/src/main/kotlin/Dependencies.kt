import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.kotlin

object Libraries {
	object Versions {
		internal const val constraintLayout = "2.0.0-beta2"
		internal const val coreKtx = "1.2.0-alpha03"
		internal const val appcompat = "1.1.0-rc01"
		internal const val room = "2.1.0"
		internal const val fragment = "1.1.0-rc03"
		public const val kotlin: String = "1.3.41"
		public const val dokka: String = "0.9.18"
		internal const val moshi = "1.8.0"
		internal const val work = "2.2.0-rc01"
		internal const val lifecycle = "2.2.0-alpha02"
		internal const val preference = "1.1.0-rc01"
		internal const val material = "1.1.0-alpha09"
		internal const val coroutines = "1.3.0-RC"

		internal const val maps = "17.0.0"
		internal const val location = "17.0.0"
		internal const val firebaseCore = "17.0.1"
		internal const val recyclerView = "1.1.0-beta01"
		internal const val paging = "2.1.0"

		internal const val crashlytics = "2.10.1"

		internal const val playServicesBase = "17.0.0"
		internal const val playCore = "1.6.1"

		internal const val sublimePicker = "2.1.2"
		internal const val spotlight = "2.1.0"
		internal const val dialogs = "3.1.0"

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
			implementation("com.adsamcik.android-components:recycler:0.4.2")
			implementation("androidx.recyclerview:recyclerview:${Versions.recyclerView}")
			implementation("android.arch.paging:runtime:${Versions.paging}")

			implementation("androidx.lifecycle:lifecycle-extensions:${Versions.lifecycle}")
			implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutines}")
			implementation("androidx.fragment:fragment:${Versions.fragment}")
			implementation("com.google.android.material:material:${Versions.material}")
			implementation("com.google.android.gms:play-services-base:${Versions.playServicesBase}")
			implementation("com.google.android.play:core:${Versions.playCore}")

			implementation("com.afollestad.material-dialogs:core:${Versions.dialogs}")
			implementation("com.codezjx.library:andlinker:0.7.2")

			work(this)

			kapt("androidx.lifecycle:lifecycle-compiler:${Versions.lifecycle}")
		}
	}

	fun corePlugins(scope: org.gradle.plugin.use.PluginDependenciesSpec) {
		with(scope) {
			kotlin("android")
			kotlin("android.extensions")
			kotlin("kapt")
		}
	}

	private fun work(dependencyHandler: DependencyHandler) {
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
			implementation("com.appeaser.sublimepickerlibrary:sublimepickerlibrary:${Versions.sublimePicker}")
		}
	}

	fun preference(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("androidx.preference:preference:${Versions.preference}")
		}
	}

	fun fileChooser(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			//todo update to version that supports Android Q when ready
			implementation("com.afollestad.material-dialogs:files:${Versions.dialogs}")
		}
	}

	fun sectionedRecyclerAdapter(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("io.github.luizgrp.sectionedrecyclerviewadapter:sectionedrecyclerviewadapter:2.1.0")
		}
	}

	fun introduction(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.adsamcik.android-forks:spotlight:${Versions.spotlight}")
		}
	}

	fun test(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			androidTestImplementation("junit:junit:4.12")
			androidTestImplementation("androidx.test:runner:${Versions.Test.androidxTest}")
			androidTestImplementation("androidx.test:rules:${Versions.Test.androidxTest}")
			androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
			androidTestImplementation("androidx.test.ext:junit:1.1.1")
			androidTestImplementation("androidx.arch.core:core-testing:2.1.0-rc01")
			androidTestImplementation("com.jraska.livedata:testing-ktx:1.1.0")
			androidTestImplementation("androidx.test.espresso:espresso-core:${Versions.Test.espresso}")
			androidTestImplementation("androidx.test.espresso:espresso-contrib:${Versions.Test.espresso}")
		}
	}
}
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.kotlin

/**
 * Object with common dependency groups for easy and central dependency management.
 */
@Suppress("TooManyFunctions", "SpellCheckingInspection")
object Dependencies {
	/**
	 * Object containing versions for various dependencies.
	 * Dependencies that are not project are required to be internal.
	 */
	object Versions {
		internal const val constraintLayout = "2.0.0-beta8"
		internal const val coreKtx = "1.5.0-alpha01"
		internal const val appcompat = "1.2.0-rc01"
		internal const val fragment = "1.2.4"
		const val kotlin: String = "1.3.72"
		const val dokka: String = "0.9.18"
		internal const val moshi = "1.9.3"
		internal const val work = "2.4.0-rc01"
		internal const val lifecycle = "2.2.0"
		internal const val preference = "1.1.1"
		internal const val material = "1.2.0-beta01"
		internal const val coroutines = "1.3.7"

		internal const val requery = "3.32.2"
		internal const val room = "2.3.0-alpha01"

		internal const val maps = "17.0.0"
		internal const val firebaseCore = "17.4.4"
		internal const val recyclerView = "1.1.0"
		internal const val paging = "3.0.0-alpha02"

		internal const val crashlytics = "17.1.1"
		const val crashlyticsGradle = "2.2.0"

		internal const val playServicesBase = "17.3.0"
		internal const val playCore = "1.7.3"
		internal const val playLocation = "17.0.0"

		internal const val stax = "1.0.1"
		internal const val jpx = "2.0.0"
		internal const val xml = "1.2.2"

		internal const val spotlight = "2.2.2"
		internal const val dialogs = "3.3.0"
		internal const val sectionedRecyclerViewAdapter = "3.2.0"

		internal const val componentsRecycler = "0.8.0"
		internal const val componentsDraggable = "0.14.1"

		/**
		 * Testing specific dependencies
		 */
		internal object Test {
			internal const val androidxTest: String = "1.3.0-rc01"
			internal const val espresso: String = "3.3.0-rc01"
			internal const val coreTesting: String = "2.1.0"
			internal const val testingKtx: String = "1.1.2"
		}
	}

	private fun DependencyHandler.api(name: String) = add("api", name)
	private fun DependencyHandler.implementation(name: String) = add("implementation", name)
	private fun DependencyHandler.kapt(name: String) = add("kapt", name)
	private fun DependencyHandler.androidTestImplementation(name: String) =
			add("androidTestImplementation", name)

	private fun DependencyHandler.compileOnly(name: String) = add("compileOnly", name)
	private fun DependencyHandler.coreLibraryDesugaring(dependencyNotation: Any) =
			add("coreLibraryDesugaring", dependencyNotation)

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
			implementation("io.requery:sqlite-android:${Versions.requery}")
			androidTestImplementation("androidx.room:room-testing:${Versions.room}")
		}
	}

	fun core(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			// Fix for compile error caused by missing annotation in JDK9+
			compileOnly("com.github.pengrad:jdk9-deps:1.0")
			coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.0.4")

			implementation("androidx.appcompat:appcompat:${Versions.appcompat}")
			implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Versions.kotlin}")
			implementation("androidx.core:core-ktx:${Versions.coreKtx}")
			implementation("androidx.constraintlayout:constraintlayout:${Versions.constraintLayout}")

			//Recycler
			implementation("com.adsamcik.android-components:recycler:${Versions.componentsRecycler}")
			implementation("androidx.recyclerview:recyclerview:${Versions.recyclerView}")
			implementation("androidx.paging:paging-runtime-ktx:${Versions.paging}")

			implementation("androidx.lifecycle:lifecycle-extensions:${Versions.lifecycle}")
			implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutines}")
			implementation("androidx.fragment:fragment:${Versions.fragment}")
			implementation("androidx.fragment:fragment-ktx:${Versions.fragment}")
			implementation("com.google.android.material:material:${Versions.material}")
			implementation("com.google.android.gms:play-services-base:${Versions.playServicesBase}")
			implementation("com.google.android.play:core:${Versions.playCore}")

			implementation("com.afollestad.material-dialogs:core:${Versions.dialogs}")
			//implementation("com.codezjx.library:andlinker:0.7.2")

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
			implementation("com.google.android.gms:play-services-location:${Versions.playLocation}")
		}
	}

	fun crashlytics(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.google.firebase:firebase-core:${Versions.firebaseCore}")
			implementation("com.google.firebase:firebase-crashlytics:${Versions.crashlytics}")
		}
	}

	fun draggable(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.adsamcik.android-components:draggable:${Versions.componentsDraggable}")
		}
	}

	fun preference(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("androidx.preference:preference:${Versions.preference}")
		}
	}

	fun fileChooser(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.afollestad.material-dialogs:files:${Versions.dialogs}")
		}
	}

	fun colorChooser(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("com.afollestad.material-dialogs:color:${Versions.dialogs}")
		}
	}

	fun gpx(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("stax:stax-api:${Versions.stax}")
			implementation("com.fasterxml:aalto-xml:${Versions.xml}")
			implementation("io.jenetics:jpx:${Versions.jpx}")
		}
	}

	fun sectionedRecyclerAdapter(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation("io.github.luizgrp.sectionedrecyclerviewadapter:sectionedrecyclerviewadapter:${Versions.sectionedRecyclerViewAdapter}")
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
			androidTestImplementation("androidx.arch.core:core-testing:${Versions.Test.coreTesting}")
			androidTestImplementation("com.jraska.livedata:testing-ktx:${Versions.Test.testingKtx}")
			androidTestImplementation("androidx.test.espresso:espresso-core:${Versions.Test.espresso}")
			androidTestImplementation("androidx.test.espresso:espresso-contrib:${Versions.Test.espresso}")
		}
	}
}

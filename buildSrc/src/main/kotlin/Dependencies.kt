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
		const val ktx = "1.1.0-alpha05"
		const val appcompat = "1.1.0-alpha04"
		const val room = "2.1.0-alpha07"
		const val kotlin = "1.3.31"
		const val dokka = "0.9.18"
		const val moshi = "1.8.0"
		const val work = "2.0.1"
	}

	private const val moshiBaseString = "com.squareup.moshi:moshi"
	private const val moshi = "$moshiBaseString:${Versions.moshi}"

	private const val kotlinStdLib = "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${Versions.kotlin}"

	private const val appCompat = "androidx.appcompat:appcompat:${Versions.appcompat}"
	private const val constraintLayout = "androidx.constraintlayout:constraintlayout:${Versions.constraintLayout}"
	private const val coreKtx = "androidx.core:core-ktx:${Versions.ktx}"

	private const val roomBaseString = "androidx.room:room"

	private const val roomRuntime = "$roomBaseString-runtime:${Versions.room}"
	private const val roomCompiler = "$roomBaseString-compiler:${Versions.room}"
	private const val roomKtx = "$roomBaseString-ktx:${Versions.room}"

	private fun DependencyHandler.implementation(name: String) = add("implementation", name)
	private fun DependencyHandler.kapt(name: String) = add("kapt", name)
	private fun DependencyHandler.androidTestImplementation(name: String) = add("androidTestImplementation", name)

	fun moshi(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation(moshi)
			kapt(Kapt.moshi)
		}
	}

	fun database(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation(roomRuntime)
			kapt(roomCompiler)
			implementation(roomKtx)
			androidTestImplementation(Test.room)
		}
	}

	fun core(dependencyHandler: DependencyHandler) {
		with(dependencyHandler) {
			implementation(appCompat)
			implementation(kotlinStdLib)
			implementation(coreKtx)
			implementation(constraintLayout)
			implementation("androidx.lifecycle:lifecycle-extensions:2.1.0-alpha04")
			implementation("androidx.fragment:fragment:1.1.0-alpha07")
			implementation("com.google.android.material:material:1.1.0-alpha05")
			implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.2.1")

			kapt("androidx.lifecycle:lifecycle-compiler:2.1.0-alpha04")

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


	private object Kapt {
		const val moshi = "$moshiBaseString-kotlin-codegen:${Versions.moshi}"
	}

	private object Test {
		const val room = "androidx.room:room-testing:${Versions.room}"
	}
}
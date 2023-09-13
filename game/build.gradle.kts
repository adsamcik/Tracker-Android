import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("com.android.dynamic-feature")
	Dependencies.corePlugins(this)
}

android {
	compileSdk = Android.compile
	buildToolsVersion = Android.buildTools

	defaultConfig {
		minSdk = Android.min

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"


		with(javaCompileOptions) {
			with(annotationProcessorOptions) {
				arguments(
					mapOf(
						"room.incremental" to "true"
					)
				)
			}
		}
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = Android.jvmTarget
		}
	}

	buildTypes {
		create("release_nominify")
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}
	namespace = "com.adsamcik.tracker.game"

	kapt {
		arguments {
			this.arg("room.schemaLocation", "$projectDir/schemas")
		}
	}
}

dependencies {
	implementation(project(":app"))
	implementation(project(":sbase"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))
	implementation(project(":points"))

	Dependencies.core(this)
	Dependencies.draggable(this)
	Dependencies.database(this)
	Dependencies.preference(this)
	Dependencies.test(this)
	Dependencies.inputDialog(this)
	Dependencies.slider(this)
}


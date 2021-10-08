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
		isCheckReleaseBuilds = true
		isAbortOnError = false
	}

	kapt {
		arguments {
			this.arg("room.schemaLocation", "$projectDir/schemas")
		}
	}
}

dependencies {
	implementation(project(":smap"))
	implementation(project(":app"))
	implementation(project(":sbase"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))

	Dependencies.core(this)
	Dependencies.draggable(this)
	Dependencies.database(this)
	Dependencies.map(this)
	Dependencies.json(this)
	Dependencies.test(this)
	Dependencies.paging(this)

	implementation("com.github.PhilJay:MPAndroidChart:3.1.0")
	implementation("com.goebl:simplify:1.0.0")
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("com.android.dynamic-feature")
	Dependencies.corePlugins(this)
}

android {
	compileSdkVersion(Android.compile)
	buildToolsVersion(Android.buildTools)

	defaultConfig {
		minSdkVersion(Android.min)
		targetSdkVersion(Android.target)
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"


		with(javaCompileOptions) {
			with(annotationProcessorOptions) {
				arguments(
						mapOf(
								"room.incremental" to "true",
								"room.expandProjection" to "true"
						)
				)
			}
		}
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = "1.8"
		}
	}

	buildTypes {
		create("release_nominify")
	}

	lintOptions {
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
	Dependencies.core(this)
	Dependencies.draggable(this)
	Dependencies.database(this)
	Dependencies.preference(this)
	Dependencies.test(this)

	implementation(project(":app"))
	implementation(project(":sbase"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	id("com.android.library")
	Dependencies.corePlugins(this)
}

android {
	compileSdk = Android.compile
	buildToolsVersion = Android.buildTools

	defaultConfig {
		minSdk = Android.min
		targetSdk = Android.target

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		kapt {
			arguments {
				this.arg("room.schemaLocation", "$projectDir/schemas")
			}
		}

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

	sourceSets {
		this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	tasks.withType<KotlinCompile> {
		with(kotlinOptions) {
			jvmTarget = Android.jvmTarget
			freeCompilerArgs = listOf("-XXLanguage:+InlineClasses")
		}
	}

	buildTypes {
		getByName("debug") {
			isTestCoverageEnabled = true
		}

		create("release_nominify") {
			isMinifyEnabled = false
		}
		getByName("release") {
			isMinifyEnabled = true
		}
	}

	lint {
		isCheckReleaseBuilds = true
		isAbortOnError = false
	}
}

dependencies {
	Dependencies.core(this)
	Dependencies.json(this)
	Dependencies.database(this)
	Dependencies.location(this)
	Dependencies.paging(this)

	Dependencies.test(this)
}

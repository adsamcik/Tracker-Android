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

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	sourceSets {
		this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.javaVersion)
	}

	buildTypes {
		getByName("debug") {
			enableAndroidTestCoverage = true
			enableUnitTestCoverage = true
		}

		create("release_nominify") {
			isMinifyEnabled = false
		}
		getByName("release") {
			isMinifyEnabled = true
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}
    namespace = "com.adsamcik.tracker.shared.utils"
}

dependencies {
	implementation(project(":sbase"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))


	Dependencies.core(this)
	Dependencies.slider(this)
	Dependencies.json(this)
	Dependencies.crashlytics(this)
	Dependencies.location(this)
	Dependencies.preference(this)
	Dependencies.introduction(this)
	Dependencies.sunCalculator(this)

	Dependencies.test(this)
}

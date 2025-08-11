plugins {
	id("com.android.library")
	Dependencies.corePlugins(this)
	// Add Compose plugin for Compose code in this module
	Dependencies.composePlugins(this)
}

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION

	defaultConfig {
		minSdk = Android.MIN_VERSION

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	sourceSets {
		this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
	}

	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
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

	// Enable Jetpack Compose
	buildFeatures {
		compose = true
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
	Dependencies.location(this)
	Dependencies.preference(this)
	Dependencies.introduction(this)
	Dependencies.sunCalculator(this)
	// Add Compose libraries used by style/compose utilities and activities
	Dependencies.compose(this)

	Dependencies.test(this)
}

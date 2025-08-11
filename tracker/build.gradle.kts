plugins {
	id("com.android.library")
	Dependencies.corePlugins(this)
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
			// Disabled temporarily due to unresolved R8 missing class issues (see proguard rules)
			// TODO: Re-enable minification after resolving class retention for shared modules
			isMinifyEnabled = false
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}
    namespace = "com.adsamcik.tracker.tracker"
}

dependencies {
	implementation(project(":sbase"))
	implementation(project(":activity"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))

	Dependencies.core(this)
	Dependencies.database(this)
	Dependencies.location(this)
	Dependencies.test(this)
}

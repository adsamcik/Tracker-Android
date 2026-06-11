plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.ksp)
	alias(libs.plugins.hilt)
}

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION

	defaultConfig {
		minSdk = Android.MIN_VERSION
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		consumerProguardFiles("consumer-rules.pro")
	}

	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
	}

	buildTypes {
		getByName("debug") {}
		create("release_nominify") { isMinifyEnabled = false }
		getByName("release") { isMinifyEnabled = false }
	}

	testOptions {
		unitTests.isReturnDefaultValues = true
		unitTests.isIncludeAndroidResources = true
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	namespace = "com.adsamcik.tracker.tracker"
}

dependencies {
	api(project(":core:base"))
	api(project(":stats:api"))
	api(project(":stats:engine"))

	implementation(project(":core:logging"))
	implementation(project(":data:preferences"))
	implementation(project(":sensor:activity-api"))

	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.core.ktx)
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
}

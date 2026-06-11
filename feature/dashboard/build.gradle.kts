plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.ksp)
	alias(libs.plugins.hilt)
	alias(libs.plugins.robolectric.junit5)
}

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION

	defaultConfig {
		minSdk = Android.MIN_VERSION
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
	}

	buildFeatures {
		compose = true
	}

	buildTypes {
		create("release_nominify")
		create("dev") {
			initWith(getByName("release"))
			matchingFallbacks += listOf("debug", "release")
		}
	}

	testOptions {
		unitTests.isIncludeAndroidResources = true
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	namespace = "com.adsamcik.tracker.dashboard"
}

dependencies {
	// Internal modules
	implementation(project(":core:base"))
	implementation(project(":core:ui"))
	implementation(project(":data:preferences"))
	implementation(project(":core:logging"))
	implementation(project(":tracker"))
	implementation(project(":stats:api"))
	implementation(project(":stats:data"))
	implementation(project(":domain:points"))

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.hilt.navigation.compose)
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)

	// Compose
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.foundation.layout)
	implementation(libs.compose.runtime)
	implementation(libs.activity.compose)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.compose.ui.tooling.preview)

	// MapLibre for tracking map hero
	implementation(libs.maplibre.compose)

	// Glass effects
	implementation(libs.haze)

	// Unit Tests
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockk)
	testImplementation(libs.kotest.assertions.core)
	testImplementation(libs.junit4)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.robolectric)
	testImplementation(libs.junit5.robolectric)
	testImplementation(libs.compose.ui.test.junit4)
	testRuntimeOnly(libs.junit5.vintage.engine)
	debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

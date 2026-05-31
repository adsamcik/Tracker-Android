plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
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
	}


	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
		compilerOptions {
			optIn.add("kotlin.ExperimentalUnsignedTypes")
		}
	}

	buildTypes {
		create("release_nominify")
		// Provide a dev variant to match :app's dev buildType
		create("dev") {
			initWith(getByName("release"))
			matchingFallbacks += listOf("debug", "release")
		}
	}

	buildFeatures {
		// Prepare for Compose-based map UI while keeping legacy stack intact
		compose = true
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
		baseline = file("lint-baseline.xml")
	}
	testOptions {
		unitTests.isIncludeAndroidResources = true
		unitTests.isReturnDefaultValues = true
	}
    namespace = "com.adsamcik.tracker.map"
}

dependencies {
	// Removed :app dependency as part of converting to a library module
	implementation(project(":sbase"))
	implementation(project(":tracker"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":stats-api"))
	implementation(project(":logger"))
	implementation(project(":network"))

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.services.location)
	// Compose
	implementation(platform(libs.compose.bom))
	androidTestImplementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)
	implementation(libs.compose.ui.tooling.preview)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.activity.compose)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.animation.graphics)
	implementation(libs.compose.foundation.layout)
	implementation(libs.navigation.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.compose.runtime)
	implementation(libs.constraintlayout.compose)
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)
	implementation(libs.kotlinx.collections.immutable)
	// MapLibre Compose (replaces Google Maps)
	implementation(libs.maplibre.compose)

	// Hilt (Dependency Injection)
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
	implementation(libs.hilt.navigation.compose)
	implementation(libs.spotlight)

	// Tests - JUnit 5 for unit tests
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testImplementation(libs.junit5.jupiter.params)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	testRuntimeOnly(libs.junit5.vintage.engine)
	testImplementation(libs.junit4)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.robolectric)
	testImplementation(libs.junit5.robolectric)
	testImplementation(libs.compose.ui.test.junit4)
	testImplementation(libs.activity.compose)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockk)
	testImplementation(libs.turbine)
	testImplementation(libs.kotest.assertions.core)
	testImplementation(kotlin("reflect"))
	testImplementation(project(":testing-common"))

	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.mockito.android)
	androidTestImplementation(libs.mockito.kotlin)
	androidTestImplementation(libs.mockk.android)
	androidTestImplementation(project(":testing-common"))
}

// Configure JUnit 5 for unit tests + disable release tests
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	if (name.contains("ReleaseUnitTest")) {
		enabled = false
	}
}

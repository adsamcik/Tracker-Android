plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
}

android {
	compileSdk = libs.versions.android.compile.get().toInt()
	buildToolsVersion = libs.versions.android.build.tools.get()

	defaultConfig {
		minSdk = libs.versions.android.min.get().toInt()

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		consumerProguardFiles("consumer-rules.pro")
	}

	compileOptions {
		sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
	}

	kotlin {
		jvmToolchain(libs.versions.java.get().toInt())
	}

	buildTypes {
		getByName("debug") {
			// Testing module
		}
		create("release_nominify") {
			isMinifyEnabled = false
		}
		getByName("release") {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	namespace = "com.adsamcik.tracker.testing"

	buildFeatures {
		compose = true
	}
}

dependencies {
	// Project dependencies for domain models
	implementation(project(":sbase"))
	implementation(project(":spreferences"))
	implementation(project(":tracker"))
	implementation(project(":activity"))
	implementation(project(":stats-api"))
	implementation(project(":stats-engine"))

	// Kotlin & Coroutines
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.kotlinx.coroutines.test)

	// Compose testing
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.ui)
	implementation(libs.compose.ui.test.junit4)
	implementation(libs.compose.foundation)
	implementation(libs.compose.material3)

	// JUnit 5 (for modern unit tests)
	implementation(platform(libs.junit5.bom))
	implementation(libs.junit5.jupiter)
	implementation(libs.junit5.jupiter.params)
	runtimeOnly(libs.junit5.jupiter.engine)
	// Vintage engine for JUnit 4 compatibility during migration
	runtimeOnly(libs.junit5.vintage.engine)

	// AndroidX Test (JUnit 4 for instrumented tests)
	implementation(libs.junit4)
	implementation(libs.androidx.test.runner)
	implementation(libs.androidx.test.core)
	implementation(libs.androidx.test.ext.junit)
	implementation(libs.uiautomator)
	implementation(libs.espresso)
	implementation(libs.espresso.intents)

	// Mocking & Assertions
	implementation(libs.mockk)
	implementation(libs.turbine)
	implementation(libs.kotest.assertions.core)
	implementation(libs.kotlin.test)
}

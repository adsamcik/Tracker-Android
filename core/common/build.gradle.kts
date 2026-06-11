plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.robolectric.junit5)
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

	java {
		toolchain {
			languageVersion.set(JavaLanguageVersion.of(Android.JAVA_VERSION))
			setSourceCompatibility(Android.JAVA_VERSION)
			setTargetCompatibility(Android.JAVA_VERSION)
		}
	}

	buildTypes {
		getByName("debug") {
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

	testOptions {
		unitTests.isIncludeAndroidResources = true
	}

	namespace = "com.adsamcik.tracker.shared.common"
	buildFeatures {
		buildConfig = true
		compose = true
	}
}

dependencies {
	// Logging contracts (the only intra-project dependency core:common needs)
	implementation(project(":core:logging-api"))

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.androidx.documentfile)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.services.location)

	// Compose runtime for CompositionLocal DI support (graph/di packages)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.runtime)
	implementation(libs.compose.material3)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.foundation)
	implementation(libs.compose.ui)
	implementation(libs.activity.compose)

	// Paging
	implementation(libs.androidx.paging.runtime)

	// DI annotations (javax.inject for @Qualifier, @Singleton, etc.)
	api(libs.javax.inject)

	// WorkManager
	implementation(libs.androidx.work.runtime.ktx)

	// Unit Tests - JUnit 5 for modern testing
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testImplementation(libs.junit5.jupiter.params)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	testRuntimeOnly(libs.junit5.vintage.engine)
	testImplementation(libs.junit4)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.mockk)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.turbine)
	testImplementation(libs.robolectric)
	testImplementation(libs.junit5.robolectric)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.kotest.assertions.core)
}

// Configure JUnit 5 for unit tests
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

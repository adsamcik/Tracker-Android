plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.ksp)
	alias(libs.plugins.hilt)
	alias(libs.plugins.protobuf)
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

	buildFeatures {
		// Enable Jetpack Compose for tracker UI migration
		compose = true
		// Enable BuildConfig generation for debug flag checking
		buildConfig = true
	}

	buildTypes {
		getByName("debug") {
		}

		create("release_nominify") {
			isMinifyEnabled = false
		}
		getByName("release") {
			// Library-level R8 is intentionally DISABLED. The app module runs R8 over
			// the entire classpath (app + every library) with full visibility, which
			// shrinks more aggressively than per-library R8 ever could and avoids
			// the consumer-rules.pro coverage trap (where a library's R8 strips an
			// "internal" class that the app actually references via Hilt/DI/reflection).
			//
			// R2 round-6 perf review (P3): re-enabling library R8 here previously
			// forced `app/proguard-rules.pro` to keep `com.adsamcik.tracker.**`
			// wholesale, disabling shrinking for 14 modules. Leave this OFF unless
			// you also fully enumerate the public API surface in `consumer-rules.pro`
			// AND verify with `:app:minifyReleaseWithR8` that no missing-class
			// errors appear. The narrow keeps that DO matter for tracker reflection
			// (notification component class names, Hilt entry points) are already
			// propagated via `consumer-rules.pro`.
			isMinifyEnabled = false
		}
	}

	testOptions {
		unitTests.isReturnDefaultValues = true
		unitTests.isIncludeAndroidResources = true
	}

	lint {
		checkReleaseBuilds = false
		abortOnError = false
		baseline = file("lint-baseline.xml")
	}

    namespace = "com.adsamcik.tracker.tracker"
}

dependencies {
	implementation(project(":sbase"))
	implementation(project(":logging-api"))
	implementation(project(":activity"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))
	implementation(project(":stats-api"))
	api(project(":stats-engine"))

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.core.location.altitude)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.services.location)

	// Hilt (Dependency Injection)
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
	ksp(libs.androidx.hilt.compiler)
	implementation(libs.hilt.work)
	implementation(libs.hilt.navigation.compose)

	// Compose (UI migration)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.foundation)
	implementation(libs.compose.foundation.layout)
	implementation(libs.compose.runtime)
	implementation(libs.androidx.lifecycle.runtime.compose)
	// DataStore (proto for typed settings, preferences for migration compatibility)
	implementation(libs.androidx.datastore.core)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.protobuf.java)
	// Required for rememberLauncherForActivityResult and setContent in main source
	implementation(libs.activity.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.compose.ui.tooling.preview)

	// UI Tests
	testImplementation(libs.compose.ui.test.junit4)
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)
	// Needed for ComponentActivity.setContent in androidTest (also added to main above)
	androidTestImplementation(libs.activity.compose)

	// DB
	implementation(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.androidx.room.paging)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// WorkManager
	implementation(libs.androidx.work.runtime.ktx)
	androidTestImplementation(libs.androidx.work.testing)

	// Tests - JUnit 5 for unit tests
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testImplementation(libs.junit5.jupiter.params)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	// Vintage engine for running JUnit 4 tests during migration period
	testRuntimeOnly(libs.junit5.vintage.engine)
	testImplementation(libs.junit4)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.mockk)
	testImplementation(libs.robolectric)
	testImplementation(libs.junit5.robolectric)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.turbine)
	testImplementation(libs.androidx.work.testing)
	testImplementation(libs.kotest.assertions.core)

	// Instrumented Tests
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.mockk.android)
	androidTestImplementation(project(":testing-common"))
}

// Configure JUnit 5 for unit tests
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

configureProtobuf()

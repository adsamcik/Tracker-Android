plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.ksp)
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
		getByName("release") {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
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

	namespace = "com.adsamcik.tracker.osm"
}

dependencies {
	api(project(":stats-api"))
	implementation(project(":sbase"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logging-api"))

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.documentfile)

	// Room (read existing osm_* tables via DAO injection)
	implementation(libs.androidx.room.runtime)
	implementation(libs.androidx.room.ktx)

	// WorkManager + Hilt worker bridge
	implementation(libs.androidx.work.runtime.ktx)
	implementation(libs.hilt.android)
	implementation(libs.hilt.work)
	ksp(libs.hilt.compiler)
	ksp(libs.androidx.hilt.compiler)

	// DI annotations
	implementation(libs.javax.inject)

	// OSM PBF parsing (offline; never touched by network).
	implementation(libs.osmpbf)

	// Unit tests
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testImplementation(libs.junit5.jupiter.params)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	testImplementation(libs.junit4)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.mockk)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.kotest.assertions.core)
	testImplementation(libs.robolectric)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.androidx.work.testing)
	testImplementation(libs.androidx.room.testing)

	// Instrumented tests
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.kotlinx.coroutines.test)
	androidTestImplementation(libs.mockk.android)
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

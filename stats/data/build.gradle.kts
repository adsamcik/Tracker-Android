plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.ksp)
	alias(libs.plugins.protobuf)
}

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION

	defaultConfig {
		minSdk = Android.MIN_VERSION

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		consumerProguardFiles("consumer-rules.pro")

		ksp {
			arg("room.schemaLocation", "$projectDir/schemas")
			arg("room.incremental", "true")
			arg("room.generateKotlin", "true")
		}
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

	namespace = "com.adsamcik.tracker.stats.data"
}

dependencies {
	api(project(":stats:api"))
	implementation(project(":core:base"))
	implementation(project(":core:ui"))
	implementation(project(":data:preferences"))
	implementation(project(":domain:osm"))

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.paging.runtime)

	// Arrow
	implementation(libs.arrow.core)

	// Room
	implementation(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// DataStore proto (live stats)
	implementation(libs.androidx.datastore.core)
	implementation(libs.protobuf.java)

	// Hilt
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
	ksp(libs.androidx.hilt.compiler)
	implementation(libs.hilt.work)

	// WorkManager
	implementation(libs.androidx.work.runtime.ktx)

	// DI annotations
	implementation(libs.javax.inject)

	// Unit Tests
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
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.kotest.assertions.core)
	testImplementation(libs.androidx.work.testing)
	testImplementation(project(":core:testing"))
	// Contract tests assert AchievementWorker (this module) and AchievementProcessor
	// (in :stats-engine) produce identical unlock decisions for the same input — the
	// R1 round-6 regression seam. Test-only edge; no production coupling.
	testImplementation(project(":stats:engine"))

	// Instrumented Tests
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.kotlinx.coroutines.test)
	androidTestImplementation(libs.mockk.android)
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

configureProtobuf()

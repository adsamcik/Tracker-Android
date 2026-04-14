plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.ksp)
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
			isMinifyEnabled = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
		baseline = file("lint-baseline.xml")
	}

	ksp {
		arg("room.schemaLocation", "$projectDir/schemas")
		arg("room.incremental", "true")
		arg("room.generateKotlin", "false")
	}

	testOptions {
		unitTests.isIncludeAndroidResources = true
	}
    namespace = "com.adsamcik.tracker.shared.base"
	buildFeatures {
		buildConfig = true
		compose = true
	}
}

dependencies {
	implementation(project(":logging-api"))

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.androidx.documentfile)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.services.location)

	// JSON
	implementation(libs.moshi)
	ksp(libs.moshi.kotlin.codegen)

	// Compose runtime for CompositionLocal DI support
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.runtime)
	implementation(libs.compose.material3)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.foundation)
	implementation(libs.compose.ui)
	implementation(libs.accompanist.permissions)
	implementation(libs.activity.compose)

	// DB (api to expose RoomDatabase supertype to consumers of sbase)
	api(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.androidx.room.paging)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// Paging
	implementation(libs.androidx.paging.runtime)

	// DI annotations (javax.inject for @Qualifier, @Singleton, etc.)
	api(libs.javax.inject)

	// WorkManager
	implementation(libs.androidx.work.runtime.ktx)
	androidTestImplementation(libs.androidx.work.testing)

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
	testImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)

	// Instrumented Tests
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.kotlinx.coroutines.test)
	androidTestImplementation(libs.mockk.android)
}

// Configure JUnit 5 for unit tests
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

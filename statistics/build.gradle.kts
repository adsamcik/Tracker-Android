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

		ksp {
			arg("room.schemaLocation", "$projectDir/schemas")
			arg("room.incremental", "true")
			arg("room.generateKotlin", "true")
		}
	}

	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
	}

	buildFeatures {
		// Enable Compose for migrating UI
		compose = true
	}

	buildTypes {
		create("release_nominify")
		create("dev") {
			initWith(getByName("release"))
			matchingFallbacks += listOf("debug", "release")
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	namespace = "com.adsamcik.tracker.statistics"
}

dependencies {
	// Removed :app dependency as part of converting to a library module
	implementation(project(":sbase"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))
	implementation(project(":impexp"))

	// Stats architecture
	implementation(project(":stats-api"))
	implementation(project(":stats-data"))

	// Arrow
	implementation(libs.arrow.core)

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	// Recycler helpers (CardListAdapter, MultiType, etc.)
	implementation(libs.components.recycler)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.services.location)
	// Draggable overlay removed
	// JSON
	implementation(libs.moshi)
	ksp(libs.moshi.kotlin.codegen)

	// DB
	implementation(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.androidx.room.paging)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// Paging
	implementation(libs.androidx.paging.runtime)
	implementation(libs.androidx.paging.compose)
	// Compose (UI migration)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.foundation.layout)
	implementation(libs.compose.runtime)
	implementation(libs.activity.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.compose.ui.tooling.preview)
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
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.kotest.assertions.core)

	// Instrumented Tests
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.espresso.intents)
	androidTestImplementation(libs.mockk.android)
	androidTestImplementation(platform(libs.compose.bom))
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)

	implementation(libs.mpandroidchart)
	implementation(libs.simplify)

	// MapLibre Compose for trip route visualization
	implementation(libs.maplibre.compose)

	// Hilt (Dependency Injection)
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
	implementation(libs.hilt.navigation.compose)
}

// Configure JUnit 5 for unit tests
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}


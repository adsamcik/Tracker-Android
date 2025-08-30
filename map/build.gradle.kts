plugins {
	alias(libs.plugins.android.dynamic.feature)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.ksp)
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
	}
	testOptions {
		unitTests.isIncludeAndroidResources = true
	}
    namespace = "com.adsamcik.tracker.map"
}

dependencies {
	implementation(project(":smap"))
	implementation(project(":app"))
	implementation(project(":sbase"))
	implementation(project(":activity"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	// Recycler helpers (BaseRecyclerAdapter, decorations, etc.)
	implementation(libs.components.recycler)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.fragment.ktx)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.feature.delivery)
	implementation(libs.google.play.feature.delivery.ktx)
	implementation(libs.google.play.services.location)
	implementation(libs.google.play.services.maps)
	implementation(libs.components.draggable)

	// Compose (Phase 0 – prep only)
	implementation(platform(libs.compose.bom))
	androidTestImplementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)
	implementation(libs.compose.ui.tooling.preview)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.activity.compose)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.animation.graphics)
	// Compose Foundation Layout (for Modifier.fillMaxSize, matchParentSize, etc.)
	implementation(libs.compose.foundation.layout)
	implementation(libs.navigation.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.compose.runtime)
	implementation(libs.compose.runtime.livedata)
	implementation(libs.constraintlayout.compose)
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)
	implementation(libs.accompanist.pager)
	implementation(libs.accompanist.swiperefresh)
	implementation(libs.kotlinx.collections.immutable)
	// Maps Compose
	implementation(libs.google.maps.compose)
	// Material dialogs
	implementation(libs.material.dialogs.core)
	implementation(libs.spotlight)

	// Tests
	testImplementation(libs.junit4)
	testImplementation(libs.robolectric)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockito.core)
	testImplementation(libs.mockito.inline)
	testImplementation(libs.mockito.kotlin)
	testImplementation(libs.turbine)
	testImplementation(libs.arch.core.testing) // for InstantTaskExecutorRule
	testImplementation(libs.androidx.test.core) // for ApplicationProvider
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.livedata.testing.ktx)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.mockito.android)
	androidTestImplementation(libs.mockito.kotlin)
}

// Disable release unit tests for this module (minification can break mocks/types at runtime)
tasks.withType<Test>().configureEach {
	if (name.contains("ReleaseUnitTest")) {
		enabled = false
	}
}

plugins {
	alias(libs.plugins.android.dynamic.feature)
	alias(libs.plugins.kotlin.android)
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
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.feature.delivery)
	implementation(libs.google.play.feature.delivery.ktx)
	implementation(libs.google.play.services.location)
	implementation(libs.google.play.services.maps)
	implementation(libs.components.draggable)
	// Material dialogs
	implementation(libs.material.dialogs.core)
	implementation(libs.spotlight)

	// Tests
	testImplementation(libs.junit4)
	testImplementation("org.robolectric:robolectric:4.12.2")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
	testImplementation("org.mockito:mockito-core:5.12.0")
	testImplementation("org.mockito:mockito-inline:5.2.0")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.livedata.testing.ktx)
	androidTestImplementation(libs.espresso)
	androidTestImplementation("org.mockito:mockito-android:5.12.0")
	androidTestImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

// Disable release unit tests for this module (minification can break mocks/types at runtime)
tasks.withType<Test>().configureEach {
	if (name.contains("ReleaseUnitTest")) {
		enabled = false
	}
}

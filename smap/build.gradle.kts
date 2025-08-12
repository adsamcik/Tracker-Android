plugins {
	alias(libs.plugins.android.library)
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
	}

	buildTypes {
		getByName("debug") {
			enableAndroidTestCoverage = true
			enableUnitTestCoverage = true
		}

		create("release_nominify") {
			isMinifyEnabled = false
		}
		getByName("release") {
			isMinifyEnabled = true
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}
    namespace = "com.adsamcik.tracker.shared.map"
}

dependencies {
	implementation(project(":sbase"))
	implementation(project(":sutils"))
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
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.fragment.ktx)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.feature.delivery)
	implementation(libs.google.play.feature.delivery.ktx)
	implementation(libs.google.play.services.maps)

	// Tests
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.livedata.testing.ktx)
	androidTestImplementation(libs.espresso)
}

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.secrets)
	alias(libs.plugins.google.services)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.ksp)
}

// Google services plugin applied via alias above

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION
	defaultConfig {
		applicationId = "com.adsamcik.tracker"
		minSdk = Android.MIN_VERSION
		targetSdk = Android.TARGET_VERSION
		versionCode = 385
		versionName = "2024.3.0 α2"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}
	
	androidResources {
		localeFilters.addAll(listOf("en", "cs-rCZ"))
	}

	testOptions {
		unitTests.isIncludeAndroidResources = true
	}
	
	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
		isCoreLibraryDesugaringEnabled = true
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
		compilerOptions {
			optIn.add("kotlin.ExperimentalUnsignedTypes")
		}
	}

	java {
		toolchain {
			setSourceCompatibility(Android.JAVA_VERSION)
			setTargetCompatibility(Android.JAVA_VERSION)
		}
	}


	buildTypes {
		getByName("debug") {
			enableAndroidTestCoverage = true
			enableUnitTestCoverage = true
			applicationIdSuffix = ".debug"
			buildConfigField("boolean", "COMPOSE_MAIN", "true")
		}

		// Installable alongside production: non-debuggable, unique appId/label
		create("dev") {
			// Base on release settings for closer-to-prod behavior
			initWith(getByName("release"))
			// Use debug dependencies if a matching dev variant doesn't exist in deps
			matchingFallbacks += listOf("debug", "release")
			// Distinct identity on device and in Play/adb lists
			applicationIdSuffix = ".dev"
			versionNameSuffix = "-dev"
			// Clear label marker so users can tell builds apart
			resValue("string", "app_name", "Advention Dev")
			// Sign with debug key for easy local installs (customize if you have a dev keystore)
			signingConfig = signingConfigs.getByName("debug")
			isDebuggable = false
			buildConfigField("boolean", "COMPOSE_MAIN", "true")
		}

		create("release_nominify") {
			isMinifyEnabled = false
		}
		getByName("release") {
			// Temporarily disable minification due to unresolved R8 missing class issues across feature modules
			// TODO: Re-enable and fix by adding proper keep rules / adjusting dynamic feature class access
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
			buildConfigField("boolean", "COMPOSE_MAIN", "false")
		}
	}

	buildFeatures {
		compose = true
		viewBinding = true
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

	packaging {
		resources.pickFirsts.add("META-INF/atomicfu.kotlin_module")
	}

	// dynamicFeatures removed; modules are now statically linked libraries
	namespace = "com.adsamcik.tracker"
	dependenciesInfo {
		includeInApk = true
		includeInBundle = true
	}
	buildFeatures {
		buildConfig = true
	}
}

dependencies {
	coreLibraryDesugaring(libs.desugar.jdk.libs)
	
	implementation(project(":sbase"))
	implementation(project(":tracker"))
	implementation(project(":activity"))
	implementation(project(":points"))
	implementation(project(":sutils"))
	implementation(project(":spreferences"))
	implementation(project(":logger"))
	implementation(project(":impexp"))
	implementation(project(":statistics"))
	implementation(project(":game"))

	// debugImplementation("com.squareup.leakcanary:leakcanary-android:2.6")

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.components.recycler)
	implementation(libs.material.dialogs.core)
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
	// WorkManager
	implementation(libs.androidx.work.runtime.ktx)
	androidTestImplementation(libs.androidx.work.testing)

	// Compose
	implementation(platform(libs.compose.bom))
	androidTestImplementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)
	implementation(libs.compose.ui.tooling.preview)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.activity.compose)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.animation.graphics)
	implementation(libs.compose.ui.viewbinding)
	implementation(libs.navigation.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.compose.runtime)
	implementation(libs.compose.runtime.livedata)
	implementation(libs.constraintlayout.compose)
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)
	implementation(libs.accompanist.pager)
	implementation(libs.accompanist.swiperefresh)
	// 1st party dependencies
	implementation(libs.component.slider)
	implementation(libs.components.draggable)

	implementation(libs.spotlight)

	// 3rd party dependencies
	implementation(libs.material.dialogs.color)
	implementation(libs.material.dialogs.input)

	implementation(libs.moshi)
	ksp(libs.moshi.kotlin.codegen)

	// Google dependencies
	implementation(libs.androidx.cardview)

	// Preference
	implementation(libs.androidx.preference)

	// Open-source licenses
	implementation(libs.licensesdialog)
	implementation(libs.play.services.oss.licenses)

	// PlayServices
	implementation(libs.google.play.services.location)

	// Database
	implementation(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.androidx.room.paging)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// Unit test deps
	testImplementation(libs.junit4)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.androidx.work.testing)
	testImplementation(libs.robolectric)

	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.livedata.testing.ktx)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.espresso)
	// workaround  Multiple APKs packaging the same library can cause runtime errors.
	implementation(project(":smap"))
	implementation(libs.google.play.services.maps)
}
//

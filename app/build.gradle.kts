plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
	alias(libs.plugins.hilt)
	alias(libs.plugins.oss.licenses)
}

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION
	defaultConfig {
		applicationId = "com.adsamcik.tracker"
		minSdk = Android.MIN_VERSION
		targetSdk = Android.TARGET_VERSION
		versionCode = 385
		versionName = "2025.1.0"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		resourceConfigurations.addAll(listOf("en", "cs-rCZ"))
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
			// Keep minification disabled for now; Compose-only main is enforced across variants
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			buildConfigField("boolean", "COMPOSE_MAIN", "true")
		}
	}

	buildFeatures {
		compose = true
		// viewBinding no longer used; Compose-only UI
		viewBinding = false
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

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
	implementation(project(":map"))
	implementation(project(":game"))
	implementation(project(":dashboard"))

	// debugImplementation("com.squareup.leakcanary:leakcanary-android:2.6")

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.components.recycler)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	// WorkManager
	implementation(libs.androidx.work.runtime.ktx)
	androidTestImplementation(libs.androidx.work.testing)

	// Hilt (Dependency Injection)
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
	implementation(libs.hilt.navigation.compose)
	implementation(libs.hilt.work)

	// Glance App Widgets
	implementation(libs.androidx.glance.appwidget)
	implementation(libs.androidx.glance.material3)

	// Compose
	implementation(platform(libs.compose.bom))
	androidTestImplementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)

	// Glance (App Widgets)
	implementation(libs.glance.appwidget)
	implementation(libs.glance.material3)
	implementation(libs.compose.ui.tooling.preview)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.activity.compose)
	implementation(libs.androidx.core.splashscreen)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.animation.graphics)
	// AndroidViewBinding is no longer used
	implementation(libs.navigation.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.compose.runtime)
	implementation(libs.constraintlayout.compose)
	implementation(libs.haze)
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)
	// 1st party dependencies
	implementation(libs.component.slider)
	// Draggable overlay removed with legacy fallback

	implementation(libs.spotlight)

	// 3rd party dependencies
	implementation(libs.moshi)
	ksp(libs.moshi.kotlin.codegen)

	// Google dependencies
	implementation(libs.androidx.cardview)

	// Preference
	implementation(libs.androidx.preference)

	// Open-source licenses
	implementation(libs.licensesdialog)

	// PlayServices
	implementation(libs.google.play.services.location)

	// Database
	implementation(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.androidx.room.paging)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// Unit test deps - JUnit 5 for modern testing
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testImplementation(libs.junit5.jupiter.params)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	testRuntimeOnly(libs.junit5.vintage.engine)
	testImplementation(libs.junit4)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.androidx.work.testing)
	testImplementation(libs.robolectric)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockk)
	testImplementation(libs.turbine)
	testImplementation(libs.kotest.assertions.core)
	testImplementation(project(":stats-api"))
	implementation(project(":stats-api"))
	testImplementation(project(":stats-engine"))

	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.androidx.test.rules)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.mockk.android)
	androidTestImplementation(project(":testing-common"))
	testImplementation(project(":testing-common"))
}

// Configure JUnit 5 for unit tests
tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

// Fix for KSP running before R class generation
// Ensure KSP waits for resource processing to complete
afterEvaluate {
	tasks.named("kspDebugKotlin") {
		dependsOn("processDebugResources")
	}
	tasks.named("kspReleaseKotlin") {
		dependsOn("processReleaseResources")
	}
	tasks.findByName("kspDevKotlin")?.dependsOn("processDevResources")
}

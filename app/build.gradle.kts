plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.secrets)
	alias(libs.plugins.google.services)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.ksp)
	alias(libs.plugins.hilt)
	alias(libs.plugins.oss.licenses)
}

// Google services plugin applied via alias above

android {
	compileSdk = libs.versions.android.compile.get().toInt()
	buildToolsVersion = libs.versions.android.build.tools.get()
	defaultConfig {
		applicationId = "com.adsamcik.tracker"
		minSdk = libs.versions.android.min.get().toInt()
		targetSdk = libs.versions.android.target.get().toInt()
		versionCode = 385
		versionName = "2024.3.0 α2"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		
		androidResources {
			localeFilters.addAll(listOf("en", "cs-rCZ"))
		}
	}

	testOptions {
		unitTests.isIncludeAndroidResources = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		isCoreLibraryDesugaringEnabled = true
	}

	kotlin {
		jvmToolchain(libs.versions.java.get().toInt())
		compilerOptions {
			optIn.add("kotlin.ExperimentalUnsignedTypes")
		}
	}

	java {
		toolchain {
			setSourceCompatibility(libs.versions.java.get().toInt())
			setTargetCompatibility(libs.versions.java.get().toInt())
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
			// Keep minification disabled for now; Compose-only main is enforced across variants
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
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

	packaging {
		resources {
			excludes += "META-INF/LICENSE.md"
			excludes += "META-INF/LICENSE-notice.md"
		}
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
	implementation(project(":map"))
	implementation(project(":game"))

	debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

	// Core
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.kotlinx.serialization.json)
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

	// Compose
	implementation(platform(libs.compose.bom))
	androidTestImplementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)
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
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)
	// Accompanist libraries removed (migrated to AndroidX)
	// 1st party dependencies
	implementation(libs.component.slider)
	// Draggable overlay removed with legacy fallback

	implementation(libs.spotlight)

	// 3rd party dependencies
	implementation(libs.moshi)
	ksp(libs.moshi.kotlin.codegen)

	// Google dependencies
	implementation(libs.androidx.cardview)

	// Haze (Glassmorphism)
	implementation(libs.haze)

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
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.mockk)

	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.androidx.test.rules)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(project(":testing-common"))
	// workaround  Multiple APKs packaging the same library can cause runtime errors.
	implementation(project(":smap"))
	implementation(libs.google.play.services.maps)
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
}

plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.ksp)
	alias(libs.plugins.hilt)
	alias(libs.plugins.protobuf)
}

android {
	compileSdk = libs.versions.android.compile.get().toInt()
	buildToolsVersion = libs.versions.android.build.tools.get()

	defaultConfig {
		minSdk = libs.versions.android.min.get().toInt()

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	sourceSets {
		this.maybeCreate("androidTest").assets.srcDirs(files("$projectDir/schemas"))
		getByName("debug") {
			java.srcDir("build/generated/source/proto/debug/java")
		}
		getByName("release") {
			java.srcDir("build/generated/source/proto/release/java")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
	}

	kotlin {
		jvmToolchain(libs.versions.java.get().toInt())
	}

	buildFeatures {
		// Enable Jetpack Compose for tracker UI migration
		compose = true
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
			// Minification disabled due to R8 issues with shared module class retention
			// Issue: R8 removes classes referenced reflectively across module boundaries
			// Requires: Comprehensive proguard rules or migration to explicit DI
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
		}
	}

	packaging {
		resources {
			excludes += "/META-INF/{AL2.0,LGPL2.1}"
			excludes += "META-INF/LICENSE.md"
			excludes += "META-INF/LICENSE-notice.md"
		}
	}
    namespace = "com.adsamcik.tracker.tracker"
}

dependencies {
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
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.lifecycle.common.java8)
	implementation(libs.google.material)
	implementation(libs.google.play.services.base)
	implementation(libs.google.play.services.location)

	// Hilt (Dependency Injection)
	implementation(libs.hilt.android)
	ksp(libs.hilt.compiler)
	implementation(libs.hilt.work)

	// Compose (UI migration)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.material3)
	implementation(libs.compose.material3.window.size)
	implementation(libs.compose.material.icons.extended)
	implementation(libs.compose.animation)
	implementation(libs.compose.foundation)
	implementation(libs.compose.foundation.layout)
	implementation(libs.compose.runtime)
	// DataStore (proto for typed settings, preferences for migration compatibility)
	implementation(libs.androidx.datastore.core)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.protobuf.java)
	// Required for rememberLauncherForActivityResult and setContent in main source
	implementation(libs.activity.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	debugImplementation(libs.compose.ui.tooling)
	implementation(libs.compose.ui.tooling.preview)

	// UI Tests
	androidTestImplementation(libs.compose.ui.test.junit4)
	debugImplementation(libs.compose.ui.test.manifest)
	// Needed for ComponentActivity.setContent in androidTest (also added to main above)
	androidTestImplementation(libs.activity.compose)

	// DB
	implementation(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.androidx.room.paging)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// WorkManager
	implementation(libs.androidx.work.runtime.ktx)
	androidTestImplementation(libs.androidx.work.testing)

	// Tests
	testImplementation(libs.junit4)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.mockk)
	testImplementation(libs.robolectric)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.androidx.test.core)
	
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	androidTestImplementation(project(":testing-common"))
}

protobuf {
	protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
	generateProtoTasks {
		all().forEach { task ->
			task.builtins { create("java") }
		}
	}
}

afterEvaluate {
    tasks.forEach { task ->
        if (task.name.startsWith("ksp") && task.name.endsWith("Kotlin")) {
            if (task.name.contains("Debug")) {
                task.dependsOn("generateDebugProto")
            } else if (task.name.contains("Release")) {
                task.dependsOn("generateReleaseProto")
            }
        }
    }
}

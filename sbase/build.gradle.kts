plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.ksp)
}

android {
	compileSdk = libs.versions.android.compile.get().toInt()
	buildToolsVersion = libs.versions.android.build.tools.get()

	defaultConfig {
		minSdk = libs.versions.android.min.get().toInt()

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
		sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
	}

	kotlin {
		jvmToolchain(libs.versions.java.get().toInt())
	}

	java {
		toolchain {
			languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
			setSourceCompatibility(libs.versions.java.get().toInt())
			setTargetCompatibility(libs.versions.java.get().toInt())
		}
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
    namespace = "com.adsamcik.tracker.shared.base"
	buildFeatures {
		buildConfig = true
		compose = true
	}
}

dependencies {
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
	implementation(libs.compose.foundation)
	implementation(libs.compose.ui)
	implementation(libs.compose.material.icons.extended)
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

	// Tests
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
	
	testImplementation(libs.kotlinx.coroutines.test)
	androidTestImplementation(libs.kotlinx.coroutines.test)
}

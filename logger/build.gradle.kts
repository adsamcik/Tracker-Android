plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.parcelize)
	alias(libs.plugins.ksp)
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
	}

	compileOptions {
		sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
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
	namespace = "com.adsamcik.tracker.logger"
	buildFeatures {
		buildConfig = true
	}

	ksp {
		arg("room.schemaLocation", "$projectDir/schemas")
		arg("room.incremental", "true")
		arg("room.generateKotlin", "true")
	}

	kotlin {
		jvmToolchain(libs.versions.java.get().toInt())
	}
}

dependencies {
	implementation(project(":sbase"))
	implementation(project(":spreferences"))
	implementation(libs.androidx.documentfile)

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

	// DB
	implementation(libs.androidx.room.runtime)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.room.ktx)
	implementation(libs.androidx.room.paging)
	implementation(libs.sqlite.android)
	androidTestImplementation(libs.androidx.room.testing)

	// Tests
	androidTestImplementation(libs.junit4)
	androidTestImplementation(libs.androidx.test.runner)
	androidTestImplementation(libs.uiautomator)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.arch.core.testing)
	androidTestImplementation(libs.espresso)
}

// Workaround for intermittent KSP wiring on Windows with AGP/Kotlin RCs:
// - Ensure the KSP output directory exists before consumers read it
// - Enforce task ordering so processDebugJavaRes waits for kspDebugKotlin
afterEvaluate {
	// Precreate expected KSP output folder to avoid NoSuchFileException
	val ensureKspDir = tasks.register("ensureKspDir") {
		doLast {
			file("$buildDir/generated/ksp/debug").mkdirs()
		}
	}

	tasks.matching { it.name == "kspDebugKotlin" }.configureEach {
		dependsOn(ensureKspDir)
	}

	tasks.matching { it.name == "processDebugJavaRes" }.configureEach {
		dependsOn("kspDebugKotlin")
	}
}

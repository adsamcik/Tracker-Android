plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION

	defaultConfig {
		minSdk = Android.MIN_VERSION
		consumerProguardFiles("consumer-rules.pro")
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

	namespace = "com.adsamcik.tracker.stats.engine"
}

dependencies {
	api(project(":stats-api"))

	// Coroutines
	implementation(libs.kotlinx.coroutines.android)

	// DI annotations
	implementation(libs.javax.inject)

	// Unit Tests
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testImplementation(libs.junit5.jupiter.params)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	testImplementation(libs.kotlin.test)
	testImplementation(libs.mockk)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.turbine)
	testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

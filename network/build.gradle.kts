plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	compileSdk = Android.COMPILE_VERSION
	buildToolsVersion = Android.BUILD_TOOLS_VERSION

	defaultConfig {
		minSdk = Android.MIN_VERSION
	}

	compileOptions {
		sourceCompatibility = Android.javaTarget
		targetCompatibility = Android.javaTarget
	}

	kotlin {
		jvmToolchain(Android.JAVA_VERSION)
	}

	buildTypes {
		create("release_nominify") {
			isMinifyEnabled = false
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	namespace = "com.adsamcik.tracker.network"
}

dependencies {
	implementation(libs.kotlin.stdlib.jdk8)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.okhttp)

	// Unit Tests
	testImplementation(platform(libs.junit5.bom))
	testImplementation(libs.junit5.jupiter)
	testRuntimeOnly(libs.junit5.jupiter.engine)
	testRuntimeOnly(libs.junit5.vintage.engine)
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation(libs.junit4)
	testImplementation(libs.mockk)
	testImplementation(libs.kotest.assertions.core)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.turbine)
	testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

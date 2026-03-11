plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.android.library)
}

kotlin {
	androidTarget {
		compilerOptions {
			jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(Android.javaTarget.toString()))
		}
	}
	jvm()

	jvmToolchain(Android.JAVA_VERSION)

	sourceSets {
		commonMain.dependencies {
			api(project(":stats-api"))
			implementation(libs.kotlinx.coroutines.core)
			implementation(libs.javax.inject)
		}
		commonTest.dependencies {
			implementation(libs.junit5.jupiter)
			implementation(libs.junit5.jupiter.params)
			runtimeOnly(libs.junit5.jupiter.engine)
			implementation(libs.kotlin.test)
			implementation(libs.mockk)
			implementation(libs.kotlinx.coroutines.test)
			implementation(libs.turbine)
			implementation(libs.kotest.assertions.core)
		}
		androidMain.dependencies {
			// Android-specific implementations
		}
		jvmMain.dependencies {
			// JVM-specific implementations
		}
	}
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

	buildTypes {
		getByName("debug") {
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

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

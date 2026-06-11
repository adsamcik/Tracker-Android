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
			api(libs.kotlinx.coroutines.core)
			api(libs.arrow.core)
			api(libs.javax.inject)
			api(project(":core:model"))
		}
		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotlinx.coroutines.test)
			implementation(libs.kotest.assertions.core)
			implementation(libs.junit5.jupiter.api)
			runtimeOnly(libs.junit5.jupiter.engine)
		}
		androidMain.dependencies {
			api(libs.androidx.paging.runtime)
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
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}
	}

	lint {
		checkReleaseBuilds = true
		abortOnError = false
	}

	namespace = "com.adsamcik.tracker.stats.api"
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
	android {
		namespace = "com.adsamcik.tracker.stats.engine"
		compileSdk = Android.COMPILE_VERSION
		minSdk = Android.MIN_VERSION

		// The ski/GPS detection classes (src/androidMain) use JVM APIs (java.util.Arrays) and are
		// consumed by Android modules; their unit tests live in src/androidHostTest. The AGP KMP
		// plugin disables host tests by default, so opt in to keep those tests running.
		withHostTest {}
	}
	jvm()

	jvmToolchain(Android.JAVA_VERSION)

	sourceSets {
		commonMain.dependencies {
			api(project(":stats:api"))
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
	}
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

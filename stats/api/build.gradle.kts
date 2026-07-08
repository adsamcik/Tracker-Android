plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
	android {
		namespace = "com.adsamcik.tracker.stats.api"
		compileSdk = Android.COMPILE_VERSION
		minSdk = Android.MIN_VERSION
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

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
}

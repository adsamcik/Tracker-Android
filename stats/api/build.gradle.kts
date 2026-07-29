plugins {
	id("tracker.kotlin.multiplatform.android-jvm")
}

kotlin {
	android {
		namespace = "com.adsamcik.tracker.stats.api"
		withHostTest {}
	}

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

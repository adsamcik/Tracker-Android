plugins {
	id("tracker.kotlin.multiplatform.android-jvm")
}

kotlin {
	android {
		namespace = "com.adsamcik.tracker.tracker.control"
		withHostTest {}
	}

	sourceSets {
		commonTest.dependencies {
			implementation(libs.kotlin.test)
			implementation(libs.kotest.assertions.core)
			implementation(libs.junit5.jupiter.api)
			runtimeOnly(libs.junit5.jupiter.engine)
		}
	}
}

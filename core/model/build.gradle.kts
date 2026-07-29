plugins {
    id("tracker.kotlin.multiplatform.android-jvm")
}

kotlin {
    android {
        namespace = "com.adsamcik.tracker.shared.model"
        withHostTest {}
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.junit5.jupiter.api)
            runtimeOnly(libs.junit5.jupiter.engine)
        }
    }
}

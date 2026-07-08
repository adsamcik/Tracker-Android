plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.adsamcik.tracker.shared.model"
        compileSdk = Android.COMPILE_VERSION
        minSdk = Android.MIN_VERSION
    }
    jvm()

    jvmToolchain(Android.JAVA_VERSION)

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.junit5.jupiter.api)
            runtimeOnly(libs.junit5.jupiter.engine)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

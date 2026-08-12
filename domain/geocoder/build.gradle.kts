plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.geocoder"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":core:base"))
    implementation(project(":core:common"))
	implementation(project(":core:model"))
    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    // DI annotations
    implementation(libs.javax.inject)

}

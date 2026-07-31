plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.activity.impl"

    lint {
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":sensor:activity-api"))
    implementation(project(":core:common"))
    implementation(project(":core:base"))
    implementation(project(":core:model"))
    implementation(project(":data:preferences"))
    implementation(project(":core:diagnostics"))
    implementation(project(":stats:api"))
    implementation(project(":stats:engine"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)

    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.turbine)
    testImplementation(project(":core:testing"))

}

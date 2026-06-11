plugins {
    id("tracker.android.library")
    id("tracker.android.hilt")
    id("tracker.android.test")
}

android {
    namespace = "com.adsamcik.tracker.activity.impl"

    sourceSets {
        this.maybeCreate("androidTest").assets.srcDir("$projectDir/schemas")
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":sensor:activity-api"))
    implementation(project(":core:base"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:logging"))
    implementation(project(":stats:api"))
    implementation(project(":stats:engine"))

    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)

    testImplementation(libs.androidx.work.testing)
    testImplementation(project(":core:testing"))

    androidTestImplementation(libs.androidx.work.testing)
}

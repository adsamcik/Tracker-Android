plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    id("tracker.android.hilt")
    id("tracker.android.test")
    id("tracker.android.protobuf")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.game"

    lint {
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // Removed :app dependency as part of converting to a library module
    implementation(project(":feature:game:api"))
    implementation(project(":feature:dashboard:api"))
    implementation(project(":tracker:api"))
    implementation(project(":core:common"))
    implementation(project(":core:base"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:logging"))
    implementation(project(":domain:points"))

    // Stats architecture
    implementation(project(":stats:api"))
    implementation(project(":stats:data"))

    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)

    // Compose (Material 3 expressive)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    // DataStore proto settings (full protobuf runtime)
    implementation(libs.androidx.datastore.core)
    implementation(libs.protobuf.java)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    debugImplementation(libs.compose.ui.test.manifest)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // UI utils
    implementation(libs.component.slider)
    // Draggable overlay removed

    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.activity.compose)
    testImplementation(libs.turbine)
    testImplementation(project(":core:testing"))

    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
}

plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    id("tracker.android.hilt")
    id("tracker.android.test")
    id("tracker.android.instrumented-test")
    id("tracker.android.protobuf")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.impexp"

    lint {
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:base"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:sqlite-runtime"))
    implementation(project(":feature:statistics:api"))
    // The portable transfer contract is release code. V2 control-trace export remains compiled
    // only from the debug source set and stays absent from the user-facing format registry.
    implementation(project(":stats:api"))
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.core)
    implementation(libs.protobuf.java)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)

    // Compose
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.constraintlayout.compose)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.intents)
    debugImplementation(libs.compose.ui.test.manifest)

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

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.work.testing)

    androidTestImplementation(project(":core:testing"))

    implementation(libs.hilt.work)
}

plugins {
    id("tracker.android.library")
    id("tracker.android.compose")
    id("tracker.android.hilt")
    id("tracker.android.room")
    id("tracker.android.test")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.adsamcik.tracker.game"

    buildTypes {
        create("dev") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("debug", "release")
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    // Removed :app dependency as part of converting to a library module
    implementation(project(":feature:game:api"))
    implementation(project(":feature:dashboard:api"))
    implementation(project(":core:base"))
    implementation(project(":core:ui"))
    implementation(project(":data:preferences"))
    implementation(project(":core:logging"))
    implementation(project(":domain:points"))

    // Stats architecture
    implementation(project(":stats:api"))
    implementation(project(":stats:data"))

    // Core
    implementation(libs.kotlin.stdlib.jdk8)
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

    // UI Tests
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    // Needed for ComponentActivity.setContent in androidTest
    androidTestImplementation(libs.activity.compose)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.work.testing)

    // UI utils
    implementation(libs.component.slider)
    // Draggable overlay removed

    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.activity.compose)
    testImplementation(project(":core:testing"))

    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
}

configureProtobuf()

plugins {
    id("tracker.android.library")
    id("tracker.android.room")
    id("tracker.android.test")
    id("tracker.android.instrumented-test")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.shared.base"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        buildConfig = true
    }
}

ksp {
    arg("room.generateKotlin", "false")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":core:sqlite-runtime"))

    // Core
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.androidx.documentfile)
    implementation(libs.google.material)
    implementation(libs.google.play.services.base)
    implementation(libs.google.play.services.location)

    // JSON
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    // DB (api to expose RoomDatabase supertype to consumers of sbase)
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    androidTestImplementation(libs.androidx.room.testing)

    // Paging
    implementation(libs.androidx.paging.runtime)

    // DI annotations (javax.inject for @Qualifier, @Singleton, etc.)
    api(libs.javax.inject)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.work.testing)

}

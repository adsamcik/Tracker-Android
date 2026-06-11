plugins {
    id("tracker.android.library")
    id("tracker.android.room")
    id("tracker.android.test")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.adsamcik.tracker.logger"

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core:logging-api"))
    implementation(project(":data:preferences"))
    implementation(libs.androidx.documentfile)

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
}

// Workaround for intermittent KSP wiring on Windows with AGP/Kotlin RCs:
// - Ensure the KSP output directory exists before consumers read it
// - Enforce task ordering so processDebugJavaRes waits for kspDebugKotlin
afterEvaluate {
    // Precreate expected KSP output folder to avoid NoSuchFileException
    val ensureKspDir = tasks.register("ensureKspDir") {
        doLast {
            file("$buildDir/generated/ksp/debug").mkdirs()
        }
    }

    tasks.matching { it.name == "kspDebugKotlin" }.configureEach {
        dependsOn(ensureKspDir)
    }

    tasks.matching { it.name == "processDebugJavaRes" }.configureEach {
        dependsOn("kspDebugKotlin")
    }
}
